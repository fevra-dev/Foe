package com.foe;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;
import net.runelite.api.NPCComposition;

/**
 * One {@code ::foegallery} run as a state machine that is driven in short steps (test source set; never ships).
 * Each {@link #step} does what fits in a time budget and returns, so the client thread is never held for long; the
 * caller (PortraitGalleryPlugin) calls it once per client tick. The client's own calls are passed in as functions, so
 * the machine is tested with plain lambdas.
 *
 * <p>Phases: <b>collect</b> every definition once (skip the ones Foe would not show, key the rest by shape);
 * <b>load</b> one mesh per shape that matches the filter, in passes: a model that is not loaded yet comes back null,
 * so those shapes wait {@link #WAIT_STEPS} steps and are asked again, and a pass that loads nothing new counts toward
 * giving up ({@link #STALL_PASSES}); <b>render</b> on the worker (at most {@link #MAX_IN_FLIGHT} queued at once, so
 * loaded meshes cannot pile up in memory); <b>deliver</b> the {@link Result} on the worker, once.
 *
 * <p>Threads: {@link #step}, {@link #progress} and {@link #cancel} are for one caller thread (the client thread); the
 * worker only runs the render tasks and the delivery.
 */
final class PortraitGalleryRun
{
	/** Renders submitted to the worker and not finished: the load phase waits when this many are. */
	static final int MAX_IN_FLIGHT = 16;
	/** Steps between two load passes. A step is a client tick, 20 ms, so this is two seconds. */
	static final int WAIT_STEPS = 100;
	/** Load passes in a row that load nothing new before the shapes still waiting count as "no model". */
	static final int STALL_PASSES = 3;
	/** A progress line goes to the log about every this many shapes. */
	private static final int LOG_EVERY = 500;

	/** What a run counted. Written when the run finishes; read on the worker. */
	static final class Stats
	{
		/** Definition ids looked at. */
		int listed;
		/** Lookups that threw or returned null. */
		int definitionErrors;
		int unnamed;
		int noCombatLevel;
		int transformParents;
		/** Monsters Foe could show. */
		int kept;
		/** Of those, with no model at all. */
		int noModelIds;
		/** Distinct shapes among the rest, and how many match the filter. */
		int shapes;
		int matched;
		int rendered;
		int notLoaded;
		int notDrawable;
		int errors;
		int passes;
	}

	static final class Result
	{
		final String filter;
		final Stats stats;
		/** In no particular order: the sheets and the index sort them. */
		final List<PortraitGallery.Entry> entries;
		final List<PortraitGallery.Missing> missing;

		Result(String filter, Stats stats, List<PortraitGallery.Entry> entries, List<PortraitGallery.Missing> missing)
		{
			this.filter = filter;
			this.stats = stats;
			this.entries = entries;
			this.missing = missing;
		}
	}

	private enum Phase
	{
		COLLECT, LOAD, DRAIN, DONE
	}

	private final int[] ids;
	private final IntFunction<NPCComposition> definitions;
	private final IntFunction<Portrait.Mesh> meshes;
	private final String filter;
	private final Executor worker;
	private final Consumer<Result> done;
	private final Consumer<String> log;
	private final LongSupplier clock;

	private Phase phase = Phase.COLLECT;
	private volatile boolean cancelled;
	private volatile String progress = "collecting";

	// collect
	private int next;
	private final Stats stats = new Stats();
	private final List<PortraitGallery.Candidate> candidates = new ArrayList<>();
	private final List<PortraitGallery.Missing> missing = new ArrayList<>();

	// load: the shapes to draw, the ones to ask for in this pass, and the ones that will be asked again
	private List<PortraitGallery.Group> pending = new ArrayList<>();
	private int cursor;
	private List<PortraitGallery.Group> retry = new ArrayList<>();
	private int loadedThisPass;
	private int stalledPasses;
	private int waitLeft;
	/** True from reaching a pass's end until the next pass starts: the pause is being waited out. */
	private boolean passEnded;
	private int totalToDraw;
	private int resolved;

	// render: written on the worker, read on the caller once inFlight is back to 0. One lock for both lists.
	private final AtomicInteger inFlight = new AtomicInteger();
	private final Object lock = new Object();
	private final List<PortraitGallery.Entry> entries = new ArrayList<>();

	PortraitGalleryRun(int[] ids, IntFunction<NPCComposition> definitions, IntFunction<Portrait.Mesh> meshes,
		String filter, Executor worker, Consumer<Result> done, Consumer<String> log, LongSupplier clock)
	{
		this.ids = ids;
		this.definitions = definitions;
		this.meshes = meshes;
		this.filter = filter == null ? "" : filter.trim();
		this.worker = worker;
		this.done = done;
		this.log = log;
		this.clock = clock;
	}

	/** What the run is doing, in a line. */
	String progress()
	{
		return progress;
	}

	/** Stops the work and drops the result; the next {@link #step} reports finished. */
	void cancel()
	{
		cancelled = true;
		progress = "cancelled";
	}

	/**
	 * Does work until the budget (nanoseconds of the clock) is spent, at least one unit of it, and returns true when
	 * the run is over: finished (the result is handed to the worker) or cancelled.
	 */
	boolean step(long budgetNanos)
	{
		if (cancelled)
		{
			return true;
		}
		if (phase == Phase.DONE)
		{
			return true;
		}
		long deadline = clock.getAsLong() + budgetNanos;
		do
		{
			if (!unit())
			{
				return phase == Phase.DONE;
			}
			if (cancelled)
			{
				return true;
			}
		}
		while (phase != Phase.DONE && clock.getAsLong() < deadline);
		return phase == Phase.DONE;
	}

	/** One piece of work. False when there is nothing to do until a later step (waiting), so the step ends. */
	private boolean unit()
	{
		switch (phase)
		{
			case COLLECT:
				collectOne();
				return true;
			case LOAD:
				return loadOne();
			case DRAIN:
				return drain();
			default:
				return false;
		}
	}

	// ---- collect ----

	private void collectOne()
	{
		if (next >= ids.length)
		{
			endCollect();
			return;
		}
		int id = ids[next++];
		stats.listed++;
		NPCComposition c;
		try
		{
			c = definitions.apply(id);
		}
		catch (RuntimeException | LinkageError e)
		{
			// past the cache's last NPC the client throws; LinkageError: the client API changed under the tool
			c = null;
		}
		if (c == null)
		{
			stats.definitionErrors++;
		}
		else
		{
			classify(c);
		}
		progress = "collecting: " + next + " of " + ids.length + " definitions";
		if (next >= ids.length)
		{
			endCollect();
		}
	}

	private void classify(NPCComposition c)
	{
		PortraitGallery.Skip skip = PortraitGallery.skipReason(c);
		if (skip == PortraitGallery.Skip.UNNAMED)
		{
			stats.unnamed++;
		}
		else if (skip == PortraitGallery.Skip.NO_COMBAT_LEVEL)
		{
			stats.noCombatLevel++;
		}
		else if (skip == PortraitGallery.Skip.TRANSFORM_PARENT)
		{
			stats.transformParents++;
		}
		else
		{
			PortraitGallery.Candidate cand = PortraitGallery.candidate(c);
			stats.kept++;
			if (cand.key == null)
			{
				stats.noModelIds++;
				missing.add(new PortraitGallery.Missing(new PortraitGallery.Group(null,
					Collections.singletonList(cand.id), Collections.singletonList(cand.name)),
					PortraitGallery.Why.NO_MODEL_IDS));
			}
			else
			{
				candidates.add(cand);
			}
		}
	}

	private void endCollect()
	{
		List<PortraitGallery.Group> groups = PortraitGallery.group(candidates);
		stats.shapes = groups.size();
		pending = PortraitGallery.matching(groups, filter);
		stats.matched = pending.size();
		totalToDraw = pending.size();
		candidates.clear();
		log.accept("gallery: looked at " + stats.listed + " definitions: " + stats.kept + " monsters, "
			+ stats.shapes + " distinct shapes" + (filter.isEmpty() ? "" : ", " + stats.matched + " match \""
			+ filter + "\"") + "; skipped " + stats.unnamed + " unnamed, " + stats.noCombatLevel
			+ " without a combat level, " + stats.transformParents + " that transform, " + stats.definitionErrors
			+ " that could not be read");
		phase = Phase.LOAD;
		startPass();
	}

	// ---- load ----

	private void startPass()
	{
		stats.passes++;
		cursor = 0;
		loadedThisPass = 0;
		retry = new ArrayList<>();
		if (!pending.isEmpty())
		{
			log.accept("gallery: pass " + stats.passes + ": asking for " + pending.size() + " shapes' models");
		}
	}

	private boolean loadOne()
	{
		if (cursor >= pending.size())
		{
			return endPass();
		}
		if (inFlight.get() >= MAX_IN_FLIGHT)
		{
			progress = progressLine();
			return false;
		}
		PortraitGallery.Group g = pending.get(cursor++);
		Portrait.Mesh mesh;
		try
		{
			mesh = meshes.apply(g.id);
		}
		catch (RuntimeException | LinkageError e)
		{
			resolve(g, PortraitGallery.Why.ERROR);
			return true;
		}
		if (mesh == null)
		{
			retry.add(g); // not loaded yet, or none: the API cannot tell
			progress = progressLine();
			return true;
		}
		loadedThisPass++;
		resolved++;
		submit(g, mesh);
		progress = progressLine();
		if (resolved % LOG_EVERY == 0)
		{
			log.accept("gallery: " + progressLine());
		}
		return true;
	}

	private void resolve(PortraitGallery.Group g, PortraitGallery.Why why)
	{
		loadedThisPass++; // a verdict is progress too
		resolved++;
		synchronized (lock)
		{
			missing.add(new PortraitGallery.Missing(g, why));
		}
	}

	/** The pass over the pending shapes is finished: wait and ask the rest again, or give up on them, or move on. */
	private boolean endPass()
	{
		if (retry.isEmpty())
		{
			phase = Phase.DRAIN;
			return true;
		}
		if (!passEnded)
		{
			// first arrival at this pass's end: did it load anything new? Then start the pause.
			passEnded = true;
			stalledPasses = loadedThisPass > 0 ? 0 : stalledPasses + 1;
			if (stalledPasses >= STALL_PASSES)
			{
				synchronized (lock)
				{
					for (PortraitGallery.Group g : retry)
					{
						missing.add(new PortraitGallery.Missing(g, PortraitGallery.Why.NOT_LOADED));
					}
				}
				log.accept("gallery: " + retry.size() + " shapes never loaded their models; counted as no model");
				retry = new ArrayList<>();
				phase = Phase.DRAIN;
				return true;
			}
			waitLeft = WAIT_STEPS;
			return false;
		}
		if (waitLeft > 0)
		{
			waitLeft--;
			progress = progressLine() + " (waiting for models to load)";
			return false;
		}
		// the pause is over: the shapes that did not load are the next pass's work
		passEnded = false;
		pending = retry;
		startPass();
		return true;
	}

	private void submit(PortraitGallery.Group g, Portrait.Mesh mesh)
	{
		inFlight.incrementAndGet();
		try
		{
			worker.execute(() -> render(g, mesh));
		}
		catch (RuntimeException e) // the worker refused it (shut down)
		{
			inFlight.decrementAndGet();
			synchronized (lock)
			{
				missing.add(new PortraitGallery.Missing(g, PortraitGallery.Why.ERROR));
			}
		}
	}

	/** On the worker: the mesh's view and extents, and the portrait. */
	private void render(PortraitGallery.Group g, Portrait.Mesh mesh)
	{
		try
		{
			PortraitGallery.Shape shape = PortraitGallery.parseDescribe(Portrait.describe(mesh));
			BufferedImage image = shape == null ? null : Portrait.render(mesh, PortraitGallery.RENDER);
			synchronized (lock)
			{
				if (image == null)
				{
					missing.add(new PortraitGallery.Missing(g, PortraitGallery.Why.NOT_DRAWABLE));
				}
				else
				{
					entries.add(new PortraitGallery.Entry(g, shape, image));
				}
			}
		}
		catch (RuntimeException | LinkageError e)
		{
			synchronized (lock)
			{
				missing.add(new PortraitGallery.Missing(g, PortraitGallery.Why.ERROR));
			}
		}
		finally
		{
			inFlight.decrementAndGet();
		}
	}

	// ---- finish ----

	private boolean drain()
	{
		if (inFlight.get() > 0)
		{
			progress = progressLine() + " (drawing)";
			return false;
		}
		Result result = result();
		phase = Phase.DONE;
		progress = "done: " + result.entries.size() + " drawn, " + result.missing.size() + " not";
		log.accept("gallery: " + progress);
		try
		{
			worker.execute(() -> deliver(result));
		}
		catch (RuntimeException e)
		{
			log.accept("gallery: the worker is gone; handing the result over on this thread");
			deliver(result);
		}
		return true;
	}

	private void deliver(Result result)
	{
		if (!cancelled)
		{
			done.accept(result);
		}
	}

	private Result result()
	{
		List<PortraitGallery.Entry> drawn;
		List<PortraitGallery.Missing> notDrawn;
		synchronized (lock)
		{
			drawn = new ArrayList<>(entries);
			notDrawn = new ArrayList<>(missing);
		}
		stats.rendered = drawn.size();
		for (PortraitGallery.Missing m : notDrawn)
		{
			switch (m.why)
			{
				case NOT_LOADED:
					stats.notLoaded++;
					break;
				case NOT_DRAWABLE:
					stats.notDrawable++;
					break;
				case ERROR:
					stats.errors++;
					break;
				default:
					break; // NO_MODEL_IDS is counted when collected
			}
		}
		return new Result(filter, stats, drawn, notDrawn);
	}

	private String progressLine()
	{
		return "pass " + stats.passes + ": " + resolved + " of " + totalToDraw + " shapes loaded";
	}
}
