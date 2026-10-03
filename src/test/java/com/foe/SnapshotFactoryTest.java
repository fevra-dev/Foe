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
