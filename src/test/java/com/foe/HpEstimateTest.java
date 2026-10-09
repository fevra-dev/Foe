package com.foe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class HpEstimateTest
{
	@Test
	public void fullBar()
	{
		// min=(70*29+28)/29=2058/29=70, max=min(2099/29=72, 70)=70 -> (70+70+1)/2=70
		assertEquals(70, HpEstimate.estimate(30, 30, 70));
	}

	@Test
	public void halfBar()
	{
		// min=(70*14+28)/29=1008/29=34, max=(70*15-1)/29=1049/29=36 -> (34+36+1)/2=35
		assertEquals(35, HpEstimate.estimate(15, 30, 70));
	}

	@Test
	public void scaleOfOneKnowsOnlyAlive()
	{
		// min=1, max=70 -> 36, matching RuneLite's own behaviour
		assertEquals(36, HpEstimate.estimate(1, 1, 70));
	}

	@Test
	public void emptyBarIsZero()
	{
		assertEquals(0, HpEstimate.estimate(0, 30, 70));
	}

	@Test
	public void unknownInputs()
	{
		assertEquals(HpEstimate.UNKNOWN, HpEstimate.estimate(-1, 30, 70));
		assertEquals(HpEstimate.UNKNOWN, HpEstimate.estimate(15, 0, 70));
		assertEquals(HpEstimate.UNKNOWN, HpEstimate.estimate(15, 30, 0));
		// The API's real "no info" value is -1 for scale (Actor.getHealthScale javadoc), not 0.
		assertEquals(HpEstimate.UNKNOWN, HpEstimate.estimate(15, -1, 70));
		assertEquals(HpEstimate.UNKNOWN, HpEstimate.estimate(-1, -1, 70));
		assertEquals(HpEstimate.UNKNOWN, HpEstimate.estimate(15, 30, -1));
	}

	@Test
	public void fullBarIsMaxHpWhateverTheMaxHp()
	{
		// ratio == scale: min=(maxHp*(scale-1)+scale-2)/(scale-1)=maxHp, and max is clamped to maxHp
		for (int scale : new int[]{2, 30, 120})
		{
			for (int maxHp : new int[]{1, 29, 30, 31, 70, 30_000})
			{
				assertEquals("scale " + scale + ", maxHp " + maxHp, maxHp, HpEstimate.estimate(scale, scale, maxHp));
			}
		}
	}

	@Test
	public void ratioOneWithRealScaleIsNearlyEmpty()
	{
		// ratio 1 skips the min formula: min=1, max=(70*1-1)/29=69/29=2 -> (1+2+1)/2=2.
		// Without the skip, min=(70*0+28)/29=0 -> (0+2+1)/2=1.
		assertEquals(2, HpEstimate.estimate(1, 30, 70));
	}

	@Test
	public void ratioAboveScaleIsUnknown()
	{
		// Inconsistent data. Upstream computes (40,30,70) as min=2758/29=95, max=70 -> 83 > maxHp. Any number here
		// would be invented (spec: never show an invented value), so it is UNKNOWN like every other bad input.
		assertEquals(HpEstimate.UNKNOWN, HpEstimate.estimate(31, 30, 70));
		assertEquals(HpEstimate.UNKNOWN, HpEstimate.estimate(40, 30, 70));
		assertEquals(HpEstimate.UNKNOWN, HpEstimate.estimate(255, 30, 70));
		assertEquals(HpEstimate.UNKNOWN, HpEstimate.estimate(100, 30, 1));
		assertEquals(HpEstimate.UNKNOWN, HpEstimate.estimate(5, 1, 70));
	}

	@Test
	public void staysWithinMaxHpAndNeverFallsAsTheBarFills()
	{
		for (int scale : new int[]{1, 2, 5, 30, 60, 120})
		{
			for (int maxHp : new int[]{1, 2, 29, 30, 31, 70, 1000, 30_000})
			{
				int previous = 0;
				for (int ratio = 0; ratio <= scale; ratio++)
				{
					int hp = HpEstimate.estimate(ratio, scale, maxHp);
					String at = "ratio " + ratio + ", scale " + scale + ", maxHp " + maxHp;
					assertTrue(at + " -> " + hp, hp >= 0 && hp <= maxHp);
					assertTrue(at + " fell from " + previous + " to " + hp, hp >= previous);
					previous = hp;
				}
			}
		}
	}

	@Test
	public void exactWhenMaxHpFitsInTheScale()
	{
		// RuneLite's comment: "able to recover the exact health if maxHealth <= healthScale", where the
		// server sends ratio = 1 + (scale - 1) * health / maxHealth. e.g. scale 30, maxHp 10, health 5:
		// ratio=1+145/10=15, min=(10*14+28)/29=5, max=(10*15-1)/29=5 -> (5+5+1)/2=5
		for (int scale : new int[]{30, 60, 120})
		{
			for (int maxHp = 1; maxHp <= scale; maxHp++)
			{
				for (int health = 1; health <= maxHp; health++)
				{
					int ratio = 1 + (scale - 1) * health / maxHp;
					assertEquals("scale " + scale + ", maxHp " + maxHp + ", health " + health,
						health, HpEstimate.estimate(ratio, scale, maxHp));
				}
			}
		}
	}

	@Test
	public void bossSizedMaxHp()
	{
		// maxHp * ratio tops out at 30000*120=3.6M here, far below Integer.MAX_VALUE (2.147B), so int math is safe.
		// (120,120): min=(30000*119+118)/119=30000, max=min(3599999/119=30252, 30000)=30000 -> 30000
		assertEquals(30_000, HpEstimate.estimate(120, 120, 30_000));
		// (60,120): min=(30000*59+118)/119=14874, max=(30000*60-1)/119=15126 -> (14874+15126+1)/2=15000
		assertEquals(15_000, HpEstimate.estimate(60, 120, 30_000));
		// (1,120): min=1, max=(30000-1)/119=252 -> (1+252+1)/2=127
		assertEquals(127, HpEstimate.estimate(1, 120, 30_000));
		// (30,30): min=30000, max=min(899999/29=31034, 30000)=30000 -> 30000; (15,30): 14483, 15517 -> 15000
		assertEquals(30_000, HpEstimate.estimate(30, 30, 30_000));
		assertEquals(15_000, HpEstimate.estimate(15, 30, 30_000));
	}

	// ---- range(): the bounds estimate() takes the midpoint of, exposed for HpTracker (spec addendum 5) ----

	@Test
	public void theRangeOfRatio22OfScale30OnMaxHp85IsSixtyTwoToSixtyFour()
	{
		// The case that prompted addendum 5: Foe showed 63 where another plugin showed 64. Both are inside 62-64.
		// min=(85*21+28)/29=1813/29=62, max=min((85*22-1)/29=1869/29=64, 85)=64
		HpEstimate.Range r = HpEstimate.range(22, 30, 85);
		assertEquals(62, r.getMin());
		assertEquals(64, r.getMax());
		assertFalse(r.isExact());
		assertTrue(r.contains(62) && r.contains(63) && r.contains(64));
		assertFalse(r.contains(61));
		assertFalse(r.contains(65));
		assertEquals(63, r.midpoint());
		assertEquals(HpEstimate.estimate(22, 30, 85), r.midpoint());
	}

	@Test
	public void aFullBarAndAnEmptyBarAreExact()
	{
		assertTrue(HpEstimate.range(30, 30, 70).isExact());
		assertEquals(70, HpEstimate.range(30, 30, 70).getMin());
		assertTrue(HpEstimate.range(0, 30, 70).isExact());
		assertEquals(0, HpEstimate.range(0, 30, 70).getMax());
	}

	@Test
	public void aSmallMonstersBarIsExactAtMostReadings()
	{
		// maxHp 20 on a scale of 30: ratio = 1 + 29 * 7 / 20 = 11 -> min = ceil(20*10/29) = 7, max = (20*11-1)/29 = 7
		HpEstimate.Range r = HpEstimate.range(11, 30, 20);
		assertTrue(r.isExact());
		assertEquals(7, r.getMin());
	}

	@Test
	public void noRangeWhereThereIsNoEstimate()
	{
		assertNull(HpEstimate.range(-1, 30, 70));
		assertNull(HpEstimate.range(15, 0, 70));
		assertNull(HpEstimate.range(15, -1, 70));
		assertNull(HpEstimate.range(15, 30, 0));
		assertNull(HpEstimate.range(31, 30, 70));
		assertNull(HpEstimate.range(-1, 0, 70));
	}

	@Test
	public void estimateIsAlwaysTheMidpointOfTheRange()
	{
		int withRange = 0;
		for (int scale : new int[]{0, 1, 2, 30, 120})
		{
			for (int maxHp : new int[]{0, 1, 5, 29, 30, 31, 70, 85, 1000})
			{
				for (int ratio = -2; ratio <= scale + 2; ratio++)
				{
					HpEstimate.Range r = HpEstimate.range(ratio, scale, maxHp);
					String at = "ratio " + ratio + ", scale " + scale + ", maxHp " + maxHp;
					if (r == null)
					{
						assertEquals(at, HpEstimate.UNKNOWN, HpEstimate.estimate(ratio, scale, maxHp));
					}
					else
					{
						withRange++;
						assertEquals(at, HpEstimate.estimate(ratio, scale, maxHp), r.midpoint());
					}
				}
			}
		}
		assertTrue("the loop reached real ranges, not only the null branch: " + withRange, withRange > 500);
	}
}
