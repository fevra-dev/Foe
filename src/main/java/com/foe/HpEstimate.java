package com.foe;

import lombok.Value;

/**
 * Estimated HP from the health bar. The game sends only a fraction (ratio/scale), so the true HP is only known to
 * lie in a range, and the estimate is the midpoint of that range. Ported from RuneLite OpponentInfoOverlay (d8e7d1e,
 * :175-198; the same logic is at :172-198 of the 1.13.1 sources jar this build resolves).
 *
 * <p>{@link #range} exposes the bounds themselves, so that {@link HpTracker} can check an exact value against the
 * very same range the estimate is taken from rather than re-deriving the formula.
 */
final class HpEstimate
{
	/**
	 * The HP values the bar allows: {@code min..max}, both inclusive. For a handful of readings on a small monster
	 * (maxHp below scale - 1) the formula yields max below min: no HP maps to that ratio, so nothing is inside it.
	 */
	@Value
	static class Range
	{
		int min;
		int max;

		boolean contains(int hp)
		{
			return hp >= min && hp <= max;
		}

		/** One HP value, not a range: a full bar, an empty one, or a monster small enough for the bar to resolve. */
		boolean isExact()
		{
			return min == max;
		}

		/** What {@link HpEstimate#estimate} shows. */
		int midpoint()
		{
			return (min + max + 1) / 2;
		}
	}

	// ponytail: int arithmetic, as upstream. maxHp * ratio overflows once maxHp > Integer.MAX_VALUE / scale
	// (17,895,697 at scale 120; 8,421,504 at 255). Widen to long if a max HP ever gets near that.
	static final int UNKNOWN = -1;

	private HpEstimate()
	{
	}

	static int estimate(int ratio, int scale, int maxHp)
	{
		Range r = range(ratio, scale, maxHp);
		return r == null ? UNKNOWN : r.midpoint();
	}

	/** @return the HP values the bar allows; null on the inputs {@link #estimate} answers {@link #UNKNOWN} for */
	static Range range(int ratio, int scale, int maxHp)
	{
		// ratio > scale is inconsistent data; upstream would return more than maxHp for it (72 for 31/30/70).
		if (ratio < 0 || scale <= 0 || maxHp <= 0 || ratio > scale)
		{
			return null;
		}
		if (ratio == 0)
		{
			return new Range(0, 0);
		}
		int min = 1;
		int max;
		if (scale > 1)
		{
			if (ratio > 1)
			{
				// ratio == 1 is excluded: the server forces ratio 0 at 0 HP, so ratio 1 only bounds the top
				min = (maxHp * (ratio - 1) + scale - 2) / (scale - 1);
			}
			max = Math.min((maxHp * ratio - 1) / (scale - 1), maxHp);
		}
		else
		{
			max = maxHp;
		}
		return new Range(min, max);
	}
}
