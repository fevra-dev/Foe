package com.foe;

import net.runelite.api.NPCComposition;
import net.runelite.client.util.Text;

/** Raw client values -> TargetSnapshot. Pure apart from Text.removeTags, so it is unit-testable. */
final class SnapshotFactory
{
	private SnapshotFactory()
	{
	}

	/**
	 * @param stats          NPCComposition.getStats(); may be null or short
	 * @param ratio          health ratio to estimate from; -1 when there is none
	 * @param scale          health scale to estimate from; 0 when there is none. (-1, 0) with {@code hpStale}
	 *                       false is HpMemory's answer for a target that has never had a bar. When max HP is
	 *                       known the snapshot then carries a full bar at max HP, flagged stale (addendum 3)
	 * @param hpStale        true when ratio and scale are the last known values, not the live bar; passed through
	 *                       so the overlay can dim the HP
	 * @param fallbackMaxHp  NPCManager.getHealth(id); may be null, and is 0 for an entry without hitpoints
	 * @return null when there is nothing nameable to show
	 */
	static TargetSnapshot build(String rawName, int combatLevel, int[] stats, int ratio, int scale, boolean hpStale,
		Integer fallbackMaxHp, Weakness weakness)
	{
		if (rawName == null)
		{
			return null;
		}
		// Names can carry markup (<col=..>, <img=..>); strip it, normalise U+00A0 to a space (trim() stops at
		// U+0020; RuneLite's Text.standardize does the same replacement), trim, then test for empty / "null".
		// "null" is the name of an unnamed definition: RuneLite's LootManager rejects it after stripping tags.
		String name = Text.removeTags(rawName).replace(' ', ' ').trim();
		if (name.isEmpty() || name.equals("null"))
		{
			return null;
		}
		int maxHp = stat(stats, NPCComposition.STAT_HITPOINTS);
		if (maxHp <= 0 && fallbackMaxHp != null)
		{
			maxHp = Math.max(fallbackMaxHp, 0);
		}
		int hp;
		if (ratio < 0 && scale <= 0 && !hpStale && maxHp > 0)
		{
			// Never had a bar (a monster shows none until it takes damage). Max HP is known, so say so: a full bar
			// at max HP, drawn in the stale style because nothing confirms it, until the first live reading
			// replaces it. This is decided here and not in HpMemory, whose contract is that stale is never true
			// when nothing is remembered. It is exactly HpMemory's "nothing known" reading, so a bar that was seen
			// and then lost (stale, with its last ratio and scale) and inconsistent data (a ratio with no scale,
			// or the reverse) never come through this branch. The raw ratio/scale of the snapshot are the full
			// bar's 1/1 here, and HpEstimate is bypassed: with scale 1 it would return a midpoint, not maxHp.
			ratio = 1;
			scale = 1;
			hpStale = true;
			hp = maxHp;
		}
		else
		{
			hp = HpEstimate.estimate(ratio, scale, maxHp);
		}
		return new TargetSnapshot(name, combatLevel, hp, maxHp,
			ratio, scale, hpStale,
			stat(stats, NPCComposition.STAT_ATTACK), stat(stats, NPCComposition.STAT_STRENGTH),
			stat(stats, NPCComposition.STAT_DEFENCE), stat(stats, NPCComposition.STAT_RANGED),
			stat(stats, NPCComposition.STAT_MAGIC), weakness);
	}

	private static int stat(int[] stats, int i)
	{
		return stats != null && i < stats.length ? Math.max(stats[i], 0) : 0;
	}
}
