package com.foe;

import lombok.AllArgsConstructor;
import lombok.Value;

/**
 * An elemental weakness, e.g. FIRE, with the percent when it is known. Elements per the OSRS wiki
 * {@code [documented]}.
 *
 * <p>The percent comes only from the bundled wiki table (spec addendum 7): the client never receives one (varp 5536
 * holds the elemental rune's item ID and varp 5537 holds 0, docs/probe/varp-5536.md), so a weakness that was learned
 * in game has a null percent, and so does a table entry the wiki gave without one. It is not bounded to 100: the wiki
 * lists 200 for some monsters, and nothing here assumes otherwise.
 *
 * <p><b>Equality includes the percent</b> (Lombok's {@code @Value}), so a learned FIRE and a table FIRE 50 are
 * different values. Nothing compares one with the other: {@link WeaknessTable#resolve} and
 * {@link WeaknessStore#put} compare elements, and the one place that compares whole values
 * ({@link WeaknessLearner#tick}) only ever sees learned ones.
 */
@Value
@AllArgsConstructor
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
	static final Weakness NONE = new Weakness(null, null);

	Element element;

	/** The weakness percentage as the wiki gives it (0 and above 100 included), or null when unknown. */
	Integer percent;

	/** An element with no percent: what the game's varp gives, and so what is learned in game. */
	Weakness(Element element)
	{
		this(element, null);
	}
}
