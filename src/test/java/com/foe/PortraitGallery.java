package com.foe;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import net.runelite.api.NPCComposition;
import net.runelite.client.util.Text;

/**
 * The pure half of the dev-only portrait gallery (test source set: it never ships). Which definitions count, the
 * shape key that collapses recolours, grouping, ordering, the sheet layout and the index. It touches no client and no
 * files, so {@code PortraitGalleryTest} covers it all; {@link PortraitGalleryRun} drives it from the client thread and
 * {@link PortraitGalleryFiles} puts it on disk.
 */
final class PortraitGallery
{
	/** Cells to a row, and to a sheet. */
	static final int ACROSS = 10;
	static final int PER_SHEET = 100;
	/** What {@link Portrait#render} is asked for (the size Foe draws), and the factor each cell shows it at. */
	static final int RENDER = 64;
	static final int SCALE = 2;
	static final int PICTURE = RENDER * SCALE;
	static final int MARGIN = 8;
	static final int PAD = 6;
	/** One line of label text, and how many there are under a picture: name, id, view. */
	static final int LINE = 13;
	static final int LINES = 3;
	static final int CELL_W = 140;
	static final int CELL_H = PAD + PICTURE + 4 + LINES * LINE + PAD;
	/** The chat command, without the {@code ::}. */
	static final String COMMAND = "foegallery";
	/** The folder name is cut here, so a long filter cannot make a long path. */
	private static final int SLUG = 32;
	/** The repo root has this file (the Plugin Hub needs it), which is how a working directory is recognised as it. */
	private static final String REPO_MARKER = "runelite-plugin.properties";
	private static final Pattern DESCRIBE =
		Pattern.compile("([A-Z]+) \\(width (-?\\d+), height (-?\\d+), length (-?\\d+)\\)");
	private static final Comparator<Entry> BY_NAME = Comparator
		.comparing((Entry e) -> e.group.name, String.CASE_INSENSITIVE_ORDER)
		.thenComparingInt(e -> e.group.id);

	/** Why a definition is left out of the gallery. */
	enum Skip
	{
		/** No real name: blank, or the cache's literal "null". */
		UNNAMED,
		/** Foe only shows monsters, which have a combat level. */
		NO_COMBAT_LEVEL,
		/** Resolves to another definition depending on a varbit; that one has its own id and is listed itself. */
		TRANSFORM_PARENT
	}

	/** A definition worth drawing. {@code key} is null when it has no model at all. */
	static final class Candidate
	{
		final int id;
		final String name;
		final String key;

		Candidate(int id, String name, String key)
		{
			this.id = id;
			this.name = name;
			this.key = key;
		}
	}

	/** Every id that shares one shape: the lowest id stands for it. */
	static final class Group
	{
		final String key;
		/** The representative: the lowest id. */
		final int id;
		/** The representative's own name. */
		final String name;
		/** Ascending; the first is {@link #id}. */
		final List<Integer> ids;
		/** Distinct, in order of the lowest id that carries each; the first is {@link #name}. */
		final List<String> names;

		Group(String key, List<Integer> ids, List<String> names)
		{
			this.key = key;
			this.id = ids.get(0);
			this.name = names.get(0);
			this.ids = ids;
			this.names = names;
		}

		/** How many other ids have this shape. */
		int shared()
		{
			return ids.size() - 1;
		}
	}

	/** The view {@link Portrait} chose and the model's extents, as {@link Portrait#describe} reports them. */
	static final class Shape
	{
		final Portrait.View view;
		final int width, height, length;

		Shape(Portrait.View view, int width, int height, int length)
		{
			this.view = view;
			this.width = width;
			this.height = height;
			this.length = length;
		}
	}

	/** A group that rendered. {@code image} may be null in layout tests. */
	static final class Entry
	{
		final Group group;
		final Shape shape;
		final Portrait.View view;
		final BufferedImage image;

		Entry(Group group, Shape shape, BufferedImage image)
		{
			this.group = group;
			this.shape = shape;
			this.view = shape.view;
			this.image = image;
		}
	}

	/** Where one entry sits on its sheet. {@code number} counts from 1, left to right, top to bottom. */
	static final class Cell
	{
		final Entry entry;
		final int number, row, col, x, y;

		Cell(Entry entry, int number, int row, int col, int x, int y)
		{
			this.entry = entry;
			this.number = number;
			this.row = row;
			this.col = col;
			this.x = x;
			this.y = y;
		}
	}

	static final class Sheet
	{
		/** For example {@code SIDE-01}; the file is this plus {@code .png}. */
		final String name;
		final Portrait.View view;
		final List<Cell> cells;
		final int width, height;

		Sheet(String name, Portrait.View view, List<Cell> cells, int width, int height)
		{
			this.name = name;
			this.view = view;
			this.cells = cells;
			this.width = width;
			this.height = height;
		}
	}

	/** Why a shape has no cell on a sheet. */
	enum Why
	{
		/** The definition lists no model at all. */
		NO_MODEL_IDS,
		/** A model was still not loaded after the retries. */
		NOT_LOADED,
		/** Every face of the mesh is hidden, or nothing could be drawn. */
		NOT_DRAWABLE,
		/** Loading or rendering threw. */
		ERROR
	}

	static final class Missing
	{
		final Group group;
		final Why why;

		Missing(Group group, Why why)
		{
			this.group = group;
			this.why = why;
		}
	}

	private PortraitGallery()
	{
	}

	static final String MISSING_HEADER = "reason\tid\tname\tshared ids\tall names";

	/** unrendered.tsv: a header and a row per shape that has no cell, by reason then name then id. */
	static String missingTsv(List<Missing> missing)
	{
		List<Missing> sorted = new ArrayList<>(missing);
		sorted.sort(Comparator.comparingInt((Missing m) -> m.why.ordinal())
			.thenComparing((Missing m) -> m.group.name, String.CASE_INSENSITIVE_ORDER)
			.thenComparingInt(m -> m.group.id));
		StringBuilder out = new StringBuilder(MISSING_HEADER).append('\n');
		for (Missing m : sorted)
		{
			Group g = m.group;
			out.append(String.join("\t", m.why.name(), String.valueOf(g.id), oneLine(g.name), sharedIds(g),
				oneLine(String.join(" | ", g.names)))).append('\n');
		}
		return out.toString();
	}

	/** The ids other than the representative, comma separated. */
	private static String sharedIds(Group g)
	{
		List<String> shared = new ArrayList<>();
		for (Integer id : g.ids.subList(1, g.ids.size()))
		{
			shared.add(String.valueOf(id));
		}
		return String.join(",", shared);
	}

	/**
	 * What shape a definition has: its chathead models if it has any, else its body models (the choice
	 * {@link PortraitModels#load} makes), sorted so the order they are listed in does not matter. Recolours are not in
	 * it, so every recoloured dragon has one key. A model used twice stays twice: the mesh draws it twice. Null when
	 * there is no model at all.
	 */
	static String shapeKey(int[] chathead, int[] body)
	{
		boolean head = chathead != null && chathead.length > 0;
		int[] ids = head ? chathead : body;
		if (ids == null || ids.length == 0)
		{
			return null;
		}
		int[] sorted = ids.clone();
		Arrays.sort(sorted);
		StringBuilder key = new StringBuilder(head ? "C:" : "B:");
		for (int i = 0; i < sorted.length; i++)
		{
			key.append(i == 0 ? "" : ",").append(sorted[i]);
		}
		return key.toString();
	}

	/** Null when Foe could show this definition as a monster; else why it is left out. */
	static Skip skipReason(NPCComposition c)
	{
		if (cleanName(c.getName()) == null)
		{
			return Skip.UNNAMED;
		}
		if (c.getCombatLevel() <= 0)
		{
			return Skip.NO_COMBAT_LEVEL;
		}
		int[] configs = c.getConfigs();
		if (configs != null && configs.length > 0)
		{
			return Skip.TRANSFORM_PARENT;
		}
		return null;
	}

	/** Call only when {@link #skipReason} is null. */
	static Candidate candidate(NPCComposition c)
	{
		String name = cleanName(c.getName());
		return new Candidate(c.getId(), name == null ? "" : name, shapeKey(c.getChatheadModels(), c.getModels()));
	}

	/** The name without colour tags or control characters, or null when it is blank or the cache's "null". */
	private static String cleanName(String raw)
	{
		if (raw == null)
		{
			return null;
		}
		String name = Text.removeTags(raw).replaceAll("[\\p{Cntrl}\\s ]+", " ").trim();
		return name.isEmpty() || name.equals("null") ? null : name;
	}

	/**
	 * One group per shape, in order of each group's lowest id. Candidates without a key (no model at all) are not
	 * grouped; the caller counts them.
	 */
	static List<Group> group(List<Candidate> candidates)
	{
		List<Candidate> byId = new ArrayList<>(candidates);
		byId.sort(Comparator.comparingInt(c -> c.id));
		Map<String, List<Candidate>> byKey = new LinkedHashMap<>();
		for (Candidate c : byId)
		{
			if (c.key != null)
			{
				byKey.computeIfAbsent(c.key, k -> new ArrayList<>()).add(c);
			}
		}
		List<Group> groups = new ArrayList<>();
		for (Map.Entry<String, List<Candidate>> e : byKey.entrySet())
		{
			List<Integer> ids = new ArrayList<>();
			LinkedHashSet<String> names = new LinkedHashSet<>();
			for (Candidate c : e.getValue())
			{
				ids.add(c.id);
				names.add(c.name);
			}
			groups.add(new Group(e.getKey(), ids, new ArrayList<>(names)));
		}
		return groups;
	}

	/** The groups with a name containing the needle, ignoring case; all of them for a blank needle. */
	static List<Group> matching(List<Group> groups, String needle)
	{
		String n = needle == null ? "" : needle.trim().toLowerCase(Locale.ROOT);
		if (n.isEmpty())
		{
			return new ArrayList<>(groups);
		}
		List<Group> out = new ArrayList<>();
		for (Group g : groups)
		{
			for (String name : g.names)
			{
				if (name.toLowerCase(Locale.ROOT).contains(n))
				{
					out.add(g);
					break;
				}
			}
		}
		return out;
	}

	/**
	 * Reads what {@link Portrait#describe} wrote ({@code SIDE (width 60, height 100, length 400)}), so the index
	 * needs nothing from Portrait but that line; {@code PortraitGalleryTest} pins the wording. Null for
	 * "no drawable face" or anything else.
	 */
	static Shape parseDescribe(String describe)
	{
		if (describe == null)
		{
			return null;
		}
		Matcher m = DESCRIBE.matcher(describe);
		if (!m.matches())
		{
			return null;
		}
		try
		{
			return new Shape(Portrait.View.valueOf(m.group(1)), Integer.parseInt(m.group(2)),
				Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)));
		}
		catch (IllegalArgumentException e) // an unknown view, or a number too big
		{
			return null;
		}
	}

	/**
	 * Sheets by view in {@link Portrait.View} order, a hundred cells to a sheet, the cells of each view by name
	 * (ignoring case) then id. A view with no entry has no sheet, and a sheet holds one view only.
	 */
	static List<Sheet> layout(List<Entry> entries)
	{
		List<Sheet> sheets = new ArrayList<>();
		for (Portrait.View view : Portrait.View.values())
		{
			List<Entry> inView = new ArrayList<>();
			for (Entry e : entries)
			{
				if (e.view == view)
				{
					inView.add(e);
				}
			}
			inView.sort(BY_NAME);
			for (int start = 0, n = 1; start < inView.size(); start += PER_SHEET, n++)
			{
				List<Entry> chunk = inView.subList(start, Math.min(start + PER_SHEET, inView.size()));
				List<Cell> cells = new ArrayList<>();
				for (int i = 0; i < chunk.size(); i++)
				{
					int row = i / ACROSS;
					int col = i % ACROSS;
					cells.add(new Cell(chunk.get(i), i + 1, row, col, MARGIN + col * CELL_W, MARGIN + row * CELL_H));
				}
				int rows = (chunk.size() + ACROSS - 1) / ACROSS;
				sheets.add(new Sheet(String.format(Locale.ROOT, "%s-%02d", view, n), view, cells,
					2 * MARGIN + ACROSS * CELL_W, 2 * MARGIN + rows * CELL_H));
			}
		}
		return sheets;
	}

	static final String TSV_HEADER = "sheet\tcell\tid\tname\tview\twidth\theight\tlength\tshared ids\tall names";

	/** index.tsv: a header and a row per cell, in sheet order. Every field is kept to one line, one column. */
	static String tsv(List<Sheet> sheets)
	{
		StringBuilder out = new StringBuilder(TSV_HEADER).append('\n');
		for (Sheet s : sheets)
		{
			for (Cell c : s.cells)
			{
				Group g = c.entry.group;
				Shape sh = c.entry.shape;
				out.append(String.join("\t", s.name, String.valueOf(c.number), String.valueOf(g.id), oneLine(g.name),
					sh.view.name(), String.valueOf(sh.width), String.valueOf(sh.height), String.valueOf(sh.length),
					sharedIds(g), oneLine(String.join(" | ", g.names)))).append('\n');
			}
		}
		return out.toString();
	}

	static String oneLine(String s)
	{
		return s.replaceAll("[\\t\\r\\n]+", " ");
	}

	/** The label's second line: {@code id 7 (+2 shared)}, or just {@code id 7} when no other id has the shape. */
	static String idLabel(Group g)
	{
		return "id " + g.id + (g.shared() > 0 ? " (+" + g.shared() + " shared)" : "");
	}

	/** The label's third line: the view and the extents {@link Portrait#describe} reports. */
	/** The chat command: {@code ::foegallery}, in any case. RuneLite hands over the word after {@code ::}. */
	static boolean isCommand(String command)
	{
		return COMMAND.equalsIgnoreCase(command);
	}

	/** The words after the command, as one name filter: {@code ::foegallery baby dragon} filters on "baby dragon". */
	static String filterOf(String[] args)
	{
		if (args == null)
		{
			return "";
		}
		List<String> words = new ArrayList<>();
		for (String a : args)
		{
			if (a != null && !a.trim().isEmpty())
			{
				words.add(a.trim());
			}
		}
		return String.join(" ", words);
	}

	/**
	 * The NPC definition ids to look at: the cache's own list for the NPC archive, sorted, without duplicates or
	 * negatives, so nothing past the last NPC is ever asked for. Without a list (the call returned null or nothing
	 * usable), or with one that ends below half of {@code fallbackMax} (the highest id the API's own NpcID names:
	 * the archive number is read off the client's bytecode, and a list that short would be some other archive), every
	 * id from 0 to {@code fallbackMax}; the caller's lookups catch what does not exist.
	 */
	static int[] npcIds(int[] fileIds, int fallbackMax)
	{
		if (fileIds != null)
		{
			int[] ids = Arrays.stream(fileIds).filter(i -> i >= 0).distinct().sorted().toArray();
			if (ids.length > 0 && ids[ids.length - 1] >= fallbackMax / 2)
			{
				return ids;
			}
		}
		return IntStream.rangeClosed(0, fallbackMax).toArray();
	}

	/** The one chat line a finished run ends with. */
	static String summary(PortraitGalleryRun.Stats s, int sheets, String filter)
	{
		int notDrawn = s.noModelIds + s.notLoaded + s.notDrawable + s.errors;
		return "Foe gallery: " + s.rendered + " portraits from " + s.kept + " monsters, " + s.shapes + " shapes"
			+ (filter == null || filter.isEmpty() ? "" : " (matching \"" + filter + "\")") + ", on " + sheets
			+ (sheets == 1 ? " sheet" : " sheets") + ", " + notDrawn + " not drawn (see unrendered.tsv).";
	}

	static String viewLabel(Shape s)
	{
		return s.view + " w" + s.width + " h" + s.height + " l" + s.length;
	}

	/**
	 * The folder a filtered run writes into, under the output root, so it never mixes with the full run's sheets:
	 * {@code filter-} and the filter in lower case, anything but letters and digits as a single dash. Empty for no
	 * filter. The filter is typed in chat, so this is the one place it becomes a path.
	 */
	static String directoryName(String filter)
	{
		if (filter == null || filter.trim().isEmpty())
		{
			return "";
		}
		String slug = filter.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
		if (slug.length() > SLUG)
		{
			slug = slug.substring(0, SLUG).replaceAll("-+$", "");
		}
		return "filter-" + (slug.isEmpty() ? "other" : slug);
	}

	/**
	 * {@code build/foe-gallery} in the repo when the working directory is its root (what {@code ./gradlew run} gives:
	 * the project directory), else {@code ~/.runelite/foe-gallery}, so a launch from an IDE with another working
	 * directory writes somewhere known and not into a stray {@code build}.
	 */
	static Path outputRoot(Path cwd, Path home)
	{
		return Files.isRegularFile(cwd.resolve(REPO_MARKER))
			? cwd.resolve("build").resolve("foe-gallery")
			: home.resolve(".runelite").resolve("foe-gallery");
	}
}
