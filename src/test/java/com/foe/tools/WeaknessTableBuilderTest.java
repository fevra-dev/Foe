package com.foe.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.foe.tools.WeaknessTableBuilder.Element;
import com.foe.tools.WeaknessTableBuilder.Entry;
import com.foe.tools.WeaknessTableBuilder.PreviousTable;
import com.foe.tools.WeaknessTableBuilder.Report;
import com.foe.tools.WeaknessTableBuilder.Result;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.Test;

/**
 * One test per generator rule in spec addenda 7, 8 and 9. The rows are the real shapes the wiki returned on
 * 2026-10-09 (docs/research/2026-10-09-wiki-weakness-table.md); where no real row exists (two different elements on one
 * id, which the wiki does not have today) the row is synthetic and says so.
 */
public class WeaknessTableBuilderTest
{
	private static final String DATE = "2026-10-09";
	private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");

	// ---- helpers ----

	private static WikiRow row(String page, String tab, String element, Integer percent, String... ids)
	{
		return WikiRow.of(page, tab, element, percent == null ? null : Long.valueOf(percent), ids);
	}

	private static Result build(WikiRow... rows)
	{
		return WeaknessTableBuilder.build(Arrays.asList(rows), DATE, null, Collections.emptyMap(), NOW, false);
	}

	private static Result build(PreviousTable previous, Map<String, Instant> edits, boolean acceptShrink,
		WikiRow... rows)
	{
		return WeaknessTableBuilder.build(Arrays.asList(rows), DATE, previous, edits, NOW, acceptShrink);
	}

	private static Entry entry(Element element, Integer percent)
	{
		return new Entry(element, percent);
	}

	/** A previous table in the form the generator writes, so the test cannot drift from the real format. */
	private static PreviousTable previous(String... lines)
	{
		StringBuilder sb = new StringBuilder("# Ids written: " + lines.length + "\n");
		for (String l : lines)
		{
			sb.append(l).append('\n');
		}
		return PreviousTable.parse(sb.toString());
	}

	private static Instant ago(Duration d)
	{
		return NOW.minus(d);
	}

	private static Map<String, Instant> edits(Object... pageAndTime)
	{
		Map<String, Instant> m = new HashMap<>();
		for (int i = 0; i < pageAndTime.length; i += 2)
		{
			m.put((String) pageAndTime[i], (Instant) pageAndTime[i + 1]);
		}
		return m;
	}

	private static void assertOk(Result r)
	{
		assertTrue("expected no failures, got " + r.failures, r.failures.isEmpty());
		assertNotNull("a run without failures has a table", r.table);
	}

	private static String lineWithPrefix(List<String> lines, String prefix)
	{
		for (String l : lines)
		{
			if (l.startsWith(prefix))
			{
				return l;
			}
		}
		fail("no line starting with '" + prefix + "' in " + lines);
		return null;
	}

	private static List<String> dataLines(String table)
	{
		List<String> out = new ArrayList<>();
		for (String l : table.split("\n"))
		{
			if (!l.startsWith("#") && !l.isEmpty())
			{
				out.add(l);
			}
		}
		return out;
	}

	// ---- rule 1: case ----

	@Test
	public void elementCaseIsNormalisedIncludingTheLowercaseRows()
	{
		Result r = build(
			row("Fire giant", "Level 86", "Water", 100, "2075", "2076"),
			row("Dagannoth Rex (Deadman)", "Permanent", "earth", 35, "12439"),
			row("Amoxliatl (Echo)", null, "fire", 30, "13686"),
			row("Spiritual mage", "Zamorak", "Air", 30, "3161"));
		assertOk(r);
		assertEquals(entry(Element.WATER, 100), r.entries.get(2075));
		assertEquals(entry(Element.WATER, 100), r.entries.get(2076));
		assertEquals("a lowercase row is kept, not dropped", entry(Element.EARTH, 35), r.entries.get(12439));
		assertEquals(entry(Element.FIRE, 30), r.entries.get(13686));
		assertEquals(entry(Element.AIR, 30), r.entries.get(3161));
		assertEquals(Arrays.asList("2075\tWATER\t100", "2076\tWATER\t100", "3161\tAIR\t30", "12439\tEARTH\t35",
			"13686\tFIRE\t30"), dataLines(r.table));
	}

	@Test
	public void theElementIsTrimmedBeforeItIsParsed()
	{
		Result r = build(row("Some boss", null, " Fire ", 50, "700"));
		assertOk(r);
		assertEquals(entry(Element.FIRE, 50), r.entries.get(700));
	}

	@Test
	public void onlyWhitespaceIsTrimmedNotControlCharacters()
	{
		// String.trim() also strips ESC and NUL (everything up to U+0020), which would let a hostile value through as
		// a valid element and then into the report. strip() takes whitespace only.
		Result padded = build(row("Some boss", null, " \t Fire\n", 50, "700"));
		assertOk(padded);
		assertEquals(entry(Element.FIRE, 50), padded.entries.get(700));
		for (String bad : new String[] {"\u001bFire", "Fire\u0000", "Fire\u009b", "\u001b[2JFire\u001bc"})
		{
			Result r = build(row("Some boss", null, bad, 50, "700"));
			assertFalse("must fail: " + bad.replace("\u001b", "ESC"), r.failures.isEmpty());
		}
	}

	// ---- rule 2: the literal None ----

	@Test
	public void theLiteralNoneIsWrittenAsNoneWithNoPercent()
	{
		// Kraken, tab Whirlpool: id 496, element "None", no percent
		Result r = build(
			row("Kraken", "Kraken", "Earth", 50, "494"),
			row("Kraken", "Whirlpool", "None", null, "496"),
			row("Cave kraken", "Whirlpool", "none", null, "493"));
		assertOk(r);
		assertEquals(entry(Element.NONE, null), r.entries.get(496));
		assertEquals("lowercase none is the same answer", entry(Element.NONE, null), r.entries.get(493));
		assertTrue(dataLines(r.table).contains("496\tNONE\t"));
	}

	@Test
	public void aRowWithNoWeaknessWritesNothingBecauseUnknownIsNotNone()
	{
		// Thug: the wiki has an id and no weakness. That is "unknown", which the table must not turn into NONE.
		Result r = build(row("Thug", null, null, null, "525"), row("Fire giant", "Level 86", "Water", 100, "2075"));
		assertOk(r);
		assertFalse(r.entries.containsKey(525));
		assertEquals("only the row that has a weakness", Collections.singletonList("2075\tWATER\t100"),
			dataLines(r.table));
	}

	// ---- addendum 8 F1: strict element parse ----

	@Test
	public void anUnknownElementFailsTheRunListingPageTabAndValue()
	{
		Result r = build(
			row("Fire giant", "Level 86", "Water", 100, "2075"),
			row("Some dragon", "Normal", "Dragonfire", 50, "900"));
		assertFalse("the run must fail", r.failures.isEmpty());
		assertNull("a failed run has no table", r.table);
		String f = r.failures.get(0);
		assertTrue(f, f.contains("Some dragon"));
		assertTrue(f, f.contains("Normal"));
		assertTrue(f, f.contains("Dragonfire"));
	}

	@Test
	public void everyBadElementIsListedNotJustTheFirst()
	{
		Result r = build(
			row("A", null, "Dragonfire", 50, "1"),
			row("B", "Tab", "Fire (melee)", 50, "2"),
			row("C", null, "", 50, "3"),
			row("D", null, "Fire", 50, "4"));
		assertEquals(r.failures.toString(), 3, r.failures.size());
		assertNull(r.table);
	}

	// ---- rule 3: the percent ----

	@Test
	public void anElementWithNoPercentIsSkippedAndCounted()
	{
		Result r = build(
			row("Some boss", null, "Fire", null, "700", "701"),
			row("Fire giant", "Level 86", "Water", 100, "2075"));
		assertOk(r);
		assertFalse(r.entries.containsKey(700));
		assertFalse(r.entries.containsKey(701));
		assertEquals("ids, not rows", 2, r.skippedNoPercentIds);
		assertTrue(r.table.contains("# Ids skipped, element without percent: 2"));
	}

	@Test
	public void zeroTwoHundredAndOneHundredFiftyAreKeptAsGiven()
	{
		Result r = build(
			row("Dinky the drink troll", null, "Earth", 0, "15171"),
			row("Spiritual mage", "Zaros", "Fire", 200, "11292"),
			row("Ice demon", "Normal", "Fire", 150, "7584"));
		assertOk(r);
		assertEquals("zero is a value, not 'none'", entry(Element.EARTH, 0), r.entries.get(15171));
		assertEquals(entry(Element.FIRE, 200), r.entries.get(11292));
		assertEquals(entry(Element.FIRE, 150), r.entries.get(7584));
		assertTrue(dataLines(r.table).contains("11292\tFIRE\t200"));
		assertTrue(dataLines(r.table).contains("15171\tEARTH\t0"));
	}

	@Test
	public void aPercentOutsideZeroToNineHundredNinetyNineFailsAndListsTheRow()
	{
		Result tooBig = build(row("Vandalised", "Tab", "Fire", 1000, "900"));
		assertFalse(tooBig.failures.isEmpty());
		assertNull(tooBig.table);
		assertTrue(tooBig.failures.get(0), tooBig.failures.get(0).contains("Vandalised"));
		assertTrue(tooBig.failures.get(0), tooBig.failures.get(0).contains("1000"));

		Result negative = build(row("Vandalised", null, "Fire", -50, "900"));
		assertFalse(negative.failures.isEmpty());

		Result edge = build(row("Edge", null, "Fire", 999, "901"));
		assertOk(edge);
		assertEquals(entry(Element.FIRE, 999), edge.entries.get(901));
	}

	@Test
	public void noneWithAPercentContradictsItselfAndFails()
	{
		// not in today's data; the spec is silent, so the run refuses rather than pick one half of the row
		Result r = build(row("Odd", "Tab", "None", 50, "900"));
		assertFalse(r.failures.isEmpty());
		assertNull(r.table);
		assertTrue(r.failures.get(0), r.failures.get(0).contains("Odd"));
	}

	@Test
	public void aPercentWithNoElementIsNotAWeaknessButIsStillRangeChecked()
	{
		Result ok = build(row("Odd", null, null, 50, "900"), row("Fire giant", "Level 86", "Water", 100, "2075"));
		assertOk(ok);
		assertFalse(ok.entries.containsKey(900));
		Result tooBig = build(row("Odd", null, null, 5000, "900"));
		assertFalse(tooBig.failures.isEmpty());
	}

	// ---- rule 4: ids ----

	@Test
	public void nonNumericIdsAreSkippedAndCounted()
	{
		// real: the wiki also carries override…, hist… and removed, not only beta…
		Result r = build(
			row("Hespori (Echo)", null, "Fire", 100, "override8583"),
			row("Mixed", null, "Water", 40, "800", "beta14278", "801"),
			row("Beta only", null, "Earth", 40, "beta481"),
			// a blank row's non-numeric id would never have been an entry, so it is not a loss and not counted
			row("Hist", null, null, null, "hist10576"));
		assertOk(r);
		assertEquals(Arrays.asList(800, 801), new ArrayList<>(r.entries.keySet()));
		assertEquals(3, r.skippedNonNumericIds);
		assertTrue(r.table.contains("# Ids skipped, non-numeric id: 3"));
	}

	@Test
	public void aWeaknessRowWithNoIdsIsSkippedAndCounted()
	{
		// real: King Black Dragon (Echo) has Water 50 and no id
		Result r = build(
			row("King Black Dragon (Echo)", null, "Water", 50),
			row("Nothing here", null, null, null),
			row("Fire giant", "Level 86", "Water", 100, "2075"));
		assertOk(r);
		assertEquals("only the weakness row counts", 1, r.skippedNoIdRows);
		assertEquals(1, r.entries.size());
		assertTrue(r.table.contains("# Rows skipped, no ids: 1"));
	}

	// ---- addendum 9: conflicts ----

	@Test
	public void aBlankTabIsIgnoredSoTheDeadmanShapeEmitsItsValue()
	{
		// real: Dagannoth Rex (Deadman), id 12439. Three tabs say earth 35, the Apocalypse tab has no weakness.
		Result r = build(
			row("Dagannoth Rex (Deadman)", "Annihilation", "earth", 35, "12439"),
			row("Dagannoth Rex (Deadman)", "Permanent", "earth", 35, "12439"),
			row("Dagannoth Rex (Deadman)", "Armageddon", "earth", 35, "12439"),
			row("Dagannoth Rex (Deadman)", "Apocalypse", null, null, "12439"));
		assertOk(r);
		assertEquals(entry(Element.EARTH, 35), r.entries.get(12439));
		assertTrue("a blank tab is not a conflict", r.report.lines(Report.ELEMENT_CONFLICTS).isEmpty());
		assertTrue("a blank tab is not a conflict", r.report.lines(Report.PERCENT_CONFLICTS).isEmpty());
	}

	@Test
	public void sameElementWithDifferentPercentsEmitsTheElementWithNoPercentAndIsListed()
	{
		// real: Maggot King, id 15742: fire 80 (Roaring), fire 5 (Nearby), fire 80 (Far)
		Result r = build(
			row("Maggot King", "Roaring", "Fire", 80, "15742"),
			row("Maggot King", "Nearby", "Fire", 5, "15742"),
			row("Maggot King", "Far", "Fire", 80, "15742"));
		assertOk(r);
		assertEquals(entry(Element.FIRE, null), r.entries.get(15742));
		assertTrue(dataLines(r.table).contains("15742\tFIRE\t"));
		String line = lineWithPrefix(r.report.lines(Report.PERCENT_CONFLICTS), "15742");
		assertTrue(line, line.contains("Maggot King"));
		assertTrue(line, line.contains("5") && line.contains("80"));
		assertTrue(r.report.lines(Report.ELEMENT_CONFLICTS).isEmpty());
	}

	@Test
	public void twoDifferentElementsGetNoEntryAndAreListed()
	{
		// synthetic: the wiki has no such id today
		Result r = build(
			row("Two faced", "Day", "Fire", 50, "900"),
			row("Two faced", "Night", "Water", 50, "900"),
			row("Fire giant", "Level 86", "Water", 100, "2075"));
		assertOk(r);
		assertFalse(r.entries.containsKey(900));
		assertEquals(1, r.skippedConflictIds);
		String line = lineWithPrefix(r.report.lines(Report.ELEMENT_CONFLICTS), "900");
		assertTrue(line, line.contains("Two faced"));
		assertTrue(line, line.contains("Day") && line.contains("Night"));
		assertTrue(r.table.contains("# Ids skipped, element conflict: 1"));
	}

	@Test
	public void noneAgainstAnElementIsTwoDifferentElements()
	{
		Result r = build(
			row("Two faced", "Day", "None", null, "900"),
			row("Two faced", "Night", "Fire", 50, "900"));
		assertOk(r);
		assertFalse(r.entries.containsKey(900));
		assertFalse(r.report.lines(Report.ELEMENT_CONFLICTS).isEmpty());
	}

	@Test
	public void noneAgreeingWithNoneIsNone()
	{
		Result r = build(
			row("Calm", "Day", "None", null, "900"),
			row("Calm", "Night", "None", null, "900"));
		assertOk(r);
		assertEquals(entry(Element.NONE, null), r.entries.get(900));
		assertTrue(r.report.lines(Report.ELEMENT_CONFLICTS).isEmpty());
		assertTrue(r.report.lines(Report.PERCENT_CONFLICTS).isEmpty());
	}

	// ---- addendum 8 F5: agreeing phases ----

	@Test
	public void agreeingTabsOnOneIdAreEmittedAndStillListed()
	{
		// synthetic: two tabs of one page share id 700 and agree
		Result r = build(
			row("Phase boss", "Phase 1", "Fire", 50, "700"),
			row("Phase boss", "Phase 2", "Fire", 50, "700"),
			// the same id on a different page is a different matter: not a tab of one page
			row("Other page", null, "Fire", 50, "701"),
			row("Third page", null, "Fire", 50, "701"));
		assertOk(r);
		assertEquals(entry(Element.FIRE, 50), r.entries.get(700));
		String line = lineWithPrefix(r.report.lines(Report.SHARED_TABS), "700");
		assertTrue(line, line.contains("Phase boss"));
		assertTrue(line, line.contains("Phase 1") && line.contains("Phase 2"));
		assertEquals(1, r.report.lines(Report.SHARED_TABS).size());
	}

	@Test
	public void theDeadmanBlankTabIsListedAsASharedTab()
	{
		Result r = build(
			row("Dagannoth Rex (Deadman)", "Permanent", "earth", 35, "12439"),
			row("Dagannoth Rex (Deadman)", "Apocalypse", null, null, "12439"));
		assertOk(r);
		assertFalse(r.report.lines(Report.SHARED_TABS).isEmpty());
	}

	// ---- addendum 8 F2: quarantine ----

	private static PreviousTable previousWaterGiant()
	{
		return previous("2075\tWATER\t100");
	}

	@Test
	public void aChangedValueOnAPageEditedThreeDaysAgoKeepsThePreviousValueAndIsListedPending()
	{
		Result r = build(previousWaterGiant(), edits("Fire giant", ago(Duration.ofDays(3))), false,
			row("Fire giant", "Level 86", "Fire", 100, "2075"));
		assertOk(r);
		assertEquals("the previous value stays", entry(Element.WATER, 100), r.entries.get(2075));
		String line = lineWithPrefix(r.report.lines(Report.PENDING), "2075");
		assertTrue(line, line.contains("Fire giant"));
		assertTrue(line, line.contains("Level 86"));
		assertEquals(1, r.heldBack);
		assertTrue(r.report.lines(Report.CHANGED).isEmpty());
	}

	@Test
	public void aChangeInThePercentAloneIsQuarantinedToo()
	{
		// the varp carries no percent (F10), so a percent-only edit is the one nothing in game can catch
		Result r = build(previousWaterGiant(), edits("Fire giant", ago(Duration.ofDays(1))), false,
			row("Fire giant", "Level 86", "Water", 150, "2075"));
		assertOk(r);
		assertEquals(entry(Element.WATER, 100), r.entries.get(2075));
		assertEquals(1, r.report.lines(Report.PENDING).size());
	}

	@Test
	public void aChangedValueOnAPageEditedEightDaysAgoIsAccepted()
	{
		Result r = build(previousWaterGiant(), edits("Fire giant", ago(Duration.ofDays(8))), false,
			row("Fire giant", "Level 86", "Fire", 100, "2075"));
		assertOk(r);
		assertEquals(entry(Element.FIRE, 100), r.entries.get(2075));
		assertFalse(r.report.lines(Report.CHANGED).isEmpty());
		assertTrue(r.report.lines(Report.PENDING).isEmpty());
		assertEquals(0, r.heldBack);
	}

	@Test
	public void exactlySevenDaysIsOldEnoughAndOneSecondLessIsNot()
	{
		WikiRow changed = row("Fire giant", "Level 86", "Fire", 100, "2075");
		Result exactly = build(previousWaterGiant(), edits("Fire giant", ago(Duration.ofDays(7))), false, changed);
		assertEquals("at least 7 days old is accepted", entry(Element.FIRE, 100), exactly.entries.get(2075));
		Result almost = build(previousWaterGiant(),
			edits("Fire giant", ago(Duration.ofDays(7).minusSeconds(1))), false, changed);
		assertEquals(entry(Element.WATER, 100), almost.entries.get(2075));
	}

	@Test
	public void aChangedValueOnAPageWithNoKnownEditTimeIsHeldBack()
	{
		// we cannot show the edit is old, so it does not ship: fail closed
		Result r = build(previousWaterGiant(), Collections.emptyMap(), false,
			row("Fire giant", "Level 86", "Fire", 100, "2075"));
		assertOk(r);
		assertEquals(entry(Element.WATER, 100), r.entries.get(2075));
		assertFalse(r.report.lines(Report.PENDING).isEmpty());
		assertFalse(r.report.lines(Report.UNKNOWN_EDIT).isEmpty());
	}

	@Test
	public void aFutureEditTimeIsNotOldEither()
	{
		Result r = build(previousWaterGiant(), edits("Fire giant", NOW.plus(Duration.ofHours(1))), false,
			row("Fire giant", "Level 86", "Fire", 100, "2075"));
		assertEquals(entry(Element.WATER, 100), r.entries.get(2075));
	}

	@Test
	public void anIdOnTwoPagesIsHeldBackWhenEitherPageWasEditedRecently()
	{
		Result r = build(previousWaterGiant(),
			edits("Fire giant", ago(Duration.ofDays(30)), "Giant list", ago(Duration.ofDays(1))), false,
			row("Fire giant", "Level 86", "Fire", 100, "2075"),
			row("Giant list", null, "Fire", 100, "2075"));
		assertEquals(entry(Element.WATER, 100), r.entries.get(2075));
	}

	@Test
	public void noPreviousTableMeansNothingIsQuarantinedAndTheRecentPagesAreListed()
	{
		Result r = build(null,
			edits("Fire giant", ago(Duration.ofDays(1)), "Kraken", ago(Duration.ofDays(8))), false,
			row("Fire giant", "Level 86", "Water", 100, "2075"),
			row("Kraken", "Kraken", "Earth", 50, "494"));
		assertOk(r);
		assertEquals("nothing to compare to, so nothing is held back", entry(Element.WATER, 100), r.entries.get(2075));
		assertEquals(0, r.heldBack);
		String line = lineWithPrefix(r.report.lines(Report.RECENT), "Fire giant");
		assertTrue(line, line.contains("2075") && line.contains("WATER"));
		assertEquals("the 8 day old page is not listed", 1, r.report.lines(Report.RECENT).size());
	}

	@Test
	public void anEditTimeInTheFutureIsListedAsRecentToo()
	{
		// the wiki's clock and ours can disagree; an edit "from the future" is certainly not a week old
		Result r = build(null, edits("Fire giant", NOW.plus(Duration.ofHours(1))), false,
			row("Fire giant", "Level 86", "Water", 100, "2075"));
		assertOk(r);
		assertEquals(1, r.report.lines(Report.RECENT).size());
	}

	@Test
	public void anUnchangedValueOnARecentPageIsNotPendingButIsStillListedAsRecent()
	{
		Result r = build(previousWaterGiant(), edits("Fire giant", ago(Duration.ofDays(2))), false,
			row("Fire giant", "Level 86", "Water", 100, "2075"));
		assertOk(r);
		assertTrue(r.report.lines(Report.PENDING).isEmpty());
		assertEquals(1, r.report.lines(Report.RECENT).size());
	}

	@Test
	public void addedAndRemovedIdsAreListedAndAddedOnesAreNotQuarantined()
	{
		PreviousTable previous = previous("494\tEARTH\t50", "2075\tWATER\t100");
		Result r = build(previous, edits("Brand new", ago(Duration.ofDays(1)), "Fire giant", ago(Duration.ofDays(30))),
			false,
			row("Fire giant", "Level 86", "Water", 100, "2075"),
			row("Brand new", null, "Fire", 50, "9000"));
		assertOk(r);
		assertEquals(entry(Element.FIRE, 50), r.entries.get(9000));
		lineWithPrefix(r.report.lines(Report.ADDED), "9000");
		lineWithPrefix(r.report.lines(Report.REMOVED), "494");
		assertFalse(r.entries.containsKey(494));
	}

	// ---- addendum 8 F7: shrink ----

	private static PreviousTable hundredIds()
	{
		String[] lines = new String[100];
		for (int i = 0; i < 100; i++)
		{
			lines[i] = (i + 1) + "\tFIRE\t50";
		}
		return previous(lines);
	}

	private static WikiRow firstIds(int n)
	{
		String[] ids = new String[n];
		for (int i = 0; i < n; i++)
		{
			ids[i] = String.valueOf(i + 1);
		}
		return row("Big page", null, "Fire", 50, ids);
	}

	@Test
	public void aTableMoreThanFivePercentSmallerThanThePreviousOneFails()
	{
		Result r = build(hundredIds(), Collections.emptyMap(), false, firstIds(94));
		assertFalse(r.failures.isEmpty());
		assertNull("nothing is written", r.table);
		String f = r.failures.get(0);
		assertTrue(f, f.toLowerCase().contains("shrink"));
		assertTrue(f, f.contains("100") && f.contains("94"));
		assertTrue(f, f.contains("--accept-shrink"));
	}

	@Test
	public void exactlyFivePercentSmallerStillPasses()
	{
		Result r = build(hundredIds(), Collections.emptyMap(), false, firstIds(95));
		assertOk(r);
		assertTrue(r.table.contains("# Accept-shrink: no"));
	}

	@Test
	public void acceptShrinkWritesTheSmallerTableAndTheHeaderSaysSo()
	{
		Result r = build(hundredIds(), Collections.emptyMap(), true, firstIds(94));
		assertOk(r);
		assertEquals(94, r.entries.size());
		assertTrue(r.table, r.table.contains("# Accept-shrink: yes"));
	}

	@Test
	public void aFirstGenerationHasNoShrinkCheck()
	{
		Result none = build(firstIds(10));
		assertOk(none);
		assertTrue(none.table.contains("# Accept-shrink: no"));
	}

	// ---- addendum 8 F7: duplicates ----

	@Test
	public void duplicateRowsAcrossPagesAreDeduplicated()
	{
		// real: Duke Sucellus "Awake" came back twice in the one pull
		WikiRow awake = row("Duke Sucellus", "Awake", "Earth", 50, "12191");
		Result r = build(awake, awake, row("Duke Sucellus", "Asleep", "Earth", 50, "12166"));
		assertOk(r);
		assertEquals(2, r.rowsRead);
		assertEquals(1, r.duplicateRowsDropped);
		assertTrue(r.table.contains("# Rows read: 2"));
		assertTrue("a repeated row is not a second tab", r.report.lines(Report.SHARED_TABS).isEmpty());
	}

	// ---- format ----

	@Test
	public void theTableIsSortedByNumericIdAndCarriesItsCounts()
	{
		Result r = build(
			row("C", null, "Fire", 50, "100"),
			row("A", null, "Water", 40, "9"),
			row("B", null, "Earth", 30, "20"));
		assertOk(r);
		assertEquals(Arrays.asList("9\tWATER\t40", "20\tEARTH\t30", "100\tFIRE\t50"), dataLines(r.table));
		assertTrue(r.table.startsWith("#"));
		assertTrue(r.table, r.table.contains("# Fetched (UTC): 2026-10-09\n"));
		assertTrue(r.table.contains("# Rows read: 3\n"));
		assertTrue(r.table.contains("# Ids written: 3\n"));
		assertTrue(r.table.contains("https://oldschool.runescape.wiki/api.php"));
		assertTrue(r.table.endsWith("\n"));
	}

	@Test
	public void theOutputDoesNotDependOnTheOrderOfTheRows()
	{
		List<WikiRow> rows = new ArrayList<>(Arrays.asList(
			row("Fire giant", "Level 86", "Water", 100, "2075", "2076"),
			row("Maggot King", "Nearby", "Fire", 5, "15742"),
			row("Maggot King", "Far", "Fire", 80, "15742"),
			row("Kraken", "Whirlpool", "None", null, "496"),
			row("Dagannoth Rex (Deadman)", "Apocalypse", null, null, "12439"),
			row("Dagannoth Rex (Deadman)", "Permanent", "earth", 35, "12439")));
		Result a = WeaknessTableBuilder.build(rows, DATE, null, Collections.emptyMap(), NOW, false);
		Collections.reverse(rows);
		Result b = WeaknessTableBuilder.build(rows, DATE, null, Collections.emptyMap(), NOW, false);
		assertTrue(a.table, a.table.contains("15742\tFIRE\t\n"));
		assertEquals(a.table, b.table);
		assertEquals(a.report.render(), b.report.render());
	}

	@Test
	public void theReportRendersEverySectionThatHasLinesAndNamesThePage()
	{
		Result r = build(
			row("Maggot King", "Nearby", "Fire", 5, "15742"),
			row("Maggot King", "Far", "Fire", 80, "15742"));
		assertOk(r);
		String text = r.report.render();
		assertTrue(text, text.contains(Report.PERCENT_CONFLICTS));
		assertTrue(text, text.contains("Maggot King"));
	}

	// ---- ADR-0006: wiki text is untrusted ----

	private static final String EVIL = "Evil\u001b[2J\nFake line\u202e\u009b";

	private static void assertSafeToPrint(String what, String text)
	{
		assertFalse(what + " has a character that does something when printed: " + text.codePoints()
			.filter(cp -> TextTest.isHostileToPrint(new String(Character.toChars(cp)))).boxed().collect(
				java.util.stream.Collectors.toList()), TextTest.isHostileToPrint(text.replace("\n", "")));
	}

	@Test
	public void aFailureMessageNeverCarriesWikiTextThatWouldActOnATerminal()
	{
		Result r = build(
			row(EVIL, "Tab" + EVIL, "Dragonfire" + EVIL, 50, "900"),
			row("Other", null, "Fire", 5000, "beta" + EVIL, "901"));
		assertFalse(r.failures.isEmpty());
		for (String f : r.failures)
		{
			assertSafeToPrint("failure", f);
			assertFalse("no forged second line", f.contains("\n"));
			assertTrue(f, f.contains("\\u001b"));
		}
	}

	@Test
	public void noReportLineCarriesWikiTextThatWouldActOnATerminalOrForgeALine()
	{
		PreviousTable previous = previous("1\tFIRE\t50", "2\tFIRE\t50", "3\tWATER\t50");
		Result r = build(previous, edits(EVIL, ago(Duration.ofDays(1))), false,
			// an element conflict, a percent conflict, shared tabs, a recent page, a held-back change
			row(EVIL, "A" + EVIL, "Fire", 50, "10"),
			row(EVIL, "B" + EVIL, "Water", 50, "10"),
			row(EVIL, "C" + EVIL, "Fire", 5, "11"),
			row(EVIL, "D" + EVIL, "Fire", 80, "11"),
			row(EVIL, "E" + EVIL, "Air", 30, "1"),
			// a non-numeric id, a row without ids, an element without percent
			row("Skipped" + EVIL, null, "Fire", 50, "override" + EVIL),
			// whitespace padding is allowed by the element parse, so it can reach this line and must not forge one
			row("NoIds" + EVIL, null, "\u2028\nFire\n", 50),
			row("NoPct" + EVIL, "T" + EVIL, "Fire", null, "12"),
			// page with no known edit time, and a removed id still in the data
			row("Unknown" + EVIL, "U" + EVIL, "Earth", 5, "13"),
			row("Removed" + EVIL, "V" + EVIL, null, null, "3"));
		assertOk(r);
		int lines = 0;
		for (String section : new String[] {Report.ELEMENT_CONFLICTS, Report.PERCENT_CONFLICTS, Report.SHARED_TABS,
			Report.RECENT, Report.UNKNOWN_EDIT, Report.PENDING, Report.CHANGED, Report.ADDED, Report.REMOVED,
			Report.SKIPPED})
		{
			for (String line : r.report.lines(section))
			{
				lines++;
				assertSafeToPrint(section, line);
				assertFalse("a forged line break in: " + line, line.contains("\n") || line.contains("\r"));
			}
		}
		assertTrue("the fixture must reach most sections: " + lines, lines >= 9);
		assertSafeToPrint("rendered report", r.report.render());
	}

	@Test
	public void aPreviousTableErrorNeverEchoesTheLineRaw()
	{
		try
		{
			PreviousTable.parse("# Ids written: 1\n\u001b[31mevil\u009b\n");
			fail("should have refused");
		}
		catch (IllegalArgumentException expected)
		{
			assertSafeToPrint("message", expected.getMessage());
		}
	}

	@Test
	public void aFirstGenerationSaysSoInsteadOfClaimingNothingChanged()
	{
		// "Ids added (0)" would read as "nothing was added", which is false: every id is new. Say why it is empty.
		String first = build(row("Fire giant", "Level 86", "Water", 100, "2075")).report.render();
		for (String section : new String[] {Report.ADDED, Report.REMOVED, Report.CHANGED, Report.PENDING})
		{
			int at = first.indexOf("== " + section);
			assertTrue(section, at >= 0);
			String body = first.substring(at, first.indexOf("\n\n", at));
			assertTrue(body, body.contains("not applicable: first generation"));
			assertFalse(body, body.contains("(none)"));
		}
		String second = build(previousWaterGiant(), Collections.emptyMap(), false,
			row("Fire giant", "Level 86", "Water", 100, "2075")).report.render();
		assertFalse(second, second.contains("not applicable"));
		assertTrue(second, second.contains("(none)"));
	}

	// ---- reading the previous table back ----

	@Test
	public void aWrittenTableReadsBackToTheSameEntries()
	{
		Result r = build(
			row("Fire giant", "Level 86", "Water", 100, "2075"),
			row("Maggot King", "Nearby", "Fire", 5, "15742"),
			row("Maggot King", "Far", "Fire", 80, "15742"),
			row("Kraken", "Whirlpool", "None", null, "496"));
		assertOk(r);
		PreviousTable back = PreviousTable.parse(r.table);
		assertEquals(r.entries, new TreeMap<>(back.entries));
		assertEquals(r.entries.size(), back.headerCount);
	}

	@Test
	public void aPreviousTableThatIsNotWhatTheGeneratorWritesIsRefused()
	{
		String[] bad = {
			"# Ids written: 1\ngarbage\n",
			"2075\tWATER\t100\n",
			"# Ids written: 2\n2075\tWATER\t100\n",
			"# Ids written: 2\n2075\tWATER\t100\n2075\tFIRE\t50\n",
			"# Ids written: 1\n2075\tPLASMA\t100\n",
			"# Ids written: 1\n496\tNONE\t5\n",
			"# Ids written: 1\n2075\tWATER\tx\n",
			"# Ids written: 1\n2075\tWATER\t1000\n",
			"# Ids written: 1\n\n2075\tWATER\t100\n",
		};
		for (String text : bad)
		{
			try
			{
				PreviousTable.parse(text);
				fail("should have refused: " + text.replace("\n", "|"));
			}
			catch (IllegalArgumentException expected)
			{
				assertNotNull(expected.getMessage());
			}
		}
	}
}
