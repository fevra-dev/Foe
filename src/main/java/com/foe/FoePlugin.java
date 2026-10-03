package com.foe;

import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
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
	@Inject
	private Client client;

	// PROBE (Task 1) — removed in Task 7. Logs varp 5536 so its encoding can be decoded.
	// Uses the long accessors on purpose: VarbitChanged.getValue() and Client.getVarpValue()
	// truncate to int in client 1.13.1, which would hide any high bits of the encoding.
	@Subscribe
	public void onVarbitChanged(VarbitChanged e)
	{
		if (e.getVarpId() != VarPlayerID.LAST_NPC_ELEMENTAL_WEAKNESS)
		{
			return;
		}
		long v = e.getLongValue();
		Player me = client.getLocalPlayer();
		log.info("[foe-probe] varp5536 changed: dec={} hex={} bin={} varbit={} | {}",
			v, Long.toHexString(v), Long.toBinaryString(v), e.getVarbitId(),
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
		log.info("[foe-probe] player now targets {} | varp5536 currently {}",
			describe(e.getTarget()), client.getVarpLongValue(VarPlayerID.LAST_NPC_ELEMENTAL_WEAKNESS));
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
