package com.foe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class SnapshotFactoryTest
{
	// NPCComposition.getStats() order, as the STAT_* constants of runelite-api 1.13.1:
	// ATTACK=0, DEFENCE=1, STRENGTH=2, HITPOINTS=3, RANGED=4, MAGIC=5
	private static final int[] ICE_GIANT = {40, 40, 40, 70, 1, 1};
	// Every slot distinct, so a swapped index shows up. ICE_GIANT cannot: attack, defence and strength are all 40.
	private static final int[] DISTINCT = {11, 22, 33, 70, 44, 55};

	@Test
	public void mapsStatsByIndex()
	{
		TargetSnapshot s = SnapshotFactory.build("Ice giant", 53, DISTINCT, 30, 30, false, null, null);
		assertEquals(11, s.getAttack());
		assertEquals(22, s.getDefence());
		assertEquals(33, s.getStrength());
		assertEquals(70, s.getMaxHp());
		assertEquals(44, s.getRanged());
		assertEquals(55, s.getMagic());
		assertEquals(70, s.getHp());
	}

	@Test
	public void passesNameLevelAndWeaknessThrough()
	{
		Weakness fire = new Weakness(Weakness.Element.FIRE);
		TargetSnapshot s = SnapshotFactory.build("Ice giant", 53, ICE_GIANT, 30, 30, false, null, fire);
		assertEquals("Ice giant", s.getName());
		assertEquals(53, s.getCombatLevel());
		assertSame(fire, s.getWeakness());
	}

	@Test
	public void stripsMarkupFromName()
	{
		TargetSnapshot s = SnapshotFactory.build("<col=ff0000>Ice giant</col>", 53, ICE_GIANT, 30, 30, false, null, null);
		assertEquals("Ice giant", s.getName());
	}

	@Test
	public void stripsIconTagFromName()
	{
		// <img=N> is a chat/name icon tag; Text.removeTags drops every <...> run
		TargetSnapshot s = SnapshotFactory.build("<img=1>Ice giant", 53, ICE_GIANT, 30, 30, false, null, null);
		assertEquals("Ice giant", s.getName());
	}

	@Test
	public void trimsSpacesAfterStrippingMarkup()
	{
		assertEquals("Ice giant",
			SnapshotFactory.build("  Ice giant  ", 53, ICE_GIANT, 30, 30, false, null, null).getName());
		// the spaces sit inside the tags, so they only become leading/trailing once the tags are gone
		assertEquals("Ice giant",
			SnapshotFactory.build("<col=ff0000> Ice giant </col>", 53, ICE_GIANT, 30, 30, false, null, null).getName());
	}

	@Test
	public void noNameMeansNoSnapshot()
	{
		assertNull(SnapshotFactory.build(null, 53, ICE_GIANT, 30, 30, false, null, null));
		assertNull(SnapshotFactory.build("", 53, ICE_GIANT, 30, 30, false, null, null));
		assertNull(SnapshotFactory.build("   ", 53, ICE_GIANT, 30, 30, false, null, null));
		assertNull(SnapshotFactory.build("null", 53, ICE_GIANT, 30, 30, false, null, null));
		assertNull(SnapshotFactory.build("<col=ff0000></col>", 53, ICE_GIANT, 30, 30, false, null, null));
		// the "null" check runs on the stripped, trimmed name (LootManager also strips tags before it, but does not trim)
		assertNull(SnapshotFactory.build("<col=ff0000>null</col>", 53, ICE_GIANT, 30, 30, false, null, null));
		assertNull(SnapshotFactory.build(" null ", 53, ICE_GIANT, 30, 30, false, null, null));
		// only the exact name "null" is rejected, not a name that merely starts with it
		assertNotNull(SnapshotFactory.build("null giant", 53, ICE_GIANT, 30, 30, false, null, null));
	}

	@Test
	public void maxHpFallsBackToNpcManager()
	{
		int[] noHp = {40, 40, 40, 0, 1, 1};
		assertEquals(70, SnapshotFactory.build("Ice giant", 53, noHp, 30, 30, false, 70, null).getMaxHp());
	}

	@Test
	public void statsHitpointsWinOverFallback()
	{
		assertEquals(70, SnapshotFactory.build("Ice giant", 53, ICE_GIANT, 30, 30, false, 99, null).getMaxHp());
	}

	@Test
	public void nonPositiveFallbackIsNoMaxHp()
	{
		// NpcInfo.hitpoints is a primitive int, so NPCManager.getHealth returns 0, not null, for an entry without it
		int[] noHp = {40, 40, 40, 0, 1, 1};
		for (int fallback : new int[]{0, -5})
		{
			TargetSnapshot s = SnapshotFactory.build("Ice giant", 53, noHp, 15, 30, false, fallback, null);
			assertEquals(0, s.getMaxHp());
			assertEquals(HpEstimate.UNKNOWN, s.getHp());
		}
	}

	@Test
	public void unknownMaxHpGivesUnknownHp()
	{
		TargetSnapshot s = SnapshotFactory.build("Ice giant", 53, null, 15, 30, false, null, null);
		assertEquals(0, s.getMaxHp());
		assertEquals(HpEstimate.UNKNOWN, s.getHp());
		assertEquals(0, s.getAttack());
		// the bar still has its ratio and scale: unknown max HP means bar only, not no bar (spec)
		assertEquals(15, s.getHpRatio());
		assertEquals(30, s.getHpScale());
	}

	@Test
	public void shortStatsArrayIsTolerated()
	{
		TargetSnapshot s = SnapshotFactory.build("Ice giant", 53, new int[]{40, 40}, 30, 30, false, null, null);
		assertEquals(40, s.getDefence());
		assertEquals(0, s.getMagic());
	}

	@Test
	public void negativeStatsClampToZero()
	{
		// distinct negatives: abs() instead of a clamp would give 1..6, and every slot is checked
		int[] negative = {-1, -2, -3, -4, -5, -6};
		TargetSnapshot s = SnapshotFactory.build("Ice giant", 53, negative, 30, 30, false, null, null);
		assertEquals(0, s.getAttack());
		assertEquals(0, s.getDefence());
		assertEquals(0, s.getStrength());
		assertEquals(0, s.getMaxHp());
		assertEquals(0, s.getRanged());
		assertEquals(0, s.getMagic());
		assertEquals(HpEstimate.UNKNOWN, s.getHp());
	}

	@Test
	public void ratioAboveScaleGivesUnknownHpButKeepsRawBar()
	{
		// HpEstimate refuses 31/30 (Task 3). The snapshot must carry that UNKNOWN, and the raw ratio and scale, so
		// the overlay can tell the data is inconsistent and skip the bar fill.
		TargetSnapshot s = SnapshotFactory.build("Ice giant", 53, ICE_GIANT, 31, 30, false, null, null);
		assertEquals(HpEstimate.UNKNOWN, s.getHp());
		assertEquals(70, s.getMaxHp());
		assertEquals(31, s.getHpRatio());
		assertEquals(30, s.getHpScale());
	}

	@Test
	public void hpStalePassesThroughAndDoesNotChangeTheEstimate()
	{
		TargetSnapshot live = SnapshotFactory.build("Ice giant", 53, ICE_GIANT, 15, 30, false, null, null);
		TargetSnapshot stale = SnapshotFactory.build("Ice giant", 53, ICE_GIANT, 15, 30, true, null, null);
		assertFalse(live.isHpStale());
		assertTrue(stale.isHpStale());
		assertEquals(35, live.getHp());
		assertEquals(live.getHp(), stale.getHp());
	}

	// ---- max HP before the first hit (spec addendum 4, replacing addendum 3's full bar) ----

	@Test
	public void noBarYetButMaxHpKnownIsUnhitWithNoInventedCurrentHp()
	{
		// HpMemory reports (-1, 0, stale=false) for a target it has never seen a bar on
		TargetSnapshot s = SnapshotFactory.build("Kalphite Soldier", 85, new int[] {70, 80, 70, 90, 1, 1}, -1, 0, false,
			null, null);
		assertEquals(90, s.getMaxHp());
		assertTrue(s.isHpUnhit());
		assertEquals("current HP is not known, and 90 would be a guess that lies after a relog", HpEstimate.UNKNOWN,
			s.getHp());
		assertEquals(-1, s.getHpRatio());
		assertEquals(0, s.getHpScale());
		assertFalse("nothing is remembered, so it is not a stale reading", s.isHpStale());
	}

	@Test
	public void theFallbackMaxHpFromNpcManagerAlsoGivesAnUnhitSnapshot()
	{
		int[] noHp = {40, 40, 40, 0, 1, 1};
		TargetSnapshot s = SnapshotFactory.build("Ice giant", 53, noHp, -1, 0, false, 64, null);
		assertEquals(64, s.getMaxHp());
		assertTrue(s.isHpUnhit());
		assertEquals(HpEstimate.UNKNOWN, s.getHp());
	}

	@Test
	public void noBarAndNoMaxHpStaysNoBar()
	{
		int[] noHp = {40, 40, 40, 0, 1, 1};
		for (Integer fallback : new Integer[] {null, 0, -5})
		{
			TargetSnapshot s = SnapshotFactory.build("Ice giant", 53, noHp, -1, 0, false, fallback, null);
			assertEquals(0, s.getMaxHp());
			assertEquals(HpEstimate.UNKNOWN, s.getHp());
			assertEquals(-1, s.getHpRatio());
			assertEquals(0, s.getHpScale());
			assertFalse(s.isHpStale());
			assertFalse("without a max HP there is nothing to show, not even an empty bar", s.isHpUnhit());
		}
	}

	@Test
	public void aBarSeenAndThenLostKeepsItsLastValueAndIsNotReplacedByAFullBar()
	{
		// the normal stale path: HpMemory hands back the remembered 15/30 with stale=true
		TargetSnapshot s = SnapshotFactory.build("Ice giant", 53, ICE_GIANT, 15, 30, true, null, null);
		assertEquals(15, s.getHpRatio());
		assertEquals(30, s.getHpScale());
		assertEquals(35, s.getHp());
		assertTrue(s.isHpStale());
		assertFalse("it was seen, so it is not unhit", s.isHpUnhit());
	}

	@Test
	public void aStaleFlagMeansAMemoryExistsSoItIsNeverCalledUnhit()
	{
		// "never had a bar" is HpMemory's stale=false, (-1, 0) reading. Stale=true says something was remembered,
		// so even with nothing left to draw the factory must not turn it into the unhit state.
		TargetSnapshot s = SnapshotFactory.build("Ice giant", 53, ICE_GIANT, -1, 0, true, null, null);
		assertEquals(HpEstimate.UNKNOWN, s.getHp());
		assertEquals(-1, s.getHpRatio());
		assertEquals(0, s.getHpScale());
		assertFalse(s.isHpUnhit());
	}

	@Test
	public void aLiveBarIsNeverUnhit()
	{
		TargetSnapshot s = SnapshotFactory.build("Ice giant", 53, ICE_GIANT, 15, 30, false, null, null);
		assertEquals(15, s.getHpRatio());
		assertEquals(35, s.getHp());
		assertFalse(s.isHpStale());
		assertFalse(s.isHpUnhit());
		TargetSnapshot dead = SnapshotFactory.build("Ice giant", 53, ICE_GIANT, 0, 30, false, null, null);
		assertEquals("ratio 0 is a live, empty bar", 0, dead.getHpRatio());
		assertEquals(0, dead.getHp());
		assertFalse(dead.isHpStale());
		assertFalse("0/30 is a bar, a dead monster's, not an unseen one", dead.isHpUnhit());
	}

	@Test
	public void inconsistentDataIsNotCalledUnhit()
	{
		// a ratio with no scale, or a scale with no ratio, is not "never had a bar": HpMemory cannot produce
		// either, and calling them unhit would hide the inconsistency behind a clean empty bar
		TargetSnapshot noScale = SnapshotFactory.build("Ice giant", 53, ICE_GIANT, 5, 0, false, null, null);
		assertEquals(HpEstimate.UNKNOWN, noScale.getHp());
		assertEquals(0, noScale.getHpScale());
		assertFalse(noScale.isHpUnhit());
		TargetSnapshot noRatio = SnapshotFactory.build("Ice giant", 53, ICE_GIANT, -1, 30, false, null, null);
		assertEquals(HpEstimate.UNKNOWN, noRatio.getHp());
		assertFalse(noRatio.isHpStale());
		assertFalse(noRatio.isHpUnhit());
	}

	@Test
	public void nonBreakingSpacesCountAsSpaces()
	{
		// String.trim() stops at U+0020; RuneLite's Text.standardize/sanitize also treat U+00A0 as a space.
		assertNull(SnapshotFactory.build(" ", 53, ICE_GIANT, 30, 30, false, null, null));
		assertNull(SnapshotFactory.build("<col=ff0000> </col>", 53, ICE_GIANT, 30, 30, false, null, null));
		assertNull(SnapshotFactory.build(" null ", 53, ICE_GIANT, 30, 30, false, null, null));
		assertEquals("Ice giant", SnapshotFactory.build("Ice giant ", 53, ICE_GIANT, 30, 30, false, null, null).getName());
	}
}
