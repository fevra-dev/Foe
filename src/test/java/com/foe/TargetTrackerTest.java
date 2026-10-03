package com.foe;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class TargetTrackerTest
{
	private static final long LINGER = 10_000;
	private final TargetTracker t = new TargetTracker();

	@Test
	public void noTargetInitially()
	{
		assertEquals(TargetTracker.NONE, t.current(0, LINGER));
	}

	@Test
	public void playerAttackSetsTarget()
	{
		t.playerAttacks(7, 0);
		assertEquals(7, t.current(0, LINGER));
	}

	@Test
	public void targetLingersThenExpires()
	{
		t.playerAttacks(7, 0);
		assertEquals(7, t.current(10_000, LINGER));
		assertEquals(TargetTracker.NONE, t.current(10_001, LINGER));
	}

	@Test
	public void zeroLingerExpiresOnNextTick()
	{
		t.playerAttacks(7, 0);
		assertEquals(7, t.current(0, 0));
		assertEquals(TargetTracker.NONE, t.current(600, 0));
	}

	@Test
	public void hitAdoptedWhenNoTarget()
	{
		t.hitBy(3, 0, LINGER);
		assertEquals(3, t.current(0, LINGER));
	}

	@Test
	public void hitIgnoredWhileTargetLive()
	{
		t.playerAttacks(7, 0);
		t.hitBy(3, 1_000, LINGER);
		assertEquals(7, t.current(1_000, LINGER));
	}

	@Test
	public void hitAdoptedAfterTargetExpires()
	{
		t.playerAttacks(7, 0);
		t.hitBy(3, 10_001, LINGER);
		assertEquals(3, t.current(10_001, LINGER));
	}

	@Test
	public void hitByCurrentTargetRefreshesIt()
	{
		t.playerAttacks(7, 0);
		t.hitBy(7, 9_000, LINGER);
		assertEquals(7, t.current(18_000, LINGER));
	}

	@Test
	public void playerAttackOverridesLiveTarget()
	{
		t.hitBy(3, 0, LINGER);
		t.playerAttacks(7, 100);
		assertEquals(7, t.current(100, LINGER));
	}

	@Test
	public void stillFightingRefreshesOnlyTheTarget()
	{
		t.playerAttacks(7, 0);
		t.stillFighting(3, 9_000);
		assertEquals(TargetTracker.NONE, t.current(10_001, LINGER));
		t.playerAttacks(7, 0);
		t.stillFighting(7, 9_000);
		assertEquals(7, t.current(18_000, LINGER));
	}

	@Test
	public void goneClearsOnlyTheTarget()
	{
		t.playerAttacks(7, 0);
		t.gone(3);
		assertEquals(7, t.current(0, LINGER));
		t.gone(7);
		assertEquals(TargetTracker.NONE, t.current(0, LINGER));
	}

	// Not in the plan. Added after review of the plan's implementation, whose current() cleared
	// the target as a side effect of being read, so a later stillFighting() behaved differently
	// depending on whether the overlay happened to read in between.
	@Test
	public void readingTheTargetDoesNotChangeLaterBehaviour()
	{
		TargetTracker looked = new TargetTracker();
		TargetTracker unlooked = new TargetTracker();
		looked.playerAttacks(7, 0);
		unlooked.playerAttacks(7, 0);

		looked.current(10_001, LINGER); // linger has lapsed; only this tracker was asked
		looked.stillFighting(7, 20_000);
		unlooked.stillFighting(7, 20_000);

		// Still interacting at 20 s means the fight is on, so the target is shown again (spec rule 1).
		assertEquals(7, unlooked.current(20_000, LINGER));
		assertEquals(unlooked.current(20_000, LINGER), looked.current(20_000, LINGER));
	}

	// Not in the plan. The probe saw getInteracting() go to null in 97 of 127 changes, so a caller
	// that maps "no NPC" to NONE must not blank a live target.
	@Test
	public void attackingNoneDoesNotClearTheTarget()
	{
		t.playerAttacks(7, 0);
		t.playerAttacks(TargetTracker.NONE, 100);
		assertEquals(7, t.current(100, LINGER));
	}

	// Not in the plan. The planned wiring (Task 7) calls playerAttacks on every hitsplat of yours
	// to keep the linger alive, and nothing above pins that a repeat attack restarts the window.
	@Test
	public void repeatAttackOnTheSameNpcRestartsTheLinger()
	{
		t.playerAttacks(7, 0);
		t.playerAttacks(7, 9_000);
		assertEquals(7, t.current(18_000, LINGER));
	}
}
