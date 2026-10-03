package com.foe;

/**
 * Estimated HP from the health bar. The game sends only a fraction (ratio/scale), so this returns
 * the midpoint of the possible range. Ported from RuneLite OpponentInfoOverlay (d8e7d1e, :175-198;
 * the same logic is at :172-198 of the 1.13.1 sources jar this build resolves).
 */
final class HpEstimate
{
	// ponytail: int arithmetic, as upstream. maxHp * ratio overflows once maxHp > Integer.MAX_VALUE / scale
	// (17,895,697 at scale 120; 8,421,504 at 255). Widen to long if a max HP ever gets near that.
	static final int UNKNOWN = -1;

	private HpEstimate()
	{
	}

	static int estimate(int ratio, int scale, int maxHp)
	{
		// ratio > scale is inconsistent data; upstream would return more than maxHp for it (72 for 31/30/70).
		if (ratio < 0 || scale <= 0 || maxHp <= 0 || ratio > scale)
		{
			return UNKNOWN;
		}
		if (ratio == 0)
		{
			return 0;
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
		return (min + max + 1) / 2;
	}
}
