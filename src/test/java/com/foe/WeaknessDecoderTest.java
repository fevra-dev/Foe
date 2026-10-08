package com.foe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/**
 * The fixtures are the values docs/probe/varp-5536.md recorded in game (client 1.13.1, 2026-10-03), by monster. The
 * encoding is the item ID of the elemental rune; nothing here is derived from a guess.
 */
public class WeaknessDecoderTest
{
	private static Weakness.Element element(int value)
	{
		Weakness w = WeaknessDecoder.decode(value);
		assertNotNull("decoded " + value, w);
		return w.getElement();
	}

	@Test
	public void theProbesValuesDecodeToTheMonstersWikiElement()
	{
		assertEquals("Blue dragon, 555 (0x22b)", Weakness.Element.WATER, element(555));
		assertEquals("Hill Giant, 557 (0x22d)", Weakness.Element.EARTH, element(557));
		assertEquals("Poison Scorpion, 554 (0x22a)", Weakness.Element.FIRE, element(554));
	}

	@Test
	public void airIsTheRuneIdNextToWaterAlthoughTheProbeNeverSawIt()
	{
		// 556 was expected and not observed (probe): fire 554, water 555, air 556, earth 557 are consecutive item IDs
		assertEquals(Weakness.Element.AIR, element(556));
	}

	@Test
	public void minusOneIsTheExplicitAnswerNoWeaknessAndNotUnknown()
	{
		// Chaos dwarf and Black Knight both read -1 (0xffffffff) in the probe
		assertSame(Weakness.NONE, WeaknessDecoder.decode(-1));
		assertNull("none has no element: it can never be drawn", Weakness.NONE.getElement());
	}

	@Test
	public void everythingElseIsUnknownAndNeverGuessed()
	{
		// 0 is what a logout resets to (probe: 0, 0, -1), not an answer. The neighbours of the four runes are other
		// runes (553 is not one, 558 is the mind rune), and the varp's own id is not a value of it.
		for (int value : new int[] {0, 1, -2, 553, 558, 5536, 0x22a << 1, Integer.MIN_VALUE, Integer.MAX_VALUE})
		{
			assertNull("value " + value, WeaknessDecoder.decode(value));
		}
	}

	@Test
	public void onlyMinusOneIsNone()
	{
		for (int value : new int[] {0, 554, 555, 556, 557})
		{
			assertEquals(false, WeaknessDecoder.decode(value) == Weakness.NONE);
		}
	}
}
