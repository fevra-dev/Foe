package com.foe;

/**
 * Varp 5536 (VarPlayerID.LAST_NPC_ELEMENTAL_WEAKNESS) -> Weakness. Pure.
 *
 * <p>The value is the item ID of the elemental rune (docs/probe/varp-5536.md: 554 fire, 555 water, 557 earth seen in
 * game, 556 air expected and not seen; ItemID.FIRERUNE/WATERRUNE/AIRRUNE/EARTHRUNE in the 1.13.1 API are 554-557),
 * and -1 means the monster has none. The literals are kept rather than the API constants so a RuneLite release that
 * renames them (the build tracks {@code latest.release}) cannot break this; the test pins the probe's values.
 */
final class WeaknessDecoder
{
	private static final Weakness FIRE = new Weakness(Weakness.Element.FIRE);
	private static final Weakness WATER = new Weakness(Weakness.Element.WATER);
	private static final Weakness AIR = new Weakness(Weakness.Element.AIR);
	private static final Weakness EARTH = new Weakness(Weakness.Element.EARTH);

	private WeaknessDecoder()
	{
	}

	/**
	 * @return the weakness; {@link Weakness#NONE} for -1 (the game says there is none); null for anything else
	 *     (0 is what a logout resets to, not an answer): unknown is never guessed
	 */
	static Weakness decode(int value)
	{
		switch (value)
		{
			case 554:
				return FIRE;
			case 555:
				return WATER;
			case 556:
				return AIR;
			case 557:
				return EARTH;
			case -1:
				return Weakness.NONE;
			default:
				return null;
		}
	}
}
