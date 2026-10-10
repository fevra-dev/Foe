package com.foe;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The saved form of what {@link WeaknessLearner} has learned (spec addendum 5): one string, {@code typeId:ELEMENT}
 * joined by commas, for example {@code 2103:EARTH,3025:FIRE,2006:NONE}. The type id is the transformed composition id
 * FoePlugin keys the cache by; the element is {@code AIR}, {@code WATER}, {@code EARTH}, {@code FIRE}, or {@code NONE}
 * for the game's explicit "this monster has no weakness". Ids are written in ascending order, so the value is stable.
 *
 * <p>It lives in RuneLite's config under the plugin's own group ({@link FoeConfig#GROUP}) as the single key
 * {@link #KEY}, written through {@code ConfigManager.setConfiguration} (the active config profile, not the per-account
 * RuneScape profile: a monster type's weakness is game data and is the same for every account). The key is
 * deliberately not a {@code @ConfigItem}: a declared item would show in the settings panel, and ConfigPanel's Reset
 * button unsets every declared key. RuneLite's ConfigManager stores and returns any group/key pair (it filters by
 * neither declaration nor group), and keeps it across plugin restarts; it reaches disk on its own schedule (every
 * five minutes and at client shutdown), so a crash can lose the last few entries, which then simply get taught again.
 *
 * <p><b>Reading is a trust boundary</b> (ADR-0006, ADR-0005). The value comes off the disk, where it can be edited or
 * corrupted. {@link #parse} lets through only an ASCII id of at most nine digits and one of the five known names, in
 * ASCII-case-insensitive form; every other token is ignored without a word and without an exception, and at most
 * {@link #MAX_ENTRIES} tokens are looked at. Nothing from the stored text is ever logged or executed.
 *
 * <p><b>Writing is a merge, never a copy of memory.</b> {@link #put} reads the stored value, changes the one entry,
 * and writes the result back. So no ordering of stop, start, a late credit and a load can erase another entry:
 * whichever thread writes, the stored value only ever gains or changes the entry being written. (Writing the
 * in-memory map out instead would let a credit that lands while that map is empty, just after a stop, overwrite the
 * whole store with one entry.) Malformed tokens in the stored value are dropped by the rewrite.
 *
 * <p>Known limits: an entry a newer version writes in a form this one cannot read (a new element name) is dropped the
 * first time this version rewrites the value. A wrong entry persists until a confirmed spell credit on that type
 * replaces it (spec addendum 5).
 */
final class WeaknessStore
{
	/** The one key under {@link FoeConfig#GROUP}. Not a setting; see the class comment. */
	static final String KEY = "learnedWeaknesses";

	/** Tokens read from one stored value. Real data is a few thousand at most (one per combat NPC type seen). */
	static final int MAX_ENTRIES = 50_000;

	/** Composition ids are below 10^5 today; nine digits cannot overflow an int and nothing real is longer. */
	private static final int MAX_ID_DIGITS = 9;

	private static final String NONE = "NONE";

	private final Supplier<String> read;
	private final Consumer<String> write;

	/**
	 * @param read  the stored value, or null when there is none (ConfigManager.getConfiguration)
	 * @param write stores a value (ConfigManager.setConfiguration); may throw, and the caller decides what that means
	 */
	WeaknessStore(Supplier<String> read, Consumer<String> write)
	{
		this.read = read;
		this.write = write;
	}

	/** Everything usable in the stored value; never null, never throws. */
	static Map<Integer, Weakness> parse(String raw)
	{
		Map<Integer, Weakness> out = new TreeMap<>();
		if (raw == null)
		{
			return out;
		}
		int pos = 0;
		for (int tokens = 0; pos <= raw.length() && tokens < MAX_ENTRIES; tokens++)
		{
			int end = raw.indexOf(',', pos);
			if (end < 0)
			{
				end = raw.length();
			}
			entry(raw.substring(pos, end), out);
			pos = end + 1;
		}
		return out;
	}

	/** The stored form of these entries, in ascending id order. An entry that could not be read back is left out. */
	static String format(Map<Integer, Weakness> entries)
	{
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<Integer, Weakness> e : new TreeMap<>(entries).entrySet())
		{
			if (e.getKey() < 0 || e.getValue() == null)
			{
				continue;
			}
			if (sb.length() > 0)
			{
				sb.append(',');
			}
			Weakness.Element element = e.getValue().getElement();
			sb.append(e.getKey()).append(':').append(element == null ? NONE : element.name());
		}
		return sb.toString();
	}

	/** What is stored now. Reads the key once. */
	Map<Integer, Weakness> load()
	{
		return parse(read.get());
	}

	/**
	 * Store this entry: read the stored value, change this one entry, write it back. Writes nothing when the entry is
	 * already stored as it is, so a repeated credit costs a read and no config change.
	 */
	void put(int key, Weakness w)
	{
		if (key < 0 || w == null)
		{
			return;
		}
		String raw = read.get();
		if (atCap(raw))
		{
			return; // rewriting from a capped parse would delete every entry past the cap (review of 77ccc4e, F5)
		}
		Map<Integer, Weakness> stored = parse(raw);
		Weakness held = stored.get(key);
		// The element, not Weakness.equals: the store holds elements only (spec addendum 7 gave Weakness a percent, and
		// equality includes it), so a percent must neither be stored nor make an unchanged entry look changed.
		if (held != null && held.getElement() == w.getElement())
		{
			return;
		}
		stored.put(key, w); // format() writes the element alone, so a percent on w goes no further
		write.accept(format(stored));
	}

	/**
	 * True when the value has at least {@link #MAX_ENTRIES} tokens, so {@link #parse} did not read all of it or a
	 * rewrite with one more would not be read back whole. Tokens are commas plus one (Task 11 review F6: counting
	 * MAX_ENTRIES commas let exactly MAX_ENTRIES tokens through).
	 */
	static boolean atCap(String raw)
	{
		if (raw == null)
		{
			return false;
		}
		int commas = 0;
		for (int i = 0; i < raw.length(); i++)
		{
			if (raw.charAt(i) == ',' && ++commas >= MAX_ENTRIES - 1)
			{
				return true;
			}
		}
		return false;
	}

	private static void entry(String token, Map<Integer, Weakness> out)
	{
		int colon = token.indexOf(':');
		if (colon < 0)
		{
			return;
		}
		int id = id(token.substring(0, colon).trim());
		Weakness w = weakness(token.substring(colon + 1).trim());
		if (id >= 0 && w != null)
		{
			out.put(id, w);
		}
	}

	/** An ASCII number of 1-9 digits, else -1. Not Integer.parseInt, which accepts signs and non-ASCII digits. */
	private static int id(String s)
	{
		int n = s.length();
		if (n == 0 || n > MAX_ID_DIGITS)
		{
			return -1;
		}
		int v = 0;
		for (int i = 0; i < n; i++)
		{
			char c = s.charAt(i);
			if (c < '0' || c > '9')
			{
				return -1;
			}
			v = v * 10 + (c - '0');
		}
		return v;
	}

	private static Weakness weakness(String name)
	{
		if (sameName(name, NONE))
		{
			return Weakness.NONE;
		}
		for (Weakness.Element e : Weakness.Element.values())
		{
			if (sameName(name, e.name()))
			{
				return new Weakness(e);
			}
		}
		return null;
	}

	/** Equal ignoring ASCII case only; String.equalsIgnoreCase would also fold a dotless i or a long s into one. */
	private static boolean sameName(String s, String upperAscii)
	{
		if (s.length() != upperAscii.length())
		{
			return false;
		}
		for (int i = 0; i < s.length(); i++)
		{
			char c = s.charAt(i);
			if (c >= 'a' && c <= 'z')
			{
				c -= 'a' - 'A';
			}
			if (c != upperAscii.charAt(i))
			{
				return false;
			}
		}
		return true;
	}
}
