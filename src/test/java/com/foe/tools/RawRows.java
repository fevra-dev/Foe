package com.foe.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.TreeSet;

/**
 * The raw Bucket rows as committed in data/wiki-infobox-monster.jsonl (addendum 8 F8): a first line that records
 * where and when they were fetched, then one row per line, sorted. The table is re-derivable from this file alone.
 */
final class RawRows
{
	static final class Parsed
	{
		final String fetchedDate;
		final List<WikiRow> rows;
		final WeaknessTableBuilder.Decisions decisions;

		Parsed(String fetchedDate, List<WikiRow> rows, WeaknessTableBuilder.Decisions decisions)
		{
			this.fetchedDate = fetchedDate;
			this.rows = rows;
			this.decisions = decisions;
		}
	}

	/** A held value for an id the previous table did not have: the add waits, so the id stays out. */
	private static final String ABSENT = "absent";
	private static final Pattern HELD_VALUE = Pattern.compile("(AIR|WATER|EARTH|FIRE)( (0|[1-9][0-9]{0,2}))?|NONE");
	private static final Pattern ID = Pattern.compile("0|[1-9][0-9]{0,8}");

	private RawRows()
	{
	}

	/** @throws IllegalArgumentException for decisions the generator would not have written */
	private static WeaknessTableBuilder.Decisions decisions(JsonObject meta)
	{
		String generation = string(meta, "generation");
		if (generation.equals("first"))
		{
			if (meta.has("previousIds") || meta.has("acceptShrink") || meta.has("held"))
			{
				throw new IllegalArgumentException("a first generation records no previous table and holds nothing");
			}
			return WeaknessTableBuilder.Decisions.first();
		}
		if (!generation.equals("diffed"))
		{
			throw new IllegalArgumentException("generation is neither 'first' nor 'diffed': " + Text.safe(generation));
		}
		JsonElement previousIds = meta.get("previousIds");
		JsonElement acceptShrink = meta.get("acceptShrink");
		JsonElement held = meta.get("held");
		if (previousIds == null || !previousIds.isJsonPrimitive() || !previousIds.getAsJsonPrimitive().isNumber()
			|| acceptShrink == null || !acceptShrink.isJsonPrimitive() || !acceptShrink.getAsJsonPrimitive().isBoolean()
			|| held == null || !held.isJsonObject())
		{
			throw new IllegalArgumentException("a diffed generation needs previousIds, acceptShrink and held");
		}
		TreeMap<Integer, WeaknessTableBuilder.Entry> map = new TreeMap<>();
		for (Map.Entry<String, JsonElement> h : held.getAsJsonObject().entrySet())
		{
			if (!ID.matcher(h.getKey()).matches() || !h.getValue().isJsonPrimitive()
				|| !h.getValue().getAsJsonPrimitive().isString())
			{
				throw new IllegalArgumentException("a held entry that is not id -> value: " + Text.safe(h.getKey()));
			}
			String v = h.getValue().getAsString();
			WeaknessTableBuilder.Entry entry = null;
			if (!v.equals(ABSENT))
			{
				Matcher m = HELD_VALUE.matcher(v);
				if (!m.matches())
				{
					throw new IllegalArgumentException("a held value that is not a table value: " + Text.safe(v));
				}
				entry = v.equals("NONE") ? new WeaknessTableBuilder.Entry(WeaknessTableBuilder.Element.NONE, null)
					: new WeaknessTableBuilder.Entry(WeaknessTableBuilder.Element.valueOf(m.group(1)),
						m.group(3) == null ? null : Integer.valueOf(m.group(3)));
			}
			map.put(Integer.valueOf(h.getKey()), entry);
		}
		return new WeaknessTableBuilder.Decisions(false, previousIds.getAsInt(), acceptShrink.getAsBoolean(), map);
	}

	private static String string(JsonObject o, String key)
	{
		JsonElement e = o.get(key);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString())
		{
			throw new IllegalArgumentException("the _meta line has no '" + key + "'");
		}
		return e.getAsString();
	}

	/** A first generation's file. */
	static String write(Collection<WikiRow> rows, String fetchedDate)
	{
		return write(rows, fetchedDate, WeaknessTableBuilder.Decisions.first());
	}

	/**
	 * The whole file: the metadata line (where, when, and what the run decided beyond the rows), then the distinct
	 * rows in a fixed order, each line ending in a newline.
	 */
	static String write(Collection<WikiRow> rows, String fetchedDate, WeaknessTableBuilder.Decisions decisions)
	{
		JsonObject meta = new JsonObject();
		meta.addProperty("source", WeaknessTableBuilder.SOURCE);
		meta.addProperty("fetched", fetchedDate);
		meta.addProperty("generation", decisions.firstGeneration ? "first" : "diffed");
		if (!decisions.firstGeneration)
		{
			meta.addProperty("previousIds", decisions.previousIds);
			meta.addProperty("acceptShrink", decisions.acceptShrinkUsed);
			JsonObject held = new JsonObject();
			for (Map.Entry<Integer, WeaknessTableBuilder.Entry> h : decisions.held.entrySet())
			{
				held.addProperty(h.getKey().toString(), h.getValue() == null ? ABSENT : h.getValue().toString());
			}
			meta.add("held", held);
		}
		JsonObject first = new JsonObject();
		first.add("_meta", meta);
		// ADR-0006: the file is a sink for wiki text. Escaping keeps it a lossless copy that cannot act when cat-ed.
		StringBuilder sb = new StringBuilder(Text.escapeJson(first.toString())).append('\n');
		for (WikiRow row : new TreeSet<>(rows))
		{
			sb.append(Text.escapeJson(row.toJson().toString())).append('\n');
		}
		return sb.toString();
	}

	/** @throws IllegalArgumentException for a file the generator did not write */
	static Parsed read(String text)
	{
		if (!text.endsWith("\n"))
		{
			throw new IllegalArgumentException("the raw file does not end with a newline: truncated?");
		}
		String[] lines = text.substring(0, text.length() - 1).split("\n", -1);
		JsonObject first = WikiRow.parseObject(lines[0]);
		JsonElement metaField = first.get("_meta");
		if (first.size() != 1 || metaField == null || !metaField.isJsonObject())
		{
			throw new IllegalArgumentException("the first line is not the _meta line");
		}
		JsonElement fetched = metaField.getAsJsonObject().get("fetched");
		if (fetched == null || !fetched.isJsonPrimitive() || !fetched.getAsJsonPrimitive().isString())
		{
			throw new IllegalArgumentException("the _meta line has no fetched date");
		}
		String date = fetched.getAsString();
		try
		{
			if (!LocalDate.parse(date).toString().equals(date))
			{
				throw new IllegalArgumentException("the fetched date is not YYYY-MM-DD: " + Text.safe(date));
			}
		}
		catch (DateTimeParseException e)
		{
			throw new IllegalArgumentException("the fetched date is not a date: " + Text.safe(date), e);
		}
		List<WikiRow> rows = new ArrayList<>();
		for (int i = 1; i < lines.length; i++)
		{
			try
			{
				JsonObject o = WikiRow.parseObject(lines[i]);
				if (o.has("_meta"))
				{
					throw new IllegalArgumentException("a second _meta line");
				}
				rows.add(WikiRow.fromJson(o));
			}
			catch (IllegalArgumentException e)
			{
				throw new IllegalArgumentException("line " + (i + 1) + ": " + e.getMessage(), e);
			}
		}
		return new Parsed(date, rows, decisions(metaField.getAsJsonObject()));
	}
}
