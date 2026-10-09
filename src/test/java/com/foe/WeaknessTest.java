package com.foe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Spec addendum 7: {@code Weakness} gains an optional percent. The learned store never has one (varp 5536 carries an
 * element only), the table sometimes does, and the two are now different values.
 */
public class WeaknessTest
{
	@Test
	public void theElementOnlyConstructorLeavesThePercentUnknown()
	{
		Weakness learned = new Weakness(Weakness.Element.FIRE);
		assertEquals(Weakness.Element.FIRE, learned.getElement());
		assertNull("a learned weakness has no percent", learned.getPercent());
	}

	@Test
	public void aLearnedFireAndATableFireFiftyAreDifferentValues()
	{
		Weakness learned = new Weakness(Weakness.Element.FIRE);
		Weakness table = new Weakness(Weakness.Element.FIRE, 50);
		assertNotEquals(learned, table);
		assertNotEquals(table, learned);
		assertNotEquals("the hash follows the equality", learned.hashCode(), table.hashCode());
		assertNotEquals("and the percent itself matters", table, new Weakness(Weakness.Element.FIRE, 51));
		assertEquals(table, new Weakness(Weakness.Element.FIRE, 50));
		assertEquals(table.hashCode(), new Weakness(Weakness.Element.FIRE, 50).hashCode());
		assertEquals(learned, new Weakness(Weakness.Element.FIRE, null));
	}

	@Test
	public void aPercentOverOneHundredIsHeldAsGiven()
	{
		assertEquals(Integer.valueOf(200), new Weakness(Weakness.Element.FIRE, 200).getPercent());
	}

	@Test
	public void noneHasNoElementAndNoPercent()
	{
		assertNull(Weakness.NONE.getElement());
		assertNull(Weakness.NONE.getPercent());
		assertEquals(new Weakness(null, null), Weakness.NONE);
	}
}
