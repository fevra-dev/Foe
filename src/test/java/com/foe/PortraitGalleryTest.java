package com.foe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.NPCComposition;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The pure half of the dev-only portrait gallery: shape keys, dedupe, filtering, ordering, sheet layout and the
 * index. No client and no files, except the one test that picks the output folder.
 */
public class PortraitGalleryTest
{
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	// ---- shape key ----

	@Test
	public void theKeyIsTheChatheadModelsWhenThereAreSomeSortedAndTheBodyIsIgnored()
	{
		String a = PortraitGallery.shapeKey(new int[]{9, 5, 3}, new int[]{100, 200});
		String b = PortraitGallery.shapeKey(new int[]{3, 9, 5}, new int[]{7});
		assertEquals("same chathead models in any order, whatever the body", a, b);
		assertNotEquals("a different chathead is a different shape", a,
			PortraitGallery.shapeKey(new int[]{3, 5, 10}, new int[]{100, 200}));
	}

	@Test
	public void withoutAChatheadTheKeyIsTheBodyModelsSorted()
	{
		String fromNull = PortraitGallery.shapeKey(null, new int[]{40, 20});
		assertEquals(fromNull, PortraitGallery.shapeKey(new int[0], new int[]{20, 40}));
		assertNotEquals(fromNull, PortraitGallery.shapeKey(null, new int[]{20, 41}));
	}

	@Test
	public void aChatheadAndABodyMadeOfTheSameModelsAreNotTheSameShape()
	{
		// Portrait draws a chathead whole and a body cropped: the same ids are two different pictures.
		assertNotEquals(PortraitGallery.shapeKey(new int[]{5, 6}, null), PortraitGallery.shapeKey(null, new int[]{5, 6}));
	}

	@Test
	public void aModelUsedTwiceIsKeptTwiceBecauseTheMeshDrawsItTwice()
	{
		assertNotEquals(PortraitGallery.shapeKey(null, new int[]{1, 1, 2}), PortraitGallery.shapeKey(null, new int[]{1, 2}));
	}

	@Test
	public void noModelsAtAllIsNoKey()
	{
		assertNull(PortraitGallery.shapeKey(null, null));
		assertNull(PortraitGallery.shapeKey(new int[0], new int[0]));
	}

	@Test
	public void theKeyDoesNotChangeTheCallersArrays()
	{
		int[] body = {3, 1, 2};
		PortraitGallery.shapeKey(null, body);
		assertEquals(Arrays.asList(3, 1, 2), Arrays.asList(body[0], body[1], body[2]));
	}

	// ---- which definitions are kept ----

	/** A composition that answers only what the gallery should ask, and throws on anything else. */
	static NPCComposition fake(int id, String name, int level, int[] configs, int[] head, int[] body)
	{
		return (NPCComposition) Proxy.newProxyInstance(PortraitGalleryTest.class.getClassLoader(),
			new Class<?>[]{NPCComposition.class}, (p, m, a) ->
			{
				switch (m.getName())
				{
					case "getId":
						return id;
					case "getName":
						return name;
					case "getCombatLevel":
						return level;
					case "getConfigs":
						return configs;
					case "getChatheadModels":
						return head;
					case "getModels":
						return body;
					default:
						throw new UnsupportedOperationException(m.toString());
				}
			});
	}

	static NPCComposition monster(int id, String name)
	{
		return fake(id, name, 50, null, null, new int[]{id});
	}

	@Test
	public void aNamedMonsterWithACombatLevelIsKept()
	{
		assertNull(PortraitGallery.skipReason(monster(1, "Goblin")));
	}

	@Test
	public void anUnnamedDefinitionIsSkipped()
	{
		for (String name : new String[]{null, "", "   ", "null", "<col=ff0000>"})
		{
			assertEquals("name " + name, PortraitGallery.Skip.UNNAMED,
				PortraitGallery.skipReason(fake(1, name, 50, null, null, new int[]{1})));
		}
	}

	@Test
	public void aDefinitionWithoutACombatLevelIsSkipped()
	{
		assertEquals(PortraitGallery.Skip.NO_COMBAT_LEVEL,
			PortraitGallery.skipReason(fake(1, "Banker", 0, null, null, new int[]{1})));
		assertEquals(PortraitGallery.Skip.NO_COMBAT_LEVEL,
			PortraitGallery.skipReason(fake(1, "Banker", -1, null, null, new int[]{1})));
	}

	@Test
	public void aTransformParentIsSkippedBecauseFoeDrawsTheFormItResolvesToAndThatFormHasItsOwnId()
	{
		assertEquals(PortraitGallery.Skip.TRANSFORM_PARENT,
			PortraitGallery.skipReason(fake(1, "Zulrah", 100, new int[]{2042, 2043, -1}, null, new int[]{1})));
		// an empty configs array is no transform
		assertNull(PortraitGallery.skipReason(fake(1, "Zulrah", 100, new int[0], null, new int[]{1})));
	}

	@Test
	public void aCandidateCarriesItsIdAGlyphFreeNameAndItsShapeKey()
	{
		PortraitGallery.Candidate c = PortraitGallery.candidate(
			fake(7, "<col=ff0000>Red\tdragon\n", 90, null, new int[]{8, 6}, new int[]{99}));
		assertEquals(7, c.id);
		assertEquals("tags and control characters are gone", "Red dragon", c.name);
		assertEquals(PortraitGallery.shapeKey(new int[]{6, 8}, new int[]{99}), c.key);
		assertNull("no models: no key", PortraitGallery.candidate(fake(1, "Ghost", 5, null, null, null)).key);
	}

	// ---- dedupe ----

	private static PortraitGallery.Candidate cand(int id, String name, String key)
	{
		return new PortraitGallery.Candidate(id, name, key);
	}

	@Test
	public void definitionsWithTheSameShapeCollapseToTheLowestIdWhateverTheInputOrder()
	{
		List<PortraitGallery.Group> groups = PortraitGallery.group(Arrays.asList(
			cand(30, "Green dragon", "B:1,2"), cand(10, "Red dragon", "B:1,2"), cand(20, "Blue dragon", "B:1,2"),
			cand(5, "Goblin", "B:9")));
		assertEquals(2, groups.size());
		PortraitGallery.Group goblin = groups.get(0);
		PortraitGallery.Group dragon = groups.get(1);
		assertEquals("groups come in order of their lowest id", 5, goblin.id);
		assertEquals(10, dragon.id);
		assertEquals("Red dragon", dragon.name);
		assertEquals(Arrays.asList(10, 20, 30), dragon.ids);
		assertEquals(2, dragon.shared());
		assertEquals(0, goblin.shared());
	}

	@Test
	public void aGroupNamesEachDistinctNameOnceInOrderOfItsFirstId()
	{
		PortraitGallery.Group g = PortraitGallery.group(Arrays.asList(
			cand(4, "Dragon", "K"), cand(2, "Dragon", "K"), cand(3, "Wyrm", "K"), cand(9, "Dragon", "K"))).get(0);
		assertEquals(Arrays.asList("Dragon", "Wyrm"), g.names);
		assertEquals("ids count, names do not", 3, g.shared());
	}

	@Test
	public void twoShapesThatDifferByOneModelStaySeparate()
	{
		assertEquals(2, PortraitGallery.group(Arrays.asList(cand(1, "A", "B:1,2"), cand(2, "A", "B:1,3"))).size());
	}

	@Test
	public void thereAreNoGroupsWithoutCandidates()
	{
		assertTrue(PortraitGallery.group(Collections.<PortraitGallery.Candidate>emptyList()).isEmpty());
	}

	// ---- the name filter ----

	@Test
	public void theFilterKeepsAGroupWhenAnyOfItsNamesContainsItIgnoringCase()
	{
		List<PortraitGallery.Group> groups = PortraitGallery.group(Arrays.asList(
			cand(1, "Goblin", "K1"), cand(2, "Baby DRAGON", "K2"), cand(3, "Wyrm", "K3"), cand(4, "Drake", "K3"),
			cand(5, "Dragonfly", "K4")));
		assertEquals(Arrays.asList(2, 5), ids(PortraitGallery.matching(groups, "dragon")));
		assertEquals("a name other than the first one counts", Arrays.asList(3), ids(PortraitGallery.matching(groups, "drake")));
		assertEquals("nothing matches", Collections.emptyList(), ids(PortraitGallery.matching(groups, "unicorn")));
	}

	@Test
	public void noFilterKeepsEverything()
	{
		List<PortraitGallery.Group> groups = PortraitGallery.group(Arrays.asList(cand(1, "A", "K1"), cand(2, "B", "K2")));
		assertEquals(2, PortraitGallery.matching(groups, "").size());
		assertEquals(2, PortraitGallery.matching(groups, "   ").size());
		assertEquals(2, PortraitGallery.matching(groups, null).size());
	}

	private static List<Integer> ids(List<PortraitGallery.Group> groups)
	{
		List<Integer> out = new ArrayList<>();
		for (PortraitGallery.Group g : groups)
		{
			out.add(g.id);
		}
		return out;
	}

	// ---- Portrait.describe, the contract this tool reads ----

	/** A single triangle whose corners span exactly width x height x length. */
	static Portrait.Mesh box(float width, float height, float length, boolean head)
	{
		return new Portrait.Mesh(new float[]{0, width, width / 2}, new float[]{0, -height, -height / 2},
			new float[]{0, 0, length}, new int[]{0}, new int[]{1}, new int[]{2}, new int[]{0}, new int[]{-1}, null, 1,
			head);
	}

	@Test
	public void parseDescribeReadsTheViewAndTheExtentsThatPortraitDescribeWrites()
	{
		// If Portrait.describe's wording changes, this fails here, not as a blank index.tsv.
		for (Object[] c : new Object[][]{
			{150f, 160f, 170f, true, Portrait.View.CHATHEAD}, // a chathead, whatever its size
			{100f, 110f, 120f, false, Portrait.View.WHOLE}, // compact
			{80f, 300f, 90f, false, Portrait.View.UPRIGHT}, // tall
			{60f, 100f, 400f, false, Portrait.View.SIDE}, // low and long
			{400f, 100f, 300f, false, Portrait.View.TOP}})  // low and wide
		{
			Portrait.Mesh m = box((Float) c[0], (Float) c[1], (Float) c[2], (Boolean) c[3]);
			PortraitGallery.Shape s = PortraitGallery.parseDescribe(Portrait.describe(m));
			assertNotNull(Portrait.describe(m), s);
			assertEquals(c[4], s.view);
			assertEquals(Math.round((Float) c[0]), s.width);
			assertEquals(Math.round((Float) c[1]), s.height);
			assertEquals(Math.round((Float) c[2]), s.length);
		}
	}

	@Test
	public void parseDescribeSaysNothingForAModelWithNoDrawableFace()
	{
		Portrait.Mesh hidden = new Portrait.Mesh(new float[]{0, 1, 2}, new float[3], new float[3], new int[]{0},
			new int[]{1}, new int[]{2}, new int[]{0}, new int[]{-2}, null, 1, false);
		assertEquals("no drawable face", Portrait.describe(hidden));
		assertNull(PortraitGallery.parseDescribe(Portrait.describe(hidden)));
		assertNull(PortraitGallery.parseDescribe(null));
		assertNull(PortraitGallery.parseDescribe("something else (width 1, height 2, length 3)"));
	}

	// ---- ordering and layout ----

	private static PortraitGallery.Entry entry(int id, String name, Portrait.View view)
	{
		PortraitGallery.Group g = PortraitGallery.group(Collections.singletonList(cand(id, name, "K" + id))).get(0);
		return new PortraitGallery.Entry(g, new PortraitGallery.Shape(view, 10, 20, 30), (BufferedImage) null);
	}

	private static List<PortraitGallery.Entry> many(int n, Portrait.View view)
	{
		List<PortraitGallery.Entry> out = new ArrayList<>();
		for (int i = 0; i < n; i++)
		{
			out.add(entry(1000 + i, String.format("Mob %04d", i), view));
		}
		return out;
	}

	@Test
	public void sheetsGoByViewInThePortraitEnumOrderAndAreNamedForTheView()
	{
		List<PortraitGallery.Entry> in = new ArrayList<>();
		in.add(entry(1, "A", Portrait.View.TOP));
		in.add(entry(2, "B", Portrait.View.SIDE));
		in.add(entry(3, "C", Portrait.View.CHATHEAD));
		in.add(entry(4, "D", Portrait.View.UPRIGHT));
		in.add(entry(5, "E", Portrait.View.WHOLE));
		List<PortraitGallery.Sheet> sheets = PortraitGallery.layout(in);
		List<String> names = new ArrayList<>();
		for (PortraitGallery.Sheet s : sheets)
		{
			names.add(s.name);
		}
		assertEquals(Arrays.asList("CHATHEAD-01", "WHOLE-01", "UPRIGHT-01", "SIDE-01", "TOP-01"), names);
	}

	@Test
	public void aViewWithNothingInItGetsNoSheet()
	{
		List<PortraitGallery.Sheet> sheets = PortraitGallery.layout(many(3, Portrait.View.SIDE));
		assertEquals(1, sheets.size());
		assertEquals("SIDE-01", sheets.get(0).name);
		assertTrue(PortraitGallery.layout(Collections.<PortraitGallery.Entry>emptyList()).isEmpty());
	}

	@Test
	public void withinAViewTheCellsAreByNameIgnoringCaseThenById()
	{
		List<PortraitGallery.Entry> in = Arrays.asList(
			entry(5, "zebra", Portrait.View.SIDE), entry(9, "Aardvark", Portrait.View.SIDE),
			entry(3, "Wolf", Portrait.View.SIDE), entry(8, "wolf", Portrait.View.SIDE),
			entry(2, "wolf", Portrait.View.SIDE));
		List<Integer> order = new ArrayList<>();
		for (PortraitGallery.Cell c : PortraitGallery.layout(in).get(0).cells)
		{
			order.add(c.entry.group.id);
		}
		assertEquals(Arrays.asList(9, 2, 3, 8, 5), order);
	}

	@Test
	public void aHundredCellsFillOneSheetAndTheHundredAndFirstStartsTheNext()
	{
		assertEquals(1, PortraitGallery.layout(many(100, Portrait.View.SIDE)).size());
		List<PortraitGallery.Sheet> two = PortraitGallery.layout(many(101, Portrait.View.SIDE));
		assertEquals(2, two.size());
		assertEquals(100, two.get(0).cells.size());
		assertEquals(1, two.get(1).cells.size());
		assertEquals("SIDE-02", two.get(1).name);
		assertEquals("the 101st by name starts the second sheet", "Mob 0100", two.get(1).cells.get(0).entry.group.name);
	}

	@Test
	public void twoHundredFiftyCellsMakeThreeSheetsOfAHundredAHundredAndFifty()
	{
		List<PortraitGallery.Sheet> sheets = PortraitGallery.layout(many(250, Portrait.View.UPRIGHT));
		assertEquals(Arrays.asList("UPRIGHT-01", "UPRIGHT-02", "UPRIGHT-03"),
			Arrays.asList(sheets.get(0).name, sheets.get(1).name, sheets.get(2).name));
		assertEquals(Arrays.asList(100, 100, 50),
			Arrays.asList(sheets.get(0).cells.size(), sheets.get(1).cells.size(), sheets.get(2).cells.size()));
	}

	@Test
	public void aViewNeverSharesASheetWithAnother()
	{
		List<PortraitGallery.Entry> in = new ArrayList<>(many(60, Portrait.View.SIDE));
		in.addAll(many(60, Portrait.View.TOP));
		List<PortraitGallery.Sheet> sheets = PortraitGallery.layout(in);
		assertEquals(2, sheets.size());
		for (PortraitGallery.Sheet s : sheets)
		{
			for (PortraitGallery.Cell c : s.cells)
			{
				assertEquals(s.view, c.entry.view);
			}
		}
	}

	@Test
	public void cellsRunLeftToRightTenToARowAndTopToBottomFromOne()
	{
		PortraitGallery.Sheet s = PortraitGallery.layout(many(100, Portrait.View.SIDE)).get(0);
		PortraitGallery.Cell first = s.cells.get(0);
		PortraitGallery.Cell tenth = s.cells.get(9);
		PortraitGallery.Cell eleventh = s.cells.get(10);
		PortraitGallery.Cell last = s.cells.get(99);
		assertEquals(1, first.number);
		assertEquals(0, first.row);
		assertEquals(0, first.col);
		assertEquals(PortraitGallery.MARGIN, first.x);
		assertEquals(PortraitGallery.MARGIN, first.y);
		assertEquals(10, tenth.number);
		assertEquals(9, tenth.col);
		assertEquals(0, tenth.row);
		assertEquals(PortraitGallery.MARGIN + 9 * PortraitGallery.CELL_W, tenth.x);
		assertEquals(11, eleventh.number);
		assertEquals(1, eleventh.row);
		assertEquals(0, eleventh.col);
		assertEquals(PortraitGallery.MARGIN + PortraitGallery.CELL_H, eleventh.y);
		assertEquals(100, last.number);
		assertEquals(9, last.row);
		assertEquals(9, last.col);
	}

	@Test
	public void noTwoCellsOverlapAndEveryCellIsInsideItsSheet()
	{
		for (PortraitGallery.Sheet s : PortraitGallery.layout(many(137, Portrait.View.SIDE)))
		{
			for (int i = 0; i < s.cells.size(); i++)
			{
				PortraitGallery.Cell a = s.cells.get(i);
				assertTrue(a.x >= 0 && a.y >= 0);
				assertTrue(s.name, a.x + PortraitGallery.CELL_W <= s.width && a.y + PortraitGallery.CELL_H <= s.height);
				for (int j = i + 1; j < s.cells.size(); j++)
				{
					PortraitGallery.Cell b = s.cells.get(j);
					boolean apart = a.x + PortraitGallery.CELL_W <= b.x || b.x + PortraitGallery.CELL_W <= a.x
						|| a.y + PortraitGallery.CELL_H <= b.y || b.y + PortraitGallery.CELL_H <= a.y;
					assertTrue(s.name + " cells " + a.number + " and " + b.number, apart);
				}
			}
		}
	}

	@Test
	public void aShortLastSheetIsOnlyAsTallAsItsRows()
	{
		List<PortraitGallery.Sheet> sheets = PortraitGallery.layout(many(105, Portrait.View.SIDE));
		int full = sheets.get(0).height;
		int shortSheet = sheets.get(1).height;
		assertEquals(2 * PortraitGallery.MARGIN + 10 * PortraitGallery.CELL_H, full);
		assertEquals("five cells are one row", 2 * PortraitGallery.MARGIN + PortraitGallery.CELL_H, shortSheet);
		assertEquals(sheets.get(0).width, sheets.get(1).width);
		assertEquals(2 * PortraitGallery.MARGIN + 10 * PortraitGallery.CELL_W, sheets.get(0).width);
	}

	// ---- the labels and the index ----

	@Test
	public void theIdLabelCountsTheOtherIdsThatShareTheShape()
	{
		PortraitGallery.Group three = PortraitGallery.group(Arrays.asList(cand(7, "A", "K"), cand(8, "A", "K"), cand(9, "B", "K"))).get(0);
		assertEquals("id 7 (+2 shared)", PortraitGallery.idLabel(three));
		assertEquals("id 5", PortraitGallery.idLabel(PortraitGallery.group(Collections.singletonList(cand(5, "A", "K5"))).get(0)));
	}

	@Test
	public void theViewLabelIsTheViewAndItsThreeExtents()
	{
		assertEquals("SIDE w60 h100 l400", PortraitGallery.viewLabel(new PortraitGallery.Shape(Portrait.View.SIDE, 60, 100, 400)));
	}

	@Test
	public void theIndexHasOneRowPerCellInSheetOrderAndTheDocumentedColumns()
	{
		List<PortraitGallery.Candidate> cs = Arrays.asList(
			cand(10, "Red dragon", "K1"), cand(20, "Blue dragon", "K1"), cand(30, "Black dragon", "K1"), cand(1, "Rat", "K2"));
		List<PortraitGallery.Group> gs = PortraitGallery.group(cs);
		List<PortraitGallery.Entry> in = new ArrayList<>();
		in.add(new PortraitGallery.Entry(gs.get(0), new PortraitGallery.Shape(Portrait.View.SIDE, 60, 100, 400), null));
		in.add(new PortraitGallery.Entry(gs.get(1), new PortraitGallery.Shape(Portrait.View.UPRIGHT, 80, 300, 90), null));
		String[] lines = PortraitGallery.tsv(PortraitGallery.layout(in)).split("\n", -1);
		assertEquals("sheet\tcell\tid\tname\tview\twidth\theight\tlength\tshared ids\tall names", lines[0]);
		// UPRIGHT sorts before SIDE
		assertEquals("UPRIGHT-01\t1\t10\tRed dragon\tUPRIGHT\t80\t300\t90\t20,30\tRed dragon | Blue dragon | Black dragon", lines[1]);
		assertEquals("SIDE-01\t1\t1\tRat\tSIDE\t60\t100\t400\t\tRat", lines[2]);
		assertEquals("a final newline, then nothing", "", lines[3]);
		assertEquals(4, lines.length);
	}

	@Test
	public void theIndexKeepsEveryRowOnOneLineEvenForAnAwkwardName()
	{
		PortraitGallery.Group g = PortraitGallery.group(Collections.singletonList(cand(1, "Two\tcolumns\nand\rlines", "K"))).get(0);
		PortraitGallery.Entry e = new PortraitGallery.Entry(g, new PortraitGallery.Shape(Portrait.View.WHOLE, 1, 2, 3), null);
		String[] lines = PortraitGallery.tsv(PortraitGallery.layout(Collections.singletonList(e))).split("\n", -1);
		assertEquals(3, lines.length);
		assertEquals(10, lines[1].split("\t", -1).length);
	}

	// ---- the chat command ----

	@Test
	public void theCommandIsFoegalleryInAnyCaseAndNothingElse()
	{
		assertTrue(PortraitGallery.isCommand("foegallery"));
		assertTrue(PortraitGallery.isCommand("FoeGallery"));
		assertFalse(PortraitGallery.isCommand("foegalleryx"));
		assertFalse(PortraitGallery.isCommand("gallery"));
		assertFalse(PortraitGallery.isCommand(""));
		assertFalse(PortraitGallery.isCommand(null));
	}

	@Test
	public void theFilterIsTheArgumentsJoinedByASpaceWithOddBlanksDropped()
	{
		assertEquals("", PortraitGallery.filterOf(new String[0]));
		assertEquals("", PortraitGallery.filterOf(null));
		assertEquals("dragon", PortraitGallery.filterOf(new String[]{"dragon"}));
		assertEquals("baby blue dragon", PortraitGallery.filterOf(new String[]{"baby", "", "blue", "dragon"}));
		assertEquals("", PortraitGallery.filterOf(new String[]{"", " "}));
	}

	// ---- which ids to look at ----

	@Test
	public void theIdsAreTheCachesOwnFileIdsSortedAndWithoutDuplicatesOrNegatives()
	{
		assertEquals(Arrays.asList(0, 3, 5, 9), toList(PortraitGallery.npcIds(new int[]{9, 3, -1, 5, 3, 0}, 10)));
	}

	@Test
	public void aListThatEndsFarBelowTheFallbackIsNotTheNpcArchiveSoItIsIgnored()
	{
		// the archive number is read off the client's bytecode: if it were wrong the list would be some other archive
		assertEquals(Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10), toList(PortraitGallery.npcIds(new int[]{0, 3, 4}, 10)));
		assertEquals("half the fallback is plausible", Arrays.asList(0, 3, 5), toList(PortraitGallery.npcIds(new int[]{0, 3, 5}, 10)));
	}

	@Test
	public void withoutTheCachesFileIdsEveryIdUpToTheFallbackIsLooked()
	{
		assertEquals(Arrays.asList(0, 1, 2, 3), toList(PortraitGallery.npcIds(null, 3)));
		assertEquals(Arrays.asList(0, 1, 2, 3), toList(PortraitGallery.npcIds(new int[0], 3)));
		assertEquals("a cache that lists only negatives is no list", Arrays.asList(0, 1), toList(PortraitGallery.npcIds(new int[]{-4}, 1)));
	}

	private static List<Integer> toList(int[] a)
	{
		List<Integer> out = new ArrayList<>();
		for (int v : a)
		{
			out.add(v);
		}
		return out;
	}

	// ---- the closing message ----

	@Test
	public void theSummaryNamesWhatWasDrawnWhatWasNotAndWhere()
	{
		PortraitGalleryRun.Stats s = new PortraitGalleryRun.Stats();
		s.kept = 9000;
		s.shapes = 3100;
		s.matched = 3100;
		s.rendered = 3050;
		s.notLoaded = 30;
		s.noModelIds = 12;
		s.notDrawable = 3;
		s.errors = 5;
		String line = PortraitGallery.summary(s, 31, "");
		assertTrue(line, line.contains("3050 portraits"));
		assertTrue(line, line.contains("9000 monsters"));
		assertTrue(line, line.contains("3100 shapes"));
		assertTrue(line, line.contains("31 sheets"));
		assertTrue(line, line.contains("50 not drawn"));
		assertTrue(line, line.contains("unrendered.tsv"));
		assertTrue("no filter, no filter text: " + line, !line.contains("matching"));
		assertTrue(PortraitGallery.summary(s, 1, "dragon").contains("matching \"dragon\""));
		assertTrue("a single sheet is not plural", PortraitGallery.summary(s, 1, "").contains("1 sheet,"));
	}

	// ---- what did not make it onto a sheet ----

	@Test
	public void theUnrenderedIndexSaysWhyEachShapeIsMissingByReasonThenNameThenId()
	{
		PortraitGallery.Group zed = PortraitGallery.group(Arrays.asList(cand(5, "Zed", "K5"), cand(6, "Zed", "K5"))).get(0);
		PortraitGallery.Group alpha = PortraitGallery.group(Collections.singletonList(cand(2, "Alpha", "K2"))).get(0);
		PortraitGallery.Group ghost = new PortraitGallery.Group(null, Collections.singletonList(9), Collections.singletonList("Ghost"));
		String[] lines = PortraitGallery.missingTsv(Arrays.asList(
			new PortraitGallery.Missing(zed, PortraitGallery.Why.NOT_LOADED),
			new PortraitGallery.Missing(ghost, PortraitGallery.Why.NO_MODEL_IDS),
			new PortraitGallery.Missing(alpha, PortraitGallery.Why.NOT_LOADED))).split("\n", -1);
		assertEquals("reason\tid\tname\tshared ids\tall names", lines[0]);
		assertEquals("NO_MODEL_IDS\t9\tGhost\t\tGhost", lines[1]);
		assertEquals("NOT_LOADED\t2\tAlpha\t\tAlpha", lines[2]);
		assertEquals("NOT_LOADED\t5\tZed\t6\tZed", lines[3]);
		assertEquals(5, lines.length);
	}

	@Test
	public void theUnrenderedIndexOfNothingIsJustItsHeader()
	{
		assertEquals("reason\tid\tname\tshared ids\tall names\n", PortraitGallery.missingTsv(Collections.<PortraitGallery.Missing>emptyList()));
	}

	// ---- where the files go ----

	@Test
	public void filesGoUnderBuildInTheRepoWhenRunFromTheRepoRoot() throws Exception
	{
		Path repo = tmp.newFolder("repo").toPath();
		java.nio.file.Files.write(repo.resolve("runelite-plugin.properties"), new byte[0]);
		Path home = tmp.newFolder("home").toPath();
		assertEquals(repo.resolve("build").resolve("foe-gallery"), PortraitGallery.outputRoot(repo, home));
	}

	@Test
	public void filesGoUnderTheRuneLiteFolderWhenTheWorkingDirectoryIsNotTheRepo() throws Exception
	{
		Path elsewhere = tmp.newFolder("elsewhere").toPath();
		Path home = tmp.newFolder("home2").toPath();
		assertEquals(home.resolve(".runelite").resolve("foe-gallery"), PortraitGallery.outputRoot(elsewhere, home));
	}

	@Test
	public void aFilteredRunGetsItsOwnFolderSoItNeverMixesWithTheFullSheets()
	{
		assertEquals("", PortraitGallery.directoryName(""));
		assertEquals("", PortraitGallery.directoryName(null));
		assertEquals("filter-dragon", PortraitGallery.directoryName("Dragon"));
		assertEquals("filter-baby-blue-dragon", PortraitGallery.directoryName("  Baby   BLUE dragon "));
	}

	@Test
	public void aFilterCannotNameAFolderOutsideTheOutputRoot()
	{
		for (String evil : new String[]{"../../etc", "..", "/etc/passwd", "a/../b", "C:\\Windows", "x\u0000y"})
		{
			String d = PortraitGallery.directoryName(evil);
			assertTrue(evil + " -> " + d, d.matches("filter-[a-z0-9]+(-[a-z0-9]+)*") || d.isEmpty());
			assertTrue(d, !d.contains("/") && !d.contains("\\") && !d.contains(".."));
		}
		assertEquals("only punctuation leaves nothing to name a folder after, but it is still a filtered run",
			"filter-other", PortraitGallery.directoryName("../.."));
		assertTrue(PortraitGallery.directoryName(new String(new char[500]).replace('\0', 'a')).length() <= 48);
	}
}
