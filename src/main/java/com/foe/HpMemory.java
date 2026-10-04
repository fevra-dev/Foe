package com.foe;

import lombok.Value;

/**
 * The last health bar seen on one target (spec: "health ratio -1 -> last known value, dimmed").
 * Pure, so FoePlugin only has to ask it once per tick.
 *
 * <p>A bar is live when the client reports both a scale above 0 and a ratio of at least 0. Upstream's
 * OpponentInfoOverlay (1.13.1, lines 107-110) tests only {@code getHealthScale() > 0}; we also require the
 * ratio, because a scale with no ratio is not a bar to draw, and storing it would overwrite a good memory with
 * nothing. The client reports -1 for both once the bar has gone (Actor.getHealthRatio/getHealthScale docs), so
 * the two rules agree on every reading the client is documented to send.
 *
 * <p>The memory belongs to one target, compared by identity: the plugin passes the NPC object, so a new target
 * starts empty even if the NPC index was reused. Nothing is time-limited. A remembered value stays until the
 * target changes, {@link #clear} is called, or a live bar replaces it, and it is always reported as stale.
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

	private Object owner;
	private int ratio = -1;
	private int scale = 0;

	/** The reading to draw for {@code target}, given what the client reports for it right now. */
	Reading read(Object target, int liveRatio, int liveScale)
	{
		if (target != owner)
		{
			clear();
			owner = target;
		}
		if (liveScale > 0 && liveRatio >= 0)
		{
			ratio = liveRatio;
			scale = liveScale;
			return new Reading(ratio, scale, false);
		}
		return scale > 0 ? new Reading(ratio, scale, true) : new Reading(-1, 0, false);
	}

	/** Forget everything: logout, hop, the target died or despawned, or the plugin stopped. */
	void clear()
	{
		owner = null;
		ratio = -1;
		scale = 0;
	}
}
