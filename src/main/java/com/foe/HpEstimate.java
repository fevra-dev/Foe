package com.foe;

/**
 * Estimated HP from the health bar. The game sends only a fraction (ratio/scale), so this returns
 * the midpoint of the possible range. Ported from RuneLite OpponentInfoOverlay (d8e7d1e, :175-198;
 * the same logic is at :172-198 of the 1.13.1 sources jar this build resolves).
 *
 * <p>ponytail: int arithmetic, as upstream. {@code maxHp * ratio} overflows past Integer.MAX_VALUE, i.e. at
 * maxHp above 17,895,697 for scale 120 (2147483647 / 120). Widen to long if a max HP ever gets near that.
 */
final class HpEstimate
{
	static final int UNKNOWN = -1;

	private HpEstimate()
	{
	}

	static int estimate(int ratio, int scale, int maxHp)
	{
		if (ratio < 0 || scale <= 0 || maxHp <= 0)
		{
			return UNKNOWN;
		}
		if (ratio == 0)
		{
			return 0;
		}
		// Not in the upstream formula, which returns more than maxHp here (72 for ratio 31, scale 30, maxHp 70).
		// A ratio above the scale cannot come from a sane server; reading it as "full" keeps the result
		// within maxHp.
		ratio = Math.min(ratio, scale);
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
		return (min + max + 1) / 2;
	}
}
