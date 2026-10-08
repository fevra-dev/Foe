package com.foe;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import lombok.AccessLevel;
import lombok.Getter;
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
 * thread (PluginManager), which is harmless because the overlay is removed first and startUp resets everything
 * before re-registering. FoeOverlay reads the volatile snapshot when it renders.
 *
 * <p>Known limits (the first is a decision, the rest are the client's):
 * <ul>
 * <li>When several NPCs are hitting you and none is the live target, the client does not say whose hit landed, so
 *     the first NPC interacting with you is taken (see {@link TargetFeed#playerHurt}).
 * <li>Only NPCs in the local player's own world view are considered as hitters, so an NPC on another world view (a
 *     boat) that hits you is not adopted by that path.
 * <li>The remembered HP of a target is not time-limited; it is always drawn as stale (see {@link HpMemory}).
 * <li>A weakness is learned only from a confirmed spell impact (see {@link WeaknessLearner}), so it is missing, never
 *     false, for a type whose spell left the varp unchanged, or whose impact could not be tied to one NPC. One
 *     coincidence is not caught: a spell cast at a monster you have since left, landing on the very tick another
 *     player's spell lands on the monster you fight now, is credited to the monster you fight now.
 * </ul>
 */
@PluginDescriptor(
	name = "Foe",
	description = "Live HP, combat levels and elemental weakness of the monster you're fighting",
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

	private final TargetFeed<NPC> feed = new TargetFeed<>(NPC::getIndex);
	private final HpMemory hpMemory = new HpMemory();
	/** What each monster type is weak to, learned in this session. Survives logout; cleared by {@link #stop}. */
	private final WeaknessLearner<NPC> weakness = new WeaknessLearner<>();

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
		forgetEverything();
		overlayManager.add(overlay);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		stop();
	}

	/**
	 * Everything shutDown does except removing the overlay, so a test can reach it without a live OverlayManager. This
	 * is the one place the learned weaknesses are forgotten: logout and hop keep them, because a type's weakness does
	 * not change between sessions.
	 */
	void stop()
	{
		forgetEverything();
		weakness.clear();
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
		if (e.getActor() instanceof NPC && hasSpotAnim((NPC) e.getActor()))
		{
			weakness.impact((NPC) e.getActor());
		}
	}

	@Subscribe
	public void onGameTick(GameTick e)
	{
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

	private static boolean hasSpotAnim(NPC npc)
	{
		IterableHashTable<ActorSpotAnim> anims = npc.getSpotAnims();
		return anims != null && anims.iterator().hasNext();
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
		HpMemory.Reading hp = hpMemory.read(npc, npc.getHealthRatio(), npc.getHealthScale());
		return SnapshotFactory.build(npc.getName(), c.getCombatLevel(), c.getStats(),
			hp.getRatio(), hp.getScale(), hp.isStale(), fallbackMaxHp(npc.getId()),
			weakness.weaknessFor(weaknessKey(c)));
	}

	private void forget(NPC npc)
	{
		hpMemory.forget(npc); // any NPC that died or left: its memory must not outlive it
		if (feed.gone(npc))
		{
			snapshot = null; // the panel clears now, not on the next tick
		}
	}

	/** Logout, hop or stop: forget the fight. The learned weaknesses stay (see {@link #stop}); only this tick's buffer goes. */
	private void forgetEverything()
	{
		feed.reset();
		hpMemory.clear();
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
