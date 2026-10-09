package com.foe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.Test;

/**
 * The saved form of the learned weaknesses (spec addendum 5): one string, {@code typeId:ELEMENT} joined by commas,
 * for example {@code 2103:EARTH,3025:FIRE,2006:NONE}. It comes off the disk, where a person can edit it, so reading it
 * is a trust boundary (ADR-0006): only a short ASCII id and one of five known names get through, and anything else
 * is dropped without a word and without an exception (ADR-0005: unknown is ignored, never guessed).
 */
public class WeaknessStoreTest
{
	private static final Weakness FIRE = new Weakness(Weakness.Element.FIRE);
	private static final Weakness EARTH = new Weakness(Weakness.Element.EARTH);
	private static final Weakness AIR = new Weakness(Weakness.Element.AIR);
	private static final Weakness WATER = new Weakness(Weakness.Element.WATER);

	/** What the config key holds, and how often it was touched. */
	private String stored;
	private int reads;
	private int writes;
	private final WeaknessStore store = new WeaknessStore(() ->
	{
		reads++;
		return stored;
	}, v ->
	{
		writes++;
		stored = v;
	});

	private static void assertEntries(Map<Integer, Weakness> expected, Map<Integer, Weakness> actual)
	{
		assertEquals(expected, actual);
	}

	private static Map<Integer, Weakness> map(Object... keyValue)
	{
		Map<Integer, Weakness> m = new TreeMap<>();
		for (int i = 0; i < keyValue.length; i += 2)
		{
			m.put((Integer) keyValue[i], (Weakness) keyValue[i + 1]);
		}
		return m;
	}

	// ---- the key ----

	@Test
	public void theKeyIsOneNamedValueInTheFoeGroup()
	{
		assertEquals("learnedWeaknesses", WeaknessStore.KEY);
		assertEquals("foe", FoeConfig.GROUP);
	}

	// ---- format and parse ----

	@Test
	public void theDocumentedExampleParses()
	{
		Map<Integer, Weakness> m = WeaknessStore.parse("2103:EARTH,3025:FIRE,2006:NONE");
		assertEquals(3, m.size());
		assertEquals(EARTH, m.get(2103));
		assertEquals(FIRE, m.get(3025));
		assertSame("NONE is the one shared instance, which weaknessFor tests by identity", Weakness.NONE, m.get(2006));
	}

	@Test
	public void everyElementAndNoneRoundTripInKeyOrder()
	{
		Map<Integer, Weakness> all = map(3025, FIRE, 2103, EARTH, 7, AIR, 12, WATER, 2006, Weakness.NONE);
		String text = WeaknessStore.format(all);
		assertEquals("numeric key order, not text order: 7 before 12", "7:AIR,12:WATER,2006:NONE,2103:EARTH,3025:FIRE", text);
		Map<Integer, Weakness> back = WeaknessStore.parse(text);
		assertEntries(all, back);
		assertSame(Weakness.NONE, back.get(2006));
	}

	@Test
	public void formatSortsWhateverOrderTheMapIterates()
	{
		Map<Integer, Weakness> backwards = new java.util.LinkedHashMap<>();
		backwards.put(3025, FIRE);
		backwards.put(2103, EARTH);
		backwards.put(7, AIR);
		assertEquals("7:AIR,2103:EARTH,3025:FIRE", WeaknessStore.format(backwards));
	}

	@Test
	public void formatLeavesOutWhatCouldNotBeReadBack()
	{
		Map<Integer, Weakness> odd = new TreeMap<>();
		odd.put(-3, FIRE);
		odd.put(4, null);
		odd.put(5, WATER);
		assertEquals("5:WATER", WeaknessStore.format(odd));
	}

	@Test
	public void anEmptyMapIsAnEmptyString()
	{
		assertEquals("", WeaknessStore.format(map()));
		assertTrue(WeaknessStore.parse("").isEmpty());
		assertTrue(WeaknessStore.parse(null).isEmpty());
		assertTrue(WeaknessStore.parse("   ").isEmpty());
		assertTrue(WeaknessStore.parse(",,,").isEmpty());
	}

	@Test
	public void malformedTokensAreIgnoredAndTheGoodOnesAroundThemKept()
	{
		String text = String.join(",",
			"2103:EARTH",
			"", // empty
			"abc", // no colon
			"12", // no colon, a number
			"12:", // no element
			":FIRE", // no id
			"-5:FIRE", // negative id
			"+5:FIRE", // a sign
			"1e3:FIRE", // not digits
			"99999999999:FIRE", // too long for an id
			"٣٢:FIRE", // Arabic-Indic digits, which Integer.parseInt would accept
			"5:FIRE:EXTRA", // trailing junk in the name
			"9:A\u0131R", // a dotless i, which String.equalsIgnoreCase would fold into AIR
			"10:F\u0130RE", // a dotted capital I, ditto
			"7:LIGHTNING", // an element nobody knows
			"3025:FIRE");
		assertEntries(map(2103, EARTH, 3025, FIRE), WeaknessStore.parse(text));
	}

	@Test
	public void unknownElementNamesAreIgnoredNotGuessed()
	{
		assertTrue(WeaknessStore.parse("1:PLASMA,2:,3:null,4:NONEX,5:0,6:554").isEmpty());
	}

	@Test
	public void whitespaceAndCaseAreForgiven()
	{
		assertEntries(map(4, WATER, 5, FIRE, 6, Weakness.NONE), WeaknessStore.parse(" 4 : water ,\t5:Fire\n,6:none"));
	}

	@Test
	public void aRepeatedIdKeepsTheLastValue()
	{
		assertEntries(map(7, EARTH), WeaknessStore.parse("7:FIRE,7:EARTH"));
	}

	@Test
	public void leadingZerosAreTheSameIdAndComeBackCanonical()
	{
		Map<Integer, Weakness> m = WeaknessStore.parse("007:FIRE");
		assertEntries(map(7, FIRE), m);
		assertEquals("7:FIRE", WeaknessStore.format(m));
	}

	@Test
	public void noInputThrowsAndEveryEntryThatSurvivesIsWellFormed()
	{
		Random random = new Random(20261008L);
		String[] ids = {"0", "7", "2103", "99999999", "007", "", " 5 ", "-1", "+3", "12a", "1.5", "\u0663", "9999999999", "\u0000"};
		String[] names = {"FIRE", "water", "Earth", "AIR", "none", "NONE", " fire ", "", "LIGHTNING", "554", "FIRE:X", "A\u0131R", "\uffff"};
		String[] glue = {":", ":", ":", "", "::", " : ", ";"};
		int kept = 0;
		int tokensSeen = 0;
		for (int i = 0; i < 20_000; i++)
		{
			StringBuilder sb = new StringBuilder();
			int n = random.nextInt(8);
			for (int j = 0; j < n; j++)
			{
				if (j > 0)
				{
					sb.append(random.nextInt(10) == 0 ? ";" : ",");
				}
				sb.append(ids[random.nextInt(ids.length)]).append(glue[random.nextInt(glue.length)])
					.append(names[random.nextInt(names.length)]);
				tokensSeen++;
			}
			Map<Integer, Weakness> m = WeaknessStore.parse(sb.toString());
			for (Map.Entry<Integer, Weakness> e : m.entrySet())
			{
				assertTrue("id " + e.getKey() + " from " + sb, e.getKey() >= 0);
				assertNotNull(e.getValue());
				kept++;
			}
			assertEquals("what survives parses back to itself", m, WeaknessStore.parse(WeaknessStore.format(m)));
		}
		assertTrue("the fuzz reached real entries, not only rejections: kept " + kept + " of ~" + tokensSeen,
			kept > 1000 && kept < tokensSeen);
	}

	@Test
	public void aHugeValueIsCappedNotLoadedWhole()
	{
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < WeaknessStore.MAX_ENTRIES + 500; i++)
		{
			sb.append(i).append(":FIRE,");
		}
		assertEquals(WeaknessStore.MAX_ENTRIES, WeaknessStore.parse(sb.toString()).size());
	}

	// Review of 77ccc4e, finding 5: a value past the cap was rewritten from what was parsed, deleting the entries
	// beyond it for good. A capped value is never rewritten.
	@Test
	public void aValueAtTheCapIsNeverRewrittenSoEntriesPastItAreNotLost()
	{
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < WeaknessStore.MAX_ENTRIES; i++)
		{
			sb.append("junk,");
		}
		sb.append("3025:FIRE");
		stored = sb.toString();
		store.put(2103, new Weakness(Weakness.Element.EARTH));
		assertEquals(0, writes);
		assertTrue(stored.endsWith("3025:FIRE"));
	}

	// ---- load ----

	@Test
	public void loadReadsTheKeyOnceAndParsesIt()
	{
		stored = "2103:EARTH,garbage";
		assertEntries(map(2103, EARTH), store.load());
		assertEquals(1, reads);
		assertEquals(0, writes);
	}

	@Test
	public void loadingNothingIsAnEmptyMap()
	{
		stored = null;
		assertTrue(store.load().isEmpty());
	}

	// ---- put: one entry merged into what is stored ----

	@Test
	public void putWritesTheEntryAndReadsAndWritesTheKeyOnceEach()
	{
		store.put(2103, EARTH);
		assertEquals("2103:EARTH", stored);
		assertEquals(1, reads);
		assertEquals(1, writes);
	}

	@Test
	public void putMergesIntoWhatIsStoredAndNeverReplacesIt()
	{
		stored = "6:AIR,5:FIRE"; // not even in order
		store.put(7, EARTH);
		assertEquals("5:FIRE,6:AIR,7:EARTH", stored);
	}

	@Test
	public void putOfTheSameValueWritesNothing()
	{
		stored = "2103:EARTH";
		store.put(2103, EARTH);
		assertEquals(0, writes);
		assertEquals("2103:EARTH", stored);
	}

	@Test
	public void putOfAChangedValueWritesOverThatEntryOnly()
	{
		stored = "5:FIRE,2103:EARTH";
		store.put(2103, WATER);
		assertEquals("5:FIRE,2103:WATER", stored);
		store.put(2103, Weakness.NONE);
		assertEquals("5:FIRE,2103:NONE", stored);
		assertEquals(2, writes);
	}

	@Test
	public void putCleansMalformedTokensOutOfTheStoredValueAndKeepsTheGoodOnes()
	{
		stored = "junk,5:FIRE,6:PLASMA";
		store.put(7, AIR);
		assertEquals("5:FIRE,7:AIR", stored);
	}

	@Test
	public void putIntoAStoredValueThatIsMissingStartsIt()
	{
		stored = null;
		store.put(1, Weakness.NONE);
		assertEquals("1:NONE", stored);
	}

	@Test
	public void putOnAGarbageValueThatHoldsNothingUsableStillWritesTheEntry()
	{
		stored = "%%%";
		store.put(9, FIRE);
		assertEquals("9:FIRE", stored);
	}
}
