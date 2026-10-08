package com.foe;

import lombok.Value;

/**
 * An elemental weakness, e.g. FIRE. Element only: the client never receives the percent (varp 5536 holds the
 * elemental rune's item ID and varp 5537 holds 0, docs/probe/varp-5536.md). Elements per the OSRS wiki
 * {@code [documented]}.
 */
@Value
class Weakness
{
	enum Element
	{
		AIR, WATER, EARTH, FIRE
	}

	/**
	 * The game's explicit answer "this monster has no weakness" (varp 5536 = -1), as opposed to {@code null}, which
	 * means "not known". It has no element, so it can never be drawn: nothing may put it on a snapshot, and
	 * {@link WeaknessLearner#weaknessFor} turns it into null.
	 */
	static final Weakness NONE = new Weakness(null);

	Element element;
}
