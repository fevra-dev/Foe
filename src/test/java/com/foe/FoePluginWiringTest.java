package com.foe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import ch.qos.logback.classic.Level;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
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
import org.junit.After;
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
	/** What the plugin's one config key holds; null is "never set". */
	private String storedWeaknesses;
	private final List<String> configWrites = new ArrayList<>();
	private int configReads;
	private boolean configReadFails;
	private boolean configWriteFails;
	/**
	 * What the plugin's weakness-table resource holds; null is "not in the jar". Empty by default, so the tests written
	 * before the table existed see no table weaknesses and keep their meaning; the real one is {@link #useRealTable}.
	 */
	private String tableText;
	private boolean tableStreamFails;
	private boolean useRealTable;
	private int tableOpens;
	private LogCapture logs;

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
		storedWeaknesses = null;
		configWrites.clear();
		configReads = 0;
		configReadFails = false;
		configWriteFails = false;
		tableText = "";
		tableStreamFails = false;
		useRealTable = false;
		tableOpens = 0;
		logs = new LogCapture(FoePlugin.class);
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

		plugin = newPlugin();
	}

	@After
	public void closeLogs()
	{
		logs.close();
	}

	/**
	 * A plugin wired to the fakes. Every instance shares the fake config above, so a second one is "the next time
	 * RuneLite starts": nothing is in its memory, and what it knows has to come from the stored value.
	 */
	private FoePlugin newPlugin() throws Exception
	{
		FoePlugin p = new FoePlugin()
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

			@Override
			String storedWeaknesses()
			{
				configReads++;
				if (configReadFails)
				{
					throw new IllegalStateException("config unavailable");
				}
				return storedWeaknesses;
			}

			@Override
			InputStream weaknessTableStream()
			{
				tableOpens++;
				if (tableStreamFails)
				{
					throw new IllegalStateException("jar unavailable");
				}
				if (useRealTable)
				{
					return super.weaknessTableStream();
				}
				return tableText == null ? null : new ByteArrayInputStream(tableText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			}

			@Override
			void storeWeaknesses(String value)
			{
				if (configWriteFails)
				{
					throw new IllegalStateException("config unavailable");
				}
				storedWeaknesses = value;
				configWrites.add(value);
			}
		};
		set(p, "client", fake(Client.class, clientValues));
		set(p, "config", new FoeConfig()
		{
			@Override
			public int lingerSeconds()
			{
				return lingerSeconds;
			}
		});
		return p;
	}

	private static void set(FoePlugin target, String field, Object value) throws Exception
	{
		Field f = FoePlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(target, value);
	}

	private static Hitsplat hitsplat(int type)
	{
		return hitsplat(type, 5);
	}

	private static Hitsplat hitsplat(int type, int amount)
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
				return amount;
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
		hit(on, hitsplatType, 5);
	}

	private void hit(Actor on, int hitsplatType, int amount)
	{
		HitsplatApplied e = new HitsplatApplied();
		e.setActor(on);
		e.setHitsplat(hitsplat(hitsplatType, amount));
		plugin.onHitsplatApplied(e);
	}

	/** The spot-anim table of an NPC that has {@code any} spot-anims. Iteration is all the plugin asks of it. */
	private static IterableHashTable<ActorSpotAnim> spotAnims(boolean any)
	{
		List<ActorSpotAnim> list = any
			? Collections.singletonList(fake(ActorSpotAnim.class, new HashMap<String, Object>(
				Collections.singletonMap("getId", 180)))) // Snare impact, as in docs/probe/raw-task8.txt
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
			"NpcDespawned", "ProfileChanged", "VarbitChanged")), events);
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

	// ---- exact HP from hitsplats (spec addendum 5) ----
	//
	// HpTrackerTest owns the rule. These check the wiring: every hitsplat on every NPC reaches the tracker with its
	// type and amount, the max HP it is judged against is the panel's, and the memory is dropped when HpMemory's is.

	private static final int[] GIANT_85 = {40, 40, 40, 85, 1, 1};

	private Npc giant85(int index)
	{
		return new Npc(index, "Giant " + index, 53, GIANT_85);
	}

	/** The ratio the server sends for this true HP (the formula in RuneLite's OpponentInfoOverlay comment). */
	private static int ratioFor(int hp, int maxHp, int scale)
	{
		return hp <= 0 ? 0 : 1 + (scale - 1) * hp / maxHp;
	}

	// The case that prompted addendum 5: ratio 22/30 on 85 max HP is 62-64; the midpoint says 63, the truth was 64.
	@Test
	public void theTrackedHpIsShownWhenTheBarAllowsIt()
	{
		Npc giant = giant85(7);
		engage(giant);
		assertEquals("unhit: no current HP", HpEstimate.UNKNOWN, tick().getHp());
		hit(giant.npc, HitsplatID.DAMAGE_ME, 21);
		giant.bar(22, 30);
		TargetSnapshot s = tick();
		assertEquals(63, HpEstimate.estimate(22, 30, 85));
		assertEquals(64, s.getHp());
		assertEquals("the bar itself is untouched", 22, s.getHpRatio());
		assertEquals(85, s.getMaxHp());
		assertFalse(s.isHpStale());
	}

	@Test
	public void otherPlayersHitsOnTheTargetAreCounted()
	{
		Npc giant = giant85(7);
		engage(giant);
		hit(giant.npc, HitsplatID.DAMAGE_OTHER, 10); // someone else's hit: not our fight, but it is damage
		hit(giant.npc, HitsplatID.DAMAGE_ME, 11);
		giant.bar(22, 30);
		assertEquals(64, tick().getHp());
	}

	@Test
	public void hitsOnAnNpcBeforeItBecomesTheTargetAreCounted()
	{
		Npc giant = giant85(7);
		hit(giant.npc, HitsplatID.DAMAGE_OTHER, 21);
		assertNull("not ours, so no panel", tick());
		engage(giant);
		giant.bar(22, 30);
		assertEquals(64, tick().getHp());
	}

	@Test
	public void poisonVenomDiseaseAndHealsCountToo()
	{
		Npc giant = giant85(7);
		engage(giant);
		hit(giant.npc, HitsplatID.DAMAGE_ME, 20);
		hit(giant.npc, HitsplatID.POISON, 3);
		hit(giant.npc, HitsplatID.VENOM, 1);
		hit(giant.npc, HitsplatID.DISEASE, 1);
		hit(giant.npc, HitsplatID.HEAL, 4);
		hit(giant.npc, HitsplatID.BLOCK_ME, 7); // a block moves nothing
		giant.bar(22, 30);
		assertEquals("20 + 3 + 1 + 1 - 4 = 21 taken", 64, tick().getHp());
	}

	@Test
	public void aMonsterDamagedBeforeFoeSawItShowsTheBarsMidpointNotTheTrackedValue()
	{
		Npc giant = giant85(7);
		engage(giant);
		hit(giant.npc, HitsplatID.DAMAGE_ME, 5); // all Foe saw; 40 more were dealt before
		giant.bar(ratioFor(45, 85, 30), 30);
		assertEquals(HpEstimate.estimate(ratioFor(45, 85, 30), 30, 85), tick().getHp());
		hit(giant.npc, HitsplatID.DAMAGE_ME, 5);
		giant.bar(ratioFor(40, 85, 30), 30);
		assertEquals("and it stays that way until an exact reading",
			HpEstimate.estimate(ratioFor(40, 85, 30), 30, 85), tick().getHp());
	}

	@Test
	public void anExactBarReadingBringsTheTrackingBack()
	{
		Npc giant = giant85(7);
		engage(giant);
		hit(giant.npc, HitsplatID.DAMAGE_ME, 5);
		giant.bar(ratioFor(45, 85, 30), 30);
		assertEquals("rejected: the midpoint is shown", HpEstimate.estimate(ratioFor(45, 85, 30), 30, 85), tick().getHp());
		giant.bar(30, 30); // healed to full: exact
		assertEquals(85, tick().getHp());
		hit(giant.npc, HitsplatID.DAMAGE_ME, 21);
		giant.bar(22, 30);
		assertEquals(64, tick().getHp());
	}

	@Test
	public void aLogoutOrHopStartsTheCountOver()
	{
		Npc giant = giant85(7);
		engage(giant);
		hit(giant.npc, HitsplatID.DAMAGE_ME, 21);
		giant.bar(22, 30);
		assertEquals("counted", 64, tick().getHp());
		gameState(GameState.HOPPING);
		gameState(GameState.LOGGED_IN);
		engage(giant);
		assertEquals("the count was dropped with the world, so the bar alone is believed", 63, tick().getHp());
	}

	@Test
	public void aDeathDropsTheCountOfThatNpcOnly()
	{
		Npc a = giant85(1);
		Npc b = giant85(2);
		hit(a.npc, HitsplatID.DAMAGE_OTHER, 21);
		hit(b.npc, HitsplatID.DAMAGE_OTHER, 21);
		plugin.onActorDeath(new ActorDeath(a.npc));
		a.bar(22, 30);
		b.bar(22, 30);
		engage(a);
		assertEquals("a's count is gone", 63, tick().getHp());
		engage(b);
		assertEquals("b's is not", 64, tick().getHp());
	}

	@Test
	public void aDespawnDropsTheCountOfThatNpcOnly()
	{
		Npc a = giant85(1);
		Npc b = giant85(2);
		hit(a.npc, HitsplatID.DAMAGE_OTHER, 21);
		hit(b.npc, HitsplatID.DAMAGE_OTHER, 21);
		plugin.onNpcDespawned(new NpcDespawned(a.npc));
		a.bar(22, 30);
		b.bar(22, 30);
		engage(a);
		assertEquals(63, tick().getHp());
		engage(b);
		assertEquals(64, tick().getHp());
	}

	@Test
	public void aReusedIndexStartsEmpty()
	{
		Npc old = giant85(7);
		hit(old.npc, HitsplatID.DAMAGE_OTHER, 21);
		plugin.onNpcDespawned(new NpcDespawned(old.npc));
		Npc fresh = giant85(7).bar(22, 30); // the same index, a different object
		engage(fresh);
		assertEquals(63, tick().getHp());
	}

	@Test
	public void stoppingThePluginDropsTheCount()
	{
		Npc giant = giant85(7);
		engage(giant);
		hit(giant.npc, HitsplatID.DAMAGE_ME, 21);
		giant.bar(22, 30);
		assertEquals(64, tick().getHp());
		plugin.stop();
		engage(giant);
		assertEquals(63, tick().getHp());
	}

	@Test
	public void noBarMeansNoCurrentHpWhateverWasCounted()
	{
		Npc giant = giant85(7);
		engage(giant);
		hit(giant.npc, HitsplatID.DAMAGE_ME, 21);
		TargetSnapshot s = tick();
		assertTrue("never seen a bar: unhit, as before", s.isHpUnhit());
		assertEquals(HpEstimate.UNKNOWN, s.getHp());
		giant.bar(22, 30);
		assertEquals("and the first bar finds the count waiting", 64, tick().getHp());
	}

	// A remembered bar and the count agree while nothing happened since; if they do not, the remembered bar wins
	// (it is what "last known" means) and the count is neither lost nor trusted.
	@Test
	public void aRememberedBarShowsTheCountedValueInsideItAndTheRememberedMidpointOtherwise()
	{
		Npc giant = giant85(7).bar(22, 30);
		engage(giant);
		hit(giant.npc, HitsplatID.DAMAGE_ME, 21);
		assertEquals(64, tick().getHp());

		giant.bar(-1, -1);
		TargetSnapshot s = tick();
		assertTrue(s.isHpStale());
		assertEquals("still 64 once the bar has gone, not a drop to the midpoint 63", 64, s.getHp());

		hit(giant.npc, HitsplatID.DAMAGE_OTHER, 28); // while the bar is off the screen: 85 - 21 - 28 = 36 now
		s = tick();
		assertTrue(s.isHpStale());
		assertEquals("36 is not in the remembered 62-64: the remembered midpoint", 63, s.getHp());

		giant.bar(ratioFor(36, 85, 30), 30);
		assertNotEquals("the bar alone would say " + HpEstimate.estimate(ratioFor(36, 85, 30), 30, 85) + ": 36 must come from the count",
			36, HpEstimate.estimate(ratioFor(36, 85, 30), 30, 85));
		s = tick();
		assertFalse(s.isHpStale());
		assertEquals("the count survived the stale stretch", 36, s.getHp());
	}

	@Test
	public void theCountIsJudgedAgainstTheMaxHpThePanelShows()
	{
		// stats say nothing; NPCManager says 85. The same figure must be used for the count's range check.
		npcManagerHealth = 85;
		Npc giant = new Npc(7, "No stats", 53, new int[] {40, 40, 40, 0, 1, 1});
		engage(giant);
		hit(giant.npc, HitsplatID.DAMAGE_ME, 21);
		giant.bar(22, 30);
		TargetSnapshot s = tick();
		assertEquals(85, s.getMaxHp());
		assertEquals(64, s.getHp());

		npcManagerHealth = null; // no max HP at all: nothing to count against
		Npc unknown = new Npc(8, "Unknown", 53, new int[] {40, 40, 40, 0, 1, 1});
		engage(unknown);
		hit(unknown.npc, HitsplatID.DAMAGE_ME, 21);
		unknown.bar(22, 30);
		assertEquals(HpEstimate.UNKNOWN, tick().getHp());
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

	// Task 8 review F5: the "combat NPC" gate on a credit had no test. A level-0 NPC interacting with you (a pet or
	// follower) that is the only one with a spell graphic must not teach its type anything.
	@Test
	public void aNonCombatNpcIsNotCreditedEvenAsTheOnlyCandidate()
	{
		Npc pet = new Npc(5, "Pet", 0, ICE_GIANT).type(2103).attacksYou();
		tick();
		varp(557);
		graphic(pet.npc);
		tick();
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

	// ---- remembered across sessions (spec addendum 5) ----
	//
	// What was learned is saved as one string under one key of the plugin's own config group (WeaknessStore) and
	// loaded again when the plugin starts. WeaknessStoreTest owns the format and WeaknessLearnerTest the credit; these
	// check the wiring and the ordering questions the Task 8 review (F4) raised about stop and start.

	// Review of 77ccc4e, finding 4: a RuneLite profile switch kept the old profile's entries and never loaded the new
	// one's. The switch now reloads on the next tick, on the client thread, before any credit.
	@Test
	public void aProfileSwitchReloadsTheSavedWeaknessesOnTheNextTick()
	{
		learn(scorpion(1), 554);
		storedWeaknesses = "2103:EARTH"; // the new profile's value
		plugin.onProfileChanged(new net.runelite.client.events.ProfileChanged());
		Npc giant = hillGiant(9);
		engage(giant);
		assertNotNull(tick().getWeakness());
		engage(scorpion(2));
		assertNull("the old profile's entry is gone", tick().getWeakness());
	}

	@Test
	public void aCreditIsSavedAtOnceUnderTheTypeNotTheNpcId()
	{
		learn(scorpion(1), 554);
		assertEquals("3025:FIRE", storedWeaknesses);
		assertEquals(1, configWrites.size());
	}

	@Test
	public void theSameCreditAgainWritesNothingMoreAndAChangedOneWritesOver()
	{
		learn(scorpion(1), 554);
		learn(scorpion(2), 554);
		assertEquals("nothing new to save", 1, configWrites.size());
		learn(scorpion(3), 557);
		assertEquals("3025:EARTH", storedWeaknesses);
		assertEquals(2, configWrites.size());
	}

	@Test
	public void aConfirmedNoneIsSavedToo()
	{
		learn(scorpion(1), -1);
		assertEquals("3025:NONE", storedWeaknesses);
	}

	@Test
	public void aDroppedChangeSavesNothing()
	{
		Npc giant = hillGiant(1);
		varp(557); // no impact: never credited
		engage(giant);
		tick();
		assertEquals(0, configWrites.size());
		assertNull(storedWeaknesses);
		assertEquals("and a tick with nothing to credit does not even read the store", 0, configReads);
	}

	@Test
	public void aNewSessionLoadsWhatWasSaved() throws Exception
	{
		learn(scorpion(1), 554);
		learn(hillGiant(2), 557);
		learn(new Npc(3, "Goblin", 2, ICE_GIANT).type(2006), -1);
		assertEquals("2006:NONE,2103:EARTH,3025:FIRE", storedWeaknesses);

		plugin = newPlugin(); // RuneLite starts again: empty memory, same config
		plugin.begin();
		engage(scorpion(10));
		assertEquals(FIRE, shownElement(tick()));
		engage(hillGiant(11));
		assertEquals(EARTH, shownElement(tick()));
		engage(new Npc(12, "Goblin", 2, ICE_GIANT).type(2006));
		assertNull("a loaded NONE shows nothing, and does not throw", tick().getWeakness());
	}

	@Test
	public void theStoreIsReadOnceAtStartNotEveryTick() throws Exception
	{
		storedWeaknesses = "3025:FIRE";
		plugin.begin();
		assertEquals("nothing is read on the Swing thread", 0, configReads);
		engage(scorpion(1));
		tick();
		tick();
		tick();
		assertEquals(1, configReads);
		assertEquals(FIRE, shownElement(plugin.getSnapshot()));
	}

	@Test
	public void aRestartReloadsTheStoreNotTheMemory()
	{
		learn(scorpion(1), 554);
		storedWeaknesses = "2103:EARTH"; // changed behind the plugin's back (a profile switch, a hand edit)
		plugin.stop();
		plugin.begin();
		engage(scorpion(2));
		assertNull("the scorpion is no longer in the store", tick().getWeakness());
		engage(hillGiant(3));
		assertEquals(EARTH, shownElement(tick()));
	}

	@Test
	public void stoppingThePluginKeepsWhatWasSavedAndWritesNothing()
	{
		learn(scorpion(1), 554);
		int writes = configWrites.size();
		plugin.stop();
		assertEquals("3025:FIRE", storedWeaknesses);
		assertEquals("stop() touches no config", writes, configWrites.size());
	}

	// F4 (Task 8 review): stop() runs on the Swing thread and a credit already running on the client thread can land
	// after it. When a learned entry could not survive a restart that was a wrong-state risk; now the entry is meant
	// to survive, so what must hold is that the late credit is kept and erases nothing already stored.
	@Test
	public void aCreditThatLandsAfterStopIsKeptAndErasesNothingStored() throws Exception
	{
		storedWeaknesses = "2103:EARTH";
		plugin.begin();
		tick(); // loads
		plugin.stop();
		learn(scorpion(1), 554); // the in-flight credit
		assertEquals("both, in key order", "2103:EARTH,3025:FIRE", storedWeaknesses);

		plugin = newPlugin();
		plugin.begin();
		engage(hillGiant(2));
		assertEquals(EARTH, shownElement(tick()));
		engage(scorpion(3));
		assertEquals(FIRE, shownElement(tick()));
	}

	// The same store is written by merging one entry into whatever it holds, never by writing out the memory, so even
	// a credit that lands while the memory is empty (just after stop(), or before the first load) cannot overwrite
	// the others with a single-entry copy.
	@Test
	public void aCreditIntoAnEmptyMemoryDoesNotOverwriteTheStore() throws Exception
	{
		storedWeaknesses = "2006:NONE,2103:EARTH";
		plugin = newPlugin(); // nothing loaded yet
		learn(scorpion(1), 554);
		assertEquals("2006:NONE,2103:EARTH,3025:FIRE", storedWeaknesses);
	}

	@Test
	public void aCreditOnTheSameTickAsTheFirstLoadSurvivesTheLoad() throws Exception
	{
		storedWeaknesses = "2103:EARTH";
		plugin = newPlugin();
		plugin.begin();
		Npc s = scorpion(1);
		varp(554);
		engage(s);
		graphic(s.npc);
		tick(); // load, then credit
		assertEquals("2103:EARTH,3025:FIRE", storedWeaknesses);
		assertEquals(FIRE, shownElement(plugin.getSnapshot()));
		engage(hillGiant(2));
		assertEquals("and the loaded one is there too", EARTH, shownElement(tick()));
	}

	// The load comes first on the tick: a credit made on that same tick must be applied to the loaded cache, not
	// replaced by it. With the store unwritable the saved value cannot bring it back, so the order is what keeps it.
	@Test
	public void aCreditOnTheFirstTickSurvivesTheLoadEvenWhenItCannotBeSaved() throws Exception
	{
		storedWeaknesses = "2103:EARTH";
		configWriteFails = true;
		plugin = newPlugin();
		plugin.begin();
		Npc s = scorpion(1);
		varp(554);
		engage(s);
		graphic(s.npc);
		assertEquals(FIRE, shownElement(tick()));
		engage(hillGiant(2));
		assertEquals("and the loaded one", EARTH, shownElement(tick()));
	}

	// begin() is startUp without the overlay and the config cleanup, and it is the second place the fight state is
	// dropped (F4): a hitsplat already running on the client thread can land after stop().
	@Test
	public void aHitThatLandsAfterStopIsNotCountedAfterTheRestart()
	{
		Npc giant = giant85(7);
		engage(giant);
		plugin.stop();
		hit(giant.npc, HitsplatID.DAMAGE_OTHER, 21); // in flight when the plugin stopped
		plugin.begin();
		engage(giant);
		giant.bar(22, 30);
		assertEquals("the count started over at the restart", 63, tick().getHp());
	}

	@Test
	public void aStoredValueThatIsGarbageIsIgnoredNotFatal()
	{
		storedWeaknesses = "garbage,,2103:EARTH,x:y,3025:PLASMA,-4:FIRE";
		plugin.begin();
		engage(hillGiant(1));
		assertEquals(EARTH, shownElement(tick()));
		engage(scorpion(2));
		assertNull(tick().getWeakness());
	}

	@Test
	public void aStoreThatCannotBeReadLeavesThePanelWorkingAndIsNotRetriedEveryTick()
	{
		configReadFails = true;
		plugin.begin();
		engage(hillGiant(1));
		assertNotNull("the panel is drawn", tick());
		tick();
		assertEquals("one attempt, not one per tick", 1, configReads);
		learn(scorpion(2), 554);
		assertEquals("still learns in memory", FIRE, shownElement(plugin.getSnapshot()));
	}

	@Test
	public void aStoreThatCannotBeWrittenDoesNotBreakTheTickOrTheCredit()
	{
		configWriteFails = true;
		Npc s = scorpion(1);
		varp(554);
		engage(s);
		graphic(s.npc);
		TargetSnapshot snap = tick();
		assertNotNull("the tick still produced a snapshot", snap);
		assertEquals("and the credit is in memory for this session", FIRE, shownElement(snap));
		assertNull(storedWeaknesses);
	}

	// ---- the bundled wiki table (spec addenda 7 to 9, plan Task 8c) ----
	//
	// WeaknessTableTest owns the file format and the precedence rule, WeaknessCheckTest the check. These check the
	// wiring: that the table is loaded when the plugin starts (begin() is startUp without the overlay and the config
	// cleanup, see its comment: startUp itself needs OverlayManager and ConfigManager, which are concrete classes that
	// need a live client), that a table which is missing or damaged costs only the table, and that what is logged is
	// what the spec says.

	private static final String TABLE = "# header\n2103\tEARTH\t60\n3025\tFIRE\t50\n2006\tNONE\t\n5000\tWATER\t200\n5001\tAIR\t\n";

	/** What the loader logged about the table at this level. The startup check logs at INFO too, under its own prefix. */
	private java.util.List<String> tableLog(Level level)
	{
		java.util.List<String> out = new java.util.ArrayList<>();
		for (String l : logs.at(level))
		{
			if (l.startsWith("weakness table"))
			{
				out.add(l);
			}
		}
		return out;
	}

	private Npc typed(int index, int compositionId)
	{
		return new Npc(index, "Monster " + index, 28, ICE_GIANT).type(compositionId);
	}

	/** The weakness on the panel after engaging this NPC, or null; fails with a message when there is no panel. */
	private Weakness shown(Npc n)
	{
		engage(n);
		TargetSnapshot s = tick();
		assertNotNull("the panel is drawn", s);
		return s.getWeakness();
	}

	@Test
	public void theTableShowsItsWeaknessWithThePercentBeforeAnySpell()
	{
		tableText = TABLE;
		plugin.begin();
		assertEquals(new Weakness(EARTH, 60), shown(hillGiant(1)));
		assertEquals(new Weakness(FIRE, 50), shown(scorpion(2)));
	}

	@Test
	public void aPercentOverOneHundredAndAnElementOnlyEntryReachThePanelAsGiven()
	{
		tableText = TABLE;
		plugin.begin();
		assertEquals(new Weakness(Weakness.Element.WATER, 200), shown(typed(1, 5000)));
		assertEquals(new Weakness(Weakness.Element.AIR, null), shown(typed(2, 5001)));
	}

	@Test
	public void aTableNoneAndATypeNotInTheTableShowNothing()
	{
		tableText = TABLE;
		plugin.begin();
		assertNull(shown(typed(1, 2006)));
		assertNull(shown(typed(2, 424242)));
	}

	// The key is the transformed composition id, the same one the learned store uses: the NPC's own getId() is the
	// untransformed form, and is deliberately a different number in these fakes (1000 + index vs 2000 + index).
	@Test
	public void theTableIsKeyedByTheCompositionIdNotTheNpcId()
	{
		tableText = "1001\tFIRE\t50\n2001\tWATER\t100\n";
		plugin.begin();
		assertEquals("the composition id 2001, not NPC.getId() 1001", new Weakness(Weakness.Element.WATER, 100), shown(iceGiant(1)));
	}

	@Test
	public void aLearnedElementThatMatchesTheTableShowsTheTablesPercent()
	{
		tableText = TABLE;
		plugin.begin();
		learn(scorpion(1), 554);
		assertEquals(new Weakness(FIRE, 50), plugin.getSnapshot().getWeakness());
		assertEquals("and what is saved is the element alone", "3025:FIRE", storedWeaknesses);
	}

	@Test
	public void aLearnedElementThatDiffersFromTheTableShowsWithoutAPercent()
	{
		tableText = TABLE;
		plugin.begin();
		learn(scorpion(1), 557);
		assertEquals(new Weakness(EARTH, null), plugin.getSnapshot().getWeakness());
		assertEquals("3025:EARTH", storedWeaknesses);
	}

	@Test
	public void aLearnedNoneYieldsToATableWeaknessButIsStillSaved()
	{
		tableText = TABLE;
		plugin.begin();
		learn(scorpion(1), -1);
		assertEquals("addendum 8 F3", new Weakness(FIRE, 50), plugin.getSnapshot().getWeakness());
		assertEquals("the game's none is remembered as before", "3025:NONE", storedWeaknesses);
		learn(typed(2, 2006), -1);
		assertNull("the table says none as well", plugin.getSnapshot().getWeakness());
	}

	@Test
	public void aLoadedLearnedNoneAlsoYieldsToTheTable() throws Exception
	{
		storedWeaknesses = "3025:NONE,2006:NONE";
		tableText = TABLE;
		plugin.begin();
		assertEquals(new Weakness(FIRE, 50), shown(scorpion(1)));
		assertNull(shown(typed(2, 2006)));
	}

	@Test
	public void theTableIsOpenedOncePerStartNotPerTick()
	{
		tableText = TABLE;
		assertEquals("nothing is opened until the plugin starts", 0, tableOpens);
		plugin.begin();
		assertEquals(1, tableOpens);
		engage(hillGiant(1));
		tick();
		tick();
		tick();
		assertEquals(1, tableOpens);
		plugin.stop();
		assertEquals("stopping reads nothing", 1, tableOpens);
		plugin.begin();
		assertEquals("and the next start reads it again", 2, tableOpens);
	}

	@Test
	public void theTableSurvivesLogoutAndHopLikeEverythingElseThatIsStaticData()
	{
		tableText = TABLE;
		plugin.begin();
		gameState(GameState.LOGIN_SCREEN);
		gameState(GameState.LOGGED_IN);
		assertEquals(new Weakness(EARTH, 60), shown(hillGiant(1)));
		assertEquals("and was not read again", 1, tableOpens);
	}

	// ---- startUp survives a table that is not there (addendum 8 F1, F6) ----

	private void assertThePluginStillWorksWithoutATable()
	{
		plugin.begin(); // must not throw
		assertNull("no table weakness", shown(hillGiant(1)));
		learn(scorpion(2), 554);
		assertEquals("in-game learning is untouched", new Weakness(FIRE, null), plugin.getSnapshot().getWeakness());
		engage(hillGiant(3));
		assertNotNull("the panel is drawn", tick());
	}

	@Test
	public void startUpSurvivesATableThatIsMissingFromTheJar()
	{
		tableText = null;
		assertThePluginStillWorksWithoutATable();
		assertEquals("one warning, and it names the table", 1, logs.at(Level.WARN).size());
		assertTrue(logs.at(Level.WARN).get(0), logs.at(Level.WARN).get(0).startsWith("weakness table: "));
		assertTrue("not an info line saying all is well", tableLog(Level.INFO).isEmpty());
	}

	@Test
	public void startUpSurvivesATableThatCannotBeOpened()
	{
		tableStreamFails = true;
		assertThePluginStillWorksWithoutATable();
		assertEquals(1, logs.at(Level.WARN).size());
		assertTrue(logs.at(Level.WARN).get(0), logs.at(Level.WARN).get(0).startsWith("weakness table: "));
	}

	@Test
	public void startUpSurvivesAnEmptyTable()
	{
		tableText = "";
		assertThePluginStillWorksWithoutATable();
		assertEquals("an empty table is a warning, not 'weakness table: 0 entries' at info", 1, logs.at(Level.WARN).size());
		assertTrue(tableLog(Level.INFO).isEmpty());
	}

	@Test
	public void startUpSurvivesATableOfNothingButGarbage()
	{
		tableText = "this is not a table\n\u0000\u0001\u0002\n7\tPLASMA\t50\n";
		assertThePluginStillWorksWithoutATable();
		assertTrue("a warning counts the lines it skipped: " + logs.at(Level.WARN),
			logs.at(Level.WARN).stream().anyMatch(l -> l.contains("skipped 3")));
	}

	@Test
	public void theTextOfABadLineNeverReachesTheLog()
	{
		tableText = "2103\tEARTH\t60\nforged\tINJECTED\tline\n";
		plugin.begin();
		for (String line : logs.all())
		{
			assertFalse(line, line.contains("forged"));
			assertFalse(line, line.contains("INJECTED"));
		}
	}

	@Test
	public void aTableWithSomeBadLinesKeepsTheGoodOnesAndSaysHowManyWereSkipped()
	{
		tableText = "2103\tEARTH\t60\nbad\n3025\tFIRE\t50\n7\tPLASMA\t1\n";
		plugin.begin();
		assertEquals(new Weakness(EARTH, 60), shown(hillGiant(1)));
		assertEquals(new Weakness(FIRE, 50), shown(scorpion(2)));
		assertEquals(java.util.Collections.singletonList("weakness table: 2 entries"), tableLog(Level.INFO));
		assertEquals(1, logs.at(Level.WARN).size());
		assertTrue(logs.at(Level.WARN).get(0), logs.at(Level.WARN).get(0).contains("skipped 2"));
	}

	@Test
	public void aTableThatLoadsLogsItsEntryCountAtInfoAndNothingAtWarn()
	{
		tableText = TABLE;
		plugin.begin();
		assertEquals(java.util.Collections.singletonList("weakness table: 5 entries"), tableLog(Level.INFO));
		assertTrue(logs.at(Level.WARN).isEmpty());
	}

	// ---- the real resource, read the way the plugin reads it ----

	@Test
	public void theShippedTableGivesAFireGiantItsWaterWeaknessBeforeAnySpell()
	{
		useRealTable = true;
		plugin.begin();
		assertEquals("Fire giant", new Weakness(Weakness.Element.WATER, 100), shown(typed(1, 2075)));
		assertEquals("Kraken", new Weakness(Weakness.Element.EARTH, 50), shown(typed(2, 494)));
		assertNull("Whirlpool: none", shown(typed(3, 496)));
		assertEquals("Spiritual mage (Zaros): 200 reaches the panel", new Weakness(FIRE, 200), shown(typed(4, 11292)));
		assertEquals("Maggot King: element only", new Weakness(FIRE, null), shown(typed(5, 15742)));
		assertEquals(1, tableLog(Level.INFO).size());
		assertTrue(tableLog(Level.INFO).get(0), tableLog(Level.INFO).get(0).matches("weakness table: 1[0-9]{3} entries"));
		assertTrue("nothing wrong with the real file", logs.at(Level.WARN).isEmpty());
	}

	@Test
	public void theDefaultSeamReadsTheJarResource() throws Exception
	{
		try (InputStream in = new FoePlugin().weaknessTableStream())
		{
			assertNotNull("getResourceAsStream found the table on the classpath", in);
			assertTrue(in.read() > 0);
		}
	}

	// ---- the learned-vs-table check (spec addendum 11) ----

	private static final String STORED = "99:AIR,2006:NONE,2103:FIRE,3025:FIRE";

	@Test
	public void theCheckIsLoggedOnceAfterBothHaveLoadedWithItsDisagreements()
	{
		storedWeaknesses = STORED;
		tableText = TABLE;
		plugin.begin();
		assertTrue("nothing is read or logged on the Swing thread", logs.all().stream().noneMatch(l -> l.startsWith("weakness check")));
		tick();
		assertEquals(java.util.Arrays.asList(
			"weakness check: 4 learned, 2 agree with the table, 1 disagree",
			"weakness check: 2103 learned=FIRE table=EARTH 60"), checkLines());
		assertTrue(logs.at(Level.WARN).isEmpty());
	}

	private java.util.List<String> checkLines()
	{
		java.util.List<String> out = new java.util.ArrayList<>();
		for (String l : logs.at(Level.INFO))
		{
			if (l.startsWith("weakness check"))
			{
				out.add(l);
			}
		}
		return out;
	}

	@Test
	public void aLearnedNoneAgainstATableWeaknessIsListed()
	{
		storedWeaknesses = "3025:NONE";
		tableText = TABLE;
		plugin.begin();
		tick();
		assertEquals(java.util.Arrays.asList(
			"weakness check: 1 learned, 0 agree with the table, 1 disagree",
			"weakness check: 3025 learned=NONE table=FIRE 50"), checkLines());
	}

	@Test
	public void theCheckIsLoggedOnceNotEveryTick()
	{
		storedWeaknesses = STORED;
		tableText = TABLE;
		plugin.begin();
		tick();
		int first = checkLines().size();
		assertTrue(first > 0);
		tick();
		tick();
		assertEquals(first, checkLines().size());
	}

	@Test
	public void aProfileSwitchReloadsTheStoreButIsNotAStartupSoItIsNotChecked()
	{
		storedWeaknesses = STORED;
		tableText = TABLE;
		plugin.begin();
		tick();
		int first = checkLines().size();
		plugin.onProfileChanged(new net.runelite.client.events.ProfileChanged());
		tick();
		assertEquals(first, checkLines().size());
	}

	@Test
	public void aRestartChecksAgain()
	{
		storedWeaknesses = STORED;
		tableText = TABLE;
		plugin.begin();
		tick();
		logs.clear();
		plugin.stop();
		plugin.begin();
		tick();
		assertEquals(2, checkLines().size());
	}

	@Test
	public void theCheckSeesTheTableEvenWhenTheFirstTickAlsoCredits() throws Exception
	{
		storedWeaknesses = "2103:FIRE";
		tableText = TABLE;
		plugin.begin();
		Npc s = scorpion(1);
		varp(554);
		engage(s);
		graphic(s.npc);
		tick(); // load, check, then credit
		assertEquals("weakness check: 1 learned, 0 agree with the table, 1 disagree", checkLines().get(0));
	}

	@Test
	public void aStoreThatCannotBeReadLogsNoCheckAndDoesNotRetryIt()
	{
		configReadFails = true;
		tableText = TABLE;
		plugin.begin();
		tick();
		tick();
		assertTrue(checkLines().isEmpty());
		assertFalse("the failure is the one warning the store already logs", logs.at(Level.WARN).isEmpty());
	}

	@Test
	public void withNoTableTheCheckStillRunsAndFindsNothingToCompare()
	{
		storedWeaknesses = STORED;
		tableText = null;
		plugin.begin();
		tick();
		assertEquals("weakness check: 4 learned, 0 agree with the table, 0 disagree", checkLines().get(0));
		assertEquals(1, checkLines().size());
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
