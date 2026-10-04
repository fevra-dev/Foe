package com.foe;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import lombok.AccessLevel;
import lombok.Getter;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Hitsplat;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.NPCManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * Wiring only: reads facts off RuneLite's events and NPC objects, hands them to {@link TargetFeed} and
 * {@link HpMemory} (which hold every decision and are unit tested), and builds one {@link TargetSnapshot} per
 * GameTick. Everything here runs on the client thread, and FoeOverlay reads the snapshot from the render thread.
 *
 * <p>Known limits (the first is a decision, the rest are the client's):
 * <ul>
 * <li>When several NPCs are hitting you and none is the live target, the client does not say whose hit landed, so
 *     the first NPC interacting with you is taken (see {@link TargetFeed#playerHurt}).
 * <li>Only NPCs in the local player's own world view are considered as hitters, so an NPC on another world view (a
 *     boat) that hits you is not adopted by that path.
 * <li>The remembered HP of a target is not time-limited; it is always drawn as stale (see {@link HpMemory}).
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

	private final TargetFeed<NPC> feed = new TargetFeed<>(NPC::getIndex);
	private final HpMemory hpMemory = new HpMemory();

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
			// reads isMine() on the local player as "something hit me"; isOthers() is accepted as well, because the
			// trace behind this plugin holds NPC victims only and a wrong guess here must not silence the path. Both
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

	@Subscribe
	public void onGameTick(GameTick e)
	{
		NPC npc = feed.tick(now(), lingerMs(), this::fighting);
		snapshot = npc == null ? null : snapshotOf(npc);
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
			null); // weakness: Task 8
	}

	private void forget(NPC npc)
	{
		if (feed.gone(npc))
		{
			hpMemory.clear();
			snapshot = null; // the panel clears now, not on the next tick
		}
	}

	private void forgetEverything()
	{
		feed.reset();
		hpMemory.clear();
		snapshot = null;
	}

	/** The player and this NPC are interacting with each other, either way round. */
	private boolean fighting(NPC npc)
	{
		Player me = client.getLocalPlayer();
		return me != null && (me.getInteracting() == npc || npc.getInteracting() == me);
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
	private static boolean isCombatNpc(NPC npc)
	{
		NPCComposition c = npc.getTransformedComposition();
		return c != null && c.getCombatLevel() > 0;
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
