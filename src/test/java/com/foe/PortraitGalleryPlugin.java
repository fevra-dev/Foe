package com.foe;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.NPCComposition;
import net.runelite.api.events.CommandExecuted;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dev only, and test source set only, so it can never ship: the Plugin Hub builds {@code src/main}, and this needs
 * {@link PortraitModels} and {@link Portrait}, which are package-private. {@code ::foegallery [name filter]} draws a
 * portrait of every distinct monster shape the way Foe does and writes contact sheets, by view, for review offline.
 * This class is the thin client-facing half; the decisions are in {@link PortraitGallery} and
 * {@link PortraitGalleryRun}, the pixels and files in {@link PortraitGalleryFiles}, and those are unit tested.
 *
 * <p>Threads: the chat command arrives on the client thread (ChatInputManager posts it from a script callback);
 * {@link PortraitGalleryRun#step} runs there too, once per client tick through {@link ClientThread#invokeLater}, for a
 * few milliseconds at a time; the renders and the file writing run on this plugin's own single worker thread, not
 * RuneLite's shared executor, which a minute of rendering would hold up for every other plugin.
 *
 * <p>Registered with Foe in {@link FoePluginTest}; {@code developerPlugin} means RuneLite loads it in developer mode
 * only, which is how {@code ./gradlew run} starts it.
 */
@PluginDescriptor(
	name = "Foe Gallery (dev)",
	description = "Dev only: ::foegallery [name] draws a contact sheet of every monster shape's portrait",
	tags = {"foe", "portrait", "dev"},
	developerPlugin = true
)
public class PortraitGalleryPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(PortraitGalleryPlugin.class);

	/**
	 * The NPC definitions' archive in the config index. Read off the client's bytecode (its NPC loader takes group 9
	 * of the index {@code getIndexConfig()} returns), and the same number as runelite-cache's ConfigType.NPC
	 * [documented, from memory]. If it is wrong, {@link PortraitGallery#npcIds} notices a list that is too short.
	 */
	static final int NPC_ARCHIVE = 9;
	/** The highest id in the API's own NpcID class in 1.13.1 [measured: 16576 constants, 0 to 16575]. */
	static final int FALLBACK_MAX_ID = 16575;
	/** What one client tick spends on the run. A tick is 20 ms; a model load and light is 0.5 to 5 ms [docs/probe]. */
	static final long BUDGET_NANOS = 6_000_000L;

	@Inject
	private Client client;
	@Inject
	private ClientThread clientThread;

	/** Renders and file writing. Created in startUp and ended in shutDown, on the Swing thread; read on the client's. */
	private volatile ExecutorService worker;
	/** The run in progress, if any. Client thread only. */
	private PortraitGalleryRun active;

	@Override
	protected void startUp()
	{
		worker = Executors.newSingleThreadExecutor(r ->
		{
			Thread t = new Thread(r, "foe-gallery");
			t.setDaemon(true);
			return t;
		});
	}

	@Override
	protected void shutDown()
	{
		clientThread.invokeLater(() ->
		{
			if (active != null)
			{
				active.cancel();
				active = null;
			}
		});
		if (worker != null)
		{
			worker.shutdownNow();
			worker = null;
		}
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		if (!PortraitGallery.isCommand(event.getCommand()))
		{
			return;
		}
		if (active != null)
		{
			chat("Foe gallery: a run is already going (" + active.progress() + "). Turn the plugin off and on to stop it.");
			return;
		}
		if (worker == null)
		{
			return; // between shutDown and startUp
		}
		String filter = PortraitGallery.filterOf(event.getArguments());
		int[] listed = null;
		try
		{
			listed = client.getIndexConfig().getFileIds(NPC_ARCHIVE);
		}
		catch (RuntimeException | LinkageError e)
		{
			log.warn("gallery: could not list the NPC archive; falling back to ids 0 to {}", FALLBACK_MAX_ID, e);
		}
		int[] ids = PortraitGallery.npcIds(listed, FALLBACK_MAX_ID);
		Path dir = outputDirectory(filter);
		log.info("gallery: {} definition ids ({}), filter \"{}\", writing to {}", ids.length,
			listed != null && ids.length == listed.length ? "the cache's own list" : "fallback range", filter, dir);
		PortraitGalleryRun run = new PortraitGalleryRun(ids, client::getNpcDefinition, this::loadMesh, filter, worker,
			result -> finished(result, dir), log::info, System::nanoTime);
		active = run;
		chat("Foe gallery: started" + (filter.isEmpty() ? "" : " for \"" + filter + "\"") + ". Progress is in the log.");
		BooleanSupplier tick = () -> tick(run);
		clientThread.invokeLater(tick);
	}

	/** One client tick of the run. True when it is over, so ClientThread drops it. Client thread. */
	private boolean tick(PortraitGalleryRun run)
	{
		if (active != run)
		{
			return true; // cancelled in shutDown
		}
		boolean over;
		try
		{
			over = run.step(BUDGET_NANOS);
		}
		catch (RuntimeException | LinkageError e)
		{
			log.error("gallery: the run stopped on an error", e);
			chat("Foe gallery: stopped by an error (" + e + "). See the log.");
			run.cancel();
			over = true;
		}
		if (over)
		{
			active = null;
		}
		return over;
	}

	/** The mesh Foe would draw for this definition id, or null while its models are not loaded. Client thread. */
	private Portrait.Mesh loadMesh(int id)
	{
		NPCComposition c = client.getNpcDefinition(id);
		return c == null ? null : PortraitModels.load(client, c);
	}

	/** On the worker: lay out the sheets, write them, and say so. */
	private void finished(PortraitGalleryRun.Result result, Path dir)
	{
		List<PortraitGallery.Sheet> sheets = PortraitGallery.layout(result.entries);
		try
		{
			PortraitGalleryFiles.write(dir, sheets, result.missing);
		}
		catch (IOException | RuntimeException e)
		{
			log.error("gallery: could not write to {}", dir, e);
			chat("Foe gallery: could not write the files to " + dir + " (" + e + ")");
			return;
		}
		log.info("gallery: wrote {} sheets and the indexes to {}", sheets.size(), dir);
		chat(PortraitGallery.summary(result.stats, sheets.size(), result.filter));
		chat("Foe gallery: files are in " + dir);
	}

	/** The repo's build folder when run from the repo, else ~/.runelite; a filtered run gets its own sub-folder. */
	static Path outputDirectory(String filter)
	{
		Path root = PortraitGallery.outputRoot(Paths.get(System.getProperty("user.dir")).toAbsolutePath(),
			Paths.get(System.getProperty("user.home")));
		String sub = PortraitGallery.directoryName(filter);
		return sub.isEmpty() ? root : root.resolve(sub);
	}

	/** To the chat box, from any thread: the game's chat is the client thread's. */
	private void chat(String message)
	{
		clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null));
	}
}
