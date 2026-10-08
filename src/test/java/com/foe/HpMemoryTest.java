package com.foe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HpMemoryTest
{
	private final HpMemory memory = new HpMemory();
	private final Object a = new Object();
	private final Object b = new Object();

	private static void assertReading(int ratio, int scale, boolean stale, HpMemory.Reading r)
	{
		assertEquals("ratio", ratio, r.getRatio());
		assertEquals("scale", scale, r.getScale());
		assertEquals("stale", stale, r.isStale());
	}

	@Test
	public void liveBarIsPassedThroughAndNotStale()
	{
		assertReading(15, 30, false, memory.read(a, 15, 30));
	}

	@Test
	public void missingBarFallsBackToTheLastKnownValueAndIsStale()
	{
		memory.read(a, 15, 30);
		// The client reports -1 / -1 once the bar has gone (Actor.getHealthRatio / getHealthScale docs).
		assertReading(15, 30, true, memory.read(a, -1, -1));
	}

	@Test
	public void theLastKnownValueIsTheLatestLiveOne()
	{
		memory.read(a, 20, 30);
		memory.read(a, 9, 30);
		assertReading(9, 30, true, memory.read(a, -1, -1));
	}

	@Test
	public void aLiveBarAgainIsNotStale()
	{
		memory.read(a, 15, 30);
		memory.read(a, -1, -1);
		assertReading(12, 30, false, memory.read(a, 12, 30));
	}

	@Test
	public void nothingKnownAndNoBarIsNotStaleBecauseThereIsNothingToDim()
	{
		assertReading(-1, 0, false, memory.read(a, -1, -1));
	}

	@Test
	public void eachNpcHasItsOwnMemory()
	{
		memory.read(a, 15, 30);
		// b is a different NPC: a's value must not be shown for it.
		assertReading(-1, 0, false, memory.read(b, -1, -1));
		// Settings-redesign review F1: switching A -> B -> A must not forget A, or the factory's
		// "never had a bar" rule would draw a damaged A at full HP.
		assertReading(15, 30, true, memory.read(a, -1, -1));
	}

	@Test
	public void forgetDropsOneNpcOnly()
	{
		memory.read(a, 15, 30);
		memory.read(b, 3, 30);
		memory.forget(a);
		assertReading(-1, 0, false, memory.read(a, -1, -1));
		assertReading(3, 30, true, memory.read(b, -1, -1));
	}

	@Test
	public void aTargetChangeStartsFromItsOwnLiveBar()
	{
		memory.read(a, 15, 30);
		assertReading(3, 30, false, memory.read(b, 3, 30));
		assertReading(3, 30, true, memory.read(b, -1, -1));
	}

	// Upstream OpponentInfoOverlay.java:107-110 stores only when getHealthScale() > 0, so a (-1, -1) reading
	// can never overwrite the memory. This pins that, and the one place we are stricter: a scale with no ratio.
	@Test
	public void aScaleWithoutARatioIsNotABarAndDoesNotOverwriteTheMemory()
	{
		memory.read(a, 15, 30);
		assertReading(15, 30, true, memory.read(a, -1, 30));
		assertReading(15, 30, true, memory.read(a, -1, -1));
	}

	@Test
	public void aRatioWithoutAScaleIsNotABar()
	{
		memory.read(a, 15, 30);
		assertReading(15, 30, true, memory.read(a, 7, 0));
		assertReading(15, 30, true, memory.read(a, 7, -1));
	}

	@Test
	public void zeroRatioIsAValidLiveReading()
	{
		// 0 means dead, and a bar at 0 is still a bar: the factory/overlay decide how to draw it.
		assertReading(0, 30, false, memory.read(a, 0, 30));
		assertReading(0, 30, true, memory.read(a, -1, -1));
	}

	@Test
	public void clearForgetsEveryNpc()
	{
		memory.read(a, 15, 30);
		memory.read(b, 3, 30);
		memory.clear();
		assertReading(-1, 0, false, memory.read(a, -1, -1));
		assertReading(-1, 0, false, memory.read(b, -1, -1));
	}

	@Test
	public void staleIsNeverTrueWithoutAMemory()
	{
		assertFalse(memory.read(a, -1, -1).isStale());
		assertTrue(memory.read(a, 5, 30).getScale() > 0);
		assertTrue(memory.read(a, -1, -1).isStale());
	}
}
