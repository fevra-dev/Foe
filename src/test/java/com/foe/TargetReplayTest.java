package com.foe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.GameState;
import org.junit.Test;

/**
 * Replays the real in-game trace (docs/probe/raw.log, taken with RuneLite 1.13.1 on 2026-10-03) through a
 * TargetFeed, driving it the way FoePlugin does: each event at its tick, then one GameTick. The log holds your
 * own interacting changes and the hitsplats on NPCs; it does not hold hits on you or deaths, so those are covered
 * by TargetFeedTest only.
 *
 * <p>The clock is tick * 600 ms. Events land 10 ms into their tick and GameTick 50 ms in, because in the real
 * client the events precede the tick by some milliseconds and linger 0 must survive that.
 *
 * <p>The file is read from the project directory, which is Gradle's working directory for tests. If it is
 * missing the tests fail: a replay that cannot find its trace has checked nothing.
 */
public class TargetReplayTest
{
	private static final long TEN_SECONDS = 10_000;
	private static final int NONE = -1;

	private static final Pattern TARGETS = Pattern.compile("tick=(\\d+) player now targets (.+?) \\(id \\d+, idx (\\d+)\\)");
	private static final Pattern NO_TARGET = Pattern.compile("tick=(\\d+) player now targets no NPC");
	private static final Pattern HITSPLAT = Pattern.compile("tick=(\\d+) hitsplat on .+? \\(id \\d+, idx (\\d+)\\) mine=(true|false)");
	private static final Pattern STATE = Pattern.compile("tick=(\\d+) gamestate ([A-Z_]+)");

	private enum Kind
	{
		ENGAGE, UNTARGET, MY_HIT, OTHERS_HIT, STATE
	}

	private static final class Event
	{
		final int tick;
		final Kind kind;
		final int idx;
		final GameState state;

		Event(int tick, Kind kind, int idx, GameState state)
		{
			this.tick = tick;
			this.kind = kind;
			this.idx = idx;
			this.state = state;
		}
	}

	private static final class Npc
	{
		final int index;

		Npc(int index)
		{
			this.index = index;
		}
	}

	/** The log, parsed once. */
	private static List<Event> events;

	private static List<Event> events() throws IOException
	{
		if (events == null)
		{
			List<Event> out = new ArrayList<>();
			for (String line : Files.readAllLines(Paths.get("docs/probe/raw.log"), StandardCharsets.UTF_8))
			{
				Matcher m;
				if ((m = TARGETS.matcher(line)).find())
				{
					out.add(new Event(Integer.parseInt(m.group(1)), Kind.ENGAGE, Integer.parseInt(m.group(3)), null));
				}
				else if ((m = NO_TARGET.matcher(line)).find())
				{
					out.add(new Event(Integer.parseInt(m.group(1)), Kind.UNTARGET, NONE, null));
				}
				else if ((m = HITSPLAT.matcher(line)).find())
				{
					out.add(new Event(Integer.parseInt(m.group(1)),
						m.group(3).equals("true") ? Kind.MY_HIT : Kind.OTHERS_HIT, Integer.parseInt(m.group(2)), null));
				}
				else if ((m = STATE.matcher(line)).find())
				{
					out.add(new Event(Integer.parseInt(m.group(1)), Kind.STATE, NONE, GameState.valueOf(m.group(2))));
				}
			}
			events = out;
		}
		return events;
	}

	private static long count(List<Event> all, Kind kind)
	{
		return all.stream().filter(e -> e.kind == kind).count();
	}

	/** The index shown on every tick of the log, or NONE. */
	private static int[] replay(long lingerMs) throws IOException
	{
		List<Event> all = events();
		int last = all.get(all.size() - 1).tick;
		int[] shown = new int[last + 1];

		TargetFeed<Npc> feed = new TargetFeed<>(n -> n.index);
		Map<Integer, Npc> npcs = new HashMap<>();
		Npc[] interacting = {null};
		Predicate<Npc> fighting = n -> interacting[0] == n;

		int next = 0;
		for (int tick = 0; tick <= last; tick++)
		{
			long eventMs = tick * 600L + 10;
			while (next < all.size() && all.get(next).tick == tick)
			{
				Event e = all.get(next++);
				Npc npc = e.idx == NONE ? null : npcs.computeIfAbsent(e.idx, Npc::new);
				switch (e.kind)
				{
					case ENGAGE:
						interacting[0] = npc;
						feed.playerEngaged(npc, true, eventMs++);
						break;
					case UNTARGET:
						interacting[0] = null;
						break;
					case MY_HIT:
						feed.playerHit(npc, true, eventMs++, lingerMs, fighting);
						break;
					case STATE:
						if (TargetFeed.endsTheFight(e.state))
						{
							feed.reset();
							interacting[0] = null;
						}
						break;
					default:
						// A hit by someone else on an NPC is nobody's evidence of OUR fight.
						break;
				}
			}
			Npc live = feed.tick(tick * 600L + 50, lingerMs, fighting);
			shown[tick] = live == null ? NONE : live.index;
		}
		return shown;
	}

	// The denominators: what the replay was fed. Counts from `grep -c` on the same file (mine=true 213,
	// mine=false 248, 127 "player now targets" of which 97 "no NPC").
	@Test
	public void theWholeLogWasReadAndNothingElseWasCounted() throws IOException
	{
		List<Event> all = events();
		assertEquals(213, count(all, Kind.MY_HIT));
		assertEquals(248, count(all, Kind.OTHERS_HIT));
		assertEquals(97, count(all, Kind.UNTARGET));
		assertEquals(127, count(all, Kind.UNTARGET) + count(all, Kind.ENGAGE));
		assertTrue(count(all, Kind.STATE) > 0);
	}

	// raw.log lines 264-268. Tick 704 targets 22009, tick 707 switches to 22005, and at tick 709 a hit that was
	// already in flight lands on 22009, then at 710 the player targets 22009 again. The panel must not move at 709.
	@Test
	public void aHitInFlightDoesNotMovePanelAtTenSecondsLinger() throws IOException
	{
		int[] shown = replay(TEN_SECONDS);
		assertEquals(22009, shown[706]);
		assertEquals(22005, shown[707]);
		assertEquals(22005, shown[708]);
		assertEquals("the in-flight hit on the old NPC must not take the panel", 22005, shown[709]);
		assertEquals(22009, shown[710]);
	}

	@Test
	public void aHitInFlightDoesNotMovePanelAtLingerZeroEither() throws IOException
	{
		int[] shown = replay(0);
		assertEquals(22005, shown[707]);
		assertEquals(22005, shown[708]);
		assertEquals("the player is still interacting with 22005, so a hit elsewhere must not take it",
			22005, shown[709]);
		assertEquals(22009, shown[710]);
	}

	// Ticks 704-737: the player's target flips to "no NPC" at 715 and 731 and back at 718 and 733. With a 10 s
	// linger the panel must never blink.
	@Test
	public void theInteractingFlipsNeverBlinkTheTenSecondPanel() throws IOException
	{
		int[] shown = replay(TEN_SECONDS);
		for (int t = 704; t <= 737; t++)
		{
			assertTrue("blank at tick " + t, shown[t] != NONE);
		}
	}

	// Linger 0 is defined as "shown only on ticks with evidence of combat" (plan amendment 6): the flip at 715
	// blanks it, your hit at 716 brings it back, the quiet tick 717 blanks it, and the re-target at 718 shows it.
	@Test
	public void atLingerZeroOnlyTicksWithEvidenceShow() throws IOException
	{
		int[] shown = replay(0);
		assertEquals(22009, shown[714]);
		assertEquals(NONE, shown[715]);
		assertEquals(22009, shown[716]);
		assertEquals(NONE, shown[717]);
		assertEquals(22009, shown[718]);
		assertEquals(22009, shown[719]);
	}

	@Test
	public void everyTickWhereYouLandAHitShowsATarget() throws IOException
	{
		for (long linger : new long[] {0, TEN_SECONDS})
		{
			int[] shown = replay(linger);
			int checked = 0;
			for (Event e : events())
			{
				if (e.kind == Kind.MY_HIT)
				{
					assertTrue("blank on a hit tick " + e.tick + " at linger " + linger, shown[e.tick] != NONE);
					checked++;
				}
			}
			assertEquals(213, checked);
		}
	}

	// Last own hit of that Hill Giant fight is tick 1913 (the GameTick of that tick is what counts). The interacting
	// flip at 1914 gives no evidence, so the panel lasts the 10 s: 16 ticks of 600 ms = 9.6 s still shown, 17 = 10.2 s not.
	@Test
	public void tenSecondsAfterTheLastHitThePanelIsGone() throws IOException
	{
		int[] shown = replay(TEN_SECONDS);
		assertEquals(21968, shown[1913]);
		assertEquals(21968, shown[1929]);
		assertEquals(NONE, shown[1930]);
	}

	// A target change must never leave a panel for an NPC the player is not fighting: at every tick, what is shown
	// is the NPC of the latest own engagement or hit, or one the player is currently interacting with.
	@Test
	public void theShownNpcIsAlwaysOneYouEngagedOrHit() throws IOException
	{
		int[] shown = replay(TEN_SECONDS);
		Set<Integer> involved = new HashSet<>();
		int next = 0;
		List<Event> all = events();
		for (int t = 0; t < shown.length; t++)
		{
			while (next < all.size() && all.get(next).tick == t)
			{
				Event e = all.get(next++);
				if (e.kind == Kind.ENGAGE || e.kind == Kind.MY_HIT)
				{
					involved.add(e.idx);
				}
			}
			if (shown[t] != NONE)
			{
				assertTrue("tick " + t + " shows " + shown[t] + ", which was never engaged or hit", involved.contains(shown[t]));
			}
		}
	}
}
