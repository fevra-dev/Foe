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
	 *                       false is HpMemory's answer for a target it has never seen a bar on. When max HP is
	 *                       known the snapshot is then flagged unhit (addendum 4): current HP stays unknown
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
		// Never seen a bar on this NPC (a monster shows none until it takes damage) while max HP is known: say that
		// max HP is known and current HP is not (spec, Missing data: never an invented value). The previous rule drew
		// a full bar here, which lied after a relog, when memory restarts and the monster may be half dead (seen
		// 2026-10-08). Decided here and not in HpMemory, whose contract is that stale is never true when nothing is
		// remembered. It fires on HpMemory's "nothing known" reading: no live bar seen on this NPC object since it
		// entered the scene or since the last relog. So "unhit" means "never seen with a bar", which includes a
		// monster another player damaged first. A raw half-bar reading (ratio without scale, or the reverse) is not
		// this state and is left alone, so inconsistent data stays visible as such. HpEstimate returns UNKNOWN for
		// (-1, 0), so hp needs no special case.
		boolean unhit = ratio < 0 && scale <= 0 && !hpStale && maxHp > 0;
		int hp = HpEstimate.estimate(ratio, scale, maxHp);
		return new TargetSnapshot(name, combatLevel, hp, maxHp,
			ratio, scale, hpStale, unhit,
			stat(stats, NPCComposition.STAT_ATTACK), stat(stats, NPCComposition.STAT_STRENGTH),
			stat(stats, NPCComposition.STAT_DEFENCE), stat(stats, NPCComposition.STAT_RANGED),
			stat(stats, NPCComposition.STAT_MAGIC), weakness, null);
	}

	private static int stat(int[] stats, int i)
	{
		return stats != null && i < stats.length ? Math.max(stats[i], 0) : 0;
	}
}
