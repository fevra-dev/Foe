package com.foe.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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

		Parsed(String fetchedDate, List<WikiRow> rows)
		{
			this.fetchedDate = fetchedDate;
			this.rows = rows;
		}
	}

	private RawRows()
	{
	}

	/** The whole file: the metadata line, then the distinct rows in a fixed order, each line ending in a newline. */
	static String write(Collection<WikiRow> rows, String fetchedDate)
	{
		JsonObject meta = new JsonObject();
		meta.addProperty("source", WeaknessTableBuilder.SOURCE);
		meta.addProperty("fetched", fetchedDate);
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
		return new Parsed(date, rows);
	}
}
