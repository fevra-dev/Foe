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
	static final String REPORT = "build/weakness-report.txt";

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
		for (String arg : args)
		{
			if (arg.equals("--accept-shrink"))
			{
				acceptShrink = true;
			}
			else
			{
				err.println("unknown argument '" + arg + "'. The only one is --accept-shrink: let a table that is more "
					+ "than 5% smaller than the previous one be written.");
				return 2;
			}
		}

		// The previous table is what changes are measured against, so one we cannot read stops the run.
		WeaknessTableBuilder.PreviousTable previous = null;
		Path tablePath = root.resolve(TABLE);
		try
		{
			if (Files.exists(tablePath))
			{
				previous = WeaknessTableBuilder.PreviousTable.parse(
					new String(Files.readAllBytes(tablePath), StandardCharsets.UTF_8));
				out.println("previous table: " + previous.entries.size() + " ids (" + TABLE + ")");
			}
			else
			{
				out.println("no previous table: this is a first generation, so nothing is quarantined");
			}
		}
		catch (IllegalArgumentException | IOException e)
		{
			err.println("previous table unreadable, refusing to run: " + e.getMessage());
			return 1;
		}

		List<WikiRow> rows;
		Map<String, Instant> lastEdit;
		try
		{
			rows = fetch.fetchRows();
			out.println("fetched " + rows.size() + " rows");
			lastEdit = fetch.fetchLastEdits(pagesWithAWeaknessRow(rows));
			out.println("fetched last-edit times for " + lastEdit.size() + " pages");
		}
		catch (IOException e)
		{
			err.println("fetch failed, nothing written: " + e.getMessage());
			return 1;
		}

		Instant now = clock.instant();
		String fetchedDate = LocalDate.ofInstant(now, ZoneOffset.UTC).toString();
		WeaknessTableBuilder.Result result = WeaknessTableBuilder.build(rows, fetchedDate, previous, lastEdit, now,
			acceptShrink);
		if (!result.failures.isEmpty())
		{
			err.println("FAILED, nothing written (" + result.failures.size() + "):");
			for (String failure : result.failures)
			{
				err.println("  " + failure);
			}
			return 1;
		}

		String raw = RawRows.write(rows, fetchedDate);
		try
		{
			// The raw file is what the table is re-derived from offline, so prove it round-trips before it is kept.
			RawRows.Parsed back = RawRows.read(raw);
			WeaknessTableBuilder.Result again = WeaknessTableBuilder.build(back.rows, back.fetchedDate, previous,
				lastEdit, now, acceptShrink);
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

	/** Stages every file next to its target first, then moves them into place, so a failure part way leaves no half. */
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
