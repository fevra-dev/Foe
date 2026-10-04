package com.foe;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import net.runelite.api.GameState;

/**
 * The decisions between RuneLite's events and {@link TargetTracker}, on plain objects, booleans and milliseconds so
 * they can be unit tested and replayed from docs/probe/raw.log without a client. FoePlugin only reads facts off the
 * NPC objects and calls in here. This class owns the target NPC object, so there is no second copy to drift from
 * the tracker's index.
 *
 * <p>Rules, each pinned by TargetFeedTest and TargetReplayTest:
 * <ul>
 * <li>Only combat NPCs count (spec addendum 2, Talk-to): the caller says which, this class honours it.
 * <li>Engaging an NPC always wins ({@code playerAttacks}). Your own landed hit is not an engagement
 *     ({@code playerHit}): a hit already in flight can land after you switch (raw.log lines 265-267).
 * <li>A hit may only replace the target when the target is not being fought right now. "Right now" is the time
 *     test (within the linger) <em>or</em> the caller's {@code fighting} predicate, evaluated at the event: at
 *     linger 0 nothing is live between ticks, so the time test alone would let every hit take the panel.
 * <li>Every event that makes an NPC the target, or refreshes it, is also evidence for the next {@link #tick}. At
 *     linger 0 the tracker's own check is exact to the millisecond and events precede GameTick by some
 *     milliseconds, so the tick must refresh the target itself when it has evidence (amendment 6).
 * <li>{@link #gone} drops the target and its object, so a dead or despawned NPC cannot be revived by its
 *     interacting flag (amendment 5).
 * </ul>
 *
 * <p>Not thread safe: the client thread calls everything.
 *
 * @param <T> the NPC type; compared by identity, never by equals
 */
final class TargetFeed<T>
{
	// The bounds of FoeConfig.lingerSeconds' @Range. @Range is enforced only by the settings spinner (client
	// 1.13.1), so a profile can hold any int. TargetFeedTest.theClampIsTheRangeTheSettingDeclares keeps these two
	// places equal.
	private static final int MIN_LINGER_SECONDS = 0;
	private static final int MAX_LINGER_SECONDS = 60;

	private final ToIntFunction<T> indexOf;
	private TargetTracker tracker = new TargetTracker();
	/** The NPC the tracker's target index refers to. Kept after the linger lapses, so a resumed fight revives it. */
	private T held;
	/** Combat evidence for {@link #held} that arrived since the last tick. */
	private boolean evidence;

	TargetFeed(ToIntFunction<T> indexOf)
	{
		this.indexOf = indexOf;
	}

	/** The player's interacting target changed to this NPC. Always wins, provided it is a combat NPC. */
	void playerEngaged(T npc, boolean combatNpc, long nowMs)
	{
		if (!combatNpc)
		{
			return;
		}
		tracker.playerAttacks(indexOf.applyAsInt(npc), nowMs);
		held = npc;
		evidence = true;
	}

	/** One of the player's hits landed on this NPC. */
	void playerHit(T npc, boolean combatNpc, long nowMs, long lingerMs, Predicate<T> fighting)
	{
		if (!combatNpc)
		{
			return;
		}
		refreshIfFightingNow(nowMs, fighting);
		tracker.playerHit(indexOf.applyAsInt(npc), nowMs, lingerMs);
		adoptIfTarget(npc, nowMs, lingerMs);
	}

	/**
	 * Something hit the player, and these are the combat NPCs that are interacting with the player. The client does
	 * not say whose hit it was, so: the live target if it is among them, otherwise the first of them. That is a known
	 * limit. Every candidate is an NPC that really is fighting the player, so what is shown is never a bystander, but
	 * with several candidates and no live target it may not be the one whose hit landed.
	 */
	void playerHurt(List<T> hitters, long nowMs, long lingerMs, Predicate<T> fighting)
	{
		if (hitters.isEmpty())
		{
			return;
		}
		refreshIfFightingNow(nowMs, fighting);
		T live = current(nowMs, lingerMs);
		T hitter = live != null && hitters.contains(live) ? live : hitters.get(0);
		tracker.hitBy(indexOf.applyAsInt(hitter), nowMs, lingerMs);
		adoptIfTarget(hitter, nowMs, lingerMs);
	}

	/**
	 * The NPC died or despawned. Its index may be reused, so neither the index nor the object may linger.
	 *
	 * @return true when it was the target, so the caller can drop whatever it drew for it without waiting a tick
	 */
	boolean gone(T npc)
	{
		tracker.gone(indexOf.applyAsInt(npc));
		if (held == npc)
		{
			held = null;
			return true;
		}
		return false;
	}

	/**
	 * GameTick. {@code fighting} says whether the player and the given NPC are interacting with each other, either
	 * way round. Returns the NPC to show on this tick, or null. One {@code nowMs} serves both the refresh and the
	 * live check, so a target refreshed on this tick is live on this tick whatever the linger (amendment 2).
	 */
	T tick(long nowMs, long lingerMs, Predicate<T> fighting)
	{
		if (held == null)
		{
			return null;
		}
		int idx = indexOf.applyAsInt(held);
		if (evidence || fighting.test(held))
		{
			tracker.stillFighting(idx, nowMs);
		}
		evidence = false;
		return tracker.current(nowMs, lingerMs) == idx ? held : null;
	}

	/**
	 * Logout, hop or plugin stop: forget the target. Pending evidence needs no clearing: it only ever applies to
	 * {@link #held}, and every call that sets {@code held} sets it afresh.
	 */
	void reset()
	{
		tracker = new TargetTracker();
		held = null;
	}

	/** The target if it is live at {@code nowMs}, else null. Reading changes nothing. */
	private T current(long nowMs, long lingerMs)
	{
		return held != null && tracker.current(nowMs, lingerMs) == indexOf.applyAsInt(held) ? held : null;
	}

	/** The held target is being fought at this instant, so it counts as live for a hit that might replace it. */
	private void refreshIfFightingNow(long nowMs, Predicate<T> fighting)
	{
		if (held != null && fighting.test(held))
		{
			tracker.stillFighting(indexOf.applyAsInt(held), nowMs);
			evidence = true;
		}
	}

	/** After a tracker call for {@code npc}: it is the target now if it was adopted or refreshed, and that is evidence. */
	private void adoptIfTarget(T npc, long nowMs, long lingerMs)
	{
		if (tracker.current(nowMs, lingerMs) == indexOf.applyAsInt(npc))
		{
			held = npc;
			evidence = true;
		}
	}

	/** Clamps the setting to its declared range before turning it into milliseconds. */
	static long lingerMs(int seconds)
	{
		return Math.max(MIN_LINGER_SECONDS, Math.min(MAX_LINGER_SECONDS, seconds)) * 1000L;
	}

	/**
	 * Whether this game state ends the fight: logout, a world hop, a lost connection. LOADING is not one, so a
	 * region boundary in the middle of a fight does not drop the panel (NPCs that leave the scene arrive as
	 * NpcDespawned), and LOGGED_IN is the state in which the fight is on.
	 */
	static boolean endsTheFight(GameState state)
	{
		return state != GameState.LOGGED_IN && state != GameState.LOADING;
	}
}
