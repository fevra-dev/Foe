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

	Element element;
}
