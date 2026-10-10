package com.foe;

import com.google.inject.Provides;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.Client;
import net.runelite.api.Hitsplat;
import net.runelite.api.IterableHashTable;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.NPCManager;
import net.runelite.client.game.NpcUtil;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * Wiring only: reads facts off RuneLite's events and NPC objects, hands them to {@link TargetFeed},
 * {@link HpMemory} and {@link WeaknessLearner} (which hold every decision and are unit tested), and builds one
 * {@link TargetSnapshot} per GameTick. Event handlers run on the client thread; startUp/shutDown run on the Swing
 * thread (PluginManager). EventBus.post is not synchronized, so a client-thread handler already running can overlap
 * shutDown: the overlay is removed first, and startUp resets everything else again, so no fight state from a stopped
 * run survives a restart. What was learned does survive, on purpose, and a credit that lands late is safe: see
 * {@link WeaknessStore} (the saved value is only ever merged into) and {@link #begin} (the cache is reloaded on the
 * client thread). FoeOverlay reads the volatile snapshot when it renders.
 *
 * <p>Known limits (the first is a decision, the rest are the client's):
 * <ul>
 * <li>When several NPCs are hitting you and none is the live target, the client does not say whose hit landed, so
 *     the first NPC interacting with you is taken (see {@link TargetFeed#playerHurt}).
 * <li>Only NPCs in the local player's own world view are considered as hitters, so an NPC on another world view (a
 *     boat) that hits you is not adopted by that path.
 * <li>The remembered HP of a target is not time-limited; it is always drawn as stale (see {@link HpMemory}).
 * <li>Exact HP is the damage counted from the hitsplats Foe saw, shown only when the bar allows it (see
 *     {@link HpTracker}). The one thing it assumes and nothing here has measured: that the bar already shows a hit
 *     when the tick that carries its hitsplat is read. If the bar lagged by a tick the count would be rejected after
 *     the first hit and the midpoint shown, as before, until an exact reading.
 * <li>A weakness is learned only from a confirmed spell impact (see {@link WeaknessLearner}), so it is missing, never
 *     false, for a type whose spell left the varp unchanged, or whose impact could not be tied to one NPC. One
 *     coincidence is not caught: our write on a tick where the only fought NPC with a spell graphic got it from
 *     another player, while our own spell's target is not one we are fighting, is credited to that NPC. The trace has
 *     our write on the tick we engage the cast target, which makes that combination unlikely; the next confirmed
 *     cast corrects it.
 * </ul>
 */
@Slf4j
@PluginDescriptor(
	name = "Foe",
	description = "Live HP, combat levels and elemental weakness of the monster you're fighting. Replaces Opponent Info: turn that off to avoid seeing it twice",
	tags = {"target", "monster", "npc", "weakness", "opponent", "hp", "combat"}
)
public class FoePlugin extends Plugin
{
	@Inject
	private Client client;
	@Inject
	private FoeConfig config;
	@Inject
	private NPCManager npcManager;
	@Inject
	private OverlayManager overlayManager;
	@Inject
	private FoeOverlay overlay;
	@Inject
	private NpcUtil npcUtil;
	@Inject
	private ConfigManager configManager;
	/**
	 * RuneLite's shared executor (core plugins inject it the same way); portraits render on it, never on a tick. It
	 * is single-threaded (client 1.13.1 RuneLiteModule.java:128), so each new portrait, 5 to 14 ms in the spike,
	 * queues ahead of other plugins' work once.
	 */
	@Inject
	private java.util.concurrent.ScheduledExecutorService injectedExecutor;
	/** What the cache runs renders on: the injected executor, or a test's. Read at run time, so tests can set it. */
	private java.util.concurrent.Executor executor = r -> injectedExecutor.execute(r);
	/** Spec addendum 14. Client thread only. Kept across stop and start: it holds at most 64 small images. */
	private final PortraitCache portraits = new PortraitCache(r -> executor.execute(r));

	private final TargetFeed<NPC> feed = new TargetFeed<>(NPC::getIndex);
	private final HpMemory hpMemory = new HpMemory();
	private final HpTracker hpTracker = new HpTracker();
	/**
	 * What each monster type is weak to. Survives logout and hop, and plugin restarts: every credit is saved through
	 * {@link #weaknessStore}, and {@link #begin} has the first tick reload the cache from it.
	 */
	private final WeaknessLearner<NPC> weakness = new WeaknessLearner<>(this::saveWeakness);
	private final WeaknessStore weaknessStore = new WeaknessStore(this::storedWeaknesses, this::storeWeaknesses);
	/** Set when the plugin starts, taken by the first GameTick: the cache is loaded on the client thread. */
	private volatile boolean loadWeaknesses;
	/**
	 * The bundled wiki table (spec addendum 7), read once in {@link #begin} and never changed after: the learner combines
	 * it with what was learned, and the startup check compares the two. Empty when it could not be loaded.
	 */
	private volatile Map<Integer, Weakness> weaknessTable = Collections.emptyMap();
	/** NPC id and form id pairs already logged by noteForm (high 32 bits the NPC id). Reset with everything else. */
	private final java.util.Set<Long> formsSeen = new java.util.HashSet<>();
	/** Set when the plugin starts, taken by the first load with it: the learned-vs-table check runs once (addendum 11). */
	private volatile boolean checkWeaknesses;

	/** The one snapshot FoeOverlay draws. Written on the client thread, read on the render thread. */
	@Getter(AccessLevel.PACKAGE)
	private volatile TargetSnapshot snapshot;

	@Provides
	FoeConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FoeConfig.class);
	}

	@Override
	protected void startUp()
	{
		// A stored MARKER (removed in Task 8) makes every config read log a stack trace, and the overlay reads it every
		// frame (Task 8 review F2). Unset it once so the default applies quietly.
		if ("MARKER".equals(configManager.getConfiguration(FoeConfig.GROUP, "staleHpStyle")))
		{
			configManager.unsetConfiguration(FoeConfig.GROUP, "staleHpStyle");
		}
		begin();
		overlayManager.add(overlay);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		stop();
	}

	/** A RuneLite profile switch: the store is per profile, so reload it on the next tick, before any credit. */
	@Subscribe
	public void onProfileChanged(net.runelite.client.events.ProfileChanged e)
	{
		loadWeaknesses = true;
	}

	/**
	 * Everything startUp does except the retired-setting cleanup and adding the overlay, so a test can reach it without
	 * a live ConfigManager and OverlayManager.
	 *
	 * <p>The fight state is reset here as well as in {@link #stop}, as before: stop() runs on the Swing thread and a
	 * client-thread handler already in flight can write after it (Task 8 review F4). The learned weaknesses are not
	 * forgotten any more (spec addendum 5): they are reloaded, and the reload is left to the first GameTick so that it
	 * runs on the client thread, the only thread that credits. That is what reconciles F4 with remembering:
	 * <ul>
	 * <li>a late credit after stop() lands in the cache and in the saved value, both correct, and the reload then
	 *     replaces the cache with the saved value, which holds it;
	 * <li>a load can never run between a credit's cache write and its save, as both are on one thread;
	 * <li>a credit can never wipe the saved value, whatever the cache holds, because saving merges one entry into the
	 *     saved value ({@link WeaknessStore#put}) instead of writing the cache out.
	 * </ul>
	 *
	 * <p>It also loads the bundled wiki weakness table (spec addendum 7), which is static data and so is read here and
	 * not on a tick; that never throws, whatever the resource is like ({@link #loadWeaknessTable}).
	 */
	void begin()
	{
		forgetEverything();
		loadWeaknessTable();
		// check first: a load that ran between the two lines would otherwise skip the check (review finding 4)
		checkWeaknesses = true;
		loadWeaknesses = true;
	}

	/**
	 * Everything shutDown does except removing the overlay. Logout and hop keep what was learned, because a type's
	 * weakness does not change between sessions, and so does a stop: it is saved, and the next start reloads it.
	 * Nothing is written to the config here.
	 */
	void stop()
	{
		forgetEverything();
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged e)
	{
		Player me = client.getLocalPlayer();
		// A null target (the player stopped interacting) is not an engagement, and is not evidence the fight ended
		// either: it happens between attacks (raw.log: 97 of 127 changes), so it is simply ignored.
		if (me == null || e.getSource() != me || !(e.getTarget() instanceof NPC))
		{
			return;
		}
		NPC npc = (NPC) e.getTarget();
		feed.playerEngaged(npc, isCombatNpc(npc), now());
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied e)
	{
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			return;
		}
		long now = now();
		long linger = lingerMs();
		Actor victim = e.getActor();
		if (victim instanceof NPC)
		{
			// Every hitsplat on every NPC counts towards its exact HP, yours or not, damage or heal (HpTracker). This runs
			// before the isMine() test below, which only decides whether the fight is ours.
			hpTracker.hit(victim, e.getHitsplat().getHitsplatType(), e.getHitsplat().getAmount());
			// Your own hit: raw.log shows mine=true on the hits the player landed, and mine=false on other players'.
			// A hit by someone else is not our fight.
			if (e.getHitsplat().isMine())
			{
				NPC npc = (NPC) victim;
				feed.playerHit(npc, isCombatNpc(npc), now, linger, this::fighting);
			}
		}
		else if (victim == me)
		{
			// A hit on you. Poison, venom, disease and heals have other types and match neither flag. IdleNotifierPlugin
			// reads isMine() on the local player as "something hit me", and the 2026-10-04 smoke run measured it: 5 of 5
			// NPC hits on the player carried mine=true (types 12, 16). isOthers() stays accepted as a fallback. Both
			// cases need an NPC that is interacting with you, so a hit by another player adds no target of its own.
			Hitsplat hs = e.getHitsplat();
			if (hs.isMine() || hs.isOthers())
			{
				feed.playerHurt(hitters(me), now, linger, this::fighting);
			}
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned e)
	{
		forget(e.getNpc());
	}

	@Subscribe
	public void onActorDeath(ActorDeath e)
	{
		if (e.getActor() instanceof NPC)
		{
			forget((NPC) e.getActor());
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (TargetFeed.endsTheFight(e.getGameState()))
		{
			forgetEverything();
		}
	}

	/**
	 * Varp 5536 holds the weakness of the last NPC a spell was cast at. It is only buffered here; {@link #onGameTick}
	 * decides whether it can be credited.
	 */
	@Subscribe
	public void onVarbitChanged(VarbitChanged e)
	{
		// A varp event has varbitId -1 (VarbitChanged javadoc); one that only carries this varp's id is not a write to it.
		if (e.getVarpId() != VarPlayerID.LAST_NPC_ELEMENTAL_WEAKNESS || e.getVarbitId() != -1)
		{
			return;
		}
		// The logout reset (0, 0, -1) arrives with no local player (docs/probe/raw-task8.txt, tick 745): not a spell.
		if (client.getLocalPlayer() == null)
		{
			return;
		}
		weakness.varpChanged(e.getValue());
	}

	/** A spot-anim on an NPC: a spell impact if the learner finds it is the one the player is fighting. */
	@Subscribe
	public void onGraphicChanged(GraphicChanged e)
	{
		// Skipping an NPC with no spot-anim left costs one iterator call and covers a removal being reported too
		// [assumed: not measured; all 65 events in docs/probe/raw-task8.txt had one].
		if (e.getActor() instanceof NPC)
		{
			int[] ids = spotAnimIds((NPC) e.getActor());
			if (ids.length > 0)
			{
				weakness.impact((NPC) e.getActor(), ids); // ids let an AoE be told from an unrelated spell
			}
		}
	}

	@Subscribe
	public void onGameTick(GameTick e)
	{
		if (loadWeaknesses)
		{
			loadWeaknesses = false;
			loadLearnedWeaknesses();
		}
		NPC npc = feed.tick(now(), lingerMs(), this::fighting);
		learnWeakness(npc);
		snapshot = npc == null ? null : snapshotOf(npc);
	}

	/**
	 * Credits this tick's varp change, if it can be tied to exactly one NPC the player is fighting. Judged at the
	 * tick, not at the events: the trace has the change before the player's target update on the same tick.
	 * "Fighting" is wider than {@link #fighting} on purpose: any NPC that is the live target, that the player is
	 * interacting with, or that is interacting with the player counts, dying and non-combat ones too. A candidate
	 * that cannot be credited then makes the change dropped rather than leaving some other NPC the sole candidate.
	 */
	private void learnWeakness(NPC shown)
	{
		Player me = client.getLocalPlayer();
		weakness.tick(
			npc -> npc == shown || (me != null && (me.getInteracting() == npc || npc.getInteracting() == me)),
			this::weaknessCreditKey);
	}

	/** The key to credit this NPC's type under, or null when it may not be credited: dying, not a combat NPC. */
	private Integer weaknessCreditKey(NPC npc)
	{
		NPCComposition c = npc.getTransformedComposition();
		return c != null && isCombatNpc(npc) ? weaknessKey(c) : null;
	}

	/**
	 * Weaknesses are kept per transformed composition id. That is the composition the stats, name and combat level on
	 * the panel come from, so the weakness shown always belongs to the form on screen. NPC.getId() is the id of the
	 * untransformed composition [assumed: the trace logged id and tid, and they were equal for every NPC in it].
	 * There is deliberately no fallback to NPC.getId() for a null composition: such an NPC is neither credited nor
	 * shown, so the fallback could never be reached.
	 */
	private static int weaknessKey(NPCComposition c)
	{
		return c.getId();
	}

	private static int[] spotAnimIds(NPC npc)
	{
		IterableHashTable<ActorSpotAnim> anims = npc.getSpotAnims();
		if (anims == null)
		{
			return new int[0];
		}
		List<Integer> ids = new ArrayList<>();
		for (ActorSpotAnim a : anims)
		{
			ids.add(a.getId());
		}
		return ids.stream().mapToInt(Integer::intValue).toArray();
	}

	/**
	 * The saved weaknesses (spec addendum 5): one value under a key of the plugin's own group that no {@code @ConfigItem}
	 * declares. The active config profile, not the RuneScape profile: a type's weakness is game data. Package-private
	 * so a test can stand in for ConfigManager, which is a concrete class that needs a live client.
	 */
	String storedWeaknesses()
	{
		return configManager.getConfiguration(FoeConfig.GROUP, WeaknessStore.KEY);
	}

	void storeWeaknesses(String value)
	{
		configManager.setConfiguration(FoeConfig.GROUP, WeaknessStore.KEY, value);
	}

	/**
	 * The table's resource, or null when the jar does not have it. {@code getResourceAsStream}, never {@code getResource}:
	 * the Plugin Hub's jar is not unpacked (spec addendum 7). Package-private so a test can stand in for the jar.
	 */
	InputStream weaknessTableStream()
	{
		return FoePlugin.class.getResourceAsStream(WeaknessTable.RESOURCE);
	}

	/**
	 * Reads the bundled table once. Never throws, so startUp cannot fail on it (addendum 8 F1, F6): a resource that is
	 * missing, empty, too big, unreadable or damaged leaves an empty table, or the lines that were good, and a warning.
	 * The log carries counts and fixed reasons only, never text from the file (ADR-0006).
	 */
	// ponytail: Errors (OutOfMemoryError, LinkageError) are not caught here or in WeaknessTable.load; RuneLite then
	// stops the plugin cleanly. The read is capped at 1 MiB, so catch Throwable only if one is ever seen.
	private void loadWeaknessTable()
	{
		WeaknessTable.Loaded loaded;
		try
		{
			loaded = WeaknessTable.load(weaknessTableStream());
		}
		catch (RuntimeException ex)
		{
			loaded = WeaknessTable.Loaded.failed("the resource could not be opened (" + ex.getClass().getSimpleName() + ")");
		}
		weaknessTable = loaded.entries;
		weakness.useTable(loaded.entries);
		if (loaded.problem != null)
		{
			log.warn("weakness table: {}; no weakness will come from it", loaded.problem);
		}
		else if (loaded.entries.isEmpty())
		{
			log.warn("weakness table: no entries; no weakness will come from it");
		}
		else
		{
			log.info("weakness table: {} entries", loaded.entries.size());
		}
		if (loaded.skipped > 0)
		{
			log.warn("weakness table: skipped {} malformed lines", loaded.skipped);
		}
	}

	/**
	 * Never throws: a config that cannot be read means a session that starts with nothing learned, not a dead panel.
	 * The first load after the plugin starts also runs the learned-vs-table check (addendum 11), the one moment both
	 * have loaded; a profile switch reloads the store without it.
	 */
	private void loadLearnedWeaknesses()
	{
		boolean check = checkWeaknesses;
		checkWeaknesses = false;
		Map<Integer, Weakness> stored;
		try
		{
			stored = weaknessStore.load();
			weakness.load(stored);
		}
		catch (RuntimeException ex)
		{
			log.warn("Could not read the remembered weaknesses; starting with none", ex);
			return;
		}
		if (check && weaknessTable.isEmpty())
		{
			// a table that did not load is not a table that agrees: "0 disagree" would read as a clean result
			log.info("weakness check: skipped, the weakness table did not load ({} learned)", stored.size());
		}
		else if (check)
		{
			WeaknessCheck result = WeaknessCheck.compare(stored, weaknessTable);
			log.info("{}", result.summary());
			for (String line : result.lines)
			{
				log.info("weakness check: {}", line);
			}
		}
	}

	/** Never throws: it runs inside WeaknessLearner.tick, and a save that fails must not cost the tick its snapshot. */
	private void saveWeakness(int typeKey, Weakness w)
	{
		try
		{
			weaknessStore.put(typeKey, w);
		}
		catch (RuntimeException ex)
		{
			log.warn("Could not save the weakness learned for type {}; it is kept for this session only", typeKey, ex);
		}
	}

	/** Package-private so a test can stand in for the client glue, which needs a live client and its cache. */
	Portrait.Mesh portraitMesh(NPCComposition c)
	{
		return PortraitModels.load(client, c);
	}

	/** Package-private so a test can stand in for NPCManager, which is a concrete class that needs a live client. */
	Integer fallbackMaxHp(int npcId)
	{
		return npcManager.getHealth(npcId);
	}

	private TargetSnapshot snapshotOf(NPC npc)
	{
		NPCComposition c = npc.getTransformedComposition();
		if (c == null)
		{
			return null;
		}
		noteForm(npc.getId(), c.getId());
		HpMemory.Reading hp = hpMemory.read(npc, npc.getHealthRatio(), npc.getHealthScale());
		TargetSnapshot s = SnapshotFactory.build(npc.getName(), c.getCombatLevel(), c.getStats(),
			hp.getRatio(), hp.getScale(), hp.isStale(), fallbackMaxHp(npc.getId()),
			weakness.weaknessFor(weaknessKey(c)));
		if (s == null)
		{
			return null;
		}
		// Judged against the snapshot's own max HP and bar reading, so the tracker and the panel cannot disagree on
		// either. UNKNOWN (no bar, no max HP, or the bar rejects the count) leaves the midpoint the factory chose.
		int exact = hpTracker.read(npc, s.getHpRatio(), s.getHpScale(), s.isHpStale(), s.getMaxHp());
		TargetSnapshot out = exact == HpEstimate.UNKNOWN ? s : s.withHp(exact);
		// Spec addendum 14: nothing is loaded while the setting is off; the image joins the snapshot once rendered.
		return config.showPortrait() ? out.withPortrait(portraits.get(c.getId(), () -> portraitMesh(c))) : out;
	}

	private void forget(NPC npc)
	{
		hpMemory.forget(npc); // any NPC that died or left: its memory must not outlive it
		hpTracker.forget(npc);
		if (feed.gone(npc))
		{
			snapshot = null; // the panel clears now, not on the next tick
		}
	}

	/** Logout, hop or stop: forget the fight. The learned weaknesses stay (see {@link #stop}); only this tick's buffer goes. */
	/**
	 * Spec addendum 13: the first time this start shows an NPC whose form has an id of its own, log both ids and what
	 * the table holds for each. Weaknesses are looked up by the form's id, and whether the wiki's ids match that for
	 * NPCs that transform is the last [assumed] in addendum 8; ordinary play now checks it. Info level, no UI.
	 */
	// ponytail: the set of pairs seen is unbounded for a start, but there are only so many NPC forms in the game
	private void noteForm(int npcId, int shownId)
	{
		if (npcId != shownId && formsSeen.add(((long) npcId << 32) | (shownId & 0xffffffffL)))
		{
			log.info("form change: npc {} is shown as {}; table {}={} is not used, {}={} is", npcId, shownId,
				npcId, tableText(npcId), shownId, tableText(shownId));
		}
	}

	private String tableText(int id)
	{
		Weakness w = weaknessTable.get(id);
		if (w == null)
		{
			return "no entry";
		}
		String element = w.getElement() == null ? "NONE" : w.getElement().name();
		return w.getPercent() == null ? element : element + " " + w.getPercent();
	}

	private void forgetEverything()
	{
		formsSeen.clear();
		feed.reset();
		hpMemory.clear();
		hpTracker.clear();
		weakness.discardTick();
		snapshot = null;
	}

	/** The player and this NPC are interacting with each other, either way round. */
	private boolean fighting(NPC npc)
	{
		Player me = client.getLocalPlayer();
		return me != null && !dying(npc) && (me.getInteracting() == npc || npc.getInteracting() == me);
	}

	/** The combat NPCs that are interacting with the player: the candidates for "who hit me". */
	private List<NPC> hitters(Player me)
	{
		List<NPC> out = new ArrayList<>();
		WorldView view = me.getWorldView();
		if (view == null)
		{
			return out;
		}
		for (NPC npc : view.npcs())
		{
			if (npc.getInteracting() == me && isCombatNpc(npc))
			{
				out.add(npc);
			}
		}
		return out;
	}

	/** Spec addendum 2: Talk-to also sets getInteracting(), so only NPCs with a combat level above 0 count. */
	private boolean isCombatNpc(NPC npc)
	{
		NPCComposition c = npc.getTransformedComposition();
		return c != null && c.getCombatLevel() > 0 && !dying(npc);
	}

	/**
	 * Spec rule 3: a dead NPC clears at once and stays cleared. ActorDeath clears it, but a late hit on it, or its
	 * last swing landing on you, would re-adopt it until it despawns. RuneLite's NpcUtil also knows death
	 * animations and the separate death-form NPCs (e.g. drakes, gargoyles) that ActorDeath may not cover.
	 */
	boolean dying(NPC npc)
	{
		return npcUtil.isDying(npc);
	}

	private long lingerMs()
	{
		return TargetFeed.lingerMs(config.lingerSeconds());
	}

	/**
	 * Monotonic, unlike the wall clock: see the TargetTracker Javadoc. Package-private so a test can drive time,
	 * which the "target lapses / is revived" tests need and the real clock would make flaky.
	 */
	long now()
	{
		return System.nanoTime() / 1_000_000;
	}
}
