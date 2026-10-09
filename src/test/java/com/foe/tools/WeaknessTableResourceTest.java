package com.foe.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.foe.tools.WeaknessTableBuilder.Result;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * The committed table, tested as shipped (addendum 8 F6 and F8). It is read the way the plugin will read it, through
 * {@code getResourceAsStream}, because a table that is missing from the jar must fail a test and not the feature.
 * And it is re-derived from the committed raw wiki rows, so a reviewer can verify table = generator(raw) offline.
 */
public class WeaknessTableResourceTest
{
	private static final String RESOURCE = "/com/foe/weakness-table.tsv";
	private static final String RAW_FILE = "data/wiki-infobox-monster.jsonl";
	/** Measured 2026-10-09: 1,754 ids with a weakness after conflicts are dropped. */
	private static final int FLOOR = 1600;
	private static final Pattern LINE = Pattern.compile("(\\d+)\t(AIR|WATER|EARTH|FIRE|NONE)\t(\\d*)");

	private static String resource() throws IOException
	{
		try (InputStream in = WeaknessTableResourceTest.class.getResourceAsStream(RESOURCE))
		{
			assertNotNull("the table is not on the classpath at " + RESOURCE, in);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			for (int n; (n = in.read(buf)) > 0; )
			{
				out.write(buf, 0, n);
			}
			return new String(out.toByteArray(), StandardCharsets.UTF_8);
		}
	}

	/** Read with a parser of its own, so these assertions do not lean on the code that wrote the file. */
	private static Map<Integer, String> entries(String table)
	{
		Map<Integer, String> entries = new HashMap<>();
		List<String> bad = new ArrayList<>();
		for (String line : table.split("\n", -1))
		{
			if (line.isEmpty() || line.startsWith("#"))
			{
				continue;
			}
			Matcher m = LINE.matcher(line);
			if (!m.matches())
			{
				bad.add(line);
				continue;
			}
			entries.put(Integer.valueOf(m.group(1)), m.group(2) + "\t" + m.group(3));
		}
		assertTrue("lines that are not 'id<TAB>ELEMENT<TAB>percent': " + bad, bad.isEmpty());
		return entries;
	}

	@Test
	public void theTableIsOnTheClasspathAndBigEnough() throws IOException
	{
		Map<Integer, String> entries = entries(resource());
		assertTrue("expected at least " + FLOOR + " entries, found " + entries.size(), entries.size() >= FLOOR);
	}

	@Test
	public void theKnownAnswersAreThere() throws IOException
	{
		Map<Integer, String> entries = entries(resource());
		assertEquals("Fire giant", "WATER\t100", entries.get(2075));
		assertEquals("Kraken", "EARTH\t50", entries.get(494));
		assertEquals("Whirlpool: a positive no-weakness", "NONE\t", entries.get(496));
		assertEquals("Spiritual mage (Zaros): over 100 is kept", "FIRE\t200", entries.get(11292));
		assertEquals("Maggot King: the tabs disagree on the percent, so the element alone",
			"FIRE\t", entries.get(15742));
		assertEquals("Dagannoth Rex (Deadman): the blank Apocalypse tab is ignored", "EARTH\t35", entries.get(12439));
	}

	@Test
	public void theHeaderStatesWhereAndWhenItCameFrom() throws IOException
	{
		String table = resource();
		assertTrue(table.startsWith("#"));
		assertTrue(table, table.contains("# Source: https://oldschool.runescape.wiki/api.php"));
		assertTrue(table, table.contains("CC BY-NC-SA 3.0"));
		assertTrue(table, Pattern.compile("# Fetched \\(UTC\\): \\d{4}-\\d{2}-\\d{2}\n").matcher(table).find());
		assertFalse("no carriage returns", table.contains("\r"));
		assertTrue(table.endsWith("\n"));
	}

	@Test
	public void theTableIsExactlyWhatTheGeneratorDerivesFromTheCommittedRawRows() throws IOException
	{
		String raw = new String(Files.readAllBytes(Paths.get(RAW_FILE)), StandardCharsets.UTF_8);
		RawRows.Parsed parsed = RawRows.read(raw);
		// no previous table and no edit times: the first generation, which is the only one this test can reproduce
		Result derived = WeaknessTableBuilder.build(parsed.rows, parsed.fetchedDate, null, Collections.emptyMap(),
			Instant.parse("2000-01-01T00:00:00Z"), false);
		assertTrue("the committed raw rows fail a generator rule: " + derived.failures, derived.failures.isEmpty());
		assertEquals("the raw file is exactly what the generator writes: sorted, escaped, no duplicates, no hand edits",
			raw, RawRows.write(parsed.rows, parsed.fetchedDate));
		String committed = resource();
		if (!derived.table.equals(committed))
		{
			String[] want = derived.table.split("\n", -1);
			String[] have = committed.split("\n", -1);
			for (int i = 0; i < Math.max(want.length, have.length); i++)
			{
				String w = i < want.length ? want[i] : "(end of file)";
				String h = i < have.length ? have[i] : "(end of file)";
				if (!w.equals(h))
				{
					throw new AssertionError("the committed table differs from generator(raw) at line " + (i + 1)
						+ ": derived '" + w + "' but committed '" + h + "'");
				}
			}
		}
		assertEquals(derived.table, committed);
	}
}
