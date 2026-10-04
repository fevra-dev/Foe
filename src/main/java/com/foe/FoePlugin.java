package com.foe;

import javax.inject.Inject;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
	name = "Foe",
	description = "Live HP, combat levels and elemental weakness of the monster you're fighting",
	tags = {"target", "monster", "npc", "weakness", "opponent", "hp", "combat"}
)
public class FoePlugin extends Plugin
{
	private static final int VARP = VarPlayerID.LAST_NPC_ELEMENTAL_WEAKNESS;

	@Inject
	private Client client;

	/** The one snapshot FoeOverlay draws. Written by the plugin on the client thread (Task 7), read on the render thread. */
	@Getter(AccessLevel.PACKAGE)
	private volatile TargetSnapshot snapshot;

	// PROBE (Task 1) — removed in Task 7. Logs varp 5536 so its encoding can be decoded.
	//
	// In client 1.13.1 a varp is either int- or long-typed, set per varp by the server packet that
	// last wrote it. getVarpValue() throws IllegalArgumentException for a long varp and
	// getVarpLongValue() throws for an int one (injected-client bytecode, "varp %d is an int/long"),
	// and EventBus swallows the throw into a log line grep 'foe-probe' never sees. So the probe reads
	// the raw arrays, which have no throw path, and prints both.
	//
	// Every line carries the tick: logback timestamps are whole seconds, a tick is 0.6s, and
	// "updates on attack vs on first hit" is only answerable by tick order.
	@Subscribe
	public void onVarbitChanged(VarbitChanged e)
	{
		if (e.getVarpId() != VARP)
		{
			return;
		}
		long v = e.getLongValue();
		Player me = client.getLocalPlayer();
		// hex32 is the low 32 bits: an int varp is sign-extended into the long, and the padding
		// is not part of the encoding.
		log.info("[foe-probe] tick={} varp5536 changed: dec={} hex32={} hex64={} bin32={} varbit={} | target {}",
			client.getTickCount(), v, Integer.toHexString((int) v), Long.toHexString(v),
			Integer.toBinaryString((int) v), e.getVarbitId(),
			describe(me == null ? null : me.getInteracting()));
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged e)
	{
		Player me = client.getLocalPlayer();
		if (me == null || e.getSource() != me)
		{
			return;
		}
		log.info("[foe-probe] tick={} player now targets {} | varp5536 {}",
			client.getTickCount(), describe(e.getTarget()), varpNow());
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied e)
	{
		if (!(e.getActor() instanceof NPC))
		{
			return;
		}
		log.info("[foe-probe] tick={} hitsplat on {} mine={} amount={} | varp5536 {}",
			client.getTickCount(), describe(e.getActor()), e.getHitsplat().isMine(),
			e.getHitsplat().getAmount(), varpNow());
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		log.info("[foe-probe] tick={} gamestate {} | varp5536 {}",
			client.getTickCount(), e.getGameState(), varpNow());
	}

	private String varpNow()
	{
		int[] ints = client.getVarps();
		long[] longs = client.getVarpsLong();
		String i = ints == null ? "null" : Integer.toHexString(ints[VARP]);
		String l = longs == null ? "null" : Long.toHexString(longs[VARP]);
		return "int=0x" + i + " long=0x" + l;
	}

	private static String describe(Actor a)
	{
		if (!(a instanceof NPC))
		{
			return "no NPC";
		}
		NPC n = (NPC) a;
		return n.getName() + " (id " + n.getId() + ", idx " + n.getIndex() + ")";
	}
}
