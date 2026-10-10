package com.foe;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The bundled wiki weakness table (spec addenda 7 to 9), read at runtime, and the rule for combining it with what was
 * learned in game.
 *
 * <p>The file is {@value #RESOURCE}: {@code #} header lines, then {@code id<TAB>ELEMENT<TAB>percent}, the percent empty
 * for NONE and for an element the wiki gave no single percent for. It is written by the dev-only generator
 * (com.foe.tools, not shipped, and not used from here); this reader is independent of it and as strict as the
 * generator's own reader.
 *
 * <p><b>Loading never throws</b> (addendum 8 F1, F6). A missing, empty, oversized or unreadable resource is an empty
 * table, and a line that is not exactly what the generator writes is skipped and counted. The caller logs what
 * happened ({@link FoePlugin}); nothing from the file's text is ever put in a message (ADR-0006), only counts and a
 * fixed reason. An empty table costs the feature, never the plugin: the panel still shows everything learned in game.
 */
final class WeaknessTable
{
	/** Read with {@code getResourceAsStream}, never {@code getResource}: the Hub's plugin jar is not unpacked. */
	static final String RESOURCE = "/com/foe/weakness-table.tsv";

	/**
	 * The most that is read. The shipped file is about 24 KB (24,349 bytes on 2026-10-09); this is about 43 times that, so a table that grows still
	 * loads and a stream that never ends does not exhaust memory.
	 */
	static final int MAX_BYTES = 1 << 20;

	/**
	 * Exactly what the generator writes: a decimal id of 1 to 9 digits with no leading zero, one of the five names in
	 * upper case, and a percent that is empty, 0, or 1 to 3 digits with no leading zero (addendum 8 F9: 0 to 999).
	 * {@code [0-9]} and not {@code \d}: ASCII digits only.
	 */
	private static final Pattern LINE = Pattern.compile(
		"(0|[1-9][0-9]{0,8})\t(AIR|WATER|EARTH|FIRE|NONE)\t(|0|[1-9][0-9]{0,2})");

	private WeaknessTable()
	{
	}

	/**
	 * What a load produced.
	 *
	 * <ul>
	 * <li>{@code entries}: immutable, never null;
	 * <li>{@code skipped}: data lines that could not be used (malformed, or an id that appears twice);
	 * <li>{@code problem}: null when the stream was read, else a short fixed reason it was not. An empty file has no
	 *     problem and no entries, and the caller treats both as "no table".
	 * </ul>
	 */
	static final class Loaded
	{
		final Map<Integer, Weakness> entries;
		final int skipped;
		final String problem;

		Loaded(Map<Integer, Weakness> entries, int skipped, String problem)
		{
			this.entries = entries;
			this.skipped = skipped;
			this.problem = problem;
		}

		static Loaded failed(String problem)
		{
			return new Loaded(Collections.emptyMap(), 0, problem);
		}
	}

	/**
	 * Reads and closes {@code in}. Never throws: an I/O failure, a runtime failure while reading (a jar closed
	 * underneath it) and a stream larger than {@link #MAX_BYTES} all give an empty table with a {@code problem}.
	 *
	 * @param in the resource, or null when it is not in the jar
	 */
	static Loaded load(InputStream in)
	{
		if (in == null)
		{
			return Loaded.failed("the resource is missing from the jar");
		}
		try
		{
			byte[] bytes = in.readNBytes(MAX_BYTES + 1);
			if (bytes.length > MAX_BYTES)
			{
				return Loaded.failed("the resource is larger than " + MAX_BYTES + " bytes");
			}
			return parse(new String(bytes, StandardCharsets.UTF_8));
		}
		catch (IOException | RuntimeException ex)
		{
			return Loaded.failed("the resource could not be read (" + ex.getClass().getSimpleName() + ")");
		}
		finally
		{
			try
			{
				in.close();
			}
			catch (IOException | RuntimeException ignored)
			{
				// already read, or already failed: a close that fails changes neither
			}
		}
	}

	/**
	 * The strict read of the text, line by line. Blank lines and {@code #} lines are not data. Any other line that is
	 * not exactly {@link #LINE} is skipped and counted. An id that appears more than once is shown as nothing, with
	 * every one of its lines counted: the generator never writes one, so two lines are a damaged file, which of them is
	 * right cannot be known, and a missing weakness is better than a false one (spec addendum 4's rule).
	 */
	static Loaded parse(String text)
	{
		Map<Integer, Weakness> entries = new HashMap<>();
		Set<Integer> repeated = new HashSet<>();
		int skipped = 0;
		for (String line : text.split("\n", -1))
		{
			if (line.isEmpty() || line.charAt(0) == '#')
			{
				continue;
			}
			Matcher m = LINE.matcher(line);
			if (!m.matches())
			{
				skipped++;
				continue;
			}
			int id = Integer.parseInt(m.group(1));
			Weakness w = weakness(m.group(2), m.group(3));
			if (w == null)
			{
				skipped++;
			}
			else if (repeated.contains(id))
			{
				skipped++;
			}
			else if (entries.containsKey(id))
			{
				entries.remove(id);
				repeated.add(id);
				skipped += 2; // the earlier line, which was kept until now, and this one
			}
			else
			{
				entries.put(id, w);
			}
		}
		return new Loaded(Map.copyOf(entries), skipped, null);
	}

	/**
	 * The line's weakness, or null when the combination is one the generator never writes (a NONE with a percent). An
	 * element at 0% is NONE: no bonus is no weakness (Task 11 review F7). The generator writes it as NONE already;
	 * this reads an older table the same way.
	 */
	private static Weakness weakness(String name, String percent)
	{
		if (name.equals("NONE"))
		{
			return percent.isEmpty() ? Weakness.NONE : null;
		}
		if (percent.equals("0"))
		{
			return Weakness.NONE;
		}
		return new Weakness(Weakness.Element.valueOf(name), percent.isEmpty() ? null : Integer.valueOf(percent));
	}

	/**
	 * What to show for one monster type, from what was learned in game and what the table says (either may be null).
	 * Spec addendum 7 with addendum 8 F3:
	 *
	 * <ul>
	 * <li>nothing learned: the table's entry, which may be null;
	 * <li>learned NONE: the table's entry if it names an element (NONE is the weakest evidence the store holds, F3),
	 *     else NONE;
	 * <li>learned element, no table entry or a table that says none: the learned element, with no percent;
	 * <li>learned element equal to the table's element: the table's, element and percent;
	 * <li>learned element different from the table's: the learned element with no percent, since the table is stale
	 *     for this monster and the only signal of that is the disagreement.
	 * </ul>
	 *
	 * <p>Elements are compared, never whole values: a learned FIRE and a table FIRE 50 are different
	 * {@link Weakness} values. The result can be {@link Weakness#NONE}, which the caller turns into "show nothing".
	 */
	static Weakness resolve(Weakness learned, Weakness table)
	{
		if (learned == null)
		{
			return table;
		}
		Weakness.Element element = learned.getElement();
		Weakness.Element tableElement = table == null ? null : table.getElement();
		if (element == null)
		{
			return tableElement != null ? table : learned;
		}
		if (element == tableElement)
		{
			return table;
		}
		// The learned store never has a percent; stated here rather than assumed, so a percent can only be the table's.
		return learned.getPercent() == null ? learned : new Weakness(element);
	}
}
