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
	 * @param scale          health scale to estimate from; 0 when there is none
	 * @param hpStale        true when ratio and scale are the last known values, not the live bar; passed through
	 *                       untouched so the overlay can dim the HP
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
		// Names can carry markup (<col=..>, <img=..>); strip it, then trim, then test for empty / "null" on the
		// result. "null" is the name of an unnamed definition: RuneLite's LootManager guards on it the same way.
		String name = Text.removeTags(rawName).trim();
		if (name.isEmpty() || name.equals("null"))
		{
			return null;
		}
		int maxHp = stat(stats, NPCComposition.STAT_HITPOINTS);
		if (maxHp <= 0 && fallbackMaxHp != null)
		{
			maxHp = Math.max(fallbackMaxHp, 0);
		}
		return new TargetSnapshot(name, combatLevel, HpEstimate.estimate(ratio, scale, maxHp), maxHp,
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
