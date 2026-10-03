package com.foe;

import lombok.Value;

/** Everything the overlay draws, captured once per tick. 0 means a stat is unknown. */
@Value
class TargetSnapshot
{
	String name;
	int combatLevel;
	/** Estimated current HP, or HpEstimate.UNKNOWN. */
	int hp;
	/** 0 when unknown: the overlay then shows the bar only (spec). */
	int maxHp;
	/** Raw health ratio as given to the factory; -1 when the client has none. */
	int hpRatio;
	/** Raw health scale as given to the factory; 0 when the client has none. */
	int hpScale;
	/**
	 * True when hp, ratio and scale are the last known values because the live bar is missing (spec: "last known
	 * value, dimmed"). The overlay dims the HP segment. The memory itself lives in the plugin, not here.
	 */
	boolean hpStale;
	int attack;
	int strength;
	int defence;
	int ranged;
	int magic;
	/** null when there is none, or it is unknown or stale. */
	Weakness weakness;
}
