package com.foe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.function.Predicate;
import net.runelite.api.GameState;
import net.runelite.client.config.Range;
import org.junit.Test;

/**
 * TargetFeed is what FoePlugin's handlers call. Time is passed in as plain milliseconds, so "event at 1000 ms,
 * GameTick at 1020 ms" can be written down exactly. Times in the linger-0 tests are not multiples of a tick on
 * purpose: events precede GameTick by some milliseconds in the real client.
 */
public class TargetFeedTest
{
	private static final long LINGER = 10_000;
	private static final Predicate<Npc> NOBODY = n -> false;
	private static final Predicate<Npc> EVERYBODY = n -> true;

	/** Identity matters (the feed compares by ==), so this is a class and not an Integer. */
	private static final class Npc
	{
		final int index;

		Npc(int index)
		{
			this.index = index;
		}

		@Override
		public String toString()
		{
			return "npc#" + index;
		}
	}

	private final TargetFeed<Npc> feed = new TargetFeed<>(n -> n.index);
	private final Npc a = new Npc(1);
	private final Npc b = new Npc(2);
	private final Npc c = new Npc(3);

	private static Predicate<Npc> only(Npc fought)
	{
		return n -> n == fought;
	}

	/**
	 * A GameTick with no combat of its own. Every event that makes an NPC the target leaves evidence behind for the
	 * next tick, and that tick refreshes the target to ITS time. A test that moves the clock without ticking first
	 * would be passing on the engagement's evidence instead of on the thing it names.
	 */
	private void settle(long nowMs, long lingerMs)
	{
		feed.tick(nowMs, lingerMs, NOBODY);
	}

	// ---- engagement ----

	@Test
	public void noTargetInitially()
	{
		assertNull(feed.tick(0, LINGER, EVERYBODY));
	}

	@Test
	public void engagingACombatNpcMakesItTheTarget()
	{
		feed.playerEngaged(a, true, 0);
		assertSame(a, feed.tick(0, LINGER, NOBODY));
	}

	@Test
	public void engagingAnotherNpcAlwaysWins()
	{
		feed.playerEngaged(a, true, 0);
		feed.playerEngaged(b, true, 100);
		assertSame(b, feed.tick(100, LINGER, NOBODY));
	}

	// Spec addendum 2: Talk-to sets getInteracting() too, so only NPCs with a combat level count.
	@Test
	public void talkingToANonCombatNpcNeverCounts()
	{
		feed.playerEngaged(a, false, 0);
		assertNull(feed.tick(0, LINGER, EVERYBODY));
	}

	@Test
	public void talkingToANonCombatNpcDoesNotReplaceTheTarget()
	{
		feed.playerEngaged(a, true, 0);
		feed.playerEngaged(c, false, 100);
		assertSame(a, feed.tick(100, LINGER, NOBODY));
	}

	// ---- your own landed hit ----

	// docs/probe/raw.log lines 265-267: switched to another NPC, then a hit already in flight landed on the old one.
	@Test
	public void aHitInFlightOnTheOldNpcDoesNotStealTheNewTarget()
	{
		feed.playerEngaged(a, true, 0);
		feed.playerEngaged(b, true, 1_800);
		feed.playerHit(a, true, 3_000, LINGER, NOBODY);
		assertSame(b, feed.tick(3_000, LINGER, NOBODY));
	}

	@Test
	public void aHitIsAdoptedWhenThereIsNoLiveTarget()
	{
		feed.playerHit(a, true, 0, LINGER, NOBODY);
		assertSame(a, feed.tick(0, LINGER, NOBODY));
	}

	@Test
	public void aHitIsAdoptedOnceTheOldTargetHasLapsed()
	{
		feed.playerEngaged(a, true, 0);
		feed.playerHit(b, true, 10_001, LINGER, NOBODY);
		assertSame(b, feed.tick(10_001, LINGER, NOBODY));
	}

	@Test
	public void aHitOnANonCombatNpcIsIgnored()
	{
		feed.playerHit(c, false, 0, LINGER, NOBODY);
		assertNull(feed.tick(0, LINGER, NOBODY));
	}

	@Test
	public void aHitRefreshesTheLinger()
	{
		feed.playerEngaged(a, true, 0);
		settle(0, LINGER);
		feed.playerHit(a, true, 9_000, LINGER, NOBODY);
		settle(9_000, LINGER);
		assertSame(a, feed.tick(18_000, LINGER, NOBODY));
		assertNull(feed.tick(19_001, LINGER, NOBODY));
	}

	// ---- an NPC hits you ----

	@Test
	public void aSingleHitterIsAdoptedWhenThereIsNoLiveTarget()
	{
		feed.playerHurt(Collections.singletonList(b), 0, LINGER, NOBODY);
		assertSame(b, feed.tick(0, LINGER, NOBODY));
	}

	@Test
	public void theLiveTargetIsKeptWhenItIsAmongSeveralHitters()
	{
		feed.playerEngaged(a, true, 0);
		feed.playerHurt(Arrays.asList(b, a), 1_000, LINGER, NOBODY);
		assertSame(a, feed.tick(1_000, LINGER, NOBODY));
	}

	@Test
	public void aHitterThatIsTheTargetRefreshesIt()
	{
		feed.playerEngaged(a, true, 0);
		settle(0, LINGER);
		feed.playerHurt(Collections.singletonList(a), 9_000, LINGER, NOBODY);
		settle(9_000, LINGER);
		assertSame(a, feed.tick(18_000, LINGER, NOBODY));
		assertNull(feed.tick(19_001, LINGER, NOBODY));
	}

	// The target is among the hitters but not first: it is the one refreshed, not the first of the list.
	@Test
	public void aHitterThatIsTheTargetRefreshesItEvenWhenListedSecond()
	{
		feed.playerEngaged(a, true, 0);
		settle(0, LINGER);
		feed.playerHurt(Arrays.asList(b, a), 9_000, LINGER, NOBODY);
		settle(9_000, LINGER);
		assertSame(a, feed.tick(18_000, LINGER, NOBODY));
		assertNull(feed.tick(19_001, LINGER, NOBODY));
	}

	@Test
	public void aHitterDoesNotReplaceALiveTarget()
	{
		feed.playerEngaged(a, true, 0);
		feed.playerHurt(Collections.singletonList(b), 1_000, LINGER, NOBODY);
		assertSame(a, feed.tick(1_000, LINGER, NOBODY));
	}

	// Known limit, pinned so it is a decision and not an accident: the client does not say which NPC's hit it
	// was, so with several candidates and no live target the first one is taken.
	@Test
	public void withSeveralHittersAndNoLiveTargetTheFirstIsTaken()
	{
		feed.playerHurt(Arrays.asList(b, c), 0, LINGER, NOBODY);
		assertSame(b, feed.tick(0, LINGER, NOBODY));
	}

	@Test
	public void noHittersChangesNothing()
	{
		feed.playerHurt(Collections.<Npc>emptyList(), 0, LINGER, NOBODY);
		assertNull(feed.tick(0, LINGER, NOBODY));
		feed.playerEngaged(a, true, 0);
		feed.playerHurt(Collections.<Npc>emptyList(), 100, LINGER, NOBODY);
		assertSame(a, feed.tick(100, LINGER, NOBODY));
	}

	// ---- death and despawn ----

	@Test
	public void goneClearsTheTargetAndSaysItWasOne()
	{
		feed.playerEngaged(a, true, 0);
		assertTrue(feed.gone(a));
		assertNull(feed.tick(0, LINGER, NOBODY));
	}

	@Test
	public void goneOfAnotherNpcKeepsTheTargetAndSaysItWasNot()
	{
		feed.playerEngaged(a, true, 0);
		assertFalse(feed.gone(b));
		assertSame(a, feed.tick(0, LINGER, NOBODY));
	}

	/**
	 * Review finding F3 (Task 11): an index is unique only within one world view, so another NPC can share the
	 * target's. A hit on it, or by it, used to refresh the tracker's index and then hand the panel to that other
	 * object, after which the real target's death did nothing. While the target is live it is ignored, as any hit on
	 * a non-target is; with no live target it is adopted like any other.
	 */
	@Test
	public void anotherNpcWithTheTargetsIndexDoesNotTakeThePanel()
	{
		Npc twin = new Npc(a.index); // a, in a different world view
		feed.playerEngaged(a, true, 0);
		settle(0, LINGER);
		feed.playerHit(twin, true, 600, LINGER, NOBODY);
		assertSame("own hit on the twin", a, feed.tick(600, LINGER, NOBODY));
		feed.playerHurt(Collections.singletonList(twin), 1200, LINGER, NOBODY);
		assertSame("the twin hit the player", a, feed.tick(1200, LINGER, NOBODY));
		assertTrue("the real target's death still clears it", feed.gone(a));
		assertNull(feed.tick(1200, LINGER, NOBODY));

		feed.playerHit(twin, true, 1800, LINGER, NOBODY);
		assertSame("no live target: the twin is adopted like anyone", twin, feed.tick(1800, LINGER, NOBODY));
	}

	@Test
	public void goneWithNoTargetSaysItWasNot()
	{
		assertFalse(feed.gone(a));
	}

	// Death and then despawn both arrive for one NPC. The second is not news: the caller clears what it drew on
	// "true", and by then it may be drawing a different target.
	@Test
	public void goneSaysSoOnlyOnce()
	{
		feed.playerEngaged(a, true, 0);
		assertTrue(feed.gone(a));
		assertFalse(feed.gone(a));
	}

	// Amendment 5: a dead NPC is still "interacting" for a few ticks. It must not be revived by that, however long
	// the linger has been running.
	@Test
	public void aGoneNpcIsNotRevivedByItsInteractingFlag()
	{
		feed.playerEngaged(a, true, 0);
		feed.gone(a);
		assertNull(feed.tick(0, LINGER, EVERYBODY));
		assertNull(feed.tick(20_000, LINGER, EVERYBODY));
	}

	@Test
	public void aGoneNpcCanBeEngagedAgainAsANewTarget()
	{
		feed.playerEngaged(a, true, 0);
		feed.gone(a);
		feed.playerEngaged(a, true, 100);
		assertSame(a, feed.tick(100, LINGER, NOBODY));
	}

	// ---- the tick ----

	@Test
	public void theTargetExpiresAfterTheLingerWithoutEvidence()
	{
		feed.playerEngaged(a, true, 0);
		settle(0, LINGER);
		assertSame(a, feed.tick(10_000, LINGER, NOBODY));
		assertNull(feed.tick(10_001, LINGER, NOBODY));
	}

	@Test
	public void beingInCombatNowKeepsATargetPastItsLinger()
	{
		feed.playerEngaged(a, true, 0);
		settle(0, LINGER);
		assertNull(feed.tick(20_000, LINGER, NOBODY));
		// The same tick with the player interacting with it: a fight is on, so it is shown again.
		assertSame(a, feed.tick(20_600, LINGER, EVERYBODY));
	}

	@Test
	public void beingInCombatWithADifferentNpcDoesNotKeepIt()
	{
		feed.playerEngaged(a, true, 0);
		settle(0, LINGER);
		assertNull(feed.tick(20_000, LINGER, only(b)));
	}

	@Test
	public void aLapsedTargetComesBackWhenTheFightResumes()
	{
		feed.playerEngaged(a, true, 0);
		settle(0, LINGER);
		assertNull(feed.tick(10_001, LINGER, NOBODY));
		assertSame(a, feed.tick(10_600, LINGER, only(a)));
	}

	// ---- linger 0: shown only on ticks with evidence of combat (amendment 6) ----

	@Test
	public void atLingerZeroAnEngagementShowsOnTheTickAfterIt()
	{
		feed.playerEngaged(a, true, 1_000);
		// GameTick comes a few ms after the event: a plain current() read would already call it lapsed.
		assertSame(a, feed.tick(1_020, 0, NOBODY));
	}

	@Test
	public void atLingerZeroAHitShowsOnTheTickAfterIt()
	{
		feed.playerHit(a, true, 1_000, 0, NOBODY);
		assertSame(a, feed.tick(1_020, 0, NOBODY));
	}

	@Test
	public void atLingerZeroAHitterShowsOnTheTickAfterIt()
	{
		feed.playerHurt(Collections.singletonList(a), 1_000, 0, NOBODY);
		assertSame(a, feed.tick(1_020, 0, NOBODY));
	}

	@Test
	public void atLingerZeroARefreshingHitOnTheTargetShowsOnTheTickAfterIt()
	{
		feed.playerEngaged(a, true, 0);
		assertSame(a, feed.tick(20, 0, NOBODY));
		feed.playerHit(a, true, 5_000, 0, NOBODY);
		assertSame(a, feed.tick(5_020, 0, NOBODY));
	}

	@Test
	public void atLingerZeroARefreshingHitterOnTheTargetShowsOnTheTickAfterIt()
	{
		feed.playerEngaged(a, true, 0);
		assertSame(a, feed.tick(20, 0, NOBODY));
		feed.playerHurt(Collections.singletonList(a), 5_000, 0, NOBODY);
		assertSame(a, feed.tick(5_020, 0, NOBODY));
	}

	@Test
	public void atLingerZeroEvidenceIsUsedUpByTheTickItBelongsTo()
	{
		feed.playerEngaged(a, true, 1_000);
		assertSame(a, feed.tick(1_020, 0, NOBODY));
		assertNull(feed.tick(1_620, 0, NOBODY));
		assertSame(a, feed.tick(2_220, 0, only(a)));
	}

	// A hit may only replace the target if the target is not being fought right now. At linger 0 the time test
	// alone cannot tell: nothing is "live" between ticks, so every hit would steal the panel.
	@Test
	public void atLingerZeroAHitDoesNotReplaceATargetThatIsBeingFoughtRightNow()
	{
		feed.playerEngaged(a, true, 1_000);
		feed.playerHit(b, true, 1_600, 0, only(a));
		assertSame(a, feed.tick(1_620, 0, only(a)));
	}

	@Test
	public void atLingerZeroAHitterDoesNotReplaceATargetThatIsBeingFoughtRightNow()
	{
		feed.playerEngaged(a, true, 1_000);
		feed.playerHurt(Collections.singletonList(b), 1_600, 0, only(a));
		assertSame(a, feed.tick(1_620, 0, only(a)));
	}

	// The interacting flag flips to null between attacks (raw.log: 97 of 127 changes), so it can be true when the hit
	// arrives and gone again by the tick. The hit was evidence of a fight with the target, and the tick must say so.
	@Test
	public void atLingerZeroAHitDuringTheFightStillShowsTheTargetIfTheFightingStopsBeforeTheTick()
	{
		feed.playerEngaged(a, true, 1_000);
		assertSame(a, feed.tick(1_020, 0, NOBODY));
		feed.playerHit(b, true, 1_600, 0, only(a));
		assertSame(a, feed.tick(1_620, 0, NOBODY));
	}

	@Test
	public void atLingerZeroAHitterDuringTheFightStillShowsTheTargetIfTheFightingStopsBeforeTheTick()
	{
		feed.playerEngaged(a, true, 1_000);
		assertSame(a, feed.tick(1_020, 0, NOBODY));
		feed.playerHurt(Collections.singletonList(b), 1_600, 0, only(a));
		assertSame(a, feed.tick(1_620, 0, NOBODY));
	}

	@Test
	public void atLingerZeroAHitDoesReplaceATargetThatIsNotBeingFought()
	{
		feed.playerEngaged(a, true, 1_000);
		feed.playerHit(b, true, 1_600, 0, NOBODY);
		assertSame(b, feed.tick(1_620, 0, NOBODY));
	}

	// ---- logout / hop ----

	@Test
	public void resetForgetsTheTarget()
	{
		feed.playerEngaged(a, true, 0);
		feed.reset();
		assertNull(feed.tick(0, LINGER, EVERYBODY));
	}

	@Test
	public void resetAlsoForgetsWhatTheTrackerKnew()
	{
		feed.playerEngaged(a, true, 0);
		feed.reset();
		// a fresh target afterwards is adopted from a hit, which only happens when the old one is truly gone
		feed.playerHit(b, true, 100, LINGER, NOBODY);
		assertSame(b, feed.tick(100, LINGER, NOBODY));
	}

	@Test
	public void everyGameStateIsDecidedAndOnlyTheInGameOnesKeepTheFight()
	{
		for (GameState s : GameState.values())
		{
			boolean keeps = s == GameState.LOGGED_IN || s == GameState.LOADING;
			assertEquals(s.name(), !keeps, TargetFeed.endsTheFight(s));
		}
	}

	// ---- config clamp (amendment 10) ----

	@Test
	public void lingerIsClampedToZeroToSixtySeconds()
	{
		assertEquals(0L, TargetFeed.lingerMs(-1));
		assertEquals(0L, TargetFeed.lingerMs(Integer.MIN_VALUE));
		assertEquals(0L, TargetFeed.lingerMs(0));
		assertEquals(1_000L, TargetFeed.lingerMs(1));
		assertEquals(10_000L, TargetFeed.lingerMs(10));
		assertEquals(60_000L, TargetFeed.lingerMs(60));
		assertEquals(60_000L, TargetFeed.lingerMs(61));
		assertEquals(60_000L, TargetFeed.lingerMs(Integer.MAX_VALUE));
	}

	@Test
	public void theClampIsTheRangeTheSettingDeclares() throws Exception
	{
		Range r = FoeConfig.class.getMethod("lingerSeconds").getAnnotation(Range.class);
		assertEquals(r.min() * 1000L, TargetFeed.lingerMs(Integer.MIN_VALUE));
		assertEquals(r.max() * 1000L, TargetFeed.lingerMs(Integer.MAX_VALUE));
	}
}
