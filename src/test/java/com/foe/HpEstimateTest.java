package com.foe;

import static org.junit.Assert.assertEquals;
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
	public void ratioAboveScaleReadsAsFull()
	{
		// Bad server data. Unclamped, (40,30,70) gives min=2758/29=95, max=min(96,70)=70 -> (95+70+1)/2=83 > maxHp.
		assertEquals(70, HpEstimate.estimate(31, 30, 70));
		assertEquals(70, HpEstimate.estimate(40, 30, 70));
		assertEquals(70, HpEstimate.estimate(255, 30, 70));
		assertEquals(1, HpEstimate.estimate(100, 30, 1));
		// scale 1: read as ratio == scale, i.e. exactly what (1, 1, 70) gives
		assertEquals(HpEstimate.estimate(1, 1, 70), HpEstimate.estimate(5, 1, 70));
	}

	@Test
	public void staysWithinMaxHpAndNeverFallsAsTheBarFills()
	{
		for (int scale : new int[]{1, 2, 5, 30, 60, 120})
		{
			for (int maxHp : new int[]{1, 2, 29, 30, 31, 70, 1000, 30_000})
			{
				int previous = 0;
				for (int ratio = 0; ratio <= scale + 3; ratio++)
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
}
