package com.foe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import net.runelite.api.Actor;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.IndexedObjectSet;
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
import net.runelite.client.eventbus.Subscribe;
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
	private final Map<String, Object> clientValues = new HashMap<>();
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
		final Map<String, Object> comp = new HashMap<>();
		final NPC npc;

		Npc(int index, String name, int combatLevel, int[] stats)
		{
			comp.put("getCombatLevel", combatLevel);
			comp.put("getStats", stats);
			comp.put("getId", 2000 + index); // the transformed composition id, deliberately not NPC.getId()
			v.put("getIndex", index);
			v.put("getId", 1000 + index);
			v.put("getSpotAnims", spotAnims(true)); // as at the moment a GraphicChanged is posted (probe: 65 of 65)
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

		/** The NPC's type, as the client's transformed composition reports it. */
		Npc type(int compositionId)
		{
			comp.put("getId", compositionId);
			return this;
		}

		/** NPC.getId(), which is not the type this plugin keys by. */
		Npc npcId(int id)
		{
			v.put("getId", id);
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

		clientValues.clear();
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

			@Override
			boolean dying(NPC npc)
			{
				return dyingNpcs.contains(npc);
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

	/** The spot-anim table of an NPC that has {@code any} spot-anims. Iteration is all the plugin asks of it. */
	private static IterableHashTable<ActorSpotAnim> spotAnims(boolean any)
	{
		List<ActorSpotAnim> list = any
			? Collections.singletonList(fake(ActorSpotAnim.class, new HashMap<String, Object>()))
			: Collections.<ActorSpotAnim>emptyList();
		return new IterableHashTable<ActorSpotAnim>()
		{
			@Override
			public ActorSpotAnim get(long hash)
			{
				throw new UnsupportedOperationException();
			}

			@Override
			public void put(ActorSpotAnim node, long hash)
			{
				throw new UnsupportedOperationException();
			}

			@Override
			public Iterator<ActorSpotAnim> iterator()
			{
				return list.iterator();
			}
		};
	}

	/** Varp 5536 changes to {@code value}. The literal is the probe's, so it checks the plugin's constant. */
	private void varp(int value)
	{
		VarbitChanged e = new VarbitChanged();
		e.setVarpId(5536);
		e.setValue(value);
		plugin.onVarbitChanged(e);
	}

	private void graphic(Actor on)
	{
		GraphicChanged e = new GraphicChanged();
		e.setActor(on);
		plugin.onGraphicChanged(e);
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

	// ---- subscriptions ----

	// RuneLite's EventBus.register throws, and the plugin fails to start, for a @Subscribe method not named
	// "on" + the event's simple name (EventBus.java:134-135 in client 1.13.1). This is that check, run without a client.
	@Test
	public void everySubscribedMethodIsNamedOnAndTheEventNameAndTheSetIsComplete()
	{
		TreeSet<String> events = new TreeSet<>();
		for (Method m : FoePlugin.class.getDeclaredMethods())
		{
			if (m.getAnnotation(Subscribe.class) == null)
			{
				continue;
			}
			assertEquals(m.toString(), 1, m.getParameterCount());
			String event = m.getParameterTypes()[0].getSimpleName();
			assertEquals("on" + event, m.getName());
			events.add(event);
		}
		assertEquals("every event the plugin listens to, none more", new TreeSet<>(java.util.Arrays.asList(
			"ActorDeath", "GameStateChanged", "GameTick", "GraphicChanged", "HitsplatApplied", "InteractingChanged",
			"NpcDespawned", "VarbitChanged")), events);
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
		assertNull("nothing has been learned yet", s.getWeakness());
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

	/** Stands in for NpcUtil.isDying: an NPC that died stays dying until it despawns. */
	private final java.util.Set<NPC> dyingNpcs = new java.util.HashSet<>();

	// Task 7 review finding 1: a late hit on, or from, a dead NPC re-adopted it until despawn (spec rule 3).
	@Test
	public void aDyingNpcIsNotReadoptedByALateHitOrItsLastSwing()
	{
		Npc giant = iceGiant(7).bar(15, 30);
		engage(giant);
		assertNotNull(tick());
		plugin.onActorDeath(new ActorDeath(giant.npc));
		dyingNpcs.add(giant.npc);
		hit(giant.npc, HitsplatID.DAMAGE_ME);
		assertNull("late hit on a dying NPC", tick());
		giant.attacksYou();
		hit(me, HitsplatID.DAMAGE_ME);
		assertNull("its last swing landing on you", tick());
	}

	// Task 7 review finding 2: another NPC with the same index despawning must not blank a live fight.
	@Test
	public void theDespawnOfAnotherNpcWithTheSameIndexLeavesThePanel()
	{
		Npc giant = iceGiant(7).bar(15, 30);
		engage(giant);
		assertNotNull(tick());
		Npc twin = iceGiant(7);
		plugin.onNpcDespawned(new NpcDespawned(twin.npc));
		assertNotNull(tick());
	}

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
		// b has never had a bar, so it is unhit (addendum 4): max HP only, and a's remembered 15/30 does not carry over
		assertTrue(s.isHpUnhit());
		assertEquals(-1, s.getHpRatio());
		assertEquals(0, s.getHpScale());
		assertEquals(HpEstimate.UNKNOWN, s.getHp());
		assertEquals(70, s.getMaxHp());
		assertFalse(s.isHpStale());
	}

	// Settings-redesign review F1: A at 35/70, switch to B, A's bar times out, back to A -> A must stay at its
	// remembered 15/30 (stale), not be redrawn as a full 70/70.
	@Test
	public void switchingAwayAndBackKeepsTheRememberedBarNotAFullOne()
	{
		Npc a = iceGiant(1).bar(15, 30);
		Npc b = iceGiant(2).bar(10, 30);
		engage(a);
		tick();
		engage(b);
		tick();
		a.bar(-1, -1);
		engage(a);
		TargetSnapshot s = tick();
		assertEquals(15, s.getHpRatio());
		assertEquals(30, s.getHpScale());
		assertTrue(s.isHpStale());
	}

	@Test
	public void aTargetThatHasNeverHadABarIsUnhitWithItsMaxHpAndNoCurrentHpUntilTheFirstHit()
	{
		Npc giant = iceGiant(7); // the game draws no bar until it takes damage
		engage(giant);
		TargetSnapshot s = tick();
		assertEquals(70, s.getMaxHp());
		assertTrue(s.isHpUnhit());
		assertEquals("no invented current HP", HpEstimate.UNKNOWN, s.getHp());
		assertEquals(0, s.getHpScale());
		assertFalse(s.isHpStale());

		giant.bar(22, 30); // first hit: the live bar replaces it
		TargetSnapshot live = tick();
		assertEquals(22, live.getHpRatio());
		assertEquals(HpEstimate.estimate(22, 30, 70), live.getHp());
		assertFalse(live.isHpStale());
		assertFalse(live.isHpUnhit());
	}

	// Seen 2026-10-08: after a relog the old rule drew a damaged monster as a full bar. Memory is cleared with the
	// session, so what was seen before the relog is unknown now, and unknown is shown as unknown.
	@Test
	public void afterARelogAMonsterWhoseBarIsNotShownIsUnhitNotFull()
	{
		Npc giant = iceGiant(7).bar(15, 30);
		engage(giant);
		assertFalse(tick().isHpUnhit());
		gameState(GameState.LOGIN_SCREEN);
		gameState(GameState.LOGGED_IN);
		giant.bar(-1, -1); // damaged, but the client shows no bar for it yet
		engage(giant);
		TargetSnapshot s = tick();
		assertTrue(s.isHpUnhit());
		assertEquals(HpEstimate.UNKNOWN, s.getHp());
		assertEquals(70, s.getMaxHp());
	}

	@Test
	public void aBarSeenAndThenLostIsStaleAtItsLastValueNotAFullBar()
	{
		Npc giant = iceGiant(7).bar(15, 30);
		engage(giant);
		tick();
		giant.bar(-1, -1);
		TargetSnapshot s = tick();
		assertEquals(15, s.getHpRatio());
		assertEquals(30, s.getHpScale());
		assertEquals(HpEstimate.estimate(15, 30, 70), s.getHp());
		assertTrue(s.isHpStale());
	}

	@Test
	public void noBarAndNoKnownMaxHpIsStillNoBar()
	{
		engage(new Npc(9, "Unknown", 53, new int[] {40, 40, 40, 0, 1, 1}));
		TargetSnapshot s = tick();
		assertEquals(0, s.getMaxHp());
		assertEquals(0, s.getHpScale());
		assertEquals(HpEstimate.UNKNOWN, s.getHp());
		assertFalse(s.isHpStale());
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

	// ---- weakness: credited only to a confirmed spell impact (plan Task 8, Revision 2026-10-08) ----
	//
	// The sequences are the ones in docs/probe/raw-task8.txt, driven through the real handlers. WeaknessLearnerTest
	// and WeaknessReplayTest own the rule itself; these check that each event reaches it with the right facts read
	// off the NPC (which id keys the entry, who counts as fought, what a missing player or an empty spot-anim table
	// means), and that the cache lives exactly as long as the spec says.

	private static final Weakness.Element EARTH = Weakness.Element.EARTH;
	private static final Weakness.Element FIRE = Weakness.Element.FIRE;

	private Npc hillGiant(int index)
	{
		return new Npc(index, "Hill Giant", 28, ICE_GIANT).type(2103);
	}

	private Npc scorpion(int index)
	{
		return new Npc(index, "Poison Scorpion", 28, ICE_GIANT).type(3025);
	}

	/** The element on the panel; fails with a message, not an NPE, when the panel shows no weakness. */
	private static Weakness.Element shownElement(TargetSnapshot s)
	{
		assertNotNull("a snapshot is shown", s);
		assertNotNull("a weakness is shown", s.getWeakness());
		return s.getWeakness().getElement();
	}

	/** A spell lands on this NPC, learned the way the trace shows it: change, target, impact, then the tick. */
	private void learn(Npc on, int value)
	{
		varp(value);
		engage(on);
		graphic(on.npc);
		tick();
	}

	// trace tick 508: "VARP5536=557 | myTarget=null", then the player's target, then the Hill Giant's impact
	@Test
	public void tick508TheChangeComesBeforeTheTargetAndTheImpactAndTheGiantIsStillCredited()
	{
		Npc giant = hillGiant(1).bar(15, 30);
		varp(557);
		engage(giant);
		graphic(giant.npc);
		assertEquals(EARTH, shownElement(tick()));
		assertEquals("and it stays, with no event on the next tick", EARTH, shownElement(tick()));
	}

	// trace ticks 738, 745 and 770
	@Test
	public void ticks738To770TheScorpionIsLearnedTheLogoutResetNeverErasesItAndAnotherScorpionNeverSpelledShowsIt()
	{
		learn(scorpion(1), 554);
		assertEquals(FIRE, shownElement(tick()));

		// tick 745: the state change first, then the reset 0, 0, -1, which arrives with no local player
		gameState(GameState.LOGIN_SCREEN);
		meValues.put("getInteracting", null);
		clientValues.put("getLocalPlayer", null);
		varp(0);
		varp(0);
		varp(-1);
		clientValues.put("getLocalPlayer", me);
		gameState(GameState.LOGGED_IN);

		// tick 770: a different scorpion object, only hit (a ranged or melee fight sets no varp)
		Npc second = scorpion(2);
		hit(second.npc, HitsplatID.DAMAGE_ME);
		TargetSnapshot s = tick();
		assertEquals("Poison Scorpion", s.getName());
		assertEquals(FIRE, shownElement(s));
		engage(hillGiant(3));
		assertNull("the reset taught nothing, not even a none for the next type", tick().getWeakness());
	}

	@Test
	public void aResetWithAFoughtImpactInTheSameTickDoesNotEraseWhatWasLearned()
	{
		Npc scorp = scorpion(1);
		learn(scorp, 554);
		varp(0);
		varp(0);
		varp(-1);
		graphic(scorp.npc); // someone else's spell, say, with the local player present
		assertEquals("0, 0, -1 is three values in one tick: not a spell", FIRE, shownElement(tick()));
	}

	@Test
	public void aChangeWhileThereIsNoLocalPlayerIsIgnoredAndNotBufferedForALaterImpact()
	{
		clientValues.put("getLocalPlayer", null);
		varp(557);
		clientValues.put("getLocalPlayer", me);
		Npc giant = hillGiant(1);
		engage(giant);
		graphic(giant.npc);
		assertNull(tick().getWeakness());
	}

	@Test
	public void aSpellCastAtOneNpcThatLandsAfterYouSwitchedToAnotherIsCreditedToNeither()
	{
		Npc a = scorpion(1); // tagged with a spell, then left: it is not interacting with you
		Npc b = hillGiant(2); // your target now
		engage(b);
		varp(554);
		graphic(a.npc); // the impact lands on A
		assertNull("B did not get the impact", tick().getWeakness());
		engage(a);
		assertNull("and A was not being fought, so it was not credited", tick().getWeakness());
	}

	@Test
	public void anAreaSpellOnTwoNpcsThatAreFightingYouCreditsNeither()
	{
		Npc a = scorpion(1).attacksYou();
		Npc b = hillGiant(2).attacksYou();
		engage(a);
		varp(554);
		graphic(a.npc);
		graphic(b.npc);
		assertNull(tick().getWeakness());
		engage(b);
		assertNull(tick().getWeakness());
	}

	@Test
	public void onlyAWriteToVarp5536IsAChangeNotAVarbitInItNorTheVarpNextToIt()
	{
		Npc giant = hillGiant(1);
		engage(giant);
		VarbitChanged varbitInThatVarp = new VarbitChanged(); // VarbitChanged.varbitId is -1 only for a varp itself
		varbitInThatVarp.setVarpId(5536);
		varbitInThatVarp.setVarbitId(12345);
		varbitInThatVarp.setValue(557);
		plugin.onVarbitChanged(varbitInThatVarp);
		VarbitChanged neighbour = new VarbitChanged(); // 5535 is LAST_NPC_TARGET_DISTANCE
		neighbour.setVarpId(5535);
		neighbour.setValue(557);
		plugin.onVarbitChanged(neighbour);
		graphic(giant.npc);
		assertNull("neither is a write to 5536", tick().getWeakness());

		// the control, in the same place and the same shape: the real write, right after, is credited
		varp(557);
		graphic(giant.npc);
		assertEquals(EARTH, shownElement(tick()));
	}

	@Test
	public void aChangeWithNoImpactAtAllIsNotCredited()
	{
		Npc giant = hillGiant(1);
		engage(giant);
		varp(557);
		assertNull(tick().getWeakness());
	}

	@Test
	public void aChangeIsNotCarriedToTheNextTickWhereAnImpactWithoutAChangeFollows()
	{
		Npc giant = hillGiant(1);
		engage(giant);
		varp(557);
		assertNull(tick().getWeakness());
		graphic(giant.npc); // another player's spell, or one whose value did not change
		assertNull(tick().getWeakness());
	}

	@Test
	public void anImpactAloneNeverTeachesAnything()
	{
		Npc giant = hillGiant(1);
		engage(giant);
		graphic(giant.npc);
		assertNull(tick().getWeakness());
	}

	@Test
	public void theEntryIsKeyedByTheTransformedCompositionIdNotByTheNpcId()
	{
		Npc a = hillGiant(1).npcId(5000).type(7000);
		learn(a, 557);
		assertEquals(EARTH, shownElement(tick()));

		engage(hillGiant(2).npcId(9999).type(7000)); // another NPC id, the same form
		assertEquals(EARTH, shownElement(tick()));

		engage(hillGiant(3).npcId(5000).type(7001)); // the same NPC id, another form
		assertNull(tick().getWeakness());
	}

	@Test
	public void aConfirmedNoneShowsNothingAndReplacesAnEarlierEntry()
	{
		Npc a = scorpion(1);
		learn(a, 554);
		assertEquals(FIRE, shownElement(tick()));
		learn(a, -1);
		assertNull(tick().getWeakness());
	}

	@Test
	public void noUnconfirmedChangeOverwritesOrErasesAGoodEntry()
	{
		Npc a = scorpion(1);
		learn(a, 554);

		varp(-1); // no impact
		assertEquals(FIRE, shownElement(tick()));

		Npc b = hillGiant(2).attacksYou(); // an area spell: two fought impacts
		varp(555);
		graphic(a.npc);
		graphic(b.npc);
		assertEquals(FIRE, shownElement(tick()));

		varp(0); // a value that is not a rune
		graphic(a.npc);
		assertEquals(FIRE, shownElement(tick()));
	}

	@Test
	public void aTargetWhoseInteractingFlagHasFlippedToNullBetweenAttacksIsStillTheOneBeingFought()
	{
		Npc giant = hillGiant(1);
		engage(giant);
		tick();
		meValues.put("getInteracting", null); // raw.log: the player's target is null between attacks
		varp(557);
		graphic(giant.npc);
		assertEquals("the panel's live target counts", EARTH, shownElement(tick()));
	}

	@Test
	public void anNpcInteractingWithYouIsFoughtEvenWhenItIsNotYourTarget()
	{
		Npc target = scorpion(1);
		Npc attacker = hillGiant(2).attacksYou();
		engage(target);
		varp(557);
		graphic(attacker.npc);
		assertNull("the panel shows the scorpion, which had no impact", tick().getWeakness());
		engage(attacker);
		assertEquals(EARTH, shownElement(tick()));
	}

	@Test
	public void aDyingNpcIsNotCredited()
	{
		Npc giant = hillGiant(1);
		engage(giant);
		tick();
		dyingNpcs.add(giant.npc);
		varp(557);
		graphic(giant.npc);
		tick();
		dyingNpcs.remove(giant.npc);
		engage(hillGiant(2));
		assertNull(tick().getWeakness());
	}

	@Test
	public void onlyAnNpcWithASpotAnimIsAnImpact()
	{
		Npc giant = hillGiant(1);
		engage(giant);
		varp(557);
		graphic(me); // a player's spot-anim
		assertNull(tick().getWeakness());

		giant.v.put("getSpotAnims", spotAnims(false)); // a spot-anim that has just ended
		varp(557);
		graphic(giant.npc);
		assertNull(tick().getWeakness());
	}

	@Test
	public void aLogoutOrHopDropsABufferedChangeButKeepsTheCache()
	{
		learn(scorpion(1), 554);
		Npc giant = hillGiant(2);
		varp(557);
		gameState(GameState.HOPPING);
		gameState(GameState.LOGGED_IN);
		engage(giant);
		graphic(giant.npc);
		assertNull("the change belonged to the world before the hop", tick().getWeakness());

		engage(scorpion(3));
		assertEquals("what was learned survives the hop", FIRE, shownElement(tick()));
		gameState(GameState.LOGIN_SCREEN);
		gameState(GameState.LOGGED_IN);
		engage(scorpion(4));
		assertEquals("and the logout", FIRE, shownElement(tick()));
	}

	@Test
	public void aRegionLoadKeepsABufferedChange()
	{
		Npc giant = hillGiant(1);
		engage(giant);
		varp(557);
		gameState(GameState.LOADING);
		gameState(GameState.LOGGED_IN);
		graphic(giant.npc);
		assertEquals(EARTH, shownElement(tick()));
	}

	@Test
	public void stoppingThePluginForgetsWhatWasLearned()
	{
		learn(scorpion(1), 554);
		plugin.stop();
		engage(scorpion(2));
		assertNull(tick().getWeakness());
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
