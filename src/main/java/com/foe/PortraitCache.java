package com.foe;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Portraits by composition id (spec addendum 14). The first ask loads the model on the caller's thread (the client
 * thread: the load reads the client's cache) and renders it on the executor; later asks get the image once it is
 * done. Up to {@link #CAPACITY} ids are kept, least recently used dropped first. Never throws: a model that cannot
 * be loaded or rendered is no portrait, and is not retried while its id is held.
 *
 * <p>Not thread-safe: {@link #get} is for the client thread only. The executor only completes futures.
 */
@Slf4j
final class PortraitCache
{
	static final int CAPACITY = 64;
	/** Rendered at twice a typical panel height, so it scales down, not up, on a high-DPI screen. */
	static final int SIZE = 64;

	private final Executor executor;
	private final Map<Integer, CompletableFuture<BufferedImage>> byId =
		new LinkedHashMap<Integer, CompletableFuture<BufferedImage>>(16, 0.75f, true)
		{
			@Override
			protected boolean removeEldestEntry(Map.Entry<Integer, CompletableFuture<BufferedImage>> eldest)
			{
				return size() > CAPACITY;
			}
		};

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
			byId.put(id, f);
		}
		return f.isDone() && !f.isCompletedExceptionally() ? f.getNow(null) : null;
	}

	private CompletableFuture<BufferedImage> start(int id, Supplier<Portrait.Mesh> load)
	{
		Portrait.Mesh mesh;
		try
		{
			mesh = load.get();
		}
		catch (RuntimeException e)
		{
			log.debug("portrait: id {} could not be loaded", id, e);
			return CompletableFuture.completedFuture(null);
		}
		if (mesh == null)
		{
			return CompletableFuture.completedFuture(null);
		}
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
		catch (RuntimeException e)
		{
			log.debug("portrait: id {} could not be rendered", id, e);
			return null;
		}
	}
}
