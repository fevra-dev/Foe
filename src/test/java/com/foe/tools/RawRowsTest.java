package com.foe.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * The row type and the raw file. The JSON here is quoted from replies the live Bucket API gave on 2026-10-09: ids are
 * an array of strings, a field with no value is left out, and the percent is a bare integer.
 */
public class RawRowsTest
{
	private static JsonObject json(String text)
	{
		return new Gson().fromJson(text, JsonObject.class);
	}

	private static void assertRejected(String text)
	{
		try
		{
			WikiRow.fromJson(json(text));
			fail("should have been rejected: " + text);
		}
		catch (IllegalArgumentException expected)
		{
			assertNotNull(expected.getMessage());
		}
	}

	// ---- the row ----

	@Test
	public void aRowReadsInTheShapeTheBucketReturnsIt()
	{
		WikiRow row = WikiRow.fromJson(json(
			"{\"id\":[\"2189\",\"2190\",\"3116\",\"3117\"],\"page_name\":\"Tz-Kih\","
				+ "\"elemental_weakness\":\"Water\",\"elemental_weakness_percent\":40}"));
		assertEquals(WikiRow.of("Tz-Kih", null, "Water", 40L, "2189", "2190", "3116", "3117"), row);
		assertNull(row.versionAnchor);
	}

	@Test
	public void aRowWithOnlyAPageNameHasNoIdsAndNoWeakness()
	{
		// real: 4 rows come back with nothing but page_name
		WikiRow row = WikiRow.fromJson(json("{\"page_name\":\"Some page\"}"));
		assertEquals(Collections.emptyList(), row.ids);
		assertNull(row.element);
		assertNull(row.percent);
	}

	@Test
	public void aJsonNullIsTheSameAsALeftOutField()
	{
		WikiRow row = WikiRow.fromJson(json(
			"{\"page_name\":\"Thug\",\"version_anchor\":null,\"id\":[\"525\"],\"elemental_weakness\":null,"
				+ "\"elemental_weakness_percent\":null}"));
		assertEquals(WikiRow.of("Thug", null, null, null, "525"), row);
	}

	@Test
	public void aRowWritesBackWithFixedKeyOrderAndLeavesAbsentFieldsOut()
	{
		assertEquals("{\"page_name\":\"Fire giant\",\"version_anchor\":\"Level 86\",\"id\":[\"2075\",\"2076\"],"
				+ "\"elemental_weakness\":\"Water\",\"elemental_weakness_percent\":100}",
			WikiRow.of("Fire giant", "Level 86", "Water", 100L, "2075", "2076").toJson().toString());
		assertEquals("{\"page_name\":\"Thug\",\"id\":[\"525\"]}",
			WikiRow.of("Thug", null, null, null, "525").toJson().toString());
		assertEquals("{\"page_name\":\"King Black Dragon (Echo)\",\"elemental_weakness\":\"Water\","
				+ "\"elemental_weakness_percent\":50}",
			WikiRow.of("King Black Dragon (Echo)", null, "Water", 50L).toJson().toString());
	}

	@Test
	public void everyShapeRoundTrips()
	{
		List<WikiRow> rows = Arrays.asList(
			WikiRow.of("Fire giant", "Level 86", "Water", 100L, "2075", "2076"),
			WikiRow.of("Kraken", "Whirlpool", "None", null, "496"),
			WikiRow.of("Dagannoth Rex (Deadman)", "Apocalypse", null, null, "12439"),
			WikiRow.of("King Black Dragon (Echo)", null, "Water", 50L),
			WikiRow.of("Dinky the drink troll", null, "Earth", 0L, "15171"));
		for (WikiRow row : rows)
		{
			assertEquals(row, WikiRow.fromJson(json(row.toJson().toString())));
		}
	}

	@Test
	public void shapesTheBucketNeverProducesAreRejected()
	{
		assertRejected("{\"id\":[\"1\"]}");
		assertRejected("{\"page_name\":5}");
		assertRejected("{\"page_name\":\"A\",\"id\":\"1\"}");
		assertRejected("{\"page_name\":\"A\",\"id\":[1]}");
		assertRejected("{\"page_name\":\"A\",\"id\":[[\"1\"]]}");
		assertRejected("{\"page_name\":\"A\",\"elemental_weakness\":5}");
		assertRejected("{\"page_name\":\"A\",\"elemental_weakness_percent\":\"50\"}");
		assertRejected("{\"page_name\":\"A\",\"elemental_weakness_percent\":5.5}");
		assertRejected("{\"page_name\":\"A\",\"elemental_weakness_percent\":99999999999999999999}");
		assertRejected("{\"page_name\":\"A\",\"surprise\":1}");
	}

	// ---- the file ----

	@Test
	public void theFileStartsWithTheFetchDateThenTheRowsSortedAndEveryLineEndsInANewline()
	{
		WikiRow b = WikiRow.of("B page", null, "Fire", 5L, "2");
		WikiRow a = WikiRow.of("A page", "Tab", "Water", 40L, "1");
		String text = RawRows.write(Arrays.asList(b, a), "2026-10-09");
		String[] lines = text.split("\n", -1);
		assertEquals(4, lines.length);
		assertEquals("", lines[3]);
		assertTrue(lines[0], lines[0].startsWith("{\"_meta\":"));
		assertTrue(lines[0], lines[0].contains("\"fetched\":\"2026-10-09\""));
		assertEquals(a.toJson().toString(), lines[1]);
		assertEquals(b.toJson().toString(), lines[2]);
	}

	@Test
	public void theFileDoesNotDependOnTheOrderTheRowsArrivedIn()
	{
		List<WikiRow> rows = new ArrayList<>(Arrays.asList(
			WikiRow.of("C", null, "Fire", 5L, "3"),
			WikiRow.of("A", "x", "Water", 40L, "1"),
			WikiRow.of("A", "y", "Water", 40L, "2")));
		String one = RawRows.write(rows, "2026-10-09");
		Collections.reverse(rows);
		assertEquals(one, RawRows.write(rows, "2026-10-09"));
	}

	@Test
	public void aRepeatedRowIsWrittenOnce()
	{
		WikiRow awake = WikiRow.of("Duke Sucellus", "Awake", "Earth", 50L, "12191");
		String text = RawRows.write(Arrays.asList(awake, awake), "2026-10-09");
		assertEquals(3, text.split("\n", -1).length);
	}

	@Test
	public void aWrittenFileReadsBackToTheSameDateAndRows()
	{
		List<WikiRow> rows = Arrays.asList(
			WikiRow.of("Fire giant", "Level 86", "Water", 100L, "2075", "2076"),
			WikiRow.of("Kraken", "Whirlpool", "None", null, "496"),
			WikiRow.of("King Black Dragon (Echo)", null, "Water", 50L));
		RawRows.Parsed back = RawRows.read(RawRows.write(rows, "2026-10-09"));
		assertEquals("2026-10-09", back.fetchedDate);
		assertEquals(3, back.rows.size());
		assertTrue(back.rows.containsAll(rows));
	}

	@Test
	public void aFileTheGeneratorDidNotWriteIsRefused()
	{
		String row = WikiRow.of("A", null, "Fire", 5L, "1").toJson().toString();
		String meta = "{\"_meta\":{\"source\":\"x\",\"fetched\":\"2026-10-09\"}}";
		String[] bad = {
			"",
			row + "\n",
			meta + "\n" + row,
			meta.replace("2026-10-09", "yesterday") + "\n" + row + "\n",
			meta.replace("2026-10-09", "2026-13-40") + "\n" + row + "\n",
			meta + "\n\n" + row + "\n",
			meta + "\nnot json\n",
			meta + "\n" + meta + "\n",
			"{\"_meta\":{\"source\":\"x\"}}\n" + row + "\n",
		};
		for (String text : bad)
		{
			try
			{
				RawRows.read(text);
				fail("should have been refused: " + text.replace("\n", "|"));
			}
			catch (IllegalArgumentException expected)
			{
				assertNotNull(expected.getMessage());
			}
		}
	}
}
