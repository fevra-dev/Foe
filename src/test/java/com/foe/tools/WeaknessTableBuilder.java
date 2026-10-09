package com.foe.tools;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pure core of the weakness table generator (spec addenda 7, 8 and 9): raw wiki rows, the previous table, the
 * pages' last-edit times and the clock go in; the new table and a report come out. No I/O and no clock of its own, so
 * every rule is a plain unit test. Dev-only: it lives under src/test and is on no shipped classpath.
 */
final class WeaknessTableBuilder
{
	enum Element
	{
		AIR, WATER, EARTH, FIRE, NONE
	}

	/** One table line: an element and, unless NONE or unknown, a percent. */
	static final class Entry
	{
		final Element element;
		final Integer percent;

		Entry(Element element, Integer percent)
		{
			this.element = Objects.requireNonNull(element);
			this.percent = percent;
		}

		@Override
		public boolean equals(Object o)
		{
			return o instanceof Entry && element == ((Entry) o).element && Objects.equals(percent, ((Entry) o).percent);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(element, percent);
		}

		@Override
		public String toString()
		{
			return percent == null ? element.name() : element + " " + percent;
		}
	}

	/** A table written by an earlier run, read back strictly. */
	static final class PreviousTable
	{
		final SortedMap<Integer, Entry> entries;
		/** The count the table's own header claims. */
		final int headerCount;

		PreviousTable(SortedMap<Integer, Entry> entries, int headerCount)
		{
			this.entries = entries;
			this.headerCount = headerCount;
		}

		private static final Pattern HEADER_COUNT = Pattern.compile("# Ids written: (0|[1-9][0-9]*)");
		private static final Pattern DATA = Pattern.compile(
			"(0|[1-9][0-9]{0,8})\t(AIR|WATER|EARTH|FIRE|NONE)\t(|0|[1-9][0-9]{0,2})");

		/**
		 * Reads a table back, as strictly as the generator writes it: the file is committed, so anyone can have edited
		 * it, and a run that diffs against a table it half-understood would quarantine against the wrong values.
		 *
		 * @throws IllegalArgumentException for anything a generator run would not have written
		 */
		static PreviousTable parse(String text)
		{
			if (!text.endsWith("\n"))
			{
				throw new IllegalArgumentException("the table does not end with a newline: truncated?");
			}
			String[] lines = text.substring(0, text.length() - 1).split("\n", -1);
			SortedMap<Integer, Entry> entries = new TreeMap<>();
			Integer headerCount = null;
			int last = -1;
			for (int i = 0; i < lines.length; i++)
			{
				String line = lines[i];
				String at = "line " + (i + 1) + ": ";
				if (line.startsWith("#"))
				{
					Matcher h = HEADER_COUNT.matcher(line);
					if (h.matches())
					{
						if (headerCount != null)
						{
							throw new IllegalArgumentException(at + "a second 'Ids written' header");
						}
						headerCount = Integer.valueOf(h.group(1));
					}
					continue;
				}
				Matcher m = DATA.matcher(line);
				if (!m.matches())
				{
					throw new IllegalArgumentException(at + "not a table line: '" + line + "'");
				}
				int id = Integer.parseInt(m.group(1));
				if (id <= last)
				{
					throw new IllegalArgumentException(at + "id " + id + " is not in ascending order (or is repeated)");
				}
				last = id;
				Element element = Element.valueOf(m.group(2));
				Integer percent = m.group(3).isEmpty() ? null : Integer.valueOf(m.group(3));
				if (element == Element.NONE && percent != null)
				{
					throw new IllegalArgumentException(at + "NONE carries a percent");
				}
				entries.put(id, new Entry(element, percent));
			}
			if (headerCount == null)
			{
				throw new IllegalArgumentException("no '# Ids written: N' header");
			}
			if (headerCount != entries.size())
			{
				throw new IllegalArgumentException(
					"the header says " + headerCount + " ids and the table holds " + entries.size());
			}
			return new PreviousTable(entries, headerCount);
		}
	}

	/** What a human reviews: the table is not the thing to read, this is. */
	static final class Report
	{
		static final String SUMMARY = "Summary";
		static final String ELEMENT_CONFLICTS = "Element conflicts (no entry written)";
		static final String PERCENT_CONFLICTS = "Percent conflicts (element written, no percent)";
		static final String SHARED_TABS = "Ids shared by two or more tabs of one page";
		static final String RECENT = "Pages edited within 7 days (check in game before committing)";
		static final String UNKNOWN_EDIT = "Pages with no known edit time (treated as edited within 7 days)";
		static final String PENDING = "Held back: changed, page edited within 7 days (previous value kept)";
		static final String ADDED = "Ids added";
		static final String REMOVED = "Ids removed";
		static final String CHANGED = "Ids changed (accepted)";
		static final String SKIPPED = "Skipped";

		/** The order a reviewer reads them in. Every section prints, empty ones as "(none)": silence is not a pass. */
		private static final String[] ORDER = {SUMMARY, ELEMENT_CONFLICTS, PERCENT_CONFLICTS, SHARED_TABS, RECENT,
			UNKNOWN_EDIT, PENDING, CHANGED, ADDED, REMOVED, SKIPPED};

		private final Map<String, List<String>> sections = new LinkedHashMap<>();

		void add(String section, String line)
		{
			sections.computeIfAbsent(section, k -> new ArrayList<>()).add(line);
		}

		List<String> lines(String section)
		{
			return sections.getOrDefault(section, new ArrayList<>());
		}

		String render()
		{
			StringBuilder sb = new StringBuilder();
			for (String section : ORDER)
			{
				List<String> lines = lines(section);
				sb.append("== ").append(section).append(" (").append(lines.size()).append(") ==\n");
				if (lines.isEmpty())
				{
					sb.append("(none)\n");
				}
				for (String line : lines)
				{
					sb.append(line).append('\n');
				}
				sb.append('\n');
			}
			return sb.toString();
		}
	}

	static final class Result
	{
		/** Every failure rule that fired; when non-empty there is no table. */
		final List<String> failures = new ArrayList<>();
		/** The whole table file, header included; null when the run failed. */
		String table;
		final SortedMap<Integer, Entry> entries = new TreeMap<>();
		final Report report = new Report();
		int rowsRead;
		int duplicateRowsDropped;
		int skippedNoIdRows;
		int skippedNonNumericIds;
		int skippedNoPercentIds;
		int skippedConflictIds;
		int heldBack;
	}

	/** The wiki's own address, for the table header and the raw file. */
	static final String SOURCE = "https://oldschool.runescape.wiki/api.php?action=bucket (bucket infobox_monster)";
	/** F9: a percent outside 0 to this fails the run. Measured range 2026-10-09: 0 to 200. */
	static final long MAX_PERCENT = 999;
	/** F2: a changed value is accepted only when its page was last edited at least this long ago. */
	static final Duration QUARANTINE = Duration.ofDays(7);
	/** F7: a table with this much fewer ids than the previous one fails unless told otherwise. */
	static final int SHRINK_LIMIT_PERCENT = 5;

	private static final Pattern NUMERIC_ID = Pattern.compile("0|[1-9][0-9]{0,8}");

	/** One row's claim about one id. */
	private static final class Claim
	{
		final WikiRow row;
		final Entry value;

		Claim(WikiRow row, Entry value)
		{
			this.row = row;
			this.value = value;
		}

		String text()
		{
			return row.label() + " " + valueText(value);
		}
	}

	private WeaknessTableBuilder()
	{
	}

	/**
	 * @param previous the table of the last release, or null for the first generation
	 * @param lastEdit page title to the time of its last wiki edit; a page the wiki did not answer for has no entry
	 */
	static Result build(Collection<WikiRow> rows, String fetchedDate, PreviousTable previous,
		Map<String, Instant> lastEdit, Instant now, boolean acceptShrink)
	{
		Result r = new Result();

		// F7: the walk can return a row twice. The raw file and the header count the distinct rows.
		TreeSet<WikiRow> distinct = new TreeSet<>(rows);
		r.rowsRead = distinct.size();
		r.duplicateRowsDropped = rows.size() - distinct.size();

		TreeMap<Integer, List<Claim>> claims = new TreeMap<>();
		TreeMap<Integer, List<WikiRow>> rowsById = new TreeMap<>();
		readRows(distinct, r, claims, rowsById);

		// addendum 9: one answer per id, or a stated reason for none
		TreeMap<Integer, Entry> resolved = new TreeMap<>();
		resolve(claims, r, resolved);

		// shared tabs (F5), then the diff against the previous table (F2)
		listSharedTabs(claims, rowsById, resolved, r.report);
		TreeMap<Integer, Entry> table = quarantine(previous, resolved, claims, rowsById, lastEdit, now, r);
		listRecentPages(claims, lastEdit, now, r.report);

		boolean shrinkUsed = false;
		if (previous != null && r.failures.isEmpty())
		{
			int before = previous.headerCount;
			int after = table.size();
			if ((long) (before - after) * 100 > (long) SHRINK_LIMIT_PERCENT * before)
			{
				if (acceptShrink)
				{
					shrinkUsed = true;
				}
				else
				{
					r.failures.add("the table shrank by more than " + SHRINK_LIMIT_PERCENT + "%: " + before
						+ " ids before, " + after + " now. Read the 'Ids removed' list in the report; if it is "
						+ "expected, run again with --accept-shrink");
				}
			}
		}

		if (!r.failures.isEmpty())
		{
			return r;
		}
		r.entries.putAll(table);
		r.table = header(r, fetchedDate, shrinkUsed ? previous : null) + body(table);
		summarise(r, shrinkUsed);
		return r;
	}

	// ---- reading rows: F1, rules 1 to 4 ----

	private static void readRows(Collection<WikiRow> distinct, Result r, Map<Integer, List<Claim>> claims,
		Map<Integer, List<WikiRow>> rowsById)
	{
		for (WikiRow row : distinct)
		{
			boolean bad = false;
			if (row.percent != null && (row.percent < 0 || row.percent > MAX_PERCENT))
			{
				r.failures.add(row.label() + ": percent " + row.percent + " is outside 0 to " + MAX_PERCENT
					+ " (ids " + row.ids + ")");
				bad = true;
			}
			Element element = null;
			if (row.element != null)
			{
				element = parseElement(row.element);
				if (element == null)
				{
					r.failures.add(row.label() + ": unknown element '" + row.element + "' (ids " + row.ids + ")");
					bad = true;
				}
			}

			TreeSet<Integer> numeric = new TreeSet<>();
			List<String> nonNumeric = new ArrayList<>();
			for (String id : row.ids)
			{
				if (NUMERIC_ID.matcher(id).matches())
				{
					numeric.add(Integer.valueOf(id));
				}
				else
				{
					nonNumeric.add(id);
				}
			}
			for (Integer id : numeric)
			{
				rowsById.computeIfAbsent(id, k -> new ArrayList<>()).add(row);
			}

			// No element: the wiki has not filled this tab in. That is "unknown", never a claim, and a percent with no
			// element beside it is not a weakness either (it was still range-checked above).
			if (row.element == null || bad)
			{
				continue;
			}
			if (element == Element.NONE && row.percent != null)
			{
				r.failures.add(row.label() + ": 'None' with a percent of " + row.percent
					+ " contradicts itself (ids " + row.ids + ")");
				continue;
			}
			if (row.ids.isEmpty())
			{
				r.skippedNoIdRows++;
				r.report.add(Report.SKIPPED, "no ids: " + row.label() + " " + row.element + " " + row.percent);
				continue;
			}
			for (String id : nonNumeric)
			{
				r.skippedNonNumericIds++;
				r.report.add(Report.SKIPPED, "non-numeric id " + id + ": " + row.label() + " "
					+ valueText(new Entry(element, row.percent == null ? null : row.percent.intValue())));
			}
			if (element != Element.NONE && row.percent == null)
			{
				if (!numeric.isEmpty())
				{
					r.skippedNoPercentIds += numeric.size();
					r.report.add(Report.SKIPPED, "element without percent: " + row.label() + " " + element
						+ ", ids " + idSummary(numeric));
				}
				continue;
			}
			Entry value = new Entry(element, row.percent == null ? null : row.percent.intValue());
			for (Integer id : numeric)
			{
				claims.computeIfAbsent(id, k -> new ArrayList<>()).add(new Claim(row, value));
			}
		}
	}

	/** F1: trim, lowercase, then one of five words. Anything else is null and fails the run. */
	private static Element parseElement(String raw)
	{
		switch (raw.trim().toLowerCase(Locale.ROOT))
		{
			case "air":
				return Element.AIR;
			case "water":
				return Element.WATER;
			case "earth":
				return Element.EARTH;
			case "fire":
				return Element.FIRE;
			case "none":
				return Element.NONE;
			default:
				return null;
		}
	}

	// ---- addendum 9: conflicts ----

	private static void resolve(Map<Integer, List<Claim>> claims, Result r, Map<Integer, Entry> resolved)
	{
		for (Map.Entry<Integer, List<Claim>> e : claims.entrySet())
		{
			int id = e.getKey();
			List<Claim> list = e.getValue();
			Set<Element> elements = new TreeSet<>();
			Set<Integer> percents = new HashSet<>();
			for (Claim c : list)
			{
				elements.add(c.value.element);
				percents.add(c.value.percent);
			}
			if (elements.size() > 1)
			{
				r.skippedConflictIds++;
				r.report.add(Report.ELEMENT_CONFLICTS, id + ": " + claimsText(list));
			}
			else if (percents.size() > 1)
			{
				resolved.put(id, new Entry(list.get(0).value.element, null));
				r.report.add(Report.PERCENT_CONFLICTS, id + ": " + claimsText(list));
			}
			else
			{
				resolved.put(id, list.get(0).value);
			}
		}
	}

	// ---- F5: agreeing phases ----

	private static void listSharedTabs(Map<Integer, List<Claim>> claims, Map<Integer, List<WikiRow>> rowsById,
		Map<Integer, Entry> resolved, Report report)
	{
		for (Map.Entry<Integer, List<WikiRow>> e : rowsById.entrySet())
		{
			int id = e.getKey();
			if (!claims.containsKey(id))
			{
				continue;
			}
			TreeMap<String, List<WikiRow>> byPage = new TreeMap<>();
			for (WikiRow row : e.getValue())
			{
				byPage.computeIfAbsent(row.pageName, k -> new ArrayList<>()).add(row);
			}
			for (Map.Entry<String, List<WikiRow>> page : byPage.entrySet())
			{
				if (page.getValue().size() < 2)
				{
					continue;
				}
				List<String> tabs = new ArrayList<>();
				for (WikiRow row : page.getValue())
				{
					tabs.add(row.versionAnchor == null ? "(no tab name)" : row.versionAnchor);
				}
				Entry value = resolved.get(id);
				report.add(Report.SHARED_TABS, id + ": " + page.getKey() + " tabs " + tabs + " -> "
					+ (value == null ? "no entry" : valueText(value)));
			}
		}
	}

	// ---- F2: quarantine ----

	private static boolean oldEnough(Instant edit, Instant now)
	{
		return edit != null && !edit.plus(QUARANTINE).isAfter(now);
	}

	/** Applies the previous table: a changed value waits for its page to be a week old. Returns the table to write. */
	private static TreeMap<Integer, Entry> quarantine(PreviousTable previous, Map<Integer, Entry> resolved,
		Map<Integer, List<Claim>> claims, Map<Integer, List<WikiRow>> rowsById, Map<String, Instant> lastEdit,
		Instant now, Result r)
	{
		TreeMap<Integer, Entry> table = new TreeMap<>(resolved);
		if (previous == null)
		{
			return table;
		}
		for (Map.Entry<Integer, Entry> e : resolved.entrySet())
		{
			int id = e.getKey();
			Entry before = previous.entries.get(id);
			Entry after = e.getValue();
			if (before == null)
			{
				r.report.add(Report.ADDED, id + ": " + valueText(after) + " <- " + claimsText(claims.get(id)));
			}
			else if (!before.equals(after))
			{
				boolean old = true;
				StringBuilder edited = new StringBuilder();
				for (String page : pagesOf(claims.get(id)))
				{
					Instant t = lastEdit.get(page);
					old &= oldEnough(t, now);
					edited.append(edited.length() == 0 ? "" : ", ").append(page).append(" edited ")
						.append(t == null ? "at an unknown time" : t.toString());
				}
				if (old)
				{
					r.report.add(Report.CHANGED, id + ": " + valueText(before) + " -> " + valueText(after) + " <- "
						+ claimsText(claims.get(id)));
				}
				else
				{
					table.put(id, before);
					r.heldBack++;
					r.report.add(Report.PENDING, id + ": kept " + valueText(before) + ", the wiki now says "
						+ claimsText(claims.get(id)) + " (" + edited + ")");
				}
			}
		}
		for (Map.Entry<Integer, Entry> e : previous.entries.entrySet())
		{
			int id = e.getKey();
			if (resolved.containsKey(id))
			{
				continue;
			}
			String why;
			if (claims.containsKey(id))
			{
				why = "element conflict now: " + claimsText(claims.get(id));
			}
			else if (rowsById.containsKey(id))
			{
				List<String> where = new ArrayList<>();
				for (WikiRow row : rowsById.get(id))
				{
					where.add(row.label());
				}
				why = "no row for it carries a weakness now: " + where;
			}
			else
			{
				why = "the id is not in the wiki data";
			}
			r.report.add(Report.REMOVED, id + ": was " + valueText(e.getValue()) + "; " + why);
		}
		return table;
	}

	private static TreeSet<String> pagesOf(List<Claim> list)
	{
		TreeSet<String> pages = new TreeSet<>();
		for (Claim c : list)
		{
			pages.add(c.row.pageName);
		}
		return pages;
	}

	/** Pages with a usable weakness row that were edited within 7 days, or whose edit time is not known. */
	private static void listRecentPages(Map<Integer, List<Claim>> claims, Map<String, Instant> lastEdit, Instant now,
		Report report)
	{
		TreeMap<WikiRow, TreeSet<Integer>> idsByRow = new TreeMap<>();
		Map<WikiRow, Entry> valueByRow = new HashMap<>();
		for (Map.Entry<Integer, List<Claim>> e : claims.entrySet())
		{
			for (Claim c : e.getValue())
			{
				idsByRow.computeIfAbsent(c.row, k -> new TreeSet<>()).add(e.getKey());
				valueByRow.put(c.row, c.value);
			}
		}
		TreeMap<String, List<String>> tabsByPage = new TreeMap<>();
		for (Map.Entry<WikiRow, TreeSet<Integer>> e : idsByRow.entrySet())
		{
			WikiRow row = e.getKey();
			String value = valueText(valueByRow.get(row));
			tabsByPage.computeIfAbsent(row.pageName, k -> new ArrayList<>()).add(
				(row.versionAnchor == null ? "(no tab name)" : row.versionAnchor) + ": ids " + idSummary(e.getValue())
					+ " -> " + value);
		}
		for (Map.Entry<String, List<String>> page : tabsByPage.entrySet())
		{
			Instant t = lastEdit.get(page.getKey());
			if (t == null)
			{
				report.add(Report.UNKNOWN_EDIT, page.getKey() + ": " + String.join(" | ", page.getValue()));
			}
			else if (!oldEnough(t, now))
			{
				report.add(Report.RECENT, page.getKey() + " (last edit " + t + "): "
					+ String.join(" | ", page.getValue()));
			}
		}
	}

	// ---- output ----

	private static String header(Result r, String fetchedDate, PreviousTable shrunkFrom)
	{
		int elementOnly = 0;
		for (Entry e : r.entries.values())
		{
			if (e.element != Element.NONE && e.percent == null)
			{
				elementOnly++;
			}
		}
		// Every number here is a function of the raw file alone (plus the previous table and the flag), so the table
		// can be re-derived offline and compared byte for byte (F8).
		return "# Foe elemental weakness table. Written by com.foe.tools.GenerateWeaknessTable; do not edit by hand.\n"
			+ "# Source: " + SOURCE + "\n"
			+ "# Licence: CC BY-NC-SA 3.0, see weakness-table.LICENSE\n"
			+ "# Fetched (UTC): " + fetchedDate + "\n"
			+ "# Rows read: " + r.rowsRead + "\n"
			+ "# Ids written: " + r.entries.size() + "\n"
			+ "# Ids written with an element and no percent: " + elementOnly + "\n"
			+ "# Ids skipped, element without percent: " + r.skippedNoPercentIds + "\n"
			+ "# Ids skipped, non-numeric id: " + r.skippedNonNumericIds + "\n"
			+ "# Ids skipped, element conflict: " + r.skippedConflictIds + "\n"
			+ "# Rows skipped, no ids: " + r.skippedNoIdRows + "\n"
			+ "# Changes held back, page edited within 7 days: " + r.heldBack + "\n"
			+ "# Accept-shrink: " + (shrunkFrom == null ? "no"
				: "yes, the previous table had " + shrunkFrom.headerCount + " ids") + "\n"
			+ "# Line format: id<TAB>ELEMENT<TAB>percent. The percent is empty for NONE, and where tabs disagree on it.\n";
	}

	private static String body(Map<Integer, Entry> table)
	{
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<Integer, Entry> e : table.entrySet())
		{
			Entry v = e.getValue();
			sb.append(e.getKey()).append('\t').append(v.element).append('\t')
				.append(v.percent == null ? "" : v.percent.toString()).append('\n');
		}
		return sb.toString();
	}

	private static void summarise(Result r, boolean shrinkUsed)
	{
		Report rep = r.report;
		rep.add(Report.SUMMARY, "rows read: " + r.rowsRead + " (" + r.duplicateRowsDropped + " duplicate rows dropped)");
		rep.add(Report.SUMMARY, "ids written: " + r.entries.size());
		rep.add(Report.SUMMARY, "ids skipped: " + r.skippedNoPercentIds + " element without percent, "
			+ r.skippedNonNumericIds + " non-numeric id, " + r.skippedConflictIds + " element conflict; rows skipped: "
			+ r.skippedNoIdRows + " with no ids");
		rep.add(Report.SUMMARY, "changes held back: " + r.heldBack);
		rep.add(Report.SUMMARY, "accept-shrink used: " + (shrinkUsed ? "yes" : "no"));
	}

	static String valueText(Entry v)
	{
		return v.toString();
	}

	private static String claimsText(List<Claim> list)
	{
		List<String> parts = new ArrayList<>();
		for (Claim c : list)
		{
			parts.add(c.text());
		}
		return String.join("; ", parts);
	}

	/** {@code 2075-2084, 2090}: consecutive ids collapse, because a Fire giant row alone lists ten. */
	static String idSummary(Collection<Integer> sorted)
	{
		List<String> parts = new ArrayList<>();
		Integer start = null;
		Integer prev = null;
		for (Integer id : sorted)
		{
			if (start == null)
			{
				start = id;
			}
			else if (id != prev + 1)
			{
				parts.add(range(start, prev));
				start = id;
			}
			prev = id;
		}
		if (start != null)
		{
			parts.add(range(start, prev));
		}
		return String.join(", ", parts);
	}

	private static String range(int from, int to)
	{
		return from == to ? String.valueOf(from) : from + "-" + to;
	}
}
