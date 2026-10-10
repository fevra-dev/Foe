package com.foe;

/**
 * Which NPC the player last chose to attack (spec addendum 16, Task 11 review F2). Talk-to sets the player's
 * interacting target exactly as Attack does, so the interaction alone can't say whether the player is fighting. This
 * remembers the player's most recent click on an NPC, and only an attack click marks that NPC. FoePlugin decides what
 * counts as an attack click; this class holds the rule about which click is current.
 *
 * <p>Not thread safe: the client thread calls everything.
 *
 * @param <T> the NPC type; compared by identity, never by equals
 */
final class AttackIntent<T>
{
	/** The NPC the most recent NPC click attacked, or null when that click was not an attack (or nothing yet). */
	private T attacked;

	/** The player clicked an option on this NPC; {@code attack} says whether the option was an attack. */
	void clicked(T npc, boolean attack)
	{
		attacked = attack ? npc : null;
	}

	/** The player's most recent click on an NPC was an attack on this one. */
	boolean attacked(T npc)
	{
		return npc != null && attacked == npc;
	}

	/** This NPC died or despawned: its object won't come back. */
	void forget(T npc)
	{
		if (attacked == npc)
		{
			attacked = null;
		}
	}

	/** Logout, hop or plugin stop. */
	void reset()
	{
		attacked = null;
	}
}
