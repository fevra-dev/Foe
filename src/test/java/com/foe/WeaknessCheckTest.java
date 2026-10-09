package com.foe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.foe.Weakness.Element;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Spec addendum 11: the learned-vs-table check. A pure function of what was learned and what the table says, so what
 * it counts and what it prints are asserted here and the plugin only has to log them.
 */
public class WeaknessCheckTest
{
	private static final Weakness LEARNED_FIRE = new Weakness(Element.FIRE);
	private static final Weakness LEARNED_WATER = new Weakness(Element.WATER);
	private static final Weakness LEARNED_AIR = new Weakness(Element.AIR);

	private static Map<Integer, Weakness> map(Object... keyValue)
	{
		Map<Integer, Weakness> m = new HashMap<>();
		for (int i = 0; i < keyValue.length; i += 2)
		{
			m.put((Integer) keyValue[i], (Weakness) keyValue[i + 1]);
		}
		return m;
	}

	@Test
	public void countsAgreementsDisagreementsAndTypesTheTableDoesNotHave()
	{
		Map<Integer, Weakness> learned = map(
			1, LEARNED_FIRE,        // table FIRE 50: agree (a learned entry has no percent to compare)
			2, LEARNED_WATER,       // table FIRE 100: disagree
			3, Weakness.NONE,       // table EARTH 60: disagree, and the case the spec names
			4, Weakness.NONE,       // table NONE: agree
			5, LEARNED_AIR,         // not in the table: neither
			6, LEARNED_FIRE);       // table NONE: disagree
		Map<Integer, Weakness> table = map(
			1, new Weakness(Element.FIRE, 50),
			2, new Weakness(Element.FIRE, 100),
			3, new Weakness(Element.EARTH, 60),
			4, Weakness.NONE,
			6, Weakness.NONE,
			7, new Weakness(Element.AIR, 50)); // in the table, never learned: not counted
		WeaknessCheck c = WeaknessCheck.compare(learned, table);
		assertEquals(6, c.learned);
		assertEquals(2, c.agree);
		assertEquals(3, c.disagree);
		assertEquals(1, c.notInTable);
		assertEquals("every learned entry is exactly one of the three", c.learned, c.agree + c.disagree + c.notInTable);
		assertEquals(Arrays.asList(
			"2 learned=WATER table=FIRE 100",
			"3 learned=NONE table=EARTH 60",
			"6 learned=FIRE table=NONE"), c.lines);
		assertEquals("weakness check: 6 learned, 2 agree with the table, 3 disagree, 1 not in the table", c.summary());
	}

	@Test
	public void aLearnedNoneAgainstATableWeaknessIsListed()
	{
		WeaknessCheck c = WeaknessCheck.compare(map(2103, Weakness.NONE), map(2103, new Weakness(Element.EARTH, 60)));
		assertEquals(1, c.disagree);
		assertEquals(Collections.singletonList("2103 learned=NONE table=EARTH 60"), c.lines);
	}

	@Test
	public void aTableElementWithNoPercentIsListedWithoutOne()
	{
		WeaknessCheck c = WeaknessCheck.compare(map(15742, LEARNED_WATER), map(15742, new Weakness(Element.FIRE)));
		assertEquals(Collections.singletonList("15742 learned=WATER table=FIRE"), c.lines);
	}

	@Test
	public void aPercentOverOneHundredAndAPercentOfZeroAreListedAsGiven()
	{
		WeaknessCheck c = WeaknessCheck.compare(map(1, LEARNED_WATER, 2, LEARNED_WATER),
			map(1, new Weakness(Element.FIRE, 200), 2, new Weakness(Element.FIRE, 0)));
		assertEquals(Arrays.asList("1 learned=WATER table=FIRE 200", "2 learned=WATER table=FIRE 0"), c.lines);
	}

	@Test
	public void theSamePercentDoesNotMatterToAgreement()
	{
		WeaknessCheck c = WeaknessCheck.compare(map(1, LEARNED_FIRE), map(1, new Weakness(Element.FIRE, 200)));
		assertEquals(1, c.agree);
		assertEquals(0, c.disagree);
		assertTrue(c.lines.isEmpty());
	}

	@Test
	public void linesComeInAscendingIdOrderWhateverTheMapOrder()
	{
		Map<Integer, Weakness> learned = new HashMap<>();
		Map<Integer, Weakness> table = new HashMap<>();
		for (int id : new int[] {900000, 5, 31337, 77, 123456})
		{
			learned.put(id, LEARNED_WATER);
			table.put(id, new Weakness(Element.FIRE, 50));
		}
		assertEquals(Arrays.asList("5", "77", "31337", "123456", "900000"), ids(WeaknessCheck.compare(learned, table)));
	}

	private static java.util.List<String> ids(WeaknessCheck c)
	{
		java.util.List<String> out = new java.util.ArrayList<>();
		for (String line : c.lines)
		{
			out.add(line.substring(0, line.indexOf(' ')));
		}
		return out;
	}

	@Test
	public void nothingLearnedSaysSoAndListsNothing()
	{
		WeaknessCheck c = WeaknessCheck.compare(Collections.<Integer, Weakness>emptyMap(), map(1, new Weakness(Element.FIRE, 50)));
		assertEquals("weakness check: 0 learned, 0 agree with the table, 0 disagree, 0 not in the table", c.summary());
		assertTrue(c.lines.isEmpty());
	}

	@Test
	public void anEmptyTableMeansEveryLearnedTypeIsNotInIt()
	{
		WeaknessCheck c = WeaknessCheck.compare(map(1, LEARNED_FIRE, 2, Weakness.NONE), Collections.<Integer, Weakness>emptyMap());
		assertEquals(2, c.learned);
		assertEquals(0, c.agree);
		assertEquals(0, c.disagree);
		assertEquals(2, c.notInTable);
		assertTrue(c.lines.isEmpty());
		assertEquals("weakness check: 2 learned, 0 agree with the table, 0 disagree, 2 not in the table", c.summary());
	}

	@Test
	public void theLinesCarryNoTextButIdsAndFixedWords()
	{
		// Every part of a line is an int or an enum name that came out of the strict readers (ADR-0006).
		WeaknessCheck c = WeaknessCheck.compare(map(1, LEARNED_WATER), map(1, new Weakness(Element.FIRE, 50)));
		assertEquals("there is a line to look at", 1, c.lines.size());
		for (String line : c.lines)
		{
			assertTrue(line, line.matches("[0-9]+ learned=(AIR|WATER|EARTH|FIRE|NONE) table=(AIR|WATER|EARTH|FIRE|NONE)( [0-9]+)?"));
		}
	}

	// ---- the real corpus (this machine's own table), not a fixture ----

	private static Map<Integer, Weakness> realTable() throws IOException
	{
		try (InputStream in = WeaknessTable.class.getResourceAsStream(WeaknessTable.RESOURCE))
		{
			WeaknessTable.Loaded t = WeaknessTable.load(in);
			assertNotNull(t.entries);
			assertTrue(t.entries.size() > 1600);
			return t.entries;
		}
	}

	@Test
	public void aStoreThatMirrorsTheRealTableAgreesWithAllOfIt() throws IOException
	{
		Map<Integer, Weakness> table = realTable();
		Map<Integer, Weakness> learned = new HashMap<>();
		for (Map.Entry<Integer, Weakness> e : table.entrySet())
		{
			// what a credit would have stored: the element only, never the percent
			learned.put(e.getKey(), e.getValue().getElement() == null ? Weakness.NONE : new Weakness(e.getValue().getElement()));
		}
		WeaknessCheck c = WeaknessCheck.compare(learned, table);
		assertEquals(table.size(), c.learned);
		assertEquals(table.size(), c.agree);
		assertEquals(0, c.disagree);
		assertTrue(c.lines.isEmpty());
	}

	@Test
	public void aStoreOfNothingButNonesDisagreesWithEveryTableWeaknessAndNoOtherEntry() throws IOException
	{
		Map<Integer, Weakness> table = realTable();
		Map<Integer, Weakness> learned = new HashMap<>();
		int weak = 0;
		for (Map.Entry<Integer, Weakness> e : table.entrySet())
		{
			learned.put(e.getKey(), Weakness.NONE);
			if (e.getValue().getElement() != null)
			{
				weak++;
			}
		}
		assertTrue("the real table has both kinds, or this proves little", weak > 0 && weak < table.size());
		WeaknessCheck c = WeaknessCheck.compare(learned, table);
		assertEquals(weak, c.disagree);
		assertEquals(table.size() - weak, c.agree);
		assertEquals(weak, c.lines.size());
	}
}
