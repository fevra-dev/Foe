package com.foe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import javax.imageio.ImageIO;
import net.runelite.api.NPCComposition;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The whole gallery pipeline on a made-up world, with the real renderer, the real sheet painter and real files: what
 * {@code ::foegallery} does except for the client's own calls (the definitions and the models), which are lambdas.
 * ADR-0010: the tool is run end to end before it is called done.
 */
public class PortraitGalleryEndToEndTest
{
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	/** A creature: three faces (front, side, top) in three colours, spanning exactly w x h x l. */
	static Portrait.Mesh creature(float w, float h, float l, int hue, boolean head)
	{
		float[] x = {0, w, w, 0, 0, 0, 0, 0, 0, w, w, 0};
		float[] y = {0, 0, -h, -h, 0, 0, -h, -h, -h, -h, -h, -h};
		float[] z = {0, 0, 0, 0, 0, l, l, 0, 0, 0, l, l};
		// vertices 0-3 are the front quad, 4-7 the side, 8-11 the top; each quad is two triangles
		int[] a = {0, 0, 4, 4, 8, 8};
		int[] b = {1, 2, 5, 6, 9, 10};
		int[] c = {2, 3, 6, 7, 10, 11};
		int light = 40;
		int[] colours = new int[6];
		for (int f = 0; f < 6; f++)
		{
			colours[f] = (hue + (f / 2) * 9) % 64 << 10 | 6 << 7 | (light + (f / 2) * 18);
		}
		return new Portrait.Mesh(x, y, z, a, b, c, colours, new int[]{-1, -1, -1, -1, -1, -1}, null, 6, head);
	}

	/** The shape (w, h, l) that makes Portrait pick each view. */
	private static float[] sizeFor(Portrait.View v)
	{
		switch (v)
		{
			case CHATHEAD:
				return new float[]{100, 110, 90};
			case WHOLE:
				return new float[]{110, 100, 120};
			case UPRIGHT:
				return new float[]{80, 300, 90};
			case SIDE:
				return new float[]{60, 100, 400};
			default:
				return new float[]{400, 100, 300};
		}
	}

	/** The made-up world: definitions by id, and the meshes by id. */
	static final class World
	{
		final List<NPCComposition> defs = new ArrayList<>();
		final Map<Integer, Portrait.Mesh> meshes = new HashMap<>();
		final Set<Integer> asked = new HashSet<>();
		int shapes;
		int monsters;

		int[] ids()
		{
			int[] ids = new int[defs.size()];
			for (int i = 0; i < ids.length; i++)
			{
				ids[i] = i;
			}
			return ids;
		}

		IntFunction<NPCComposition> definitions()
		{
			return defs::get;
		}

		IntFunction<Portrait.Mesh> loader()
		{
			return id ->
			{
				asked.add(id);
				return meshes.get(id);
			};
		}
	}

	/**
	 * perView shapes of each view, each in {@code recolours} ids (same models, different recolour: one shape), plus the
	 * kinds of definition that are not shown: unnamed, no combat level, a transform parent, and one with no model.
	 */
	static World world(int sideShapes, int otherShapes, int recolours)
	{
		World w = new World();
		int model = 1000;
		for (Portrait.View v : Portrait.View.values())
		{
			int count = v == Portrait.View.SIDE ? sideShapes : otherShapes;
			float[] s = sizeFor(v);
			for (int i = 0; i < count; i++)
			{
				int[] models = {model++};
				Portrait.Mesh mesh = creature(s[0], s[1], s[2], (i * 5 + v.ordinal() * 11) % 64, v == Portrait.View.CHATHEAD);
				for (int r = 0; r < recolours; r++)
				{
					int id = w.defs.size();
					String name = String.format("%s %c%d", v.name().charAt(0) + v.name().substring(1).toLowerCase(),
						(char) ('a' + i % 26), i / 26);
					w.defs.add(PortraitGalleryTest.fake(id, r == 0 ? name : name + " (variant " + r + ")", 10 + i, null,
						v == Portrait.View.CHATHEAD ? models : null, v == Portrait.View.CHATHEAD ? new int[]{7} : models));
					w.meshes.put(id, mesh);
					w.monsters++;
				}
				w.shapes++;
			}
		}
		w.defs.add(PortraitGalleryTest.fake(w.defs.size(), "null", 10, null, null, new int[]{1}));
		w.defs.add(PortraitGalleryTest.fake(w.defs.size(), "Banker", 0, null, null, new int[]{2}));
		w.defs.add(PortraitGalleryTest.fake(w.defs.size(), "Shifter", 90, new int[]{1, 2}, null, new int[]{3}));
		w.defs.add(PortraitGalleryTest.fake(w.defs.size(), "Invisible", 20, null, null, null));
		return w;
	}

	/** Runs a world to the end with the real clock, small time slices and a worker that runs tasks inline. */
	static PortraitGalleryRun.Result runToEnd(World w, String filter)
	{
		AtomicReference<PortraitGalleryRun.Result> out = new AtomicReference<>();
		PortraitGalleryRun run = new PortraitGalleryRun(w.ids(), w.definitions(), w.loader(), filter, Runnable::run,
			out::set, m ->
			{
			}, System::nanoTime);
		for (int steps = 0; steps < 1_000_000; steps++)
		{
			if (run.step(2_000_000L))
			{
				break;
			}
		}
		assertNotNull("the run delivered a result", out.get());
		return out.get();
	}

	@Test
	public void aWholeRunWritesTheSheetsAndIndexesForEveryDistinctShape() throws IOException
	{
		World w = world(105, 6, 3); // SIDE needs a second sheet; every shape has three ids
		PortraitGalleryRun.Result r = runToEnd(w, "");

		PortraitGalleryRun.Stats s = r.stats;
		assertEquals(w.defs.size(), s.listed);
		assertEquals(w.monsters + 1, s.kept); // the monsters and the one with no model
		assertEquals(w.shapes, s.shapes);
		assertEquals(w.shapes, s.matched);
		assertEquals("every shape drawn", w.shapes, s.rendered);
		assertEquals(1, s.unnamed);
		assertEquals(1, s.noCombatLevel);
		assertEquals(1, s.transformParents);
		assertEquals(1, s.noModelIds);
		assertEquals("one model load per shape, not per id", w.shapes, w.asked.size());
		for (int id : w.asked)
		{
			assertEquals("the lowest id of each shape", 0, id % 3);
		}

		List<PortraitGallery.Sheet> sheets = PortraitGallery.layout(r.entries);
		Path dir = tmp.getRoot().toPath().resolve("foe-gallery");
		int written = PortraitGalleryFiles.write(dir, sheets, r.missing);

		Set<String> files = new TreeSet<>();
		try (java.util.stream.Stream<Path> list = Files.list(dir))
		{
			list.forEach(p -> files.add(p.getFileName().toString()));
		}
		assertEquals(new TreeSet<>(Arrays.asList("CHATHEAD-01.png", "WHOLE-01.png", "UPRIGHT-01.png", "SIDE-01.png",
			"SIDE-02.png", "TOP-01.png", "index.tsv", "unrendered.tsv")), files);
		assertEquals(files.size(), written);
		for (PortraitGallery.Sheet sheet : sheets)
		{
			BufferedImage png = ImageIO.read(new File(dir.toFile(), sheet.name + ".png"));
			assertEquals(sheet.width, png.getWidth());
			assertEquals(sheet.height, png.getHeight());
		}
		assertEquals(100, sheets.stream().filter(x -> x.name.equals("SIDE-01")).findFirst().get().cells.size());
		assertEquals(5, sheets.stream().filter(x -> x.name.equals("SIDE-02")).findFirst().get().cells.size());

		List<String> index = Files.readAllLines(dir.resolve("index.tsv"), StandardCharsets.UTF_8);
		assertEquals("a row per drawn shape, and the header", w.shapes + 1, index.size());
		Set<String> views = new HashSet<>();
		for (String line : index.subList(1, index.size()))
		{
			String[] f = line.split("\t", -1);
			assertEquals(10, f.length);
			views.add(f[4]);
			assertTrue(line, f[8].split(",").length == 2); // the two other ids that share this shape
			assertEquals("the view column is the sheet's view", f[0].substring(0, f[0].indexOf('-')), f[4]);
		}
		assertEquals(5, views.size());
		List<String> unrendered = Files.readAllLines(dir.resolve("unrendered.tsv"), StandardCharsets.UTF_8);
		assertEquals(2, unrendered.size());
		assertTrue(unrendered.get(1), unrendered.get(1).startsWith("NO_MODEL_IDS\t"));
	}

	@Test
	public void aFilteredRunDrawsOnlyThoseShapes() throws IOException
	{
		World w = world(5, 5, 2);
		PortraitGalleryRun.Result r = runToEnd(w, "side");
		assertEquals(5, r.stats.matched);
		assertEquals(5, r.stats.rendered);
		assertEquals("only the matching shapes' models were loaded", 5, w.asked.size());
		assertFalse(r.entries.stream().anyMatch(e -> e.view != Portrait.View.SIDE));
	}
}
