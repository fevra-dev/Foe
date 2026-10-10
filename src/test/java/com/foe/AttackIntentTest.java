package com.foe;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Spec addendum 16 (Task 11 review F2): the player's own interaction adopts an NPC only when their most recent click
 * on an NPC was an attack on that same NPC. Talk-to sets the interacting target exactly as Attack does.
 */
public class AttackIntentTest
{
	private final AttackIntent<Object> intent = new AttackIntent<>();
	private final Object giant = new Object();
	private final Object man = new Object();

	@Test
	public void nothingIsAttackedBeforeAnyClick()
	{
		assertFalse(intent.attacked(giant));
	}

	@Test
	public void anAttackClickMarksThatNpcOnly()
	{
		intent.clicked(giant, true);
		assertTrue(intent.attacked(giant));
		assertFalse(intent.attacked(man));
	}

	@Test
	public void aLaterNonAttackClickOnAnyNpcClearsIt()
	{
		intent.clicked(giant, true);
		intent.clicked(man, false); // Talk-to the Man mid-fight
		assertFalse(intent.attacked(giant));
		assertFalse(intent.attacked(man));
	}

	@Test
	public void aNonAttackClickOnTheSameNpcClearsItToo()
	{
		intent.clicked(man, true);
		intent.clicked(man, false); // then Pickpocket it
		assertFalse(intent.attacked(man));
	}

	@Test
	public void theLatestAttackClickWins()
	{
		intent.clicked(giant, true);
		intent.clicked(man, true);
		assertFalse(intent.attacked(giant));
		assertTrue(intent.attacked(man));
	}

	@Test
	public void forgettingTheAttackedNpcClearsItAndForgettingAnotherDoesNot()
	{
		intent.clicked(giant, true);
		intent.forget(man);
		assertTrue(intent.attacked(giant));
		intent.forget(giant);
		assertFalse(intent.attacked(giant));
	}

	@Test
	public void resetClearsIt()
	{
		intent.clicked(giant, true);
		intent.reset();
		assertFalse(intent.attacked(giant));
	}
}
