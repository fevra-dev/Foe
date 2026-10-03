package com.foe;

/**
 * Decides which NPC is the foe. Pure: no RuneLite types, so time and NPC indices are passed in.
 * Sticky by design (spec addendum 1): in multi-combat, another NPC's hit does not steal a live target.
 *
 * <p>There is deliberately no "lost the target" method. {@code getInteracting()} leaves the NPC between
 * attacks (97 of 127 interacting changes in docs/probe/raw.log were to "no NPC": null or a non-NPC
 * actor, which the probe did not distinguish), so losing the NPC is not evidence
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
		involved(npcIndex, nowMs, lingerMs);
	}

	/**
	 * One of the player's hits landed on this NPC. Not an engagement: a hit already in flight can land
	 * after the player switched targets (docs/probe/raw.log lines 265-267), so it refreshes the target
	 * or fills an empty slot, and never overrides a live one. Use {@link #playerAttacks} for engagement.
	 */
	void playerHit(int npcIndex, long nowMs, long lingerMs)
	{
		involved(npcIndex, nowMs, lingerMs);
	}

	private void involved(int npcIndex, long nowMs, long lingerMs)
	{
		if (npcIndex == NONE)
		{
			return;
		}
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
	 * evidence of a fight now, so it also brings back a target whose linger has lapsed, however long
	 * ago. Callers must therefore pass only the index of an NPC object they hold and have not seen
	 * despawn or die, or a reused index could revive a stale target.
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
