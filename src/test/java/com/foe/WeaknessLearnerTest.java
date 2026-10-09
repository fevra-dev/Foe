package com.foe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.foe.WeaknessLearner.Outcome;
import org.junit.Before;
import org.junit.Test;

/**
 * The attribution rule (plan Task 8, Revision 2026-10-08), on plain objects. A varp 5536 change is credited to a
 * monster type only when, in the same tick, exactly one NPC that the player is fighting got a new spot-anim.
 * Everything else is dropped and never guesses, and a dropped change never writes or erases anything.
 *
 * <p>Every test asserts the {@link Outcome} as well as the cache, because "nothing was learned" is also what a
 * learner that is simply broken produces: the reason has to be the one the test is about. The sequences from the
 * real trace are in WeaknessReplayTest.
 */
public class WeaknessLearnerTest
{
	private static final int HILL_GIANT = 2103;
	private static final int SCORPION = 3025;

	/** An NPC, as far as the learner cares: whether the player is fighting it, and which type credit would go to. */
	private static final class Mob
	{
		final int type;
		final boolean fought;
		final boolean creditable;

		Mob(int type, boolean fought, boolean creditable)
		{
			this.type = type;
			this.fought = fought;
			this.creditable = creditable;
		}
	}

	private WeaknessLearner<Mob> learner;
	/** Every (type, weakness) the learner said it had just learned, in order. */
	private final java.util.List<String> learned = new java.util.ArrayList<>();

	private static Mob fought(int type)
	{
		return new Mob(type, true, true);
	}

	private static Mob bystander(int type)
	{
		return new Mob(type, false, true);
	}

	@Before
	public void setUp()
	{
		learner = new WeaknessLearner<>((key, w) -> learned.add(key + ":" + (w.getElement() == null ? "NONE" : w.getElement())));
	}

	private Outcome tick()
	{
		return learner.tick(m -> m.fought, m -> m.creditable ? m.type : null);
	}

	private void assertElement(Weakness.Element expected, int type)
	{
		Weakness w = learner.weaknessFor(type);
		assertNotNull("type " + type, w);
		assertEquals(expected, w.getElement());
	}

	// ---- the confirmed case ----

	@Test
	public void aChangeWithExactlyOneFoughtImpactIsCreditedToThatNpcsType()
	{
		learner.varpChanged(557);
		learner.impact(fought(HILL_GIANT));
		assertEquals(Outcome.CREDITED, tick());
		assertElement(Weakness.Element.EARTH, HILL_GIANT);
		assertNull("only the type that was hit", learner.weaknessFor(SCORPION));
	}

	@Test
	public void theOrderOfTheChangeAndTheImpactWithinTheTickDoesNotMatter()
	{
		// the trace has the change first (docs/probe/varp-5536.md, consequence 4); a client that reversed it must work
		learner.impact(fought(SCORPION));
		learner.varpChanged(554);
		assertEquals(Outcome.CREDITED, tick());
		assertElement(Weakness.Element.FIRE, SCORPION);
	}

	@Test
	public void theElementComesFromTheValueNotFromTheNpc()
	{
		for (int[] row : new int[][] {{554, 0}, {555, 1}, {556, 2}, {557, 3}})
		{
			Weakness.Element expected = new Weakness.Element[] {Weakness.Element.FIRE, Weakness.Element.WATER,
				Weakness.Element.AIR, Weakness.Element.EARTH}[row[1]];
			learner.varpChanged(row[0]);
			learner.impact(fought(100 + row[1]));
			assertEquals(Outcome.CREDITED, tick());
			assertElement(expected, 100 + row[1]);
		}
	}

	@Test
	public void aConfirmedNoneIsLearnedAsNoWeaknessAndErasesAnEarlierEntry()
	{
		learner.varpChanged(554);
		learner.impact(fought(SCORPION));
		tick();
		assertElement(Weakness.Element.FIRE, SCORPION);

		learner.varpChanged(-1);
		learner.impact(fought(SCORPION));
		assertEquals(Outcome.CREDITED, tick());
		assertNull("-1 is a confirmed answer: nothing is shown, and the old entry is gone", learner.weaknessFor(SCORPION));
	}

	@Test
	public void aConfirmedCreditCanCorrectAnEarlierEntry()
	{
		learner.varpChanged(554);
		learner.impact(fought(SCORPION));
		tick();
		learner.varpChanged(555);
		learner.impact(fought(SCORPION));
		assertEquals(Outcome.CREDITED, tick());
		assertElement(Weakness.Element.WATER, SCORPION);
	}

	@Test
	public void entriesAreKeyedByTypeSoANewNpcOfTheSameTypeSharesThem()
	{
		learner.varpChanged(554);
		learner.impact(fought(SCORPION));
		tick();
		// a different object of the same type (the trace's second scorpion, idx 21978 after 21975) reads the same entry
		assertElement(Weakness.Element.FIRE, SCORPION);
	}

	// ---- dropped: the change is not confirmed ----

	@Test
	public void aChangeWithNoImpactAtAllIsDropped()
	{
		learner.varpChanged(557);
		assertEquals(Outcome.NO_CANDIDATE, tick());
		assertNull(learner.weaknessFor(HILL_GIANT));
	}

	@Test
	public void impactsWithoutAChangeLearnNothing()
	{
		// other players' spells land on nearby NPCs all day and never touched our varp (trace, ticks 0-35)
		learner.impact(fought(HILL_GIANT));
		assertEquals(Outcome.NO_CHANGE, tick());
		assertNull(learner.weaknessFor(HILL_GIANT));
	}

	@Test
	public void twoFoughtImpactsAreDroppedBecauseNeitherCanBeNamed()
	{
		// an AoE spell: the varp holds one NPC's weakness, and the client does not say whose
		learner.varpChanged(557);
		learner.impact(fought(HILL_GIANT));
		learner.impact(fought(SCORPION));
		assertEquals(Outcome.SEVERAL_CANDIDATES, tick());
		assertNull(learner.weaknessFor(HILL_GIANT));
		assertNull(learner.weaknessFor(SCORPION));
	}

	@Test
	public void anImpactOnAnNpcThePlayerIsNotFightingIsNotACandidate()
	{
		learner.varpChanged(557);
		learner.impact(bystander(SCORPION)); // someone else's spell, or a cast at a target we have since left
		assertEquals(Outcome.NO_CANDIDATE, tick());
		assertNull(learner.weaknessFor(SCORPION));
	}

	@Test
	public void aBystanderImpactDoesNotBlockTheCreditOfTheOneFoughtImpact()
	{
		learner.varpChanged(557);
		learner.impact(bystander(SCORPION));
		learner.impact(fought(HILL_GIANT));
		assertEquals(Outcome.CREDITED, tick());
		assertElement(Weakness.Element.EARTH, HILL_GIANT);
		assertNull("the bystander is not credited", learner.weaknessFor(SCORPION));
	}

	// Task 8 review F1: an AoE that hits the one fought NPC and an unfought one. The varp may hold either's
	// weakness (never probed), so the same spell graphic on a bystander drops the credit.
	@Test
	public void theSameSpellGraphicOnABystanderMakesItAnAoeAndDropsTheCredit()
	{
		learner.varpChanged(554);
		learner.impact(bystander(SCORPION), 363);
		learner.impact(fought(HILL_GIANT), 363);
		assertEquals(Outcome.AOE_BYSTANDER, tick());
		assertNull(learner.weaknessFor(HILL_GIANT));
	}

	@Test
	public void anotherPlayersDifferentSpellOnABystanderStillLetsTheCreditThrough()
	{
		learner.varpChanged(557);
		learner.impact(bystander(SCORPION), 369);
		learner.impact(fought(HILL_GIANT), 180);
		assertEquals(Outcome.CREDITED, tick());
		assertElement(Weakness.Element.EARTH, HILL_GIANT);
	}

	@Test
	public void theSameNpcImpactedTwiceInOneTickIsOneCandidate()
	{
		Mob giant = fought(HILL_GIANT);
		learner.varpChanged(557);
		learner.impact(giant);
		learner.impact(giant); // a second spot-anim event on the same object
		assertEquals(Outcome.CREDITED, tick());
		assertElement(Weakness.Element.EARTH, HILL_GIANT);
	}

	@Test
	public void theSoleCandidateMustBeCreditable()
	{
		learner.varpChanged(557);
		learner.impact(new Mob(HILL_GIANT, true, false)); // dying, not a combat NPC, or no composition
		assertEquals(Outcome.NOT_CREDITABLE, tick());
		assertNull(learner.weaknessFor(HILL_GIANT));
	}

	@Test
	public void aValueThatIsNotARuneNorMinusOneIsDroppedEvenWithAConfirmedImpact()
	{
		// 0 is what a logout resets to (probe); 558 is some other item
		for (int value : new int[] {0, 558, 5536})
		{
			learner.varpChanged(value);
			learner.impact(fought(HILL_GIANT));
			assertEquals("value " + value, Outcome.UNKNOWN_VALUE, tick());
			assertNull("value " + value, learner.weaknessFor(HILL_GIANT));
		}
	}

	@Test
	public void twoDifferentValuesInOneTickAreDroppedBecauseTheFinalOneIsNotWhatTheImpactWrote()
	{
		learner.varpChanged(557);
		learner.varpChanged(-1);
		learner.impact(fought(HILL_GIANT));
		assertEquals(Outcome.CONFLICTING_CHANGES, tick());
		assertNull(learner.weaknessFor(HILL_GIANT));
	}

	@Test
	public void theLoginResetIsNeverCreditedEvenWithAFoughtImpactInTheSameTick()
	{
		// the trace's tick 745: 0, 0, -1
		learner.varpChanged(0);
		learner.varpChanged(0);
		learner.varpChanged(-1);
		learner.impact(fought(SCORPION));
		assertEquals(Outcome.CONFLICTING_CHANGES, tick());
		assertNull(learner.weaknessFor(SCORPION));
	}

	@Test
	public void theSameValueTwiceInOneTickIsOneChange()
	{
		learner.varpChanged(554);
		learner.varpChanged(554);
		learner.impact(fought(SCORPION));
		assertEquals(Outcome.CREDITED, tick());
		assertElement(Weakness.Element.FIRE, SCORPION);
	}

	// ---- nothing unconfirmed writes or erases ----

	@Test
	public void noUnconfirmedChangeOverwritesOrErasesAGoodEntry()
	{
		learner.varpChanged(554);
		learner.impact(fought(SCORPION));
		assertEquals(Outcome.CREDITED, tick());

		// no impact
		learner.varpChanged(-1);
		assertEquals(Outcome.NO_CANDIDATE, tick());
		assertElement(Weakness.Element.FIRE, SCORPION);

		// AoE
		learner.varpChanged(555);
		learner.impact(fought(SCORPION));
		learner.impact(fought(HILL_GIANT));
		assertEquals(Outcome.SEVERAL_CANDIDATES, tick());
		assertElement(Weakness.Element.FIRE, SCORPION);

		// unknown value, one confirmed impact
		learner.varpChanged(0);
		learner.impact(fought(SCORPION));
		assertEquals(Outcome.UNKNOWN_VALUE, tick());
		assertElement(Weakness.Element.FIRE, SCORPION);

		// a conflicting pair
		learner.varpChanged(-1);
		learner.varpChanged(555);
		learner.impact(fought(SCORPION));
		assertEquals(Outcome.CONFLICTING_CHANGES, tick());
		assertElement(Weakness.Element.FIRE, SCORPION);

		// a sole candidate that may not be credited
		learner.varpChanged(555);
		learner.impact(new Mob(SCORPION, true, false));
		assertEquals(Outcome.NOT_CREDITABLE, tick());
		assertElement(Weakness.Element.FIRE, SCORPION);

		// a bystander
		learner.varpChanged(555);
		learner.impact(bystander(SCORPION));
		assertEquals(Outcome.NO_CANDIDATE, tick());
		assertElement(Weakness.Element.FIRE, SCORPION);
	}

	// ---- the tick is the unit ----

	@Test
	public void aStaleChangeIsNotCarriedToTheNextTick()
	{
		learner.varpChanged(557);
		assertEquals(Outcome.NO_CANDIDATE, tick());
		learner.impact(fought(HILL_GIANT)); // the impact of a spell that did not write the varp
		assertEquals("the earlier change must be gone", Outcome.NO_CHANGE, tick());
		assertNull(learner.weaknessFor(HILL_GIANT));
	}

	@Test
	public void anImpactIsNotCarriedToTheNextTick()
	{
		learner.impact(fought(HILL_GIANT));
		assertEquals(Outcome.NO_CHANGE, tick());
		learner.varpChanged(557); // a later change, with no impact of its own
		assertEquals("the earlier impact must be gone", Outcome.NO_CANDIDATE, tick());
		assertNull(learner.weaknessFor(HILL_GIANT));
	}

	@Test
	public void everyTickEmptiesTheBuffersWhateverTheOutcome()
	{
		learner.varpChanged(557);
		learner.impact(fought(HILL_GIANT));
		learner.impact(fought(SCORPION));
		assertEquals(Outcome.SEVERAL_CANDIDATES, tick());
		assertEquals(Outcome.NO_CHANGE, tick());
		learner.varpChanged(0);
		assertEquals(Outcome.UNKNOWN_VALUE, tick());
		assertEquals(Outcome.NO_CHANGE, tick());
	}

	@Test
	public void discardingTheTickDropsABufferedChangeAndItsImpactsButKeepsWhatWasLearned()
	{
		learner.varpChanged(554);
		learner.impact(fought(SCORPION));
		tick();

		learner.varpChanged(557);
		learner.impact(fought(HILL_GIANT));
		learner.discardTick(); // logout or hop: the next tick belongs to another session
		assertEquals(Outcome.NO_CHANGE, tick());
		assertNull(learner.weaknessFor(HILL_GIANT));
		assertElement(Weakness.Element.FIRE, SCORPION);
	}

	// ---- persistence hooks (spec addendum 5) ----

	@Test
	public void aCreditSaysWhatItLearned()
	{
		learner.varpChanged(557);
		learner.impact(fought(HILL_GIANT));
		assertEquals(Outcome.CREDITED, tick());
		assertEquals(java.util.Collections.singletonList("2103:EARTH"), learned);
	}

	@Test
	public void aCreditThatChangesNothingSaysNothing()
	{
		learner.varpChanged(557);
		learner.impact(fought(HILL_GIANT));
		tick();
		learner.varpChanged(557);
		learner.impact(fought(HILL_GIANT));
		assertEquals(Outcome.CREDITED, tick());
		assertEquals("the same value for the same type is not news", 1, learned.size());
	}

	@Test
	public void aChangedEntryAndANoneAreSaidToo()
	{
		learner.varpChanged(557);
		learner.impact(fought(HILL_GIANT));
		tick();
		learner.varpChanged(554);
		learner.impact(fought(HILL_GIANT));
		tick();
		learner.varpChanged(-1);
		learner.impact(fought(HILL_GIANT));
		tick();
		assertEquals(java.util.Arrays.asList("2103:EARTH", "2103:FIRE", "2103:NONE"), learned);
	}

	@Test
	public void aDroppedChangeSaysNothing()
	{
		learner.varpChanged(557);
		assertEquals(Outcome.NO_CANDIDATE, tick());
		learner.varpChanged(0);
		learner.impact(fought(HILL_GIANT));
		assertEquals(Outcome.UNKNOWN_VALUE, tick());
		learner.varpChanged(557);
		learner.impact(fought(HILL_GIANT));
		learner.impact(fought(SCORPION));
		assertEquals(Outcome.SEVERAL_CANDIDATES, tick());
		learner.varpChanged(557);
		learner.impact(new Mob(HILL_GIANT, true, false));
		assertEquals(Outcome.NOT_CREDITABLE, tick());
		assertEquals(java.util.Collections.<String>emptyList(), learned);
	}

	@Test
	public void loadReplacesWhatWasKnownAndSaysNothing()
	{
		learner.varpChanged(554);
		learner.impact(fought(SCORPION));
		tick();
		learned.clear();

		java.util.Map<Integer, Weakness> stored = new java.util.HashMap<>();
		stored.put(HILL_GIANT, new Weakness(Weakness.Element.EARTH));
		stored.put(7, Weakness.NONE);
		learner.load(stored);

		assertNull("the memory mirrors the store: the scorpion was not in it", learner.weaknessFor(SCORPION));
		assertElement(Weakness.Element.EARTH, HILL_GIANT);
		assertNull("a loaded NONE is a known none, shown as nothing", learner.weaknessFor(7));
		assertEquals("loading is not learning", java.util.Collections.<String>emptyList(), learned);
	}

	@Test
	public void loadLeavesThisTicksBuffersAlone()
	{
		learner.varpChanged(557);
		learner.impact(fought(HILL_GIANT));
		learner.load(new java.util.HashMap<>());
		assertEquals("the change buffered before the load is still credited by the tick", Outcome.CREDITED, tick());
		assertElement(Weakness.Element.EARTH, HILL_GIANT);
	}

	@Test
	public void aLearnerBuiltWithNoSinkStillLearns()
	{
		WeaknessLearner<Mob> plain = new WeaknessLearner<>();
		plain.varpChanged(557);
		plain.impact(fought(HILL_GIANT));
		assertEquals(Outcome.CREDITED, plain.tick(m -> m.fought, m -> m.creditable ? m.type : null));
		assertNotNull(plain.weaknessFor(HILL_GIANT));
	}
}
