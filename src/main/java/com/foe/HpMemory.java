package com.foe;

import java.util.IdentityHashMap;
import java.util.Map;
import lombok.Value;

/**
 * The last health bar seen on each NPC (spec: "health ratio -1 -> last known value, dimmed").
 * Pure, so FoePlugin only has to ask it once per tick.
 *
 * <p>A bar is live when the client reports both a scale above 0 and a ratio of at least 0. Upstream's
 * OpponentInfoOverlay (1.13.1, lines 107-110) tests only {@code getHealthScale() > 0}; we also require the
 * ratio, because a scale with no ratio is not a bar to draw, and storing it would overwrite a good memory with
 * nothing. The client reports -1 for both once the bar has gone (Actor.getHealthRatio/getHealthScale docs), so
 * the two rules agree on every reading the client is documented to send.
 *
 * <p>Memory is kept per NPC object, compared by identity, so a reused NPC index starts empty. It is kept across
 * target switches: switching A -> B -> A must not forget A, or SnapshotFactory's "never had a bar" rule would
 * draw a damaged A at full HP (settings-redesign review F1). An entry lives until the NPC dies or despawns
 * ({@link #forget}), or the plugin forgets everything on logout, hop or stop ({@link #clear}); despawn bounds the
 * map to NPCs still in the scene. Only the current target is read each tick, so a remembered value is the last
 * one seen while it was the target, and it is always reported as stale.
 */
final class HpMemory
{
	@Value
	static class Reading
	{
		/** The ratio to estimate from; -1 when nothing is known. */
		int ratio;
		/** The scale to estimate from; 0 when nothing is known. */
		int scale;
		/** True when ratio and scale are remembered, not live. Never true when there is nothing remembered. */
		boolean stale;
	}

	private final Map<Object, int[]> bars = new IdentityHashMap<>();

	/** The reading to draw for {@code target}, given what the client reports for it right now. */
	Reading read(Object target, int liveRatio, int liveScale)
	{
		if (liveScale > 0 && liveRatio >= 0)
		{
			bars.put(target, new int[]{liveRatio, liveScale});
			return new Reading(liveRatio, liveScale, false);
		}
		int[] last = bars.get(target);
		return last != null ? new Reading(last[0], last[1], true) : new Reading(-1, 0, false);
	}

	/** This NPC died or despawned: its object will not come back, and its index may be reused. */
	void forget(Object target)
	{
		bars.remove(target);
	}

	/** Forget every NPC: logout, hop, or the plugin stopped. */
	void clear()
	{
		bars.clear();
	}
}
