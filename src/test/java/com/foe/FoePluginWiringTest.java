package com.foe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.IndexedObjectSet;
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
import org.junit.Before;
import org.junit.Test;

/**
 * FoePlugin's event handlers, driven with fake Client/Player/NPC objects (java.lang.reflect.Proxy: no Mockito in
 * this build). The pure decisions are tested in TargetFeedTest, HpMemoryTest and TargetReplayTest; this file
 * checks the part those cannot: that each RuneLite event reaches the right call, with the right facts read off
 * the NPC (which flag, which side, which argument). A fake throws on any method it was not told about, so a
 * handler that starts reading something new fails loudly instead of getting a default.
 *
 * <p>Time is driven by the {@code clock} field through FoePlugin's package-private now(), so lapsing and reviving
 * are asserted exactly. The one test that uses the production clock only checks which clock it is.
 *
 * <p>Not covered, because OverlayManager is a concrete class that needs a live client: startUp and shutDown.
 * Registering and removing the overlay is therefore verified in game (Task 9), not here.
 */
public class FoePluginWiringTest
{
	private static final int[] ICE_GIANT = {40, 40, 40, 70, 1, 1};

	private int lingerSeconds;
	private long clock;
	private Integer npcManagerHealth;
	private final List<NPC> world = new ArrayList<>();
	private final Map<String, Object> meValues = new HashMap<>();
	private Player me;
	private FoePlugin plugin;

	/** A Proxy that answers from a map and throws on anything else. */
	private static <T> T fake(Class<T> type, Map<String, Object> values)
	{
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) ->
		{
			switch (method.getName())
			{
				case "hashCode":
					return System.identityHashCode(proxy);
				case "equals":
					return proxy == args[0];
				case "toString":
					return type.getSimpleName() + "@" + System.identityHashCode(proxy);
				default:
					if (values.containsKey(method.getName()))
					{
						return values.get(method.getName());
					}
					throw new UnsupportedOperationException(type.getSimpleName() + "." + method.getName());
			}
		}));
	}

	/** One fake NPC and the map behind it, so a test can change what the client would now report. */
	private final class Npc
	{
		final Map<String, Object> v = new HashMap<>();
		final NPC npc;

		Npc(int index, String name, int combatLevel, int[] stats)
		{
			Map<String, Object> comp = new HashMap<>();
			comp.put("getCombatLevel", combatLevel);
			comp.put("getStats", stats);
			v.put("getIndex", index);
			v.put("getId", 1000 + index);
			v.put("getName", name);
			v.put("getInteracting", null);
			v.put("getHealthRatio", -1);
			v.put("getHealthScale", -1);
			v.put("getTransformedComposition", fake(NPCComposition.class, comp));
			npc = fake(NPC.class, v);
			world.add(npc);
		}

		Npc bar(int ratio, int scale)
		{
			v.put("getHealthRatio", ratio);
			v.put("getHealthScale", scale);
			return this;
		}

		Npc attacksYou()
		{
			v.put("getInteracting", me);
			return this;
		}
	}

	private Npc iceGiant(int index)
	{
		return new Npc(index, "Ice giant " + index, 53, ICE_GIANT);
	}

	@Before
	public void setUp() throws Exception
	{
		lingerSeconds = 10;
		clock = 1_000;
		npcManagerHealth = null;
		world.clear();
		meValues.clear();

		Map<String, Object> viewValues = new HashMap<>();
		viewValues.put("npcs", new IndexedObjectSet<NPC>()
		{
			@Override
			public NPC byIndex(int index)
			{
				throw new UnsupportedOperationException();
			}

			@Override
			public Iterator<NPC> iterator()
			{
				return world.iterator();
			}
		});
		meValues.put("getInteracting", null);
		meValues.put("getWorldView", fake(WorldView.class, viewValues));
		me = fake(Player.class, meValues);

		Map<String, Object> clientValues = new HashMap<>();
		clientValues.put("getLocalPlayer", me);

		plugin = new FoePlugin()
		{
			@Override
			Integer fallbackMaxHp(int npcId)
			{
				return npcManagerHealth;
			}

			@Override
			long now()
			{
				return clock;
			}
		};
		set("client", fake(Client.class, clientValues));
		set("config", new FoeConfig()
		{
			@Override
			public int lingerSeconds()
			{
				return lingerSeconds;
			}
		});
	}

	private void set(String field, Object value) throws Exception
	{
		Field f = FoePlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(plugin, value);
	}

	private static Hitsplat hitsplat(int type)
	{
		return new Hitsplat()
		{
			@Override
			public int getHitsplatType()
			{
				return type;
			}

			@Override
			public int getAmount()
			{
				return 5;
			}

			@Override
			public int getDisappearsOnGameCycle()
			{
				return 0;
			}
		};
	}

	private void engage(Npc n)
	{
		meValues.put("getInteracting", n.npc);
		plugin.onInteractingChanged(new InteractingChanged(me, n.npc));
	}

	private void hit(Actor on, int hitsplatType)
	{
		HitsplatApplied e = new HitsplatApplied();
		e.setActor(on);
		e.setHitsplat(hitsplat(hitsplatType));
		plugin.onHitsplatApplied(e);
	}

	private TargetSnapshot tick()
	{
		plugin.onGameTick(new GameTick());
		return plugin.getSnapshot();
	}

	private void gameState(GameState s)
	{
		GameStateChanged e = new GameStateChanged();
		e.setGameState(s);
		plugin.onGameStateChanged(e);
	}

	// ---- engagement ----

	@Test
	public void engagingACombatNpcShowsItFromTheNpcsOwnValues()
	{
		Npc giant = iceGiant(7).bar(15, 30);
		engage(giant);
		TargetSnapshot s = tick();
		assertNotNull(s);
		assertEquals("Ice giant 7", s.getName());
		assertEquals(53, s.getCombatLevel());
		assertEquals(70, s.getMaxHp());
		assertEquals(15, s.getHpRatio());
		assertEquals(30, s.getHpScale());
		assertEquals(HpEstimate.estimate(15, 30, 70), s.getHp());
		assertEquals(40, s.getAttack());
		assertFalse(s.isHpStale());
		assertNull("weakness is Task 8", s.getWeakness());
	}

	@Test
	public void talkingToAnNpcWithNoCombatLevelNeverShowsAPanel()
	{
		Npc banker = new Npc(3, "Banker", 0, new int[] {1, 1, 1, 1, 1, 1});
		engage(banker);
		assertNull(tick());
	}

	@Test
	public void aNullOrPlayerTargetIsNotAnEngagement()
	{
		iceGiant(7);
		plugin.onInteractingChanged(new InteractingChanged(me, null));
		plugin.onInteractingChanged(new InteractingChanged(me, fake(Player.class, new HashMap<String, Object>())));
		assertNull(tick());
	}

	// Other players fight NPCs all day next to you, and each of those is an InteractingChanged with an NPC target.
	@Test
	public void anotherActorsInteractionIsNotYours()
	{
		Npc giant = iceGiant(7).bar(15, 30);
		Player other = fake(Player.class, new HashMap<String, Object>());
		plugin.onInteractingChanged(new InteractingChanged(other, giant.npc));
		// an NPC starting to look at you is not YOU engaging it either
		plugin.onInteractingChanged(new InteractingChanged(giant.npc, me));
		assertNull(tick());
	}

	@Test
	public void aNullTargetDoesNotBlankALiveTarget()
	{
		Npc giant = iceGiant(7).bar(15, 30);
		engage(giant);
		assertNotNull(tick());
		meValues.put("getInteracting", null);
		plugin.onInteractingChanged(new InteractingChanged(me, null));
		assertNotNull(tick());
	}

	// ---- your own hit ----

	@Test
	public void yourOwnHitAdoptsAnNpcWhenThereIsNoTarget()
	{
		Npc giant = iceGiant(7).bar(15, 30);
		hit(giant.npc, HitsplatID.DAMAGE_ME);
		assertEquals("Ice giant 7", tick().getName());
	}

	@Test
	public void aHitBySomeoneElseOnAnNpcIsNotYourFight()
	{
		Npc giant = iceGiant(7).bar(15, 30);
		hit(giant.npc, HitsplatID.DAMAGE_OTHER);
		assertNull(tick());
	}

	@Test
	public void yourHitOnANonCombatNpcIsIgnored()
	{
		Npc dummy = new Npc(3, "Dummy", 0, new int[] {1, 1, 1, 1, 1, 1});
		hit(dummy.npc, HitsplatID.DAMAGE_ME);
		assertNull(tick());
	}

	// raw.log lines 265-267, through the real handlers.
	@Test
	public void aHitInFlightOnTheOldNpcDoesNotTakeThePanel()
	{
		Npc a = iceGiant(1).bar(15, 30);
		Npc b = iceGiant(2).bar(15, 30);
		engage(a);
		engage(b);
		hit(a.npc, HitsplatID.DAMAGE_ME);
		assertEquals("Ice giant 2", tick().getName());
	}

	// ---- an NPC hits you ----

	@Test
	public void anNpcThatIsInteractingWithYouAndHitsYouIsAdopted()
	{
		iceGiant(7).bar(15, 30).attacksYou();
		hit(me, HitsplatID.DAMAGE_ME);
		assertEquals("Ice giant 7", tick().getName());
	}

	@Test
	public void theOthersFlagOnYouIsAcceptedToo()
	{
		iceGiant(7).bar(15, 30).attacksYou();
		hit(me, HitsplatID.DAMAGE_OTHER);
		assertEquals("Ice giant 7", tick().getName());
	}

	@Test
	public void poisonOnYouIsNotAnNpcHit()
	{
		iceGiant(7).bar(15, 30).attacksYou();
		hit(me, HitsplatID.POISON);
		assertNull(tick());
	}

	@Test
	public void aHitOnYouAdoptsOnlyNpcsThatAreInteractingWithYou()
	{
		iceGiant(7).bar(15, 30); // in the scene, not interacting with you
		hit(me, HitsplatID.DAMAGE_ME);
		assertNull(tick());
	}

	@Test
	public void aHitOnYouNeverAdoptsANonCombatNpcThatFollowsYou()
	{
		new Npc(3, "Follower", 0, new int[] {1, 1, 1, 1, 1, 1}).attacksYou();
		hit(me, HitsplatID.DAMAGE_ME);
		assertNull(tick());
	}

	@Test
	public void theLiveTargetWinsAmongSeveralNpcsHittingYou()
	{
		iceGiant(1).bar(15, 30).attacksYou(); // listed first
		Npc second = iceGiant(2).bar(15, 30).attacksYou();
		engage(second);
		hit(me, HitsplatID.DAMAGE_ME);
		assertEquals("the second NPC is the one you engaged, though the first is listed first",
			"Ice giant 2", tick().getName());
	}

	// ---- the fight is on: either side interacting keeps the target (and brings a lapsed one back) ----

	@Test
	public void youInteractingWithTheTargetKeepsItPastTheLinger()
	{
		Npc giant = iceGiant(7).bar(15, 30);
		engage(giant);
		assertNotNull(tick());
		clock += 20_000; // linger is 10 s, and you are still interacting with it
		assertNotNull(tick());
	}

	@Test
	public void theTargetInteractingWithYouKeepsItPastTheLinger()
	{
		Npc giant = iceGiant(7).bar(15, 30);
		engage(giant);
		assertNotNull(tick());
		meValues.put("getInteracting", null);
		giant.attacksYou();
		clock += 20_000;
		assertNotNull(tick());
	}

	@Test
	public void withNeitherInteractingTheTargetLapsesAfterTheLingerAndComesBackWhenTheFightResumes()
	{
		Npc giant = iceGiant(7).bar(15, 30);
		engage(giant);
		assertNotNull(tick()); // consumes the engagement; the linger runs from here
		meValues.put("getInteracting", null);
		clock += 10_000;
		assertNotNull("exactly at the linger", tick());
		clock += 1;
		assertNull("one ms past it", tick());
		meValues.put("getInteracting", giant.npc);
		clock += 20_000;
		assertNotNull("the fight resumed", tick());
	}

	@Test
	public void anotherNpcInteractingWithYouDoesNotKeepTheTarget()
	{
		Npc giant = iceGiant(7).bar(15, 30);
		Npc other = iceGiant(8).bar(15, 30);
		engage(giant);
		assertNotNull(tick());
		meValues.put("getInteracting", other.npc);
		other.attacksYou();
		clock += 20_000;
		assertNull(tick());
	}

	// ---- death, despawn, logout ----

	@Test
	public void deathClearsThePanelAtOnceAndAFollowingTickDoesNotBringItBack()
	{
		Npc giant = iceGiant(7).bar(15, 30);
		engage(giant);
		assertNotNull(tick());
		plugin.onActorDeath(new ActorDeath(giant.npc));
		assertNull("cleared without waiting for a tick", plugin.getSnapshot());
		// a dying NPC is still interacting with you for a few ticks
		giant.attacksYou();
		assertNull(tick());
	}

	@Test
	public void despawnClearsThePanelAtOnceAndAFollowingTickDoesNotBringItBack()
	{
		Npc giant = iceGiant(7).bar(15, 30);
		engage(giant);
		assertNotNull(tick());
		plugin.onNpcDespawned(new NpcDespawned(giant.npc));
		assertNull(plugin.getSnapshot());
		assertNull(tick());
	}

	@Test
	public void theDeathOrDespawnOfAnotherNpcLeavesThePanel()
	{
		Npc giant = iceGiant(7).bar(15, 30);
		Npc other = iceGiant(8);
		engage(giant);
		assertNotNull(tick());
		plugin.onActorDeath(new ActorDeath(other.npc));
		plugin.onNpcDespawned(new NpcDespawned(other.npc));
		assertNotNull(plugin.getSnapshot());
		assertNotNull(tick());
	}

	@Test
	public void theDeathOfANonNpcActorIsIgnored()
	{
		engage(iceGiant(7).bar(15, 30));
		assertNotNull(tick());
		plugin.onActorDeath(new ActorDeath(me));
		assertNotNull(plugin.getSnapshot());
	}

	@Test
	public void logoutAndHopClearThePanelButARegionLoadDoesNot()
	{
		Npc giant = iceGiant(7).bar(15, 30);
		engage(giant);
		assertNotNull(tick());

		gameState(GameState.LOADING);
		assertNotNull(plugin.getSnapshot());
		gameState(GameState.LOGGED_IN);
		assertNotNull(plugin.getSnapshot());

		gameState(GameState.HOPPING);
		assertNull(plugin.getSnapshot());
		assertNull("and the target is gone, not just the drawing", tick());

		engage(giant);
		assertNotNull(tick());
		gameState(GameState.LOGIN_SCREEN);
		assertNull(plugin.getSnapshot());
		assertNull(tick());
	}

	// ---- one snapshot per tick ----

	@Test
	public void theSnapshotChangesOnlyOnATick()
	{
		Npc giant = iceGiant(7).bar(15, 30);
		engage(giant);
		TargetSnapshot first = tick();
		giant.bar(9, 30);
		assertEquals(15, plugin.getSnapshot().getHpRatio());
		assertEquals(9, tick().getHpRatio());
		assertEquals(15, first.getHpRatio());
	}

	@Test
	public void aTargetWithNoCompositionHasNoSnapshot()
	{
		Npc giant = iceGiant(7).bar(15, 30);
		engage(giant);
		giant.v.put("getTransformedComposition", null);
		assertNull(tick());
	}

	// ---- last-known HP ----

	@Test
	public void theLastBarIsRememberedWhenTheBarGoesAndIsMarkedStale()
	{
		Npc giant = iceGiant(7).bar(15, 30);
		engage(giant);
		assertFalse(tick().isHpStale());
		giant.bar(-1, -1);
		TargetSnapshot s = tick();
		assertTrue(s.isHpStale());
		assertEquals(15, s.getHpRatio());
		assertEquals(30, s.getHpScale());
		assertEquals(HpEstimate.estimate(15, 30, 70), s.getHp());
		giant.bar(12, 30);
		assertFalse(tick().isHpStale());
	}

	@Test
	public void theRememberedBarDoesNotFollowYouToAnotherTarget()
	{
		Npc a = iceGiant(1).bar(15, 30);
		Npc b = iceGiant(2); // no bar yet
		engage(a);
		tick();
		engage(b);
		TargetSnapshot s = tick();
		assertEquals("Ice giant 2", s.getName());
		assertEquals(-1, s.getHpRatio());
		assertEquals(0, s.getHpScale());
		assertFalse(s.isHpStale());
		assertEquals(HpEstimate.UNKNOWN, s.getHp());
	}

	// ---- max HP ----

	@Test
	public void maxHpComesFromTheStatsAndFallsBackToTheNpcManager() throws Exception
	{
		npcManagerHealth = 999;
		engage(iceGiant(7).bar(15, 30));
		assertEquals("stats win", 70, tick().getMaxHp());

		setUp();
		npcManagerHealth = 64;
		engage(new Npc(8, "No stats", 53, new int[] {40, 40, 40, 0, 1, 1}).bar(15, 30));
		assertEquals("fallback used", 64, tick().getMaxHp());

		setUp();
		engage(new Npc(9, "Unknown", 53, new int[] {40, 40, 40, 0, 1, 1}).bar(15, 30));
		assertEquals("neither knows", 0, tick().getMaxHp());
	}

	// ---- clock ----

	// Plan amendment 3: a wall clock stepping backwards keeps a target live until it catches up. Nothing in a unit
	// test can step the clock, so this pins the choice itself: now() is on the System.nanoTime() timeline, which the
	// wall clock (currentTimeMillis, ~1.7e12 ms) is nowhere near.
	@Test
	public void theProductionClockIsTheMonotonicOne()
	{
		long mono = System.nanoTime() / 1_000_000;
		long now = new FoePlugin().now();
		assertTrue("now()=" + now + " but nanoTime/1e6=" + mono, Math.abs(now - mono) < 5_000);
	}

	// ---- config ----

	@Test
	public void aNegativeLingerFromAHandEditedProfileBehavesAsZeroAndStillShowsOnEvidence()
	{
		lingerSeconds = -5;
		engage(iceGiant(7).bar(15, 30));
		assertNotNull("engagement is evidence, so even linger 0 shows it on the tick", tick());
	}
}
