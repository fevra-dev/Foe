package com.foe.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.foe.tools.WeaknessTableBuilder.Result;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The main of the generator against a fake wiki and a temp directory: what it writes, what it refuses to write, and
 * that a refusal leaves the working tree exactly as it was.
 */
public class GenerateWeaknessTableTest
{
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
	private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
	private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
	private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

	private static final Instant T0 = Instant.parse("2026-10-09T12:00:00Z");

	/** A wiki that serves the given rows from the bucket and the given edit times from the query API. */
	private static final class FakeWiki implements WikiFetch.Transport
	{
		final List<WikiRow> rows;
		final Map<String, Instant> edits = new HashMap<>();
		int status = 200;
		int requests;

		FakeWiki(List<WikiRow> rows)
		{
			this.rows = rows;
		}

		@Override
		public WikiFetch.Reply get(URI uri, String userAgent)
		{
			requests++;
			if (status != 200)
			{
				return new WikiFetch.Reply(status, "{}");
			}
			Map<String, String> q = new HashMap<>();
			for (String pair : uri.getRawQuery().split("&"))
			{
				int eq = pair.indexOf('=');
				q.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
					URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
			}
			if ("bucket".equals(q.get("action")))
			{
				String query = q.get("query");
				int limit = Integer.parseInt(query.substring(query.indexOf(".limit(") + 7, query.indexOf(").offset(")));
				int offset = Integer.parseInt(query.substring(query.indexOf(".offset(") + 8, query.indexOf(").run()")));
				JsonArray array = new JsonArray();
				for (int i = offset; i < Math.min(rows.size(), offset + limit); i++)
				{
					array.add(rows.get(i).toJson());
				}
				JsonObject o = new JsonObject();
				o.add("bucket", array);
				return new WikiFetch.Reply(200, o.toString());
			}
			JsonObject pages = new JsonObject();
			int n = 0;
			for (String title : q.get("titles").split("\\|"))
			{
				Instant t = edits.get(title);
				if (t != null)
				{
					JsonObject rev = new JsonObject();
					rev.addProperty("timestamp", t.toString());
					JsonArray revs = new JsonArray();
					revs.add(rev);
					JsonObject page = new JsonObject();
					page.addProperty("title", title);
					page.add("revisions", revs);
					pages.add(String.valueOf(++n), page);
				}
			}
			JsonObject query = new JsonObject();
			query.add("pages", pages);
			JsonObject o = new JsonObject();
			o.add("query", query);
			return new WikiFetch.Reply(200, o.toString());
		}
	}

	private int run(FakeWiki wiki, Instant at, String... args) throws IOException
	{
		Path root = folder.getRoot().toPath();
		return GenerateWeaknessTable.run(args, root, new WikiFetch(wiki, ms -> { }, 2),
			Clock.fixed(at, ZoneOffset.UTC), out, err);
	}

	private Path file(String relative)
	{
		return folder.getRoot().toPath().resolve(relative);
	}

	private String read(String relative) throws IOException
	{
		return new String(Files.readAllBytes(file(relative)), StandardCharsets.UTF_8);
	}

	private String stderr()
	{
		return new String(errBytes.toByteArray(), StandardCharsets.UTF_8);
	}

	private static List<WikiRow> goodRows()
	{
		List<WikiRow> rows = new ArrayList<>();
		rows.add(WikiRow.of("Fire giant", "Level 86", "Water", 100L, "2075", "2076"));
		rows.add(WikiRow.of("Maggot King", "Nearby", "Fire", 5L, "15742"));
		rows.add(WikiRow.of("Maggot King", "Far", "Fire", 80L, "15742"));
		rows.add(WikiRow.of("Kraken", "Whirlpool", "None", null, "496"));
		rows.add(WikiRow.of("Dagannoth Rex (Deadman)", "Permanent", "earth", 35L, "12439"));
		rows.add(WikiRow.of("Dagannoth Rex (Deadman)", "Apocalypse", null, null, "12439"));
		rows.add(WikiRow.of("Thug", null, null, null, "525"));
		return rows;
	}

	private static boolean noFilesBelow(Path root) throws IOException
	{
		try (java.util.stream.Stream<Path> s = Files.walk(root))
		{
			return s.noneMatch(Files::isRegularFile);
		}
	}

	// ---- a clean run ----

	@Test
	public void aCleanRunWritesTheRawFileTheTableAndTheReport() throws IOException
	{
		FakeWiki wiki = new FakeWiki(goodRows());
		wiki.edits.put("Fire giant", T0.minusSeconds(86400));
		int rc = run(wiki, T0);
		assertEquals(stderr(), 0, rc);

		String raw = read(GenerateWeaknessTable.RAW);
		String table = read(GenerateWeaknessTable.TABLE);
		String report = read(GenerateWeaknessTable.REPORT);
		assertTrue(raw, raw.startsWith("{\"_meta\":"));
		assertTrue(table, table.contains("2075\tWATER\t100\n"));
		assertTrue(table, table.contains("15742\tFIRE\t\n"));
		assertTrue(table, table.contains("496\tNONE\t\n"));
		assertTrue(table, table.contains("12439\tEARTH\t35\n"));
		assertFalse(table, table.contains("525\t"));
		assertTrue(report, report.contains(WeaknessTableBuilder.Report.PERCENT_CONFLICTS));
		assertTrue("the recent page is listed with the edit time the wiki gave", report.contains("Fire giant (last edit "
			+ T0.minusSeconds(86400) + ")"));
	}

	@Test
	public void theFilesGetTheSamePermissionsAsAnyOtherFileMadeInThatFolder() throws IOException
	{
		assertEquals(stderr(), 0, run(new FakeWiki(goodRows()), T0));
		Path plain = folder.newFile("plain.txt").toPath();
		if (!Files.getFileStore(plain).supportsFileAttributeView("posix"))
		{
			return;
		}
		// the staging files must not be owner-only temp files: a committed table should look like any other
		for (String name : new String[] {GenerateWeaknessTable.RAW, GenerateWeaknessTable.TABLE,
			GenerateWeaknessTable.REPORT})
		{
			assertEquals(name, Files.getPosixFilePermissions(plain), Files.getPosixFilePermissions(file(name)));
		}
	}

	@Test
	public void theTableIsExactlyWhatTheBuilderDerivesFromTheRawFileAlone() throws IOException
	{
		// the property WeaknessTableResourceTest asserts on the committed files: table = generator(raw)
		FakeWiki wiki = new FakeWiki(goodRows());
		wiki.edits.put("Fire giant", T0.minusSeconds(86400));
		assertEquals(stderr(), 0, run(wiki, T0));
		RawRows.Parsed parsed = RawRows.read(read(GenerateWeaknessTable.RAW));
		Result again = WeaknessTableBuilder.build(parsed.rows, parsed.fetchedDate, null, Collections.emptyMap(), T0,
			false);
		assertEquals(again.table, read(GenerateWeaknessTable.TABLE));
	}

	@Test
	public void theFetchDateIsTheUtcDateWhateverTheMachinesZoneIs() throws IOException
	{
		TimeZone before = TimeZone.getDefault();
		TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"));
		try
		{
			// 23:30 UTC on the 9th is already the 10th in Auckland
			assertEquals(stderr(), 0, run(new FakeWiki(goodRows()), Instant.parse("2026-10-09T23:30:00Z")));
		}
		finally
		{
			TimeZone.setDefault(before);
		}
		assertTrue(read(GenerateWeaknessTable.TABLE).contains("# Fetched (UTC): 2026-10-09\n"));
		assertTrue(read(GenerateWeaknessTable.RAW).contains("\"fetched\":\"2026-10-09\""));
	}

	// ---- failure writes nothing ----

	@Test
	public void aFailureRuleWritesNothingAtAll() throws IOException
	{
		List<WikiRow> rows = goodRows();
		rows.add(WikiRow.of("Some dragon", "Normal", "Dragonfire", 50L, "900"));
		int rc = run(new FakeWiki(rows), T0);
		assertEquals(1, rc);
		assertTrue(stderr(), stderr().contains("Dragonfire"));
		assertTrue(stderr(), stderr().contains("Some dragon"));
		assertTrue("not even the raw file", noFilesBelow(folder.getRoot().toPath()));
	}

	@Test
	public void whatItPrintsAboutAHostileRowCannotActOnTheTerminal() throws IOException
	{
		List<WikiRow> rows = goodRows();
		rows.add(WikiRow.of("Evil\u001b[2J\u009b", "Tab\u202e", "Dragonfire\u001b[31m", 50L, "900"));
		assertEquals(1, run(new FakeWiki(rows), T0));
		String shown = stderr() + new String(outBytes.toByteArray(), StandardCharsets.UTF_8);
		assertFalse(shown, TextTest.isHostileToPrint(shown.replace("\n", "").replace("\r", "")));
		assertTrue(shown, shown.contains("Dragonfire"));
	}

	@Test
	public void aFetchFailureWritesNothingAndLeavesThePreviousTableAlone() throws IOException
	{
		assertEquals(0, run(new FakeWiki(goodRows()), T0));
		String tableBefore = read(GenerateWeaknessTable.TABLE);
		String rawBefore = read(GenerateWeaknessTable.RAW);
		FakeWiki down = new FakeWiki(goodRows());
		down.status = 503;
		assertEquals(1, run(down, T0.plusSeconds(86400 * 30)));
		assertTrue(stderr(), stderr().contains("503"));
		assertEquals(tableBefore, read(GenerateWeaknessTable.TABLE));
		assertEquals(rawBefore, read(GenerateWeaknessTable.RAW));
	}

	@Test
	public void aPreviousTableTheGeneratorCannotReadStopsTheRun() throws IOException
	{
		Files.createDirectories(file(GenerateWeaknessTable.TABLE).getParent());
		Files.write(file(GenerateWeaknessTable.TABLE), "# Ids written: 5\nhand edited garbage\n".getBytes(StandardCharsets.UTF_8));
		FakeWiki wiki = new FakeWiki(goodRows());
		assertEquals(1, run(wiki, T0));
		assertTrue(stderr(), stderr().contains("previous table"));
		assertEquals("never even asked the wiki", 0, wiki.requests);
		assertEquals("# Ids written: 5\nhand edited garbage\n", read(GenerateWeaknessTable.TABLE));
	}

	@Test
	public void anArgumentItDoesNotKnowIsRefusedBeforeAnyRequest() throws IOException
	{
		FakeWiki wiki = new FakeWiki(goodRows());
		assertEquals(2, run(wiki, T0, "--accept-shrnk"));
		assertEquals(0, wiki.requests);
		assertTrue(noFilesBelow(folder.getRoot().toPath()));
		assertTrue(stderr(), stderr().contains("--accept-shrink"));
	}

	// ---- a second run: quarantine and shrink ----

	@Test
	public void aSecondRunKeepsAValueThatChangedOnAPageEditedYesterday() throws IOException
	{
		FakeWiki first = new FakeWiki(goodRows());
		first.edits.put("Fire giant", T0.minusSeconds(86400 * 30));
		assertEquals(0, run(first, T0));

		List<WikiRow> changed = goodRows();
		changed.set(0, WikiRow.of("Fire giant", "Level 86", "Fire", 100L, "2075", "2076"));
		FakeWiki second = new FakeWiki(changed);
		second.edits.put("Fire giant", T0.plusSeconds(86400 * 10 - 86400));
		assertEquals(stderr(), 0, run(second, T0.plusSeconds(86400 * 10)));

		String table = read(GenerateWeaknessTable.TABLE);
		assertTrue(table, table.contains("2075\tWATER\t100\n"));
		assertTrue(table, table.contains("# Changes held back, page edited within 7 days: 2\n"));
		assertTrue(read(GenerateWeaknessTable.REPORT).contains(WeaknessTableBuilder.Report.PENDING));
	}

	@Test
	public void aShrinkNeedsTheFlagAndTheHeaderRecordsIt() throws IOException
	{
		List<WikiRow> many = new ArrayList<>();
		String[] ids = new String[100];
		for (int i = 0; i < 100; i++)
		{
			ids[i] = String.valueOf(i + 1);
		}
		many.add(WikiRow.of("Big page", null, "Fire", 50L, ids));
		assertEquals(0, run(new FakeWiki(many), T0));
		String before = read(GenerateWeaknessTable.TABLE);

		List<WikiRow> fewer = new ArrayList<>();
		String[] some = new String[90];
		System.arraycopy(ids, 0, some, 0, 90);
		fewer.add(WikiRow.of("Big page", null, "Fire", 50L, some));
		assertEquals(1, run(new FakeWiki(fewer), T0.plusSeconds(1)));
		assertTrue(stderr(), stderr().contains("--accept-shrink"));
		assertEquals("a refused run leaves the table alone", before, read(GenerateWeaknessTable.TABLE));

		assertEquals(stderr(), 0, run(new FakeWiki(fewer), T0.plusSeconds(2), "--accept-shrink"));
		assertTrue(read(GenerateWeaknessTable.TABLE).contains("# Accept-shrink: yes"));
	}
}
