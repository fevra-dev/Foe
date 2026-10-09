package com.foe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.foe.Weakness.Element;
import com.foe.WeaknessTable.Loaded;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * The runtime half of the wiki table (spec addenda 7, 8 and 9): the strict loader, which never throws, and the
 * precedence rule {@link WeaknessTable#resolve}. The generator that writes the file has its own tests in
 * com.foe.tools and is not shipped, so this reads the file with the production code only.
 */
public class WeaknessTableTest
{
	private static final Weakness FIRE_50 = new Weakness(Element.FIRE, 50);
	private static final Weakness FIRE_200 = new Weakness(Element.FIRE, 200);
	private static final Weakness FIRE_ONLY = new Weakness(Element.FIRE);
	private static final Weakness WATER_100 = new Weakness(Element.WATER, 100);
	private static final Weakness LEARNED_FIRE = new Weakness(Element.FIRE);
	private static final Weakness LEARNED_WATER = new Weakness(Element.WATER);

	private static Loaded load(String text)
	{
		return WeaknessTable.load(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
	}

	// ---- the loader: good input ----

	@Test
	public void aGoodTableLoadsEveryLineWithItsPercent()
	{
		Loaded t = load("# header\n# another\n1\tEARTH\t60\n2\tAIR\t0\n3\tFIRE\t200\n4\tFIRE\t\n5\tNONE\t\n999999999\tWATER\t999\n");
		assertNull(t.problem);
		assertEquals(0, t.skipped);
		assertEquals(6, t.entries.size());
		assertEquals(new Weakness(Element.EARTH, 60), t.entries.get(1));
		assertEquals("0 is a real percent, not 'unknown'", new Weakness(Element.AIR, 0), t.entries.get(2));
		assertEquals("over 100 is kept as given", FIRE_200.getPercent(), t.entries.get(3).getPercent());
		assertEquals(Element.FIRE, t.entries.get(3).getElement());
		assertNull("an element-only line has no percent", t.entries.get(4).getPercent());
		assertEquals(Element.FIRE, t.entries.get(4).getElement());
		assertSame("NONE is the one shared instance, which the rest of the plugin tests", Weakness.NONE, t.entries.get(5));
		assertEquals(new Weakness(Element.WATER, 999), t.entries.get(999999999));
	}

	@Test
	public void blankAndCommentLinesAreNotBadLines()
	{
		Loaded t = load("\n# a comment\n\n1\tFIRE\t50\n\n#\tFIRE\t50\n");
		assertEquals(0, t.skipped);
		assertEquals(1, t.entries.size());
		assertEquals(FIRE_50, t.entries.get(1));
	}

	@Test
	public void aFinalLineWithNoNewlineStillLoads()
	{
		assertEquals(FIRE_50, load("1\tFIRE\t50").entries.get(1));
	}

	@Test
	public void theLoadedMapCannotBeChanged()
	{
		Loaded t = load("1\tFIRE\t50\n");
		try
		{
			t.entries.put(2, FIRE_50);
			throw new AssertionError("the table is shared by every tick: it must be immutable");
		}
		catch (UnsupportedOperationException expected)
		{
			// good
		}
		try
		{
			t.entries.remove(1);
			throw new AssertionError("the table is shared by every tick: it must be immutable");
		}
		catch (UnsupportedOperationException expected)
		{
			// good
		}
	}

	// ---- the loader: bad lines are skipped and counted, never fatal (addendum 8 F1) ----

	/** Every shape here is something the generator never writes, so every one is a bad line. */
	private static final String[] BAD_LINES = {
		"7\tPLASMA\t50",           // not one of the five names
		"7\tfire\t50",             // case is the generator's job: the file has upper case only
		"7\tFire\t50",
		"7\tFIRE \t50",            // trailing space on the element
		"7\tFIRE\t50 ",            // trailing space on the percent
		"7\tFIRE\t50\r",           // a CRLF checkout
		"7\tFIRE",                 // percent column missing
		"7\tFIRE\t50\textra",      // too many columns
		"7 FIRE 50",               // spaces for tabs
		"\tFIRE\t50",              // no id
		"abc\tFIRE\t50",
		"-7\tFIRE\t50",
		"+7\tFIRE\t50",
		"007\tFIRE\t50",           // leading zero: the generator never writes one
		"1234567890\tFIRE\t50",    // ten digits
		"٧\tFIRE\t50",        // an Arabic-Indic digit, which Integer.parseInt would accept
		"7\tFIRE\t1000",           // F9: the generator fails above 999
		"7\tFIRE\t-5",
		"7\tFIRE\t5%",
		"7\tFIRE\t050",
		"7\tFIRE\t5.5",
		"7\tNONE\t0",              // a NONE never carries a percent
		"7\tNONE\t50",
		"7\t\t50",                 // a percent with no element
		"7\t\t",
	};

	@Test
	public void everyBadShapeIsSkippedAndCountedAndLeavesTheGoodLinesAlone()
	{
		for (String bad : BAD_LINES)
		{
			Loaded t = load("1\tEARTH\t60\n" + bad + "\n2\tWATER\t100\n");
			String why = "line " + bad.replace("\t", "<TAB>").replace("\r", "<CR>");
			assertNull(why, t.problem);
			assertEquals(why, 1, t.skipped);
			assertEquals(why, 2, t.entries.size());
			assertEquals(why, new Weakness(Element.EARTH, 60), t.entries.get(1));
			assertEquals(why, WATER_100, t.entries.get(2));
		}
	}

	@Test
	public void severalBadLinesAreAllCounted()
	{
		Loaded t = load("x\n1\tFIRE\t50\ny\tz\n\t\t\n");
		assertEquals(3, t.skipped);
		assertEquals(1, t.entries.size());
	}

	// A repeated id is outside what the generator writes (it is sorted and unique, and its own reader refuses a
	// repeat). Which of two lines is right is unknowable here, so neither is shown: missing, never false.
	@Test
	public void aRepeatedIdIsShownAsNothingAndBothLinesAreCounted()
	{
		Loaded t = load("1\tFIRE\t50\n2\tWATER\t100\n1\tWATER\t100\n");
		assertEquals(2, t.skipped);
		assertFalse(t.entries.containsKey(1));
		assertEquals(WATER_100, t.entries.get(2));
		Loaded same = load("1\tFIRE\t50\n1\tFIRE\t50\n1\tFIRE\t50\n");
		assertEquals("a third line is counted too, and the id stays out", 3, same.skipped);
		assertTrue(same.entries.isEmpty());
	}

	// ---- the loader: it never throws (addendum 8 F1 and F6) ----

	@Test
	public void aNullStreamIsAnEmptyTableWithAProblem()
	{
		Loaded t = WeaknessTable.load(null);
		assertTrue(t.entries.isEmpty());
		assertNotNull("a missing resource is a problem, not an empty file", t.problem);
	}

	@Test
	public void anEmptyStreamIsAnEmptyTableAndNotAFailure()
	{
		Loaded t = load("");
		assertTrue(t.entries.isEmpty());
		assertEquals(0, t.skipped);
		assertNull(t.problem);
		Loaded headerOnly = load("# only a header\n");
		assertTrue(headerOnly.entries.isEmpty());
		assertEquals(0, headerOnly.skipped);
	}

	/** An InputStream whose every read fails with this. */
	private static final class Failing extends InputStream
	{
		final boolean checked;
		boolean closed;

		Failing(boolean checked)
		{
			this.checked = checked;
		}

		@Override
		public int read() throws IOException
		{
			if (checked)
			{
				throw new IOException("disk gone");
			}
			throw new IllegalStateException("jar closed");
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException
		{
			return read();
		}

		@Override
		public void close()
		{
			closed = true;
		}
	}

	@Test
	public void aStreamThatFailsToReadIsAnEmptyTableWithAProblemAndIsClosed()
	{
		for (boolean checked : new boolean[] {true, false})
		{
			Failing in = new Failing(checked);
			Loaded t = WeaknessTable.load(in);
			assertTrue("checked=" + checked, t.entries.isEmpty());
			assertNotNull("checked=" + checked, t.problem);
			assertTrue("the stream is the loader's to close, checked=" + checked, in.closed);
		}
	}

	@Test
	public void aStreamThatCannotBeClosedStillGivesTheTable()
	{
		for (boolean checked : new boolean[] {true, false})
		{
			InputStream in = new ByteArrayInputStream("1\tFIRE\t50\n".getBytes(StandardCharsets.UTF_8))
			{
				@Override
				public void close() throws IOException
				{
					if (checked)
					{
						throw new IOException("close failed");
					}
					throw new IllegalStateException("close failed");
				}
			};
			Loaded t = WeaknessTable.load(in);
			assertNull("checked=" + checked, t.problem);
			assertEquals("checked=" + checked, FIRE_50, t.entries.get(1));
		}
	}

	@Test
	public void aStreamThatNeverEndsIsRefusedAfterTheCapNotReadForever()
	{
		final long[] served = {0};
		InputStream endless = new InputStream()
		{
			@Override
			public int read()
			{
				served[0]++;
				return 'a';
			}

			@Override
			public int read(byte[] b, int off, int len)
			{
				served[0] += len;
				java.util.Arrays.fill(b, off, off + len, (byte) 'a');
				return len;
			}
		};
		Loaded t = WeaknessTable.load(endless);
		assertTrue(t.entries.isEmpty());
		assertNotNull(t.problem);
		assertTrue("read " + served[0] + " bytes: the shipped file is about 15 KB", served[0] <= 2L * WeaknessTable.MAX_BYTES);
	}

	@Test
	public void aFileJustUnderTheCapLoadsAndOneOverIsRefused()
	{
		StringBuilder sb = new StringBuilder();
		while (sb.length() < WeaknessTable.MAX_BYTES - 100)
		{
			sb.append("# padding\n");
		}
		String under = sb.append("1\tFIRE\t50\n").toString();
		assertTrue(under.length() <= WeaknessTable.MAX_BYTES);
		assertEquals(FIRE_50, load(under).entries.get(1));
		StringBuilder over = new StringBuilder(under);
		while (over.length() <= WeaknessTable.MAX_BYTES)
		{
			over.append("# padding\n");
		}
		Loaded t = load(over.toString());
		assertTrue(t.entries.isEmpty());
		assertNotNull(t.problem);
	}

	@Test
	public void binaryGarbageIsBadLinesNotAnException()
	{
		byte[] junk = new byte[4096];
		for (int i = 0; i < junk.length; i++)
		{
			junk[i] = (byte) (i * 31 + 7); // every byte value, invalid UTF-8 in many places, NULs, no tabs to speak of
		}
		Loaded t = WeaknessTable.load(new ByteArrayInputStream(junk));
		assertTrue(t.entries.isEmpty());
		assertNull(t.problem);
	}

	@Test
	public void aTextWithOnlyHugeLinesDoesNotBlowUp()
	{
		StringBuilder sb = new StringBuilder("1\tFIRE\t");
		for (int i = 0; i < 100_000; i++)
		{
			sb.append('9');
		}
		Loaded t = load(sb.append('\n').toString());
		assertEquals(1, t.skipped);
		assertTrue(t.entries.isEmpty());
	}

	// ---- the real resource (addendum 8 F6), through the production loader ----

	private static byte[] realBytes() throws IOException
	{
		try (InputStream in = WeaknessTable.class.getResourceAsStream(WeaknessTable.RESOURCE))
		{
			assertNotNull("the table is not on the classpath at " + WeaknessTable.RESOURCE, in);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			for (int n; (n = in.read(buf)) > 0; )
			{
				out.write(buf, 0, n);
			}
			return out.toByteArray();
		}
	}

	@Test
	public void theRealResourceLoadsWithNoProblemAndNoSkippedLine() throws IOException
	{
		Loaded t = WeaknessTable.load(WeaknessTable.class.getResourceAsStream(WeaknessTable.RESOURCE));
		assertNull(t.problem);
		assertEquals("every line of the shipped file is one the strict loader accepts", 0, t.skipped);
		assertTrue("expected at least 1,600 entries, found " + t.entries.size(), t.entries.size() >= 1600);
		Matcher m = Pattern.compile("# Ids written: (\\d+)\n").matcher(new String(realBytes(), StandardCharsets.UTF_8));
		assertTrue("the header states how many ids it holds", m.find());
		assertEquals("the loader got every id the generator says it wrote", Integer.parseInt(m.group(1)), t.entries.size());
	}

	@Test
	public void theRealResourceHasTheSpecsKnownAnswers()
	{
		Loaded t = WeaknessTable.load(WeaknessTable.class.getResourceAsStream(WeaknessTable.RESOURCE));
		assertEquals("Fire giant", WATER_100, t.entries.get(2075));
		assertEquals("Kraken", new Weakness(Element.EARTH, 50), t.entries.get(494));
		assertSame("Whirlpool: a positive no-weakness", Weakness.NONE, t.entries.get(496));
		assertEquals("Spiritual mage (Zaros): over 100 is kept", FIRE_200, t.entries.get(11292));
		assertEquals("Maggot King: the tabs disagree on the percent, so the element alone", FIRE_ONLY, t.entries.get(15742));
		assertEquals("Dagannoth Rex (Deadman): the blank Apocalypse tab is ignored",
			new Weakness(Element.EARTH, 35), t.entries.get(12439));
	}

	// ---- precedence (addendum 7 with addendum 8 F3) ----

	private static void assertResolves(String why, Weakness expected, Weakness learned, Weakness table)
	{
		assertEquals(why, expected, WeaknessTable.resolve(learned, table));
	}

	@Test
	public void nothingLearnedTheTableAnswers()
	{
		assertResolves("neither", null, null, null);
		assertResolves("table element with percent", FIRE_50, null, FIRE_50);
		assertResolves("table element, no percent", FIRE_ONLY, null, FIRE_ONLY);
		assertResolves("over 100", FIRE_200, null, FIRE_200);
		assertResolves("table says none", Weakness.NONE, null, Weakness.NONE);
	}

	@Test
	public void aLearnedNoneYieldsToATableWeaknessAndOtherwiseStaysNone()
	{
		assertResolves("F3: the weakest evidence the store holds gives way", FIRE_50, Weakness.NONE, FIRE_50);
		assertResolves("including an element with no percent", FIRE_ONLY, Weakness.NONE, FIRE_ONLY);
		assertResolves("and over 100", FIRE_200, Weakness.NONE, FIRE_200);
		assertSame("no table entry: the game's none stands", Weakness.NONE, WeaknessTable.resolve(Weakness.NONE, null));
		assertSame("the table agrees there is none", Weakness.NONE, WeaknessTable.resolve(Weakness.NONE, Weakness.NONE));
	}

	@Test
	public void aLearnedElementThatMatchesTheTableTakesTheTablesPercent()
	{
		assertResolves("same element: element and percent are the table's", FIRE_50, LEARNED_FIRE, FIRE_50);
		assertResolves("over 100", FIRE_200, LEARNED_FIRE, FIRE_200);
		assertResolves("a table entry with no percent has none to give", FIRE_ONLY, LEARNED_FIRE, FIRE_ONLY);
		assertResolves("0 is a percent", new Weakness(Element.FIRE, 0), LEARNED_FIRE, new Weakness(Element.FIRE, 0));
	}

	@Test
	public void aLearnedElementThatDiffersFromTheTableIsShownWithNoPercent()
	{
		assertResolves("the table is stale for this monster", LEARNED_FIRE, LEARNED_FIRE, WATER_100);
		assertResolves("whatever percent the table had", LEARNED_WATER, LEARNED_WATER, FIRE_200);
		assertResolves("a table element without a percent", LEARNED_WATER, LEARNED_WATER, FIRE_ONLY);
		assertResolves("a confirmed credit beats a table that says none", LEARNED_FIRE, LEARNED_FIRE, Weakness.NONE);
		assertNull(WeaknessTable.resolve(LEARNED_FIRE, WATER_100).getPercent());
	}

	@Test
	public void aLearnedElementWithNoTableEntryStands()
	{
		assertResolves("nothing to disagree with", LEARNED_FIRE, LEARNED_FIRE, null);
	}

	@Test
	public void thePercentOnlyEverComesFromTheTable()
	{
		Weakness odd = new Weakness(Element.FIRE, 77); // the learned store cannot make one, but resolve does not rely on that
		assertResolves("differs", LEARNED_FIRE, odd, WATER_100);
		assertResolves("no table entry", LEARNED_FIRE, odd, null);
		assertResolves("matches: the table's, not the learned 77", FIRE_50, odd, FIRE_50);
		assertResolves("matches an element-only table entry: not the learned 77", FIRE_ONLY, odd, FIRE_ONLY);
	}

	/** Learned x table over every pair of real elements, written from the rule and not from the cases above. */
	@Test
	public void everyPairOfElementsFollowsTheRule()
	{
		Integer[] percents = {null, 0, 50, 200};
		for (Element learned : Element.values())
		{
			for (Element table : Element.values())
			{
				for (Integer percent : percents)
				{
					Weakness t = new Weakness(table, percent);
					Weakness expected = learned == table ? t : new Weakness(learned);
					assertEquals("learned " + learned + " table " + t, expected,
						WeaknessTable.resolve(new Weakness(learned), t));
				}
			}
		}
	}
}
