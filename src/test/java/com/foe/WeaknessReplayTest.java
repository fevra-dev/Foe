package com.foe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.foe.WeaknessLearner.Outcome;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * Replays the real trace of the impact-graphic probe (docs/probe/raw-task8.txt, RuneLite 1.13.1, Taverley, 2026-10-08)
 * through a WeaknessLearner, one GameTick per tick label, the way FoePlugin drives it. The trace holds every varp
 * 5536 change and every spot-anim event (GFX) with the NPC's index, its transformed composition id (tid) and the
 * player's held target ({@code myTarget}) at that moment; it does not hold the player's interacting changes, so
 * "fighting" is taken from {@code myTarget}.
 *
 * <p>What the trace contains, and so what is asserted: three varp changes that each come with an impact on the
 * player's target (ticks 508, 738, 770), the logout reset 0, 0, -1 (745) and the login-time 0, -1 (0) with no
 * target, and 62 other impacts: other players' spells on Greater Nechryaels and Death spawn (which never touched the
 * varp) and the player's own repeated Snares on a Hill Giant, whose value did not change and so posted nothing.
 *
 * <p>The file is read from the project directory, which is Gradle's working directory for tests. If it is missing
 * the tests fail: a replay that cannot find its trace has checked nothing.
 */
public class WeaknessReplayTest
{
	private static final int HILL_GIANT = 2103;
	private static final int POISON_SCORPION = 3025;

	private static final Pattern VARP = Pattern.compile("tick=(\\d+) VARP5536=(-?\\d+) \\|");
	private static final Pattern GFX = Pattern.compile(
		"tick=(\\d+) GFX on .+? id=\\d+ tid=(\\d+) idx=(\\d+) spotanims=\\[[^\\]]*\\] \\| myTarget=(?:null|.+? idx=(\\d+))");

	/** One NPC of the trace, kept across ticks like the client's own object. */
	private static final class Npc
	{
		final int idx;
		final int tid;
		/** idx of the player's held target at this NPC's latest impact event; -1 for none. */
		int targetIdx = -1;

		Npc(int idx, int tid)
		{
			this.idx = idx;
			this.tid = tid;
		}

		boolean fought()
		{
			return targetIdx == idx;
		}
	}

	private static final class Tick
	{
		final List<Integer> varps = new ArrayList<>();
		final List<Npc> impacts = new ArrayList<>();
	}

	private static final class Replay
	{
		final Map<Integer, Outcome> outcomes = new TreeMap<>();
		final WeaknessLearner<Npc> learner = new WeaknessLearner<>();
		int varpLines;
		int gfxLines;
	}

	/**
	 * @param varpShift  moves every varp change this many ticks later (0 = as logged)
	 * @param withVarp   false drops every varp line
	 * @param withGfx    false drops every spot-anim line
	 */
	private static Replay replay(int varpShift, boolean withVarp, boolean withGfx) throws IOException
	{
		Replay r = new Replay();
		Map<Integer, Npc> world = new HashMap<>();
		Map<Integer, Tick> ticks = new TreeMap<>();
		for (String line : Files.readAllLines(Paths.get("docs/probe/raw-task8.txt"), StandardCharsets.UTF_8))
		{
			Matcher m;
			if ((m = VARP.matcher(line)).find())
			{
				r.varpLines++;
				if (withVarp)
				{
					ticks.computeIfAbsent(Integer.parseInt(m.group(1)) + varpShift, t -> new Tick())
						.varps.add(Integer.parseInt(m.group(2)));
				}
			}
			else if ((m = GFX.matcher(line)).find())
			{
				r.gfxLines++;
				if (withGfx)
				{
					int idx = Integer.parseInt(m.group(3));
					int tid = Integer.parseInt(m.group(2));
					Npc npc = world.computeIfAbsent(idx, i -> new Npc(i, tid));
					npc.targetIdx = m.group(4) == null ? -1 : Integer.parseInt(m.group(4));
					ticks.computeIfAbsent(Integer.parseInt(m.group(1)), t -> new Tick()).impacts.add(npc);
				}
			}
		}
		for (Map.Entry<Integer, Tick> e : ticks.entrySet())
		{
			for (int v : e.getValue().varps)
			{
				r.learner.varpChanged(v);
			}
			for (Npc npc : e.getValue().impacts)
			{
				r.learner.impact(npc);
			}
			r.outcomes.put(e.getKey(), r.learner.tick(Npc::fought, npc -> npc.tid));
		}
		return r;
	}

	private static long count(Replay r, Outcome o)
	{
		return r.outcomes.values().stream().filter(x -> x == o).count();
	}

	@Test
	public void theTraceHasWhatThisTestAssumes()
	{
		// the denominators: a parser that matched nothing would make every test below vacuous
		Replay r;
		try
		{
			r = replay(0, true, true);
		}
		catch (IOException e)
		{
			throw new AssertionError(e);
		}
		assertEquals("varp lines: 0, -1 at tick 0; 557, 554, 0, 0, -1, 554", 8, r.varpLines);
		assertEquals("spot-anim events", 65, r.gfxLines);
	}

	@Test
	public void exactlyTheThreeConfirmedImpactsAreCredited() throws IOException
	{
		Replay r = replay(0, true, true);
		assertEquals(Outcome.CREDITED, r.outcomes.get(508));
		assertEquals(Outcome.CREDITED, r.outcomes.get(738));
		assertEquals(Outcome.CREDITED, r.outcomes.get(770));
		assertEquals(3, count(r, Outcome.CREDITED));
		assertEquals(Weakness.Element.EARTH, r.learner.weaknessFor(HILL_GIANT).getElement());
		assertEquals("the second scorpion (idx 21978) is the same type as the first (21975)",
			Weakness.Element.FIRE, r.learner.weaknessFor(POISON_SCORPION).getElement());
	}

	@Test
	public void nothingElseInTheTraceIsLearned() throws IOException
	{
		Replay r = replay(0, true, true);
		// the other players' spells (Greater Nechryael 7278, Death spawn 10), the baby blue dragon, the blue dragon
		for (int type : new int[] {7278, 10, 242, 265, 267, 291, 11952, 0, -1})
		{
			assertNull("type " + type, r.learner.weaknessFor(type));
		}
	}

	@Test
	public void theLoginAndLogoutResetsAreDroppedAndNeverCredited() throws IOException
	{
		Replay r = replay(0, true, true);
		assertEquals("tick 0: 0 then -1, no target", Outcome.CONFLICTING_CHANGES, r.outcomes.get(0));
		assertEquals("tick 745, the logout: 0, 0, -1", Outcome.CONFLICTING_CHANGES, r.outcomes.get(745));
		assertNotNull("and the entry learned before the logout survives it", r.learner.weaknessFor(POISON_SCORPION));
	}

	@Test
	public void repeatedSnaresThatLeftTheValueUnchangedPostNothingAndChangeNothing() throws IOException
	{
		// ticks 538 (a splash, spot-anim 85), 575, 659 and 702: impacts on the fought Hill Giant, no varp event
		Replay r = replay(0, true, true);
		for (int tick : new int[] {538, 575, 659, 702})
		{
			assertEquals("tick " + tick, Outcome.NO_CHANGE, r.outcomes.get(tick));
		}
		assertEquals(Weakness.Element.EARTH, r.learner.weaknessFor(HILL_GIANT).getElement());
	}

	// Controls, in the same class as the real run: the credits above must depend on both halves of the evidence.

	@Test
	public void withoutTheSpotAnimsTheSameChangesAreAllDropped() throws IOException
	{
		Replay r = replay(0, true, false);
		assertEquals(0, count(r, Outcome.CREDITED));
		assertEquals(Outcome.NO_CANDIDATE, r.outcomes.get(508));
		assertEquals(Outcome.NO_CANDIDATE, r.outcomes.get(738));
		assertEquals(Outcome.NO_CANDIDATE, r.outcomes.get(770));
		assertNull(r.learner.weaknessFor(HILL_GIANT));
	}

	@Test
	public void withoutTheVarpChangesTheSameSpotAnimsLearnNothing() throws IOException
	{
		Replay r = replay(0, false, true);
		assertEquals(0, count(r, Outcome.CREDITED));
		assertEquals(Outcome.NO_CHANGE, r.outcomes.get(508));
		assertNull(r.learner.weaknessFor(HILL_GIANT));
	}

	@Test
	public void aChangeDelayedByOneTickFindsNoImpactAndIsNotCarriedBack() throws IOException
	{
		Replay r = replay(1, true, true);
		assertEquals(0, count(r, Outcome.CREDITED));
		assertEquals(Outcome.NO_CANDIDATE, r.outcomes.get(509));
		assertEquals("the impact tick itself saw no change", Outcome.NO_CHANGE, r.outcomes.get(508));
		assertNull(r.learner.weaknessFor(HILL_GIANT));
		assertNull(r.learner.weaknessFor(POISON_SCORPION));
	}
}
