package com.foe;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Learns which monster type has which elemental weakness, and only from a confirmed spell impact.
 *
 * <p>The client gives one number, varp 5536, that holds "the weakness of the last NPC you cast a spell at"
 * (docs/probe/varp-5536.md). It does not say which NPC. The trace (docs/probe/raw-task8.txt) shows that every write
 * arrives in the same tick as an impact spot-anim (GraphicChanged) on the NPC the spell landed on, and that other
 * players' impacts on nearby NPCs never write our varp. So a change is credited only when it can be tied to one NPC:
 *
 * <ol>
 * <li>A change is buffered for the current tick only. Nothing is carried to a later tick.
 * <li>Every NPC that got a spot-anim this tick is buffered too (each once, by identity).
 * <li>On {@link #tick}, the NPCs among them that the player is fighting are the candidates. Exactly one candidate,
 *     a value that decodes, and a type that may be credited: the entry is written. Anything else is dropped, never
 *     guessed, and a dropped change writes nothing and erases nothing.
 * </ol>
 *
 * <p>Entries are kept per type, by the key the caller's {@code creditKey} gives (the transformed composition id, see
 * FoePlugin), for as long as this object lives: a type's weakness does not change between fights, logouts or hops.
 * This class does not touch the disk. It tells its {@code onLearned} callback whenever a credit adds or changes an
 * entry (FoePlugin saves it through {@link WeaknessStore}), and {@link #load} replaces the cache with what was saved
 * (FoePlugin calls it on the first tick after the plugin starts). Both run on the client thread, the same thread as
 * {@link #tick}, so a load can never interleave with a credit.
 *
 * <p>Known limits, none of which can show a false weakness:
 * <ul>
 * <li>The game posts no event when a write leaves the value unchanged, so a second spell on another type with the
 *     same element teaches nothing. That type shows no weakness until a spell on it changes the value.
 * <li>Whether a splashed spell writes the varp is unknown; if it does, the credit is still the right NPC.
 * <li>One AoE that hits two fought NPCs is dropped, even when both are the same type and the credit would be safe.
 * </ul>
 *
 * <p>The cache is a ConcurrentHashMap out of caution only: every reader and writer of it is the client thread. The
 * per-tick buffers are touched by the client thread and, on stop/start, by the Swing thread (FoePlugin discards them);
 * an overlap there can at worst drop or misplace one tick's buffer.
 *
 * @param <T> the NPC type; compared by identity, never by equals
 */
final class WeaknessLearner<T>
{
	/** What {@link #tick} did, so a caller or a test can tell "dropped because X" from "nothing to do". */
	enum Outcome
	{
		/** No varp 5536 change this tick: nothing to credit. */
		NO_CHANGE,
		/** The change was tied to exactly one NPC and written to its type. */
		CREDITED,
		/** The value is not a rune or -1 (a logout resets it to 0). */
		UNKNOWN_VALUE,
		/** The varp changed to two different values in one tick (the logout reset is 0, 0, -1). */
		CONFLICTING_CHANGES,
		/** No NPC that the player is fighting got a spot-anim this tick. */
		NO_CANDIDATE,
		/** Two or more did (an AoE): the varp does not say whose weakness it holds. */
		SEVERAL_CANDIDATES,
		/** One did, but an NPC the player is not fighting got the same spell graphic this tick: an AoE whose varp
		 * may hold the bystander's weakness (never probed), so it is dropped. */
		AOE_BYSTANDER,
		/** The one candidate may not be credited (dying, not a combat NPC, no composition). */
		NOT_CREDITABLE
	}

	private final Map<Integer, Weakness> known = new ConcurrentHashMap<>();
	private final BiConsumer<Integer, Weakness> onLearned;

	/** A learner that remembers nothing beyond this object's life. */
	WeaknessLearner()
	{
		this((key, w) ->
		{
		});
	}

	/**
	 * @param onLearned told the type key and the weakness whenever a credit adds an entry or changes one, never for a
	 *                  credit that leaves the entry as it was, and never for {@link #load}. It runs inside {@link #tick}
	 *                  before the buffers are emptied, so it must not throw; FoePlugin's does not
	 */
	WeaknessLearner(BiConsumer<Integer, Weakness> onLearned)
	{
		this.onLearned = onLearned;
	}

	private boolean changed;
	private int value;
	private boolean conflicting;
	private final List<T> impacts = new ArrayList<>();
	/** Spot-anim ids seen on each impacted NPC this tick; an NPC impacted without ids has an empty set. */
	private final Map<T, Set<Integer>> anims = new IdentityHashMap<>();

	/** Varp 5536 changed to {@code newValue}. */
	void varpChanged(int newValue)
	{
		if (changed && newValue != value)
		{
			conflicting = true;
		}
		changed = true;
		value = newValue;
	}

	/** This NPC got a new spot-anim (GraphicChanged). Counted once per tick, whatever the number of events. */
	void impact(T npc, int... spotAnimIds)
	{
		Set<Integer> ids = anims.get(npc);
		if (ids == null)
		{
			ids = new HashSet<>();
			anims.put(npc, ids);
			impacts.add(npc);
		}
		for (int id : spotAnimIds)
		{
			ids.add(id);
		}
	}

	/**
	 * GameTick: credit the tick's change if it can be tied to exactly one NPC, then empty the buffers whatever
	 * happened.
	 *
	 * @param involved   the player is fighting this NPC: it is the live target, the player is interacting with it, or
	 *                   it is interacting with the player. Judged now, at the tick, so the order of the events within
	 *                   the tick does not matter
	 * @param creditKey  the key to credit this NPC's type under, or null when it may not be credited
	 */
	Outcome tick(Predicate<T> involved, Function<T, Integer> creditKey)
	{
		try
		{
			if (!changed)
			{
				return Outcome.NO_CHANGE;
			}
			if (conflicting)
			{
				return Outcome.CONFLICTING_CHANGES;
			}
			Weakness decoded = WeaknessDecoder.decode(value);
			if (decoded == null)
			{
				return Outcome.UNKNOWN_VALUE;
			}
			T sole = null;
			for (T npc : impacts)
			{
				if (involved.test(npc))
				{
					if (sole != null)
					{
						return Outcome.SEVERAL_CANDIDATES;
					}
					sole = npc;
				}
			}
			if (sole == null)
			{
				return Outcome.NO_CANDIDATE;
			}
			Set<Integer> soleIds = anims.get(sole);
			for (T npc : impacts)
			{
				if (npc != sole && !involved.test(npc) && !java.util.Collections.disjoint(soleIds, anims.get(npc)))
				{
					return Outcome.AOE_BYSTANDER;
				}
			}
			Integer key = creditKey.apply(sole);
			if (key == null)
			{
				return Outcome.NOT_CREDITABLE;
			}
			if (!decoded.equals(known.put(key, decoded)))
			{
				onLearned.accept(key, decoded);
			}
			return Outcome.CREDITED;
		}
		finally
		{
			discardTick();
		}
	}

	/** The weakness to show for this type, or null: not known, or known to have none. */
	Weakness weaknessFor(int key)
	{
		Weakness w = known.get(key);
		return w == Weakness.NONE ? null : w;
	}

	/** Forget this tick's change and impacts, keep what was learned: logout or hop starts a new session. */
	void discardTick()
	{
		changed = false;
		conflicting = false;
		impacts.clear();
		anims.clear();
	}

	/**
	 * Replace what is known with what was saved. Not a credit, so nothing is reported to {@code onLearned}, and this
	 * tick's buffers are left alone.
	 */
	void load(Map<Integer, Weakness> stored)
	{
		known.clear();
		known.putAll(stored);
	}
}
