package com.foe;

import lombok.Value;
import lombok.With;

/** Everything the overlay draws, captured once per tick. 0 means a stat is unknown. */
@Value
class TargetSnapshot
{
	String name;
	int combatLevel;
	/**
	 * Current HP, or HpEstimate.UNKNOWN. Always UNKNOWN for an unhit target. Usually the midpoint of what the bar
	 * allows (an estimate); the exact value instead when HpTracker has counted the hitsplats and the bar agrees.
	 * {@code @With} so the plugin can swap it in without the factory knowing about the tracker.
	 */
	@With
	int hp;
	/** 0 when unknown: the overlay then shows the bar only (spec). */
	int maxHp;
	/** Health ratio as given to the factory; -1 when the client has none, and for an unhit target. */
	int hpRatio;
	/** Health scale as given to the factory; 0 when the client has none. */
	int hpScale;
	/**
	 * True when hp, ratio and scale are the last known values because the live bar is missing (spec: "last known
	 * value, dimmed"). The overlay draws the HP in the stale style. The memory itself lives in the plugin, not here.
	 * Never true together with {@link #hpUnhit}: nothing was remembered.
	 */
	boolean hpStale;
	/**
	 * True when max HP is known and Foe has never seen a health bar on this NPC (a monster shows none until it takes
	 * damage). The current HP is then unknown, and an invented value is never shown (spec, Missing data): the overlay
	 * draws an empty outlined bar with the max HP as its only text (addendum 4). It is "never seen", not "never hit":
	 * after a relog or for a monster another player damaged first, it is also true. hp is UNKNOWN, ratio -1, scale 0.
	 */
	boolean hpUnhit;
	int attack;
	int strength;
	int defence;
	int ranged;
	int magic;
	/** null when there is none, or it is unknown or stale. */
	Weakness weakness;
}
