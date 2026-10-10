package com.foe;

import static net.runelite.api.HitsplatID.BLEED;
import static net.runelite.api.HitsplatID.BLOCK_ME;
import static net.runelite.api.HitsplatID.BLOCK_OTHER;
import static net.runelite.api.HitsplatID.BURN;
import static net.runelite.api.HitsplatID.CORRUPTION;
import static net.runelite.api.HitsplatID.CYAN_DOWN;
import static net.runelite.api.HitsplatID.CYAN_UP;
import static net.runelite.api.HitsplatID.DAMAGE_ME;
import static net.runelite.api.HitsplatID.DAMAGE_ME_CYAN;
import static net.runelite.api.HitsplatID.DAMAGE_ME_ORANGE;
import static net.runelite.api.HitsplatID.DAMAGE_ME_POISE;
import static net.runelite.api.HitsplatID.DAMAGE_ME_WHITE;
import static net.runelite.api.HitsplatID.DAMAGE_ME_YELLOW;
import static net.runelite.api.HitsplatID.DAMAGE_MAX_ME;
import static net.runelite.api.HitsplatID.DAMAGE_MAX_ME_ORANGE;
import static net.runelite.api.HitsplatID.DAMAGE_OTHER;
import static net.runelite.api.HitsplatID.DAMAGE_OTHER_CYAN;
import static net.runelite.api.HitsplatID.DAMAGE_OTHER_ORANGE;
import static net.runelite.api.HitsplatID.DISEASE;
import static net.runelite.api.HitsplatID.DISEASE_BLOCKED;
import static net.runelite.api.HitsplatID.DOOM;
import static net.runelite.api.HitsplatID.HEAL;
import static net.runelite.api.HitsplatID.POISON;
import static net.runelite.api.HitsplatID.PRAYER_DRAIN;
import static net.runelite.api.HitsplatID.SANITY_DRAIN;
import static net.runelite.api.HitsplatID.SANITY_RESTORE;
import static net.runelite.api.HitsplatID.VENOM;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.Random;
import org.junit.Test;

/**
 * Exact HP from hitsplats (spec addendum 5), on plain objects. The "true HP" in these tests is the one the game
 * knows; the tracker is told only the hitsplats, and a bar reading derived from the true HP the way the server does
 * it ({@link #ratioFor}), which is the inverse of the formula HpEstimate was ported from.
 */
public class HpTrackerTest
{
	private static final int UNKNOWN = HpEstimate.UNKNOWN;
	private static final int SCALE = 30;
	private static final int MAX = 85;

	private final HpTracker tracker = new HpTracker();
	private final Object npc = new Object();

	/**
	 * The ratio the server sends for this true HP: {@code 1 + (scale - 1) * hp / maxHp} (the comment in RuneLite's
	 * OpponentInfoOverlay, and the formula HpEstimateTest.exactWhenMaxHpFitsInTheScale already uses), 0 at 0 HP.
	 */
	private static int ratioFor(int hp, int maxHp, int scale)
	{
		return hp <= 0 ? 0 : 1 + (scale - 1) * hp / maxHp;
	}

	/** The tracker's answer for a live bar showing this true HP, on the 85 HP / scale 30 monster. */
	private int live(int trueHp)
	{
		return tracker.read(npc, ratioFor(trueHp, MAX, SCALE), SCALE, false, MAX);
	}

	private int stale(int trueHp)
	{
		return tracker.read(npc, ratioFor(trueHp, MAX, SCALE), SCALE, true, MAX);
	}

	private void damage(int amount)
	{
		tracker.hit(npc, DAMAGE_ME, amount);
	}

	// ---- the fixture itself: a test that builds its own bar had better build a real one ----

	@Test
	public void theBarTheseTestsBuildAlwaysContainsTheTrueHp()
	{
		int checked = 0;
		for (int scale : new int[] {1, 2, 30, 120})
		{
			for (int maxHp : new int[] {1, 2, 5, 29, 30, 31, 85, 1000, 2000})
			{
				for (int hp = 0; hp <= maxHp; hp++)
				{
					HpEstimate.Range r = HpEstimate.range(ratioFor(hp, maxHp, scale), scale, maxHp);
					String at = "hp " + hp + " of " + maxHp + " on scale " + scale;
					assertTrue(at, r != null && r.contains(hp));
					checked++;
				}
			}
		}
		assertTrue("checked " + checked, checked > 10_000);
	}

	// ---- exact when watched from full ----

	@Test
	public void aMonsterWatchedFromFullIsExactAtEveryHit()
	{
		int hp = MAX;
		assertEquals("full bar, nothing hit yet", MAX, live(hp));
		int midpointWasWrong = 0;
		for (int dmg : new int[] {5, 12, 0, 9, 1, 16, 7, 11, 3})
		{
			damage(dmg);
			hp -= dmg;
			assertEquals("true HP " + hp, hp, live(hp));
			if (HpEstimate.estimate(ratioFor(hp, MAX, SCALE), SCALE, MAX) != hp)
			{
				midpointWasWrong++;
			}
		}
		assertTrue("the sequence includes readings where the plain midpoint is off, or it proves nothing: "
			+ midpointWasWrong, midpointWasWrong >= 3);
	}

	// Why this exists: ratio 22/30 on 85 max HP means 62-64. The midpoint says 63; another plugin said 64.
	@Test
	public void the6364CaseShowsTheTrackedValueWhenItIsConsistent()
	{
		assertEquals("the bar alone says 63", 63, HpEstimate.estimate(22, SCALE, MAX));
		damage(21);
		assertEquals(64, tracker.read(npc, 22, SCALE, false, MAX));
	}

	@Test
	public void otherPlayersHitsCountAsMuchAsYours()
	{
		tracker.hit(npc, DAMAGE_OTHER, 10);
		tracker.hit(npc, DAMAGE_ME, 11);
		assertEquals(64, tracker.read(npc, 22, SCALE, false, MAX));
	}

	// ---- when the tracked value cannot be trusted ----

	// relog, a hit by someone before we watched it, a hitsplat the client never sent, regeneration: all of them look
	// the same to the tracker, a number that the bar says is impossible
	@Test
	public void aMonsterDamagedBeforeItWasSeenFallsBackToTheMidpoint()
	{
		damage(10); // seen; the 30 before it were not. True HP is 45, the tracker thinks 75.
		assertEquals(UNKNOWN, live(45));
	}

	// Review of 77ccc4e, finding 2: the bar may lag the hitsplat. On a read right after a hit a mismatch shows the
	// midpoint and leaves the count alone; the next read with no hit in between resyncs it to the bar.
	@Test
	public void aBigMismatchShowsTheMidpointOnTheHitTickAndAfterIt()
	{
		damage(10); // the 30 before it were not seen: true 45, tracked 75
		assertEquals("a hit just landed: midpoint, count kept", UNKNOWN, live(45));
		assertEquals("no hit since and 29 off: more than regeneration, so midpoint until an exact reading", UNKNOWN,
			live(45));
		assertEquals("75 would fit this bar, but the count was shown to be wrong", UNKNOWN, live(75));
	}

	@Test
	public void aLaggingBarDoesNotCorruptTheCount()
	{
		damage(20);
		assertEquals(65, live(65));
		damage(17); // true 48; the bar has not caught up and still reads 65
		assertEquals("lagging bar on the hit tick: midpoint, count untouched", UNKNOWN, live(65));
		assertEquals("the bar catches up: the untouched count is exact", 48, live(48));
	}

	// The in-game case (2026-10-08): six mismatches of exactly -1, an unseen 1 HP regeneration each time.
	@Test
	public void oneUnseenRegenerationIsCorrectedExactly()
	{
		// a true HP t at the top edge of its bar, so t + 1 (one regeneration) starts the next bar. Start mid-range:
		// near full, t + 1 is the full bar, an exact reading that anchors and would not exercise the resync at all.
		int t = MAX / 2;
		while (HpEstimate.range(ratioFor(t + 1, MAX, SCALE), SCALE, MAX).contains(t))
		{
			t--;
		}
		damage(MAX - t);
		assertEquals("watched from full: exact", t, live(t));
		assertEquals("regenerated 1 HP unseen, no hit since: resynced, and exact again", t + 1, live(t + 1));
	}

	/**
	 * Review finding F4 (Task 11): a track no bar has confirmed is only an assumption (full health), so a near-full bar
	 * on first sight (a relog mid-fight, a boss hurt before Foe looked) was "resynced" to the top of its range and
	 * shown as exact: 999 for a bar of 966-999. Regeneration explains a small drift only in a count a bar already
	 * agreed with, so an unconfirmed one shows the midpoint (addendum 5) until a bar confirms or anchors it.
	 */
	@Test
	public void aCountNoBarHasConfirmedIsNeverResyncedToTheBarEdge()
	{
		int max = 1000;
		int ratio = ratioFor(970, max, SCALE);
		HpEstimate.Range bar = HpEstimate.range(ratio, SCALE, max);
		assertTrue("precondition: the assumed full HP is just above this bar", bar.getMax() == max - 1);
		assertEquals("first sight of a near-full bar: midpoint", UNKNOWN, tracker.read(npc, ratio, SCALE, false, max));
		assertEquals("and still on the next read", UNKNOWN, tracker.read(npc, ratio, SCALE, false, max));

		Object hitFirst = new Object(); // a hitsplat before any bar: still only an assumption
		tracker.hit(hitFirst, DAMAGE_ME, 0);
		assertEquals(UNKNOWN, tracker.read(hitFirst, ratio, SCALE, false, max));
		assertEquals(UNKNOWN, tracker.read(hitFirst, ratio, SCALE, false, max));

		Object watched = new Object(); // control: a full bar confirms the count, and then a 1 HP drift is resynced
		assertEquals(max, tracker.read(watched, SCALE, SCALE, false, max));
		tracker.hit(watched, DAMAGE_ME, 34);
		assertEquals(966, tracker.read(watched, ratio, SCALE, false, max));
		int below = ratioFor(965, max, SCALE);
		assertTrue("precondition: 965 is on the next bar down", !HpEstimate.range(below, SCALE, max).contains(966));
		assertEquals("confirmed, so a 1 HP drift is resynced", 965, tracker.read(watched, below, SCALE, false, max));

		Object anchored = new Object(); // an exact reading confirms by itself: a 1 HP drift right after it resyncs
		assertEquals(max, tracker.read(anchored, SCALE, SCALE, false, max));
		assertEquals(bar.getMax(), tracker.read(anchored, ratioFor(max - 1, max, SCALE), SCALE, false, max));
	}

	@Test
	public void driftBeyondRegenerationIsNotNudgedButShownAsTheMidpoint()
	{
		damage(20);
		assertEquals(65, live(65));
		assertEquals("10 unseen HP is not regeneration: midpoint", UNKNOWN, live(75));
	}

	@Test
	public void aOneHpDriftAtTheBarEdgeIsResyncedToThatEdge()
	{
		// a true HP t at the top edge of its bar, so one regeneration crosses into the next bar
		int t = MAX - HpTracker.MAX_DRIFT - 2;
		while (HpEstimate.range(ratioFor(t + 1, MAX, SCALE), SCALE, MAX).contains(t))
		{
			t--;
		}
		HpEstimate.Range next = HpEstimate.range(ratioFor(t + 1, MAX, SCALE), SCALE, MAX);
		damage(MAX - t);
		assertEquals(t, live(t));
		assertEquals("gap 1 at the bar edge: resynced", next.getMin(), live(t + 1));
	}

	// The known limit, pinned so it is a decision rather than a surprise: a drift smaller than the bar's resolution
	// is invisible to the range check. The shown value is still one the bar allows.
	@Test
	public void driftInsideTheBarsResolutionIsNotSeenButTheValueStaysInsideTheBar()
	{
		damage(20); // tracked 65
		int shown = live(66); // true HP is 66 (regenerated 1): the bar 65-67 for both
		assertEquals(65, shown);
		HpEstimate.Range r = HpEstimate.range(ratioFor(66, MAX, SCALE), SCALE, MAX);
		assertTrue(r.contains(shown));
	}

	@Test
	public void anExactBarReadingReanchorsAfterARejection()
	{
		damage(10);
		assertEquals(UNKNOWN, live(45));
		assertEquals("a full bar is exact", MAX, tracker.read(npc, SCALE, SCALE, false, MAX));
		damage(7);
		assertEquals("tracking resumes from the anchor", 78, live(78));
	}

	@Test
	public void anExactBarReadingBelowFullReanchorsToo()
	{
		// maxHp 20 on scale 30: ratio 11 is exactly 7 HP
		// maxHp 40 on scale 30: ratio 5 is exactly 6 HP, and ratio 3 is 3-4 HP (midpoint 4)
		HpTracker t = new HpTracker();
		t.hit(npc, DAMAGE_ME, 3); // tracked 37; the bar says 6 exactly, and an exact reading is not compared, it anchors
		assertEquals(6, t.read(npc, 5, SCALE, false, 40));
		t.hit(npc, DAMAGE_ME, 3);
		assertEquals("6 - 3, inside the bar's 3-4, where the midpoint would say 4", 3, t.read(npc, 3, SCALE, false, 40));
	}

	@Test
	public void anExactReadingIsTheAnswerEvenWhenTheTrackedValueDisagrees()
	{
		damage(10); // tracked 75
		assertEquals("bar says 85 exactly", MAX, tracker.read(npc, SCALE, SCALE, false, MAX));
		assertEquals("and a dead bar is 0 exactly", 0, tracker.read(npc, 0, SCALE, false, MAX));
	}

	// ---- heals ----

	@Test
	public void aHealAddsBack()
	{
		damage(30);
		tracker.hit(npc, HEAL, 10);
		assertEquals(65, live(65));
	}

	@Test
	public void aHealCannotTakeItAboveMaxHp()
	{
		damage(10);
		tracker.hit(npc, HEAL, 50); // the game stops at max HP
		damage(5);
		assertEquals("85 - 10, healed to the cap, minus 5", 80, live(80));
	}

	// ---- never outside what is possible ----

	@Test
	public void neverBelowZeroAndNeverAboveMaxHp()
	{
		damage(200); // more than it has: tracked would be -115
		assertEquals(UNKNOWN, tracker.read(npc, 15, SCALE, false, MAX));
		HpTracker fresh = new HpTracker();
		fresh.hit(npc, HEAL, 1000);
		assertEquals("a heal on a monster that was never hurt is no HP above max", UNKNOWN,
			fresh.read(npc, 15, SCALE, false, MAX));
		assertEquals("a dead bar is exactly 0, whatever the tracker thought", 0, tracker.read(npc, 0, SCALE, false, MAX));
	}

	// One at a time: a negative damage and a negative heal cancel each other, which hides that either was counted.
	@Test
	public void aNegativeOrZeroAmountChangesNothing()
	{
		int[][] cases = {{DAMAGE_ME, -50}, {DAMAGE_OTHER, -1}, {POISON, -7}, {HEAL, -50}, {HEAL, -1}, {DAMAGE_ME, 0}, {HEAL, 0}};
		for (int[] c : cases)
		{
			HpTracker t = new HpTracker();
			t.hit(npc, DAMAGE_ME, 21);
			t.hit(npc, c[0], c[1]);
			assertEquals("type " + c[0] + " amount " + c[1], 64, t.read(npc, 22, SCALE, false, MAX));
		}
	}

	@Test
	public void aHugeAmountIsRejectedByTheRangeNotWrappedIntoIt()
	{
		// In int arithmetic this is 2 * (2^31 - 1) + 47 = 45 (mod 2^32): a believable 45 damage, and the bar for a true
		// 40 HP would then accept it. Summed as a long it is billions, and the bar rejects it.
		damage(Integer.MAX_VALUE);
		damage(Integer.MAX_VALUE);
		damage(47);
		assertEquals(UNKNOWN, live(40));
	}

	// Seeded, so a failure reproduces. Hits are the true change in HP, which is what the hitsplat shows (the server
	// does not show damage beyond what the monster had). Unseen changes are regeneration and missed hitsplats.
	@Test
	public void whateverHappensTheShownValueIsInsideTheBarAndExactWhenNothingWasMissed()
	{
		Random random = new Random(20261008L);
		int exact = 0;
		int rejected = 0;
		for (int trial = 0; trial < 4000; trial++)
		{
			int maxHp = 1 + random.nextInt(random.nextBoolean() ? 60 : 3000);
			int scale = random.nextInt(4) == 0 ? 120 : SCALE;
			boolean cleanFight = random.nextInt(3) != 0;
			HpTracker t = new HpTracker();
			Object o = new Object();
			int hp = maxHp;
			boolean missed = false;
			for (int step = 0; step < 14 && hp > 0; step++)
			{
				int kind = random.nextInt(cleanFight ? 4 : 6);
				if (kind <= 2)
				{
					int dealt = Math.min(hp, random.nextInt(Math.max(1, maxHp / 3) + 1));
					hp -= dealt;
					t.hit(o, kind == 0 ? DAMAGE_ME : kind == 1 ? DAMAGE_OTHER : POISON, dealt);
				}
				else if (kind == 3)
				{
					int healed = Math.min(maxHp - hp, random.nextInt(Math.max(1, maxHp / 4) + 1));
					hp += healed;
					t.hit(o, HEAL, healed);
				}
				else if (kind == 4)
				{
					hp = Math.max(1, hp - random.nextInt(Math.max(1, maxHp / 3) + 1)); // a hit nobody told us about
					missed = true;
				}
				else
				{
					hp = Math.min(maxHp, hp + 1 + random.nextInt(Math.max(1, maxHp / 5))); // regeneration
					missed = true;
				}
				int ratio = ratioFor(hp, maxHp, scale);
				int shown = t.read(o, ratio, scale, false, maxHp);
				String at = "trial " + trial + " step " + step + ": true " + hp + "/" + maxHp + " scale " + scale
					+ " ratio " + ratio;
				if (shown == UNKNOWN)
				{
					rejected++;
					assertTrue(at + ": a clean fight is never rejected", missed);
					continue;
				}
				HpEstimate.Range range = HpEstimate.range(ratio, scale, maxHp);
				assertTrue(at + ": shown " + shown + " must lie in the bar's range", range.contains(shown));
				assertTrue(at + ": shown " + shown + " must lie in [0, maxHp]", shown >= 0 && shown <= maxHp);
				if (!missed)
				{
					assertEquals(at + ": nothing was missed, so it is exact", hp, shown);
					exact++;
				}
			}
		}
		assertTrue("exact answers were exercised: " + exact, exact > 5000);
		assertTrue("rejections were exercised: " + rejected, rejected > 500);
	}

	// ---- which hitsplat types move HP ----

	private static final int[] DAMAGE_TYPES = {DAMAGE_ME, DAMAGE_OTHER, DAMAGE_MAX_ME, POISON, VENOM, DISEASE};

	// Types left out on purpose, each for a stated reason (HpTracker's class comment): zero-amount blocks,
	// non-HP bars (prayer, sanity), colour and poise variants whose bar is not established, and effects whose HP
	// semantics nothing in the 1.13.1 sources establishes.
	private static final int[] IGNORED_TYPES = {
		BLOCK_ME, BLOCK_OTHER, DISEASE_BLOCKED, CYAN_UP, CYAN_DOWN,
		DAMAGE_ME_CYAN, DAMAGE_OTHER_CYAN, DAMAGE_ME_ORANGE, DAMAGE_OTHER_ORANGE, DAMAGE_MAX_ME_ORANGE,
		DAMAGE_ME_YELLOW, DAMAGE_ME_WHITE, DAMAGE_ME_POISE,
		CORRUPTION, PRAYER_DRAIN, BLEED, SANITY_DRAIN, SANITY_RESTORE, DOOM, BURN,
		-1, 9999
	};

	@Test
	public void everyDamageTypeLowersHp()
	{
		for (int type : DAMAGE_TYPES)
		{
			HpTracker t = new HpTracker();
			t.hit(npc, type, 21);
			assertEquals("type " + type, 64, t.read(npc, 22, SCALE, false, MAX));
		}
	}

	@Test
	public void healRaisesHp()
	{
		tracker.hit(npc, DAMAGE_ME, 30);
		tracker.hit(npc, HEAL, 9);
		assertEquals(64, tracker.read(npc, 22, SCALE, false, MAX));
	}

	@Test
	public void theIgnoredTypesNeverMoveHp()
	{
		for (int type : IGNORED_TYPES)
		{
			HpTracker t = new HpTracker();
			t.hit(npc, DAMAGE_ME, 20); // tracked 65
			t.hit(npc, type, 10); // counted, it would be 55, outside the bar's 65-67
			assertEquals("type " + type, 65, t.read(npc, ratioFor(65, MAX, SCALE), SCALE, false, MAX));
		}
	}

	@Test
	public void theTypeListsDoNotOverlap()
	{
		for (int d : DAMAGE_TYPES)
		{
			assertNotEquals(HEAL, d);
			for (int i : IGNORED_TYPES)
			{
				assertNotEquals("type " + d + " is both counted and ignored", d, i);
			}
		}
		for (int i : IGNORED_TYPES)
		{
			assertNotEquals("heal is also ignored", HEAL, i);
		}
	}

	// ---- stale: a remembered bar says what the NPC was, not what it is ----

	@Test
	public void aRememberedBarShowsTheTrackedValueWhenItLiesInsideIt()
	{
		damage(20);
		assertEquals(65, live(65));
		assertEquals("the bar went; 65 is still the last known value, not the midpoint 66", 65, stale(65));
		assertEquals(66, HpEstimate.estimate(ratioFor(65, MAX, SCALE), SCALE, MAX));
	}

	@Test
	public void hitsSinceTheRememberedBarThatPutTheValueOutsideItShowTheRememberedMidpoint()
	{
		damage(20);
		live(65);
		damage(30); // by someone else, while the bar was off the screen: tracked 35
		assertEquals("the remembered bar is 65-67 and 35 is not in it", UNKNOWN, stale(65));
	}

	@Test
	public void aStaleReadingNeitherRejectsNorReanchors()
	{
		damage(20);
		live(65);
		damage(30); // tracked 35
		assertEquals(UNKNOWN, stale(65));
		assertEquals("the stale mismatch did not kill the tracking: the bar is back and says 35", 35, live(35));

		HpTracker t = new HpTracker();
		t.hit(npc, DAMAGE_ME, 10); // tracked 75
		assertEquals(UNKNOWN, t.read(npc, ratioFor(45, MAX, SCALE), SCALE, false, MAX)); // rejected
		assertEquals("an exact but remembered reading is not an anchor", UNKNOWN, t.read(npc, SCALE, SCALE, true, MAX));
		assertEquals("the hit-tick mismatch did not reject it: 75 fits this bar and shows", 75,
			t.read(npc, ratioFor(75, MAX, SCALE), SCALE, false, MAX));
	}

	@Test
	public void aStaleReadingOfAnNpcNeverSeenLiveIsUnknown()
	{
		assertEquals(UNKNOWN, stale(65));
	}

	// ---- no reading, no value ----

	@Test
	public void noBarMeansNoValueAndChangesNothing()
	{
		damage(21);
		assertEquals(UNKNOWN, tracker.read(npc, -1, 0, false, MAX));
		assertEquals(UNKNOWN, tracker.read(npc, -1, -1, false, MAX));
		assertEquals(UNKNOWN, tracker.read(npc, 22, 0, false, MAX));
		assertEquals(UNKNOWN, tracker.read(npc, -1, SCALE, false, MAX));
		assertEquals("the tracking is intact", 64, tracker.read(npc, 22, SCALE, false, MAX));
	}

	@Test
	public void unknownMaxHpMeansNoValue()
	{
		damage(21);
		assertEquals(UNKNOWN, tracker.read(npc, 22, SCALE, false, 0));
		assertEquals(UNKNOWN, tracker.read(npc, 22, SCALE, false, -5));
	}

	@Test
	public void aBarThatCannotBeTrueHasNoValue()
	{
		damage(21);
		assertEquals("ratio above scale", UNKNOWN, tracker.read(npc, 31, SCALE, false, MAX));
	}

	// ---- one memory per NPC object ----

	/** equals() says every Same is every other Same; the tracker must still tell them apart. */
	private static final class Same
	{
		@Override
		public boolean equals(Object o)
		{
			return o instanceof Same;
		}

		@Override
		public int hashCode()
		{
			return 1;
		}
	}

	@Test
	public void eachNpcObjectIsTrackedOnItsOwnByIdentity()
	{
		Same a = new Same();
		Same b = new Same();
		tracker.hit(a, DAMAGE_ME, 21);
		assertEquals(64, tracker.read(a, 22, SCALE, false, MAX));
		assertEquals("b was never hit: it is still at 85, and the bar says 62-64", UNKNOWN,
			tracker.read(b, 22, SCALE, false, MAX));
		tracker.hit(b, DAMAGE_ME, 3);
		assertEquals(64, tracker.read(a, 22, SCALE, false, MAX));
	}

	@Test
	public void forgetDropsOneNpcAndClearDropsAll()
	{
		Object other = new Object();
		tracker.hit(npc, DAMAGE_ME, 21);
		tracker.hit(other, DAMAGE_ME, 21);
		tracker.forget(npc);
		assertEquals("npc starts over at full, which the bar contradicts", UNKNOWN, tracker.read(npc, 22, SCALE, false, MAX));
		assertEquals("other is untouched", 64, tracker.read(other, 22, SCALE, false, MAX));
		tracker.clear();
		assertEquals(UNKNOWN, tracker.read(other, 22, SCALE, false, MAX));
	}

	@Test
	public void aForgottenNpcCanBeTrackedAgainFromFull()
	{
		tracker.hit(npc, DAMAGE_ME, 21);
		tracker.forget(npc);
		assertEquals("a fresh object at full", MAX, tracker.read(npc, SCALE, SCALE, false, MAX));
		tracker.hit(npc, DAMAGE_ME, 21);
		assertEquals(64, tracker.read(npc, 22, SCALE, false, MAX));
	}
}
