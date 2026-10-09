package com.foe.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * The fetch loop against canned replies. The reply bodies are the shapes the live wiki returned on 2026-10-09 (a
 * bucket reply is {@code {"bucketQuery":...,"bucket":[...]}}, and a query reply is {@code {"batchcomplete":"",
 * "query":{"pages":{...}}}}), and no test here touches the network.
 */
public class WikiFetchTest
{
	/** Replays canned replies in order and logs what happened, requests and sleeps in one sequence. */
	private static final class Canned implements WikiFetch.Transport, WikiFetch.Sleeper
	{
		final Deque<WikiFetch.Reply> replies = new ArrayDeque<>();
		final List<URI> uris = new ArrayList<>();
		final List<String> agents = new ArrayList<>();
		/** "GET" for a request, "sleep:N" for a sleep, in the order they happened. */
		final List<String> events = new ArrayList<>();

		Canned reply(int status, String body)
		{
			replies.add(new WikiFetch.Reply(status, body));
			return this;
		}

		Canned ok(String body)
		{
			return reply(200, body);
		}

		@Override
		public WikiFetch.Reply get(URI uri, String userAgent)
		{
			events.add("GET");
			uris.add(uri);
			agents.add(userAgent);
			assertFalse("the fetch asked for more replies than the test canned", replies.isEmpty());
			return replies.remove();
		}

		@Override
		public void sleep(long millis)
		{
			events.add("sleep:" + millis);
		}

		WikiFetch fetch(int pageSize)
		{
			return new WikiFetch(this, this, pageSize);
		}

		Map<String, String> query(int request)
		{
			Map<String, String> q = new LinkedHashMap<>();
			for (String pair : uris.get(request).getRawQuery().split("&"))
			{
				int eq = pair.indexOf('=');
				q.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
			}
			return q;
		}

		private static String decode(String s)
		{
			return URLDecoder.decode(s, StandardCharsets.UTF_8);
		}
	}

	private static String bucket(String... rows)
	{
		return "{\"bucketQuery\":\"bucket(...)\",\"bucket\":[" + String.join(",", rows) + "]}";
	}

	private static final String R1 = "{\"id\":[\"2189\",\"2190\"],\"page_name\":\"Tz-Kih\","
		+ "\"elemental_weakness\":\"Water\",\"elemental_weakness_percent\":40}";
	private static final String R2 = "{\"id\":[\"2919\",\"8088\"],\"page_name\":\"Mithril dragon\","
		+ "\"elemental_weakness\":\"Earth\",\"elemental_weakness_percent\":50}";
	private static final String R3 = "{\"id\":[\"525\"],\"page_name\":\"Thug\"}";
	private static final String R4 = "{\"page_name\":\"Fire giant\",\"id\":[\"7252\"],\"elemental_weakness\":\"Water\","
		+ "\"version_anchor\":\"Level 104\",\"elemental_weakness_percent\":100}";
	private static final String R5 = "{\"id\":[\"15742\"],\"page_name\":\"Maggot King\","
		+ "\"elemental_weakness\":\"Fire\",\"version_anchor\":\"Nearby\",\"elemental_weakness_percent\":5}";

	private static String assertFetchFails(Canned canned, int pageSize)
	{
		try
		{
			List<WikiRow> rows = canned.fetch(pageSize).fetchRows();
			fail("should have failed, got " + rows);
		}
		catch (IOException expected)
		{
			assertNotNull(expected.getMessage());
			return expected.getMessage();
		}
		return null;
	}

	// ---- ADR-0006 ----

	private static void assertSafe(String message)
	{
		assertFalse(message, TextTest.isHostileToPrint(message));
	}

	@Test
	public void aReplyBodyIsNeverEchoedRawInAFailure()
	{
		String evil = "\u001b[2J\u009b<html>\nFake: all good\u202e</html>";
		assertSafe(assertFetchFails(new Canned().reply(503, evil), 500));
		assertSafe(assertFetchFails(new Canned().ok(evil), 500));
		assertSafe(assertFetchFails(new Canned().ok("{\"error\":{\"info\":\"" + "\\u001b[2J\\u009b" + "\"}}"), 500));
		assertSafe(assertFetchFails(new Canned().ok(bucket("{\"page_name\":\"\\u001b[2J\\u009b\",\"id\":\"7\"}")), 500));
	}

	@Test
	public void aTitleOrTimestampIsNeverEchoedRawInAFailure()
	{
		String evil = "\\u001b[2J\\u009b\\u202e";
		try
		{
			new Canned().ok(revisions(page("Kraken", evil))).fetch(500).fetchLastEdits(Arrays.asList("Kraken"));
			fail("should have failed");
		}
		catch (IOException expected)
		{
			assertSafe(expected.getMessage());
		}
		try
		{
			new Canned().fetch(500).fetchLastEdits(Arrays.asList("A|\u001b[2J\u009b"));
			fail("should have refused");
		}
		catch (IOException expected)
		{
			assertSafe(expected.getMessage());
		}
	}

	// ---- the bucket walk ----

	@Test
	public void itWalksPastAShortPageAndStopsOnlyOnAnEmptyOne() throws IOException
	{
		// page size 2: a full page, a SHORT page (1 row), another full page, then empty
		Canned canned = new Canned()
			.ok(bucket(R1, R2))
			.ok(bucket(R3))
			.ok(bucket(R4, R5))
			.ok(bucket());
		List<WikiRow> rows = canned.fetch(2).fetchRows();
		assertEquals(5, rows.size());
		assertEquals("Tz-Kih", rows.get(0).pageName);
		assertEquals("Maggot King", rows.get(4).pageName);
		assertEquals("a short page is not the end", 4, canned.uris.size());
		// each request starts where the rows read so far end, so a short page cannot make the walk skip anything
		assertEquals("0", offsetOf(canned.query(0)));
		assertEquals("2", offsetOf(canned.query(1)));
		assertEquals("3", offsetOf(canned.query(2)));
		assertEquals("5", offsetOf(canned.query(3)));
	}

	private static String offsetOf(Map<String, String> query)
	{
		String q = query.get("query");
		int i = q.indexOf(".offset(") + ".offset(".length();
		return q.substring(i, q.indexOf(')', i));
	}

	@Test
	public void itAsksForTheFiveFieldsAndNothingElse() throws IOException
	{
		Canned canned = new Canned().ok(bucket(R1)).ok(bucket());
		canned.fetch(500).fetchRows();
		Map<String, String> q = canned.query(0);
		assertEquals("bucket", q.get("action"));
		assertEquals("json", q.get("format"));
		assertEquals("bucket(\"infobox_monster\").select(\"page_name\",\"version_anchor\",\"id\","
			+ "\"elemental_weakness\",\"elemental_weakness_percent\").limit(500).offset(0).run()", q.get("query"));
		assertTrue(canned.uris.get(0).toString().startsWith("https://oldschool.runescape.wiki/api.php?"));
	}

	@Test
	public void anEmptyFirstPageFailsBecauseTheWikiIsNotEmpty() throws IOException
	{
		String message = assertFetchFails(new Canned().ok(bucket()), 500);
		assertTrue(message, message.contains("no rows"));
	}

	@Test
	public void anErrorReplyFails()
	{
		String message = assertFetchFails(new Canned().ok(
			"{\"error\":{\"code\":\"internal_api_error_BucketException\",\"info\":\"Invalid field name: *\"}}"), 500);
		assertTrue(message, message.contains("Invalid field name"));
	}

	@Test
	public void anErrorKeyFailsEvenBesideABucket()
	{
		String message = assertFetchFails(new Canned().ok(
			"{\"error\":{\"code\":\"ratelimited\",\"info\":\"slow down\"},\"bucket\":[" + R1 + "]}"), 500);
		assertTrue(message, message.contains("slow down"));
	}

	@Test
	public void aReplyWithoutABucketKeyFails()
	{
		String message = assertFetchFails(new Canned().ok("{\"warnings\":{\"main\":{\"warnings\":\"x\"}}}"), 500);
		assertTrue(message, message.contains("bucket"));
	}

	@Test
	public void aBucketThatIsNotAnArrayFails()
	{
		assertFetchFails(new Canned().ok("{\"bucket\":{\"a\":1}}"), 500);
	}

	@Test
	public void aNon200ReplyFailsWhateverItSays()
	{
		for (int status : new int[] {429, 500, 503, 301, 404})
		{
			String message = assertFetchFails(new Canned().reply(status, bucket(R1)), 500);
			assertTrue(message, message.contains(String.valueOf(status)));
		}
	}

	@Test
	public void aReplyThatIsNotJsonFails()
	{
		assertFetchFails(new Canned().ok("<html><body>Rate limited</body></html>"), 500);
		assertFetchFails(new Canned().ok(""), 500);
	}

	@Test
	public void aRowOfAShapeTheBucketNeverProducesFailsAndNamesTheOffset()
	{
		String message = assertFetchFails(new Canned().ok(bucket(R1, "{\"id\":\"7\",\"page_name\":\"Bad\"}")), 500);
		assertTrue(message, message.contains("offset 0") || message.contains("Bad"));
	}

	@Test
	public void anEndlessWalkFailsRatherThanLoopingForever()
	{
		Canned canned = new Canned();
		for (int i = 0; i < 200; i++)
		{
			canned.ok(bucket(R1));
		}
		String message = assertFetchFails(canned, 1);
		assertTrue(message, message.contains("pages"));
	}

	// ---- manners ----

	@Test
	public void itWaitsAtLeastHalfASecondBetweenRequestsAndNotBeforeTheFirst() throws IOException
	{
		Canned canned = new Canned().ok(bucket(R1, R2)).ok(bucket(R3)).ok(bucket());
		canned.fetch(2).fetchRows();
		assertEquals(Arrays.asList("GET", "sleep:500", "GET", "sleep:500", "GET"), canned.events);
	}

	@Test
	public void theGapAlsoHoldsBetweenTheLastBucketRequestAndTheFirstRevisionRequest() throws IOException
	{
		Canned canned = new Canned().ok(bucket(R1)).ok(bucket()).ok(revisions(page("Tz-Kih", "2026-10-01T00:00:00Z")));
		WikiFetch fetch = canned.fetch(500);
		fetch.fetchRows();
		fetch.fetchLastEdits(Arrays.asList("Tz-Kih"));
		assertEquals(Arrays.asList("GET", "sleep:500", "GET", "sleep:500", "GET"), canned.events);
	}

	@Test
	public void everyRequestSaysWhoIsAsking() throws IOException
	{
		Canned canned = new Canned().ok(bucket(R1)).ok(bucket()).ok(revisions(page("Tz-Kih", "2026-10-01T00:00:00Z")));
		WikiFetch fetch = canned.fetch(500);
		fetch.fetchRows();
		fetch.fetchLastEdits(Arrays.asList("Tz-Kih"));
		assertEquals(3, canned.agents.size());
		for (String agent : canned.agents)
		{
			assertEquals(WikiFetch.USER_AGENT, agent);
		}
		assertTrue(WikiFetch.USER_AGENT, WikiFetch.USER_AGENT.contains("Foe"));
		assertTrue(WikiFetch.USER_AGENT, WikiFetch.USER_AGENT.contains("weakness-table"));
		assertTrue("long enough to say something", WikiFetch.USER_AGENT.length() > 40);
	}

	// ---- last edits ----

	private static String page(String title, String timestamp)
	{
		return "\"" + Math.abs(title.hashCode()) + "\":{\"pageid\":" + Math.abs(title.hashCode()) + ",\"ns\":0,\"title\":\""
			+ title + "\",\"revisions\":[{\"timestamp\":\"" + timestamp + "\"}]}";
	}

	private static String revisions(String... pages)
	{
		return "{\"batchcomplete\":\"\",\"query\":{\"pages\":{" + String.join(",", pages) + "}}}";
	}

	@Test
	public void itReadsTheLastEditOfEachTitleAndLeavesOutOnesTheWikiDoesNotKnow() throws IOException
	{
		// real shape: a missing page is {"-1":{"ns":0,"title":"...","missing":""}}
		Canned canned = new Canned().ok(revisions(
			page("Fire giant", "2026-10-04T11:09:10Z"),
			page("Kraken", "2026-10-08T18:21:09Z"),
			"\"-1\":{\"ns\":0,\"title\":\"Nonexistent page xyz\",\"missing\":\"\"}"));
		Map<String, Instant> edits = canned.fetch(500)
			.fetchLastEdits(Arrays.asList("Fire giant", "Kraken", "Nonexistent page xyz"));
		assertEquals(2, edits.size());
		assertEquals(Instant.parse("2026-10-04T11:09:10Z"), edits.get("Fire giant"));
		assertEquals(Instant.parse("2026-10-08T18:21:09Z"), edits.get("Kraken"));
		assertFalse(edits.containsKey("Nonexistent page xyz"));
	}

	@Test
	public void itAsksForRevisionTimestampsFiftyTitlesAtATime() throws IOException
	{
		List<String> titles = new ArrayList<>();
		for (int i = 0; i < 120; i++)
		{
			titles.add(String.format("Page %03d", i));
		}
		Canned canned = new Canned().ok(revisions()).ok(revisions()).ok(revisions());
		canned.fetch(500).fetchLastEdits(titles);
		assertEquals(3, canned.uris.size());
		Map<String, String> q = canned.query(0);
		assertEquals("query", q.get("action"));
		assertEquals("json", q.get("format"));
		assertEquals("revisions", q.get("prop"));
		assertEquals("timestamp", q.get("rvprop"));
		assertEquals(50, q.get("titles").split("\\|").length);
		assertEquals(50, canned.query(1).get("titles").split("\\|").length);
		assertEquals(20, canned.query(2).get("titles").split("\\|").length);
		assertEquals(Arrays.asList("GET", "sleep:500", "GET", "sleep:500", "GET"), canned.events);
	}

	@Test
	public void aTitleTheWikiNormalisedIsMappedBackToTheTitleWeAskedFor() throws IOException
	{
		Canned canned = new Canned().ok("{\"batchcomplete\":\"\",\"query\":{\"normalized\":"
			+ "[{\"from\":\"Fire_giant\",\"to\":\"Fire giant\"}],\"pages\":{" + page("Fire giant", "2026-10-04T11:09:10Z")
			+ "}}}");
		Map<String, Instant> edits = canned.fetch(500).fetchLastEdits(Arrays.asList("Fire_giant"));
		assertEquals(Instant.parse("2026-10-04T11:09:10Z"), edits.get("Fire_giant"));
	}

	@Test
	public void aRepeatedTitleIsAskedForOnce() throws IOException
	{
		Canned canned = new Canned().ok(revisions(page("Kraken", "2026-10-08T18:21:09Z")));
		canned.fetch(500).fetchLastEdits(Arrays.asList("Kraken", "Kraken"));
		assertEquals("Kraken", canned.query(0).get("titles"));
	}

	@Test
	public void noTitlesMeansNoRequest() throws IOException
	{
		Canned canned = new Canned();
		assertTrue(canned.fetch(500).fetchLastEdits(new ArrayList<>()).isEmpty());
		assertTrue(canned.uris.isEmpty());
	}

	private static void assertEditsFail(Canned canned)
	{
		try
		{
			canned.fetch(500).fetchLastEdits(Arrays.asList("Kraken"));
			fail("should have failed");
		}
		catch (IOException expected)
		{
			assertNotNull(expected.getMessage());
		}
	}

	@Test
	public void theEditTimeLookupFailsOnTheSameThingsTheBucketWalkDoes()
	{
		assertEditsFail(new Canned().reply(503, revisions()));
		assertEditsFail(new Canned().ok("{\"error\":{\"code\":\"x\",\"info\":\"y\"}}"));
		assertEditsFail(new Canned().ok("{\"batchcomplete\":\"\"}"));
		assertEditsFail(new Canned().ok("<html></html>"));
		assertEditsFail(new Canned().ok(revisions(page("Kraken", "last Tuesday"))));
	}

	@Test
	public void aTitleThatCouldNotBeSentInABatchIsRefused()
	{
		try
		{
			new Canned().fetch(500).fetchLastEdits(Arrays.asList("A|B"));
			fail("should have refused");
		}
		catch (IOException expected)
		{
			assertTrue(expected.getMessage(), expected.getMessage().contains("A|B"));
		}
	}
}
