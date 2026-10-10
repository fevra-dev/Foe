package com.foe;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Portraits by composition id (spec addenda 14 and 15). The first ask loads the model on the caller's thread (the
 * client thread: the load reads the client's cache) and renders it on the executor; later asks get the image once it
 * is done. Up to {@link #CAPACITY} ids are kept, least recently used dropped first. A load that returns null may be a
 * model still loading (the API cannot tell that from none), so it is asked again on later asks, up to
 * {@link #MAX_MISSES} times. Never throws: a model that cannot be loaded or rendered is no portrait, and is not
 * retried while its id is held.
 *
 * <p>Not thread-safe: {@link #get} is for the client thread only. The executor only completes futures.
 */
@Slf4j
final class PortraitCache
{
	static final int CAPACITY = 64;
	/** Asks a null load gets before the id counts as having no model: ten ticks, six seconds of the target shown. */
	static final int MAX_MISSES = 10;
	/** Rendered at twice a typical panel height, so it scales down, not up, on a high-DPI screen. */
	static final int SIZE = 64;

	private final Executor executor;
	private final Map<Integer, CompletableFuture<BufferedImage>> byId = lru();
	/** Null loads so far, per id not yet in {@link #byId}. */
	private final Map<Integer, Integer> misses = lru();

	PortraitCache(Executor executor)
	{
		this.executor = executor;
	}

	/** The portrait for this id, or null while it renders, or when there is none. */
	BufferedImage get(int id, Supplier<Portrait.Mesh> load)
	{
		CompletableFuture<BufferedImage> f = byId.get(id);
		if (f == null)
		{
			f = start(id, load);
			if (f == null)
			{
				return null; // not loaded yet: ask again next time
			}
			byId.put(id, f);
		}
		return f.isDone() && !f.isCompletedExceptionally() ? f.getNow(null) : null;
	}

	/** Null to ask again later; else the render, or a done null for no portrait. */

	private CompletableFuture<BufferedImage> start(int id, Supplier<Portrait.Mesh> load)
	{
		Portrait.Mesh mesh;
		try
		{
			mesh = load.get();
		}
		catch (RuntimeException | LinkageError e)
		{
			// LinkageError: the client API changed under the plugin (review finding 3). Other Errors propagate.
			log.debug("portrait: id {} could not be loaded", id, e);
			return CompletableFuture.completedFuture(null);
		}
		if (mesh == null)
		{
			if (misses.merge(id, 1, Integer::sum) < MAX_MISSES)
			{
				return null;
			}
			misses.remove(id);
			return CompletableFuture.completedFuture(null);
		}
		misses.remove(id);
		try
		{
			return CompletableFuture.supplyAsync(() -> render(id, mesh), executor);
		}
		catch (RuntimeException e)
		{
			log.debug("portrait: id {} could not be queued", id, e);
			return CompletableFuture.completedFuture(null);
		}
	}

	private static BufferedImage render(int id, Portrait.Mesh mesh)
	{
		try
		{
			return Portrait.render(mesh, SIZE);
		}
		catch (RuntimeException | LinkageError e)
		{
			log.debug("portrait: id {} could not be rendered", id, e);
			return null;
		}
	}

	private static <V> Map<Integer, V> lru()
	{
		return new LinkedHashMap<Integer, V>(16, 0.75f, true)
		{
			@Override
			protected boolean removeEldestEntry(Map.Entry<Integer, V> eldest)
			{
				return size() > CAPACITY;
			}
		};
	}
}
