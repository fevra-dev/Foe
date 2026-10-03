package com.foe;

/**
 * Decides which NPC is the foe. Pure: no RuneLite types, so time and NPC indices are passed in.
 * Sticky by design (spec addendum 1): in multi-combat, another NPC's hit does not steal a live target.
 *
 * <p>There is deliberately no "lost the target" method. {@code getInteracting()} drops to null between
 * attacks (97 of 127 interacting changes in docs/probe/raw.log were to no NPC), so null is not evidence
 * that the fight ended. A target ends only by {@link #gone} (death or despawn) or by the linger running
 * out, and callers must not turn a null into {@link #NONE} and hand it to a method here.
 *
 * <p>{@code nowMs} must come from a monotonic clock, not the wall clock: a wall clock stepping backwards
 * makes {@code nowMs - lastActiveMs} negative, and the target would stay live until it caught up.
 */
final class TargetTracker
{
	static final int NONE = -1;

	private int target = NONE;
	private long lastActiveMs;

	/** The player attacked this NPC. Always wins. {@link #NONE} is ignored: it is not an NPC to attack. */
	void playerAttacks(int npcIndex, long nowMs)
	{
		if (npcIndex == NONE)
		{
			return;
		}
		target = npcIndex;
		lastActiveMs = nowMs;
	}

	/** An NPC hit the player. Adopted only when there is no live target. */
	void hitBy(int npcIndex, long nowMs, long lingerMs)
	{
		if (npcIndex == target)
		{
			lastActiveMs = nowMs;
			return;
		}
		if (current(nowMs, lingerMs) == NONE)
		{
			target = npcIndex;
			lastActiveMs = nowMs;
		}
	}

	/**
	 * Combat with this NPC is still going on (either side is interacting with the other). That is
	 * evidence of a fight now, so it also brings back a target whose linger lapsed unnoticed.
	 */
	void stillFighting(int npcIndex, long nowMs)
	{
		if (npcIndex == target)
		{
			lastActiveMs = nowMs;
		}
	}

	/** The NPC died or despawned. Its index may be reused, so it must not linger. */
	void gone(int npcIndex)
	{
		if (npcIndex == target)
		{
			target = NONE;
		}
	}

	/** The live target's index, or {@link #NONE}. Reading never changes what a later call does. */
	int current(long nowMs, long lingerMs)
	{
		return target != NONE && nowMs - lastActiveMs <= lingerMs ? target : NONE;
	}
}
