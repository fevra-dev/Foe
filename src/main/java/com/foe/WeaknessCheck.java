package com.foe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The learned-vs-table check (spec addendum 11): how many of the types learned in game agree with the bundled wiki
 * table, and which do not. A pure function of the two maps, so the counts and the lines are tested here and
 * {@link FoePlugin} only logs them, once, when the plugin starts and both have loaded. There is no UI.
 *
 * <p>Each learned entry is exactly one of:
 * <ul>
 * <li><b>not in the table</b>: the table has no entry for the type, so there is nothing to compare;
 * <li><b>agree</b>: the same element, or both say none. The percent plays no part: a learned entry never has one,
 *     because the varp never carries it (addendum 7);
 * <li><b>disagree</b>: anything else, a learned NONE against a table weakness included, and a learned element
 *     against a table that says none.
 * </ul>
 *
 * <p>A disagreement is the signal of a table that is stale or wrong for that monster, or of a learned entry that is
 * (addendum 8 F3 lets the table win over a learned NONE, so the check is where a wrong NONE shows up). Each is listed
 * as {@code id learned=ELEMENT table=ELEMENT [percent]} in ascending id order, NONE written for "no weakness", and
 * the percent only when the table has one. A line holds ints and enum names and nothing else (ADR-0006).
 */
final class WeaknessCheck
{
	final int learned;
	final int agree;
	final int disagree;
	final int notInTable;
	/** One line per disagreement, ascending id. Immutable. */
	final List<String> lines;

	private WeaknessCheck(int learned, int agree, int disagree, int notInTable, List<String> lines)
	{
		this.learned = learned;
		this.agree = agree;
		this.disagree = disagree;
		this.notInTable = notInTable;
		this.lines = Collections.unmodifiableList(lines);
	}

	/** Neither map is changed. */
	static WeaknessCheck compare(Map<Integer, Weakness> learned, Map<Integer, Weakness> table)
	{
		int agree = 0;
		int notInTable = 0;
		List<String> lines = new ArrayList<>();
		for (Map.Entry<Integer, Weakness> e : new TreeMap<>(learned).entrySet())
		{
			Weakness in = table.get(e.getKey());
			if (in == null)
			{
				notInTable++;
			}
			else if (e.getValue().getElement() == in.getElement())
			{
				agree++;
			}
			else
			{
				lines.add(e.getKey() + " learned=" + name(e.getValue()) + " table=" + name(in)
					+ (in.getPercent() == null ? "" : " " + in.getPercent()));
			}
		}
		return new WeaknessCheck(learned.size(), agree, lines.size(), notInTable, lines);
	}

	/** {@code weakness check: N learned, M agree with the table, K disagree} */
	String summary()
	{
		return "weakness check: " + learned + " learned, " + agree + " agree with the table, " + disagree + " disagree";
	}

	private static String name(Weakness w)
	{
		return w.getElement() == null ? "NONE" : w.getElement().name();
	}
}
