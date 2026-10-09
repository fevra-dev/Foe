package com.foe.tools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One row of the wiki's {@code infobox_monster} bucket: one variant (tab) of one page, with every NPC id it covers.
 * The generator's own type, so nothing here touches the plugin's classes.
 */
final class WikiRow implements Comparable<WikiRow>
{
	/** The page title. */
	final String pageName;
	/** The tab, e.g. {@code Level 86}; null when the page has one version. */
	final String versionAnchor;
	/** Every id this variant covers, as the wiki wrote them; empty when the row has none. */
	final List<String> ids;
	/** The element field as the wiki wrote it, so unvalidated; null when the wiki has no weakness for the row. */
	final String element;
	/** The percent as the wiki wrote it, so unvalidated; null when absent. */
	final Long percent;

	private WikiRow(String pageName, String versionAnchor, List<String> ids, String element, Long percent)
	{
		this.pageName = Objects.requireNonNull(pageName, "page_name");
		this.versionAnchor = versionAnchor;
		this.ids = Collections.unmodifiableList(new ArrayList<>(ids));
		this.element = element;
		this.percent = percent;
	}

	static WikiRow of(String pageName, String versionAnchor, String element, Long percent, String... ids)
	{
		return new WikiRow(pageName, versionAnchor, Arrays.asList(ids), element, percent);
	}

	private static final Set<String> KEYS = new HashSet<>(Arrays.asList(
		"page_name", "version_anchor", "id", "elemental_weakness", "elemental_weakness_percent"));

	/** Parses one JSON object from text; a reply that is not one (an HTML error page, say) is rejected. */
	static JsonObject parseObject(String text)
	{
		try
		{
			JsonObject o = new Gson().fromJson(text, JsonObject.class);
			if (o == null)
			{
				throw new IllegalArgumentException("empty JSON");
			}
			return o;
		}
		catch (JsonParseException e)
		{
			throw new IllegalArgumentException("not a JSON object: " + e.getMessage(), e);
		}
	}

	/**
	 * Reads one bucket row. The five fields the query selects are the only keys the bucket returns, so another key
	 * means the shape moved under us; that and every wrong type is rejected rather than guessed at.
	 *
	 * @throws IllegalArgumentException for a shape the bucket never produces
	 */
	static WikiRow fromJson(JsonObject o)
	{
		for (String key : o.keySet())
		{
			if (!KEYS.contains(key))
			{
				throw new IllegalArgumentException("unexpected field '" + key + "' in " + o);
			}
		}
		String page = stringOrNull(o, "page_name");
		if (page == null)
		{
			throw new IllegalArgumentException("no page_name in " + o);
		}
		List<String> ids = new ArrayList<>();
		JsonElement idField = o.get("id");
		if (idField != null && !idField.isJsonNull())
		{
			if (!idField.isJsonArray())
			{
				throw new IllegalArgumentException("id is not an array in " + o);
			}
			for (JsonElement e : idField.getAsJsonArray())
			{
				if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString())
				{
					throw new IllegalArgumentException("an id is not a string in " + o);
				}
				ids.add(e.getAsString());
			}
		}
		Long percent = null;
		JsonElement pct = o.get("elemental_weakness_percent");
		if (pct != null && !pct.isJsonNull())
		{
			if (!pct.isJsonPrimitive() || !pct.getAsJsonPrimitive().isNumber())
			{
				throw new IllegalArgumentException("the percent is not a number in " + o);
			}
			try
			{
				percent = pct.getAsBigDecimal().longValueExact();
			}
			catch (ArithmeticException e)
			{
				throw new IllegalArgumentException("the percent is not an integer in " + o, e);
			}
		}
		return new WikiRow(page, stringOrNull(o, "version_anchor"), ids, stringOrNull(o, "elemental_weakness"),
			percent);
	}

	private static String stringOrNull(JsonObject o, String key)
	{
		JsonElement e = o.get(key);
		if (e == null || e.isJsonNull())
		{
			return null;
		}
		if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString())
		{
			throw new IllegalArgumentException(key + " is not a string in " + o);
		}
		return e.getAsString();
	}

	/** In a fixed key order, with a field the wiki left out left out, so the committed file diffs cleanly. */
	JsonObject toJson()
	{
		JsonObject o = new JsonObject();
		o.addProperty("page_name", pageName);
		if (versionAnchor != null)
		{
			o.addProperty("version_anchor", versionAnchor);
		}
		if (!ids.isEmpty())
		{
			JsonArray a = new JsonArray();
			ids.forEach(a::add);
			o.add("id", a);
		}
		if (element != null)
		{
			o.addProperty("elemental_weakness", element);
		}
		if (percent != null)
		{
			o.addProperty("elemental_weakness_percent", percent);
		}
		return o;
	}

	/** {@code Page} or {@code Page [Tab]}, for messages. */
	String label()
	{
		return versionAnchor == null ? pageName : pageName + " [" + versionAnchor + "]";
	}

	@Override
	public int compareTo(WikiRow o)
	{
		int c = pageName.compareTo(o.pageName);
		if (c == 0)
		{
			c = nullFirst(versionAnchor, o.versionAnchor);
		}
		if (c == 0)
		{
			c = String.join(",", ids).compareTo(String.join(",", o.ids));
		}
		if (c == 0)
		{
			c = nullFirst(element, o.element);
		}
		if (c == 0)
		{
			c = percent == null ? (o.percent == null ? 0 : -1) : (o.percent == null ? 1 : percent.compareTo(o.percent));
		}
		return c;
	}

	private static int nullFirst(String a, String b)
	{
		return a == null ? (b == null ? 0 : -1) : (b == null ? 1 : a.compareTo(b));
	}

	@Override
	public boolean equals(Object o)
	{
		return o instanceof WikiRow && compareTo((WikiRow) o) == 0;
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(pageName, versionAnchor, ids, element, percent);
	}

	@Override
	public String toString()
	{
		return label() + " " + ids + " " + element + " " + percent;
	}
}
