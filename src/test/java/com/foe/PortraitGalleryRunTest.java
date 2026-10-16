package com.foe;

import static com.foe.PortraitGalleryTest.box;
import static com.foe.PortraitGalleryTest.fake;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;
import net.runelite.api.NPCComposition;
import org.junit.Test;

/**
 * The cooperative state machine behind {@code ::foegallery}, driven with plain lambdas where the client's calls go:
 * which definitions are looked at, which models are asked for and how often, what is rendered, what is reported.
 * The client's own behaviour (a model that is not loaded yet, the thread rules) is stood in for here and has to be
 * checked in game: see FoeGalleryPlugin.
 */
public class PortraitGalleryRunTest
{
	/** A budget no test run hits: the fake clock below never moves. */
	private static final long FOREVER = Long.MAX_VALUE / 4;
	private static final LongSupplier STILL = () -> 0L;
	private static final Portrait.Mesh UPRIGHT = box(80, 300, 90, false);
	private static final Portrait.Mesh SIDE = box(60, 100, 400, false);
	private static final Portrait.Mesh HEAD = box(100, 100, 20, true);
	private static final Portrait.Mesh HIDDEN = new Portrait.Mesh(new float[]{0, 1, 2}, new float[3], new float[3],
		new int[]{0}, new int[]{1}, new int[]{2}, new int[]{0}, new int[]{-2}, null, 1, false);

	/** Runs everything inline, on the calling thread. */
	private static final Executor DIRECT = Runnable::run;

	private final AtomicReference<PortraitGalleryRun.Result> result = new AtomicReference<>();
	private final AtomicInteger doneCalls = new AtomicInteger();
	private final List<String> log = new ArrayList<>();

	private PortraitGalleryRun run(int[] ids, IntFunction<NPCComposition> defs, IntFunction<Portrait.Mesh> meshes,
		String filter, Executor worker, LongSupplier clock)
	{
		return new PortraitGalleryRun(ids, defs, meshes, filter, worker, r ->
		{
			doneCalls.incrementAndGet();
			result.set(r);
		}, log::add, clock);
	}

	private static int[] range(int n)
	{
		int[] ids = new int[n];
		for (int i = 0; i < n; i++)
		{
			ids[i] = i;
		}
		return ids;
	}

	/** Steps until the run says it is finished; returns how many steps that took. Fails on a run that never ends. */
	private static int finish(PortraitGalleryRun r)
	{
		for (int steps = 1; steps < 100_000; steps++)
		{
			if (r.step(FOREVER))
			{
				return steps;
			}
		}
		throw new AssertionError("the run never finished: " + r.progress());
	}

	/** One monster per id, each its own shape (body model = id), unless the test says otherwise. */
	private static IntFunction<NPCComposition> monsters()
	{
		return id -> fake(id, "Mob " + id, 10, null, null, new int[]{id + 1000});
	}

	// ---- the whole run ----

	@Test
	public void aRunKeepsMonstersCollapsesShapesLoadsOneModelPerShapeAndSaysWhatItLeftOut()
	{
		IntFunction<NPCComposition> defs = id ->
		{
			switch (id)
			{
				case 0:
					return fake(0, "null", 50, null, null, new int[]{1});
				case 1:
					return fake(1, "Banker", 0, null, null, new int[]{1});
				case 2:
					return fake(2, "Zulrah", 100, new int[]{3, 4}, null, new int[]{1});
				case 3:
				case 4:
					return fake(id, "Goblin", 5, null, null, new int[]{70});
				case 5:
					return fake(5, "Hobgoblin", 9, null, null, new int[]{70});
				case 6:
					return fake(6, "Giant rat", 3, null, null, new int[]{80});
				case 7:
					return fake(7, "Ghost", 40, null, null, null);
				case 8:
					throw new IllegalStateException("no such file");
				default:
					return fake(9, "Cow", 2, null, new int[]{90}, new int[]{91});
			}
		};
		Set<Integer> asked = new HashSet<>();
		IntFunction<Portrait.Mesh> meshes = id ->
		{
			asked.add(id);
			return id == 3 ? UPRIGHT : id == 6 ? SIDE : id == 9 ? HEAD : null;
		};
		PortraitGalleryRun r = run(range(10), defs, meshes, "", DIRECT, STILL);
		assertTrue(r.progress(), r.progress().contains("collecting"));

		finish(r);

		PortraitGalleryRun.Result res = result.get();
		assertNotNull(res);
		assertEquals("asked for the lowest id of each shape only", new HashSet<>(Arrays.asList(3, 6, 9)), asked);
		PortraitGalleryRun.Stats s = res.stats;
		assertEquals(10, s.listed);
		assertEquals(1, s.definitionErrors);
		assertEquals(1, s.unnamed);
		assertEquals(1, s.noCombatLevel);
		assertEquals(1, s.transformParents);
		assertEquals("goblin x2, hobgoblin, rat, ghost, cow", 6, s.kept);
		assertEquals(1, s.noModelIds);
		assertEquals(3, s.shapes);
		assertEquals(3, s.matched);
		assertEquals(3, res.entries.size());
		assertEquals(3, s.rendered);
		Map<Integer, PortraitGallery.Entry> byId = new HashMap<>();
		for (PortraitGallery.Entry e : res.entries)
		{
			byId.put(e.group.id, e);
			assertNotNull("rendered at the size Foe draws", e.image);
			assertEquals(PortraitGallery.RENDER, e.image.getWidth());
		}
		assertEquals(Portrait.View.UPRIGHT, byId.get(3).view);
		assertEquals(Arrays.asList(3, 4, 5), byId.get(3).group.ids);
		assertEquals(Arrays.asList("Goblin", "Hobgoblin"), byId.get(3).group.names);
		assertEquals(Portrait.View.SIDE, byId.get(6).view);
		assertEquals(400, byId.get(6).shape.length);
		assertEquals(Portrait.View.CHATHEAD, byId.get(9).view);
		assertEquals("the only one left out was a definition with no model at all", 1, res.missing.size());
		assertEquals(7, res.missing.get(0).group.id);
		assertEquals(PortraitGallery.Why.NO_MODEL_IDS, res.missing.get(0).why);
		assertTrue(r.progress(), r.progress().contains("done"));
	}

	@Test
	public void aRunLogsItsProgress()
	{
		PortraitGalleryRun r = run(range(5), monsters(), id -> UPRIGHT, "", DIRECT, STILL);
		finish(r);
		assertTrue(log.toString(), log.size() >= 3);
		assertTrue(log.toString(), log.stream().anyMatch(l -> l.contains("pass 1")));
		assertTrue(log.toString(), log.stream().anyMatch(l -> l.contains("5 definitions")));
	}

	@Test
	public void nothingToListIsAnEmptyResultNotAnError()
	{
		finish(run(new int[0], monsters(), id -> UPRIGHT, "", DIRECT, STILL));
		assertEquals(0, result.get().stats.listed);
		assertEquals(0, result.get().entries.size());
		assertEquals(1, doneCalls.get());
	}

	@Test
	public void aDefinitionThatComesBackNullIsCountedAsAFailureNotACrash()
	{
		finish(run(range(2), id -> null, id -> UPRIGHT, "", DIRECT, STILL));
		assertEquals(2, result.get().stats.definitionErrors);
	}

	// ---- models that are not loaded yet ----

	@Test
	public void aModelThatIsNotLoadedYetIsAskedForAgainAfterAPause()
	{
		AtomicInteger asks = new AtomicInteger();
		PortraitGalleryRun r = run(range(1), monsters(), id -> asks.incrementAndGet() < 3 ? null : UPRIGHT, "", DIRECT, STILL);
		int steps = finish(r);
		assertEquals(3, asks.get());
		assertEquals(1, result.get().stats.rendered);
		assertEquals(3, result.get().stats.passes);
		assertTrue("a pause between the passes, steps=" + steps, steps >= 2 * PortraitGalleryRun.WAIT_STEPS);
		assertTrue(result.get().missing.isEmpty());
	}

	@Test
	public void aModelThatNeverLoadsIsNoModelAfterAFewPassesAndTheRunStillEnds()
	{
		AtomicInteger asks = new AtomicInteger();
		PortraitGalleryRun r = run(range(1), monsters(), id ->
		{
			asks.incrementAndGet();
			return null;
		}, "", DIRECT, STILL);
		int steps = finish(r);
		assertEquals("given up after STALL_PASSES passes with nothing new", PortraitGalleryRun.STALL_PASSES, asks.get());
		assertTrue("and not waited for forever, steps=" + steps,
			steps <= (PortraitGalleryRun.STALL_PASSES + 1) * (PortraitGalleryRun.WAIT_STEPS + 3));
		assertEquals(1, result.get().stats.notLoaded);
		assertEquals(PortraitGallery.Why.NOT_LOADED, result.get().missing.get(0).why);
		assertEquals(0, result.get().stats.rendered);
	}

	@Test
	public void aPassThatLoadsSomethingNewKeepsTheRetriesGoingForTheRest()
	{
		// shape i loads on its (i+1)th ask: the last needs more asks than STALL_PASSES, which only progress allows
		int n = PortraitGalleryRun.STALL_PASSES + 3;
		Map<Integer, Integer> asks = new HashMap<>();
		PortraitGalleryRun r = run(range(n), monsters(), id -> asks.merge(id, 1, Integer::sum) > id ? UPRIGHT : null,
			"", DIRECT, STILL);
		finish(r);
		assertEquals(n, result.get().stats.rendered);
		assertEquals(0, result.get().stats.notLoaded);
		assertEquals(Integer.valueOf(n), asks.get(n - 1));
	}

	// ---- one bad model does not stop the rest ----

	@Test
	public void aModelThatThrowsOrCannotBeDrawnIsReportedAndTheRestStillRender()
	{
		IntFunction<Portrait.Mesh> meshes = id ->
		{
			switch (id)
			{
				case 0:
					throw new IllegalStateException("bad model");
				case 1:
					throw new NoSuchMethodError("the client API changed");
				case 2:
					return HIDDEN;
				default:
					return UPRIGHT;
			}
		};
		finish(run(range(4), monsters(), meshes, "", DIRECT, STILL));
		PortraitGalleryRun.Stats s = result.get().stats;
		assertEquals(1, s.rendered);
		assertEquals(2, s.errors);
		assertEquals(1, s.notDrawable);
		assertEquals(3, result.get().missing.size());
		assertEquals(PortraitGallery.Why.NOT_DRAWABLE, why(result.get(), 2));
		assertEquals(PortraitGallery.Why.ERROR, why(result.get(), 0));
		assertEquals(PortraitGallery.Why.ERROR, why(result.get(), 1));
	}

	private static PortraitGallery.Why why(PortraitGalleryRun.Result r, int id)
	{
		for (PortraitGallery.Missing m : r.missing)
		{
			if (m.group.id == id)
			{
				return m.why;
			}
		}
		return null;
	}

	@Test
	public void aWorkerThatRefusesARenderMakesThatShapeAnErrorAndNothingMore()
	{
		AtomicInteger refuse = new AtomicInteger(2);
		Executor flaky = task ->
		{
			if (refuse.getAndDecrement() > 0)
			{
				throw new RejectedExecutionException("shut down");
			}
			task.run();
		};
		finish(run(range(3), monsters(), id -> UPRIGHT, "", flaky, STILL));
		assertEquals(1, result.get().stats.rendered);
		assertEquals(2, result.get().stats.errors);
	}

	// ---- the name filter ----

	@Test
	public void aFilterRendersOnlyTheShapesWithAMatchingNameButCountsAllOfThem()
	{
		IntFunction<NPCComposition> defs = id ->
		{
			String[] names = {"Giant rat", "Goblin", "Rat king"};
			return fake(id, names[id], 10, null, null, new int[]{id + 1000});
		};
		Set<Integer> asked = new HashSet<>();
		finish(run(range(3), defs, id ->
		{
			asked.add(id);
			return SIDE;
		}, "RAT", DIRECT, STILL));
		assertEquals(new HashSet<>(Arrays.asList(0, 2)), asked);
		assertEquals(3, result.get().stats.shapes);
		assertEquals(2, result.get().stats.matched);
		assertEquals(2, result.get().stats.rendered);
	}

	// ---- not freezing the client ----

	@Test
	public void aStepStopsAtItsTimeBudgetAndTheRunTakesSeveralSteps()
	{
		AtomicLong now = new AtomicLong();
		LongSupplier clock = () -> now.addAndGet(1_000_000); // every look at the clock costs 1 ms
		AtomicInteger lookups = new AtomicInteger();
		IntFunction<NPCComposition> defs = id ->
		{
			lookups.incrementAndGet();
			return monsters().apply(id);
		};
		PortraitGalleryRun r = run(range(100), defs, id -> UPRIGHT, "", DIRECT, clock);
		assertFalse(r.step(5_000_000));
		assertTrue("some work", lookups.get() > 0);
		assertTrue("not all of it: " + lookups.get(), lookups.get() < 20);
		int steps = 1;
		while (!r.step(5_000_000))
		{
			assertTrue("ends", ++steps < 100_000);
		}
		assertEquals(100, lookups.get());
		assertEquals(100, result.get().stats.rendered);
		assertTrue("took many steps: " + steps, steps > 10);
	}

	@Test
	public void rendersQueuedOnTheWorkerAreCappedSoLoadedModelsDoNotPileUpInMemory()
	{
		Deque<Runnable> queue = new ArrayDeque<>();
		AtomicInteger loads = new AtomicInteger();
		PortraitGalleryRun r = run(range(40), monsters(), id ->
		{
			loads.incrementAndGet();
			return UPRIGHT;
		}, "", queue::add, STILL);
		// a worker that has not run anything yet: the run must stop loading, not keep going
		for (int i = 0; i < 300; i++)
		{
			assertFalse(r.step(FOREVER));
		}
		assertEquals(PortraitGalleryRun.MAX_IN_FLIGHT, loads.get());
		assertEquals(PortraitGalleryRun.MAX_IN_FLIGHT, queue.size());
		// now the worker catches up, one task per step
		int steps = 0;
		boolean over = false;
		while (!over)
		{
			Runnable task = queue.poll();
			if (task != null)
			{
				task.run();
			}
			over = r.step(FOREVER);
			assertTrue("ends", ++steps < 100_000);
			assertTrue("never more than the cap waiting", queue.size() <= PortraitGalleryRun.MAX_IN_FLIGHT + 1);
		}
		while (!queue.isEmpty())
		{
			queue.poll().run(); // the hand-off of the result goes through the worker too
		}
		assertEquals(40, loads.get());
		assertNotNull(result.get());
		assertEquals(40, result.get().stats.rendered);
	}

	// ---- stopping, and who runs what ----

	@Test
	public void cancellingStopsTheWorkAndDeliversNothing()
	{
		AtomicInteger lookups = new AtomicInteger();
		AtomicLong now = new AtomicLong();
		PortraitGalleryRun r = run(range(100), id ->
		{
			lookups.incrementAndGet();
			return monsters().apply(id);
		}, id -> UPRIGHT, "", DIRECT, () -> now.addAndGet(1_000_000));
		assertFalse(r.step(5_000_000));
		int before = lookups.get();
		r.cancel();
		assertTrue("a cancelled run reports finished", r.step(5_000_000));
		assertEquals("and does no more work", before, lookups.get());
		assertNull(result.get());
		assertEquals(0, doneCalls.get());
	}

	@Test
	public void aResultWaitingOnTheWorkerIsDroppedIfTheRunIsCancelledBeforeItRuns()
	{
		Deque<Runnable> queue = new ArrayDeque<>();
		PortraitGalleryRun r = run(range(2), monsters(), id -> UPRIGHT, "", queue::add, STILL);
		int steps = 0;
		while (!r.step(FOREVER))
		{
			Runnable task = queue.poll();
			if (task != null)
			{
				task.run();
			}
			assertTrue("ends", ++steps < 1000);
		}
		assertEquals("the delivery is queued, not yet run", 1, queue.size());
		r.cancel();
		while (!queue.isEmpty())
		{
			queue.poll().run();
		}
		assertNull(result.get());
		assertEquals(0, doneCalls.get());
	}

	@Test
	public void theResultIsHandedOverOnTheWorkerNotTheCallingThread()
	{
		ThreadLocal<Boolean> onWorker = ThreadLocal.withInitial(() -> false);
		Executor worker = task ->
		{
			onWorker.set(true);
			try
			{
				task.run();
			}
			finally
			{
				onWorker.set(false);
			}
		};
		AtomicReference<Boolean> deliveredOnWorker = new AtomicReference<>();
		PortraitGalleryRun r = new PortraitGalleryRun(range(2), monsters(), id -> UPRIGHT, "", worker,
			res -> deliveredOnWorker.set(onWorker.get()), log::add, STILL);
		finish(r);
		assertEquals(Boolean.TRUE, deliveredOnWorker.get());
	}

	@Test
	public void aFinishedRunDeliversOnceHoweverOftenItIsSteppedAgain()
	{
		PortraitGalleryRun r = run(range(2), monsters(), id -> UPRIGHT, "", DIRECT, STILL);
		finish(r);
		assertTrue(r.step(FOREVER));
		assertTrue(r.step(FOREVER));
		assertEquals(1, doneCalls.get());
		assertTrue(r.progress(), r.progress().startsWith("done"));
	}
}
