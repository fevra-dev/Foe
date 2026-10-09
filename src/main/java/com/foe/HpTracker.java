package com.foe;

import static net.runelite.api.HitsplatID.DAMAGE_ME;
import static net.runelite.api.HitsplatID.DAMAGE_MAX_ME;
import static net.runelite.api.HitsplatID.DAMAGE_OTHER;
import static net.runelite.api.HitsplatID.DISEASE;
import static net.runelite.api.HitsplatID.HEAL;
import static net.runelite.api.HitsplatID.POISON;
import static net.runelite.api.HitsplatID.VENOM;

import java.util.IdentityHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Exact HP from hitsplats (spec addendum 5). The bar only gives a range (ratio 22/30 on 85 max HP is 62-64); the
 * hitsplats give exact amounts. Per NPC object this keeps the damage taken, "max HP minus damage plus heals" in
 * effect, over every hitsplat seen on it, whoever dealt it, from the moment the NPC was first seen.
 *
 * <p>That number is a claim, not a fact: a monster hurt before it was seen, a hitsplat the client never sent,
 * regeneration and a form change all make it wrong. So it is only ever <em>shown</em> when the bar agrees:
 * {@link #read} returns it when it lies in the range the live bar allows ({@link HpEstimate#range}), and otherwise
 * answers {@link HpEstimate#UNKNOWN}, which makes the caller show the midpoint as before. Hence the guarantee: a
 * returned value is always inside the bar's range and inside {@code [0, maxHp]}. It is not always the truth (a
 * drift smaller than the bar's resolution is invisible) and where it is off it is off by at most the width of that
 * range; the midpoint is off by at most half of it.
 *
 * <p>A rejection is sticky: the count is dropped for that NPC and not trusted again until the bar gives an exact
 * reading ({@link HpEstimate.Range#isExact}: a full bar, an empty one, or a small monster the bar resolves), which
 * re-anchors it. Without that, a count that is wrong by the bar's resolution would be re-accepted whenever a later
 * reading happened to fall on it.
 *
 * <p>Stale readings (the remembered bar, shown dimmed) are read but never judged. A remembered bar says what the NPC
 * was, not what it is: a mismatch with the count may only mean the NPC was hit while its bar was off the screen. So a
 * stale read returns the count when it fits the remembered range (the usual case: the last live reading was exact
 * and nothing happened since, so the value does not jump to the midpoint when the bar goes), and otherwise answers
 * UNKNOWN, but it neither rejects the count nor re-anchors it. Only a live bar does either.
 *
 * <p>An NPC with no bar reading at all (a monster shows none until it takes damage), or no known max HP, has no
 * current HP to show, tracked or not, so nothing is returned and nothing changes.
 *
 * <h2>Which hitsplat types move HP</h2>
 * Verified against runelite-api 1.13.1 ({@code HitsplatID}, {@code Hitsplat}) and the client sources:
 * <table>
 * <caption>Hitsplat types</caption>
 * <tr><th>type</th><th>effect</th><th>why</th></tr>
 * <tr><td>DAMAGE_ME 16, DAMAGE_OTHER 17, DAMAGE_MAX_ME 43</td><td>damage</td>
 *     <td>the plain hit, yours and other players' (others' max hits arrive as 17); ZalcanoPlugin counts exactly
 *     16 and 43 as health damage</td></tr>
 * <tr><td>POISON 65, VENOM 5, DISEASE 4</td><td>damage</td><td>damage over time to HP (operator decision)</td></tr>
 * <tr><td>HEAL 6</td><td>heal, never above max HP</td><td>adds back</td></tr>
 * <tr><td>BLOCK_ME 12, BLOCK_OTHER 13, DISEASE_BLOCKED 3</td><td>ignored</td><td>a blocked hit does 0</td></tr>
 * <tr><td>the colour variants 18-25, 44-47 and the poise variants 53-55</td><td>ignored</td>
 *     <td>{@code Hitsplat.isMine/isOthers} class them as damage, but ZalcanoPlugin reads DAMAGE_*_ORANGE as damage to
 *     her <em>shield</em>, not her health: some of them are another bar. Which is not established {@code [assumed]},
 *     and counting a shield hit would make the count wrong. They only appear on special bosses.</td></tr>
 * <tr><td>BURN 74, BLEED 67, DOOM 73, CORRUPTION 0</td><td>ignored</td>
 *     <td>nothing in the 1.13.1 sources says what these do to an NPC's HP {@code [assumed]}</td></tr>
 * <tr><td>PRAYER_DRAIN 60, SANITY_DRAIN 71, SANITY_RESTORE 72, CYAN_UP 11, CYAN_DOWN 15</td><td>ignored</td>
 *     <td>not hitpoints</td></tr>
 * <tr><td>any other (a type a later release adds)</td><td>ignored</td><td>unknown is never guessed</td></tr>
 * </table>
 * Ignoring a type that does move HP, or counting one that does not, has the same safe outcome: the count drifts, the
 * bar contradicts it, and the midpoint is shown, as before this class existed. Re-check this table when a release
 * adds {@code HitsplatID} constants.
 *
 * <p>Memory is kept per NPC object, compared by identity, so a reused NPC index starts empty. An entry lives until
 * the NPC dies or despawns ({@link #forget}) or the plugin forgets everything on logout, hop or stop
 * ({@link #clear}); like {@link HpMemory}, despawn bounds the map to NPCs still in the scene.
 */
@Slf4j
final class HpTracker
{
	/** The most drift a current bar may correct by resync: one or two unseen regenerations. Beyond it, the count
	 * is treated as wrong rather than nudged (2026-10-08: all six measured mismatches were exactly 1). */
	static final int MAX_DRIFT = 2;

	private static final class Track
	{
		/** Damage taken minus heals, never below 0. A long, so a pathological amount cannot wrap into a believable one. */
		long damage;
		/** A hitsplat landed since the last live read: the bar may not have caught up yet (a client-side delay). */
		boolean hitSinceRead;
		/** False once a current live bar contradicted the count by more than regeneration explains; true again only
		 * after an exact live reading. */
		boolean valid = true;
	}

	private final Map<Object, Track> tracks = new IdentityHashMap<>();

	/** A hitsplat landed on this NPC. Any hitsplat on any NPC, whoever dealt it. */
	void hit(Object npc, int hitsplatType, int amount)
	{
		track(npc).hitSinceRead = true; // any hitsplat, even a 0: its bar update may lag behind it
		if (amount <= 0)
		{
			return;
		}
		switch (hitsplatType)
		{
			case DAMAGE_ME:
			case DAMAGE_OTHER:
			case DAMAGE_MAX_ME:
			case POISON:
			case VENOM:
			case DISEASE:
				track(npc).damage += amount;
				break;
			case HEAL:
			{
				// The game stops healing at max HP, so a heal can undo damage but never create HP.
				Track t = track(npc);
				t.damage = Math.max(0, t.damage - amount);
				break;
			}
			default:
				break; // see the class comment
		}
	}

	/**
	 * The exact HP to show for this NPC, or {@link HpEstimate#UNKNOWN} to show the midpoint.
	 *
	 * @param ratio  the bar reading (live, or the remembered one when {@code stale})
	 * @param stale  the reading is the remembered bar, not a live one
	 * @param maxHp  the max HP the panel shows (SnapshotFactory's), 0 when unknown
	 */
	int read(Object npc, int ratio, int scale, boolean stale, int maxHp)
	{
		HpEstimate.Range range = HpEstimate.range(ratio, scale, maxHp);
		if (range == null)
		{
			return HpEstimate.UNKNOWN;
		}
		Track t = tracks.get(npc);
		if (t == null)
		{
			if (stale)
			{
				return HpEstimate.UNKNOWN;
			}
			t = track(npc); // first seen: assumed to be at full health
		}
		boolean hitThisTick = t.hitSinceRead;
		if (!stale)
		{
			t.hitSinceRead = false;
		}
		if (!stale && range.isExact())
		{
			t.damage = maxHp - range.getMin();
			t.valid = true;
			return range.getMin();
		}
		if (!t.valid)
		{
			return HpEstimate.UNKNOWN;
		}
		long hp = maxHp - t.damage;
		if (hp >= range.getMin() && hp <= range.getMax())
		{
			return (int) hp;
		}
		if (stale || hitThisTick)
		{
			// A remembered bar can be out of date, and a live one may not reflect this tick's hit yet (the client can
			// delay a bar update; HitsplatApplied is posted at once). Show the midpoint and keep the count as it is.
			return HpEstimate.UNKNOWN;
		}
		// A live bar with no hit since the last read is current, so the count has drifted.
		long resynced = Math.max(range.getMin(), Math.min(range.getMax(), hp));
		if (Math.abs(resynced - hp) > MAX_DRIFT)
		{
			// More than regeneration explains: damage Foe never saw (a relog, another player before Foe looked). The
			// count is wrong by an unknown amount, so show the midpoint until an exact reading re-anchors it.
			t.valid = false;
			log.debug("Tracked HP {} is {} outside the bar's {}-{}: midpoint until an exact reading", hp,
				Math.abs(resynced - hp), range.getMin(), range.getMax());
			return HpEstimate.UNKNOWN;
		}
		// Unseen regeneration (1 HP about once a minute; measured 2026-10-08 as six mismatches of exactly -1). Move
		// the count to the nearest value the bar allows, which after one regeneration is the true value.
		log.debug("Tracked HP {} outside the bar's {}-{}: resynced to {}", hp, range.getMin(), range.getMax(), resynced);
		t.damage = maxHp - resynced;
		return (int) resynced;
	}

	/** This NPC died or despawned: its object will not come back, and its index may be reused. */
	void forget(Object npc)
	{
		tracks.remove(npc);
	}

	/** Forget every NPC: logout, hop, or the plugin stopped. */
	void clear()
	{
		tracks.clear();
	}

	private Track track(Object npc)
	{
		Track t = tracks.get(npc);
		if (t == null)
		{
			t = new Track();
			tracks.put(npc, t);
		}
		return t;
	}
}
