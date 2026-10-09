package com.foe.tools;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Regenerates the bundled weakness table from the OSRS Wiki (spec addenda 7 to 9). Dev-only: it lives under src/test,
 * is run by hand before a release with {@code ./gradlew generateWeaknessTable}, and is not part of any build the
 * Plugin Hub runs.
 */
public final class GenerateWeaknessTable
{
	static final String RAW = "data/wiki-infobox-monster.jsonl";
	static final String TABLE = "src/main/resources/com/foe/weakness-table.tsv";
	/** Committed beside the raw file: it is what a reviewer reads (addendum 8 F2), so `clean` must not wipe it. */
	static final String REPORT = "data/weakness-report.txt";

	private GenerateWeaknessTable()
	{
	}

	public static void main(String[] args)
	{
		System.exit(run(args, java.nio.file.Paths.get("").toAbsolutePath(), WikiFetch.live(), Clock.systemUTC(),
			System.out, System.err));
	}

	/**
	 * @return the exit code: 0 written, 1 a failure rule fired or the wiki misbehaved (nothing written), 2 bad
	 * arguments (nothing fetched)
	 */
	static int run(String[] args, Path root, WikiFetch fetch, Clock clock, PrintStream out, PrintStream err)
	{
		boolean acceptShrink = false;
		boolean firstGeneration = false;
		for (String arg : args)
		{
			if (arg.equals("--accept-shrink"))
			{
				acceptShrink = true;
			}
			else if (arg.equals("--first-generation"))
			{
				firstGeneration = true;
			}
			else
			{
				err.println("unknown argument '" + Text.safe(arg) + "'. Known: --accept-shrink (let a table more than "
					+ "5% smaller than the previous one be written), --first-generation (there is no previous table).");
				return 2;
			}
		}

		// The previous table is what changes are measured against, and the previous raw file says which pages gave
		// each id its value. Without both there is no hold, so their absence is a refusal, not a first generation:
		// deleting the table must not be the way to switch the hold off (review finding 2).
		Path tablePath = root.resolve(TABLE);
		Path rawPath = root.resolve(RAW);
		boolean haveTable = Files.exists(tablePath);
		if (firstGeneration != !haveTable)
		{
			err.println(haveTable
				? "--first-generation, but " + TABLE + " exists: a first generation would skip the 7-day hold"
				: "no previous table at " + TABLE + ". If this really is the first generation, run again with "
					+ "--first-generation; otherwise restore the table and " + RAW + " from git");
			return 1;
		}
		WeaknessTableBuilder.PreviousTable previous = null;
		Map<Integer, Set<String>> previousPages = new HashMap<>();
		if (haveTable)
		{
			try
			{
				String previousText = new String(Files.readAllBytes(tablePath), StandardCharsets.UTF_8);
				previous = WeaknessTableBuilder.PreviousTable.parse(previousText);
				RawRows.Parsed previousRaw = RawRows.read(new String(Files.readAllBytes(rawPath), StandardCharsets.UTF_8));
				// the pair must agree, or the hold would be measured against pages that did not make this table
				WeaknessTableBuilder.Result again = WeaknessTableBuilder.rebuild(previousRaw.rows,
					previousRaw.fetchedDate, previousRaw.decisions);
				if (!previousText.equals(again.table))
				{
					err.println("the previous table is not what " + RAW + " derives; refusing to run");
					return 1;
				}
				previousPages = WeaknessTableBuilder.pagesById(previousRaw.rows);
				out.println("previous table: " + previous.entries.size() + " ids (" + TABLE + ")");
			}
			catch (IllegalArgumentException | IOException e)
			{
				err.println("previous table or raw file unreadable, refusing to run: " + Text.safe(String.valueOf(
					e.getMessage())));
				return 1;
			}
		}
		else
		{
			out.println("first generation: no previous table, so nothing is held back");
		}

		List<WikiRow> rows;
		Map<String, Instant> lastEdit;
		try
		{
			rows = fetch.fetchRows();
			// F7 and review finding 6: one offset walk over a live database can skip a row without any error, so walk
			// again and refuse unless both saw the same distinct rows
			TreeSet<WikiRow> once = new TreeSet<>(rows);
			TreeSet<WikiRow> twice = new TreeSet<>(fetch.fetchRows());
			if (!once.equals(twice))
			{
				TreeSet<WikiRow> onlyFirst = new TreeSet<>(once);
				onlyFirst.removeAll(twice);
				twice.removeAll(once);
				err.println("the wiki changed during the fetch: " + onlyFirst.size() + " rows only in the first walk, "
					+ twice.size() + " only in the second. Nothing written; run again.");
				return 1;
			}
			out.println("fetched " + rows.size() + " rows, twice, and both walks agree");
			Set<String> pages = pagesWithAWeaknessRow(rows);
			for (Set<String> before : previousPages.values())
			{
				pages.addAll(before);
			}
			lastEdit = fetch.fetchLastEdits(pages);
			out.println("fetched last-edit times for " + lastEdit.size() + " pages");
		}
		catch (IOException e)
		{
			err.println("fetch failed, nothing written: " + e.getMessage());
			return 1;
		}

		Instant now = clock.instant();
		String fetchedDate = LocalDate.ofInstant(now, ZoneOffset.UTC).toString();
		WeaknessTableBuilder.Result result = WeaknessTableBuilder.build(rows, fetchedDate, previous, previousPages,
			lastEdit, now, acceptShrink);
		if (!result.failures.isEmpty())
		{
			err.println("FAILED, nothing written (" + result.failures.size() + "):");
			for (String failure : result.failures)
			{
				err.println("  " + failure);
			}
			return 1;
		}

		String raw = RawRows.write(rows, fetchedDate, result.decisions);
		try
		{
			// The raw file is what the table is re-derived from offline, so prove it does before it is kept.
			RawRows.Parsed back = RawRows.read(raw);
			WeaknessTableBuilder.Result again = WeaknessTableBuilder.rebuild(back.rows, back.fetchedDate,
				back.decisions);
			if (!again.failures.isEmpty() || !result.table.equals(again.table))
			{
				err.println("the table built from the raw file differs from the table just built; nothing written");
				return 1;
			}
		}
		catch (IllegalArgumentException e)
		{
			err.println("the raw file does not read back, nothing written: " + e.getMessage());
			return 1;
		}

		String report = "Foe weakness table report\n"
			+ "run at " + now + ", fetched date " + fetchedDate + "\n"
			+ (previous == null ? "no previous table (first generation: nothing quarantined)\n"
				: "previous table: " + previous.headerCount + " ids\n")
			+ "\n" + result.report.render();
		try
		{
			writeAll(root.resolve(RAW), raw, tablePath, result.table, root.resolve(REPORT), report);
		}
		catch (IOException e)
		{
			err.println("writing failed: " + e.getMessage());
			return 1;
		}
		out.println("wrote " + RAW + ", " + TABLE + " (" + result.entries.size() + " ids) and " + REPORT);
		for (String section : new String[] {WeaknessTableBuilder.Report.ELEMENT_CONFLICTS,
			WeaknessTableBuilder.Report.PERCENT_CONFLICTS, WeaknessTableBuilder.Report.SHARED_TABS,
			WeaknessTableBuilder.Report.RECENT, WeaknessTableBuilder.Report.UNKNOWN_EDIT,
			WeaknessTableBuilder.Report.PENDING})
		{
			out.println("  " + section + ": " + result.report.lines(section).size());
		}
		return 0;
	}

	/** The pages whose edit time matters: any page with a row that states a weakness. */
	private static Set<String> pagesWithAWeaknessRow(List<WikiRow> rows)
	{
		Set<String> pages = new TreeSet<>();
		for (WikiRow row : rows)
		{
			if (row.element != null)
			{
				pages.add(row.pageName);
			}
		}
		return pages;
	}

	/**
	 * Stages every file next to its target first, then moves them into place. A failure while staging leaves the old
	 * files untouched; a failure between two moves leaves a new raw file beside the old table, which the resource
	 * test then fails on loudly.
	 */
	// ponytail: per-file moves, not one atomic swap; a directory-rename swap if a half-written pair ever matters
	private static void writeAll(Path rawPath, String raw, Path tablePath, String table, Path reportPath, String report)
		throws IOException
	{
		Path[] targets = {rawPath, tablePath, reportPath};
		String[] contents = {raw, table, report};
		Path[] staged = new Path[3];
		try
		{
			for (int i = 0; i < 3; i++)
			{
				Files.createDirectories(targets[i].getParent());
				// not createTempFile: that makes the file owner-only (0600), and a table should look like any other
				staged[i] = targets[i].resolveSibling(targets[i].getFileName() + ".tmp");
				Files.write(staged[i], contents[i].getBytes(StandardCharsets.UTF_8));
			}
			for (int i = 0; i < 3; i++)
			{
				Files.move(staged[i], targets[i], StandardCopyOption.REPLACE_EXISTING);
				staged[i] = null;
			}
		}
		finally
		{
			for (Path p : staged)
			{
				if (p != null)
				{
					Files.deleteIfExists(p);
				}
			}
		}
	}
}
