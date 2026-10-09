package com.foe.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The network edge of the generator: the OSRS Wiki's Bucket API for the rows, and the MediaWiki query API for each
 * page's last edit. Everything that can go wrong with a live wiki is a failure here (addendum 8 F7), never a short
 * table. The transport and the sleeper are injected so the whole loop runs against canned replies in a unit test.
 */
final class WikiFetch
{
	static final String API = "https://oldschool.runescape.wiki/api.php";
	/** Says who is asking and why, so a wiki admin can tell a script from an attack and knows where to write. */
	static final String USER_AGENT =
		"Foe-RuneLite-plugin weakness-table generator (dev-run by hand before a release; https://github.com/fevra-dev/Foe)";
	static final int PAGE_SIZE = 500;
	static final int TITLES_PER_REQUEST = 50;
	/** Politeness: this long between one request finishing and the next starting. */
	static final long MIN_GAP_MS = 500;

	static final class Reply
	{
		final int status;
		final String body;

		Reply(int status, String body)
		{
			this.status = status;
			this.body = body;
		}
	}

	interface Transport
	{
		Reply get(URI uri, String userAgent) throws IOException;
	}

	interface Sleeper
	{
		void sleep(long millis) throws InterruptedException;
	}

	/** A walk longer than this many pages is a loop, not a wiki: 500 rows each is far past the ~3,300 rows it has. */
	private static final int MAX_PAGES = 100;
	private static final String QUERY_TEMPLATE = "bucket(\"infobox_monster\")"
		+ ".select(\"page_name\",\"version_anchor\",\"id\",\"elemental_weakness\",\"elemental_weakness_percent\")"
		+ ".orderBy(\"page_name\",\"asc\").limit(%d).offset(%d).run()";

	private final Transport transport;
	private final Sleeper sleeper;
	private final int pageSize;
	private int requests;

	WikiFetch(Transport transport, Sleeper sleeper, int pageSize)
	{
		this.transport = transport;
		this.sleeper = sleeper;
		this.pageSize = pageSize;
	}

	/** The real thing: java.net.http, the thread's own sleep. */
	static WikiFetch live()
	{
		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
			.followRedirects(HttpClient.Redirect.NORMAL).build();
		Transport transport = (uri, userAgent) ->
		{
			HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
				.header("User-Agent", userAgent).header("Accept", "application/json").GET().build();
			try
			{
				HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
				return new Reply(response.statusCode(), response.body());
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				throw new IOException("interrupted while waiting for the wiki", e);
			}
		};
		return new WikiFetch(transport, Thread::sleep, PAGE_SIZE);
	}

	/**
	 * Walks the bucket until it returns an empty page. Not until a short one: a page cut short mid-walk is the sign
	 * of a hiccup, and stopping there is how a truncated table gets committed (F7). Each request starts where the
	 * rows read so far end, so a short page cannot make the walk skip rows either.
	 *
	 * Every row read is returned, duplicates included: the builder counts and drops those. One walk pages by offset
	 * over a live database, so an edit during it can skip a row silently; the generator walks twice and compares.
	 */
	List<WikiRow> fetchRows() throws IOException
	{
		List<WikiRow> rows = new ArrayList<>();
		for (int page = 0; page < MAX_PAGES; page++)
		{
			int offset = rows.size();
			String query = String.format(QUERY_TEMPLATE, pageSize, offset);
			JsonObject reply = get("bucket", "action=bucket&format=json&query=" + encode(query));
			JsonElement bucket = reply.get("bucket");
			if (bucket == null || !bucket.isJsonArray())
			{
				throw new IOException("the reply has no 'bucket' array (offset " + offset + "): " + snippet(reply.toString()));
			}
			JsonArray array = bucket.getAsJsonArray();
			if (array.size() == 0)
			{
				if (rows.isEmpty())
				{
					throw new IOException("the bucket returned no rows at all");
				}
				return rows;
			}
			for (JsonElement element : array)
			{
				try
				{
					if (!element.isJsonObject())
					{
						throw new IllegalArgumentException("a row that is not an object: " + Text.safe(element.toString()));
					}
					rows.add(WikiRow.fromJson(element.getAsJsonObject()));
				}
				catch (IllegalArgumentException e)
				{
					throw new IOException("bad row in the page at offset " + offset + ": " + e.getMessage(), e);
				}
			}
		}
		throw new IOException("walked " + MAX_PAGES + " pages of " + pageSize + " rows without reaching an empty one");
	}

	/** The last-edit time of each title the wiki knows; a title it does not know is absent from the map. */
	Map<String, Instant> fetchLastEdits(Collection<String> titles) throws IOException
	{
		TreeSet<String> sorted = new TreeSet<>(titles);
		for (String title : sorted)
		{
			if (title.indexOf('|') >= 0)
			{
				throw new IOException("a title with '|' cannot be sent in a batch: " + Text.safe(title));
			}
		}
		Map<String, Instant> edits = new TreeMap<>();
		List<String> batch = new ArrayList<>();
		for (String title : sorted)
		{
			batch.add(title);
			if (batch.size() == TITLES_PER_REQUEST)
			{
				readEdits(batch, edits);
				batch.clear();
			}
		}
		if (!batch.isEmpty())
		{
			readEdits(batch, edits);
		}
		return edits;
	}

	private void readEdits(List<String> batch, Map<String, Instant> edits) throws IOException
	{
		try
		{
			readEditsUnchecked(batch, edits);
		}
		catch (IllegalStateException | UnsupportedOperationException | NullPointerException | ClassCastException e)
		{
			// Gson's getAs* throw these on a reply of the wrong shape; their messages can quote the reply (ADR-0006)
			throw new IOException("the edit-time reply is not the shape the API documents: "
				+ Text.safe(String.valueOf(e.getMessage())), e);
		}
	}

	private void readEditsUnchecked(List<String> batch, Map<String, Instant> edits) throws IOException
	{
		JsonObject reply = get("query", "action=query&format=json&prop=revisions&rvprop=timestamp&titles="
			+ encode(String.join("|", batch)));
		JsonElement query = reply.get("query");
		if (query == null || !query.isJsonObject())
		{
			throw new IOException("the reply has no 'query' object: " + snippet(reply.toString()));
		}
		Map<String, String> normalized = new HashMap<>();
		JsonElement norm = query.getAsJsonObject().get("normalized");
		if (norm != null && norm.isJsonArray())
		{
			for (JsonElement n : norm.getAsJsonArray())
			{
				JsonObject o = n.getAsJsonObject();
				normalized.put(o.get("from").getAsString(), o.get("to").getAsString());
			}
		}
		Map<String, Instant> byTitle = new HashMap<>();
		JsonElement pages = query.getAsJsonObject().get("pages");
		if (pages != null && pages.isJsonObject())
		{
			for (Map.Entry<String, JsonElement> page : pages.getAsJsonObject().entrySet())
			{
				JsonObject o = page.getValue().getAsJsonObject();
				if (o.has("missing"))
				{
					// a deleted page: deleting one takes a wiki administrator, so it is not an anonymous edit to wait
					// out, and holding its ids for ever would be a refusal nobody can clear
					byTitle.put(o.get("title").getAsString(), Instant.EPOCH);
					continue;
				}
				JsonElement revisions = o.get("revisions");
				if (revisions == null || !revisions.isJsonArray() || revisions.getAsJsonArray().size() == 0)
				{
					continue;
				}
				String stamp = revisions.getAsJsonArray().get(0).getAsJsonObject().get("timestamp").getAsString();
				try
				{
					byTitle.put(o.get("title").getAsString(), Instant.parse(stamp));
				}
				catch (DateTimeParseException e)
				{
					throw new IOException("not a timestamp: '" + Text.safe(stamp) + "' for " + Text.safe(o.get("title").toString()), e);
				}
			}
		}
		for (String title : batch)
		{
			Instant t = byTitle.get(normalized.getOrDefault(title, title));
			if (t != null)
			{
				edits.put(title, t);
			}
		}
	}

	/** One polite GET: waits first (except before the very first request), then checks everything F7 lists. */
	private JsonObject get(String what, String rawQuery) throws IOException
	{
		if (requests++ > 0)
		{
			try
			{
				sleeper.sleep(MIN_GAP_MS);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				throw new IOException("interrupted between requests", e);
			}
		}
		Reply reply = transport.get(URI.create(API + "?" + rawQuery), USER_AGENT);
		if (reply.status != 200)
		{
			throw new IOException("HTTP " + reply.status + " from the " + what + " request: " + snippet(reply.body));
		}
		JsonObject json;
		try
		{
			json = WikiRow.parseObject(reply.body);
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("the " + what + " reply is not JSON: " + snippet(reply.body), e);
		}
		if (json.has("error"))
		{
			throw new IOException("the wiki answered with an error: " + snippet(json.get("error").toString()));
		}
		return json;
	}

	private static String encode(String s)
	{
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	/** The start of a reply on one line, for a message. ADR-0006: a reply is the wiki's text, so it goes through Text. */
	private static String snippet(String body)
	{
		return Text.safe(body.replaceAll("\\s+", " ").trim(), 300);
	}
}
