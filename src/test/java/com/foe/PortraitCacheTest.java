package com.foe;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class PortraitCacheTest
{
	/** An executor that runs nothing until told to: "the render has not finished yet". */
	private final Deque<Runnable> queued = new ArrayDeque<>();
	private final Executor later = queued::add;
	private int loads;

	private static Portrait.Mesh mesh()
	{
		return new Portrait.Mesh(new float[]{-50, 50, 50, -50}, new float[]{-100, -100, 0, 0}, new float[4],
			new int[]{0, 0}, new int[]{1, 2}, new int[]{2, 3}, new int[]{64, 64}, new int[]{-1, -1}, null, 2, true);
	}

	private Supplier<Portrait.Mesh> counting(Supplier<Portrait.Mesh> s)
	{
		return () ->
		{
			loads++;
			return s.get();
		};
	}

	private void runQueued()
	{
		while (!queued.isEmpty())
		{
			queued.poll().run();
		}
	}

	@Test
	public void nothingUntilTheRenderFinishesThenTheImageAndTheModelIsLoadedOnce()
	{
		PortraitCache cache = new PortraitCache(later);
		assertNull("not rendered yet", cache.get(7, counting(PortraitCacheTest::mesh)));
		assertNull(cache.get(7, counting(PortraitCacheTest::mesh)));
		runQueued();
		BufferedImage img = cache.get(7, counting(PortraitCacheTest::mesh));
		assertNotNull(img);
		assertEquals(PortraitCache.SIZE, img.getWidth());
		assertEquals("loaded once, whatever the number of asks", 1, loads);
	}

	@Test
	public void noModelMeansNoPortraitAndNoRetry()
	{
		PortraitCache cache = new PortraitCache(Runnable::run);
		assertNull(cache.get(7, counting(() -> null)));
		assertNull(cache.get(7, counting(() -> null)));
		assertEquals(1, loads);
	}

	@Test
	public void aLoadThatThrowsIsNoPortraitNotAnException()
	{
		PortraitCache cache = new PortraitCache(Runnable::run);
		assertNull(cache.get(7, counting(() ->
		{
			throw new IllegalStateException("cache not ready");
		})));
		assertNull(cache.get(7, counting(PortraitCacheTest::mesh)));
		assertEquals("not retried", 1, loads);
	}

	@Test
	public void aRenderThatThrowsIsNoPortraitNotAnException()
	{
		// Face indices past the vertex arrays: render throws ArrayIndexOutOfBoundsException on the executor.
		Portrait.Mesh broken = new Portrait.Mesh(new float[1], new float[1], new float[1], new int[]{0},
			new int[]{5}, new int[]{9}, new int[]{64}, null, null, 1, true);
		PortraitCache cache = new PortraitCache(Runnable::run);
		assertNull(cache.get(7, () -> broken));
		assertNull(cache.get(7, () -> broken));
	}

	@Test
	public void aRejectingExecutorIsNoPortraitNotAnException()
	{
		PortraitCache cache = new PortraitCache(r ->
		{
			throw new RejectedExecutionException("shut down");
		});
		assertNull(cache.get(7, PortraitCacheTest::mesh));
	}

	@Test
	public void theLeastRecentlyUsedIsDroppedPastCapacity()
	{
		PortraitCache cache = new PortraitCache(Runnable::run);
		for (int id = 0; id <= PortraitCache.CAPACITY; id++)
		{
			cache.get(id, counting(PortraitCacheTest::mesh));
		}
		assertEquals(PortraitCache.CAPACITY + 1, loads);
		cache.get(PortraitCache.CAPACITY, counting(PortraitCacheTest::mesh));
		assertEquals("the newest is still held", PortraitCache.CAPACITY + 1, loads);
		cache.get(0, counting(PortraitCacheTest::mesh));
		assertEquals("the oldest was dropped and loads again", PortraitCache.CAPACITY + 2, loads);
	}
}
