package com.foe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The sheet painter and the file writer of the dev-only portrait gallery. Real PNGs, in a temporary folder. */
public class PortraitGalleryFilesTest
{
	private static final int GREY = 0x808080;

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private static BufferedImage solid(int rgb, int alpha)
	{
		BufferedImage img = new BufferedImage(PortraitGallery.RENDER, PortraitGallery.RENDER, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setColor(new Color(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, alpha));
		g.setComposite(java.awt.AlphaComposite.Src);
		g.fillRect(0, 0, img.getWidth(), img.getHeight());
		g.dispose();
		return img;
	}

	private static PortraitGallery.Entry entry(int id, String name, Portrait.View view, BufferedImage image)
	{
		PortraitGallery.Group g = PortraitGallery.group(Collections.singletonList(
			new PortraitGallery.Candidate(id, name, "K" + id))).get(0);
		return new PortraitGallery.Entry(g, new PortraitGallery.Shape(view, 10, 20, 30), image);
	}

	private static List<PortraitGallery.Sheet> sheetOf(PortraitGallery.Entry... entries)
	{
		List<PortraitGallery.Entry> in = new ArrayList<>();
		Collections.addAll(in, entries);
		return PortraitGallery.layout(in);
	}

	private static int rgb(BufferedImage img, int x, int y)
	{
		return img.getRGB(x, y) & 0xFFFFFF;
	}

	// ---- painting ----

	@Test
	public void aSheetIsItsLayoutsSizeOnAMidGreyBackground()
	{
		PortraitGallery.Sheet s = sheetOf(entry(1, "Rat", Portrait.View.SIDE, solid(0xFF0000, 255))).get(0);
		BufferedImage img = PortraitGalleryFiles.paint(s);
		assertEquals(s.width, img.getWidth());
		assertEquals(s.height, img.getHeight());
		assertEquals("the margin is the background", GREY, rgb(img, 0, 0));
		assertEquals(GREY, rgb(img, s.width - 1, s.height - 1));
	}

	@Test
	public void aPortraitIsDrawnAtTwiceItsSizeInTheMiddleOfItsCell()
	{
		PortraitGallery.Sheet s = sheetOf(entry(1, "Rat", Portrait.View.SIDE, solid(0x0000FF, 255))).get(0);
		PortraitGallery.Cell c = s.cells.get(0);
		BufferedImage img = PortraitGalleryFiles.paint(s);
		int mid = c.x + PortraitGallery.CELL_W / 2;
		int top = c.y + PortraitGallery.PAD; // the picture starts one pad below the cell's top
		assertEquals("the middle of the picture", 0x0000FF, rgb(img, mid, top + PortraitGallery.PICTURE / 2));
		assertEquals("its first row, at twice the size, spans 128 px", 0x0000FF, rgb(img, mid - 60, top + 2));
		assertEquals("and the 128th", 0x0000FF, rgb(img, mid + 60, top + PortraitGallery.PICTURE - 3));
		assertEquals("a picture is not drawn larger than 128 px", GREY, rgb(img, mid + PortraitGallery.PICTURE / 2 + 3, top + 10));
	}

	@Test
	public void aTransparentPortraitShowsTheGreyThroughSoDarkAndLightModelsBothShow()
	{
		PortraitGallery.Sheet s = sheetOf(entry(1, "Ghost", Portrait.View.UPRIGHT, solid(0xFF0000, 0))).get(0);
		PortraitGallery.Cell c = s.cells.get(0);
		BufferedImage img = PortraitGalleryFiles.paint(s);
		assertEquals(GREY, rgb(img, c.x + PortraitGallery.CELL_W / 2, c.y + PortraitGallery.PAD + PortraitGallery.PICTURE / 2));
	}

	@Test
	public void aCellWithNoPictureStillGetsItsLabel()
	{
		PortraitGallery.Sheet s = sheetOf(entry(1, "Rat", Portrait.View.SIDE, null)).get(0);
		BufferedImage img = PortraitGalleryFiles.paint(s);
		assertTrue("text was drawn under the picture area", inkIn(img, s.cells.get(0), labelTop(s.cells.get(0)),
			PortraitGallery.LINES * PortraitGallery.LINE) > 20);
	}

	@Test
	public void eachOfTheThreeLabelLinesDrawsSomething()
	{
		PortraitGallery.Sheet s = sheetOf(entry(1, "Rat", Portrait.View.SIDE, solid(0x00FF00, 255))).get(0);
		PortraitGallery.Cell c = s.cells.get(0);
		BufferedImage img = PortraitGalleryFiles.paint(s);
		for (int line = 0; line < PortraitGallery.LINES; line++)
		{
			assertTrue("line " + line, inkIn(img, c, labelTop(c) + line * PortraitGallery.LINE, PortraitGallery.LINE) > 5);
		}
	}

	@Test
	public void aLongNameIsCutToItsCellAndNeverSpillsIntoTheNext()
	{
		String name = new String(new char[200]).replace('\0', 'W');
		PortraitGallery.Sheet s = sheetOf(entry(1, name, Portrait.View.SIDE, solid(0x00FF00, 255))).get(0);
		PortraitGallery.Cell c = s.cells.get(0);
		BufferedImage img = PortraitGalleryFiles.paint(s);
		// the next column's cell is empty: it must be all background
		int nextX = c.x + PortraitGallery.CELL_W;
		for (int y = c.y; y < c.y + PortraitGallery.CELL_H; y++)
		{
			for (int x = nextX; x < nextX + PortraitGallery.CELL_W; x++)
			{
				assertEquals("(" + x + "," + y + ")", GREY, rgb(img, x, y));
			}
		}
		assertTrue("the name is still there", inkIn(img, c, labelTop(c), PortraitGallery.LINE) > 20);
	}

	/** The y of the first label line in a cell. */
	private static int labelTop(PortraitGallery.Cell c)
	{
		return c.y + PortraitGallery.PAD + PortraitGallery.PICTURE + 4;
	}

	/** How many pixels in a band of the cell are not the background, frame aside (the frame is a 1 px line). */
	private static int inkIn(BufferedImage img, PortraitGallery.Cell c, int top, int height)
	{
		int ink = 0;
		for (int y = top; y < top + height; y++)
		{
			for (int x = c.x + 2; x < c.x + PortraitGallery.CELL_W - 2; x++)
			{
				if (rgb(img, x, y) != GREY)
				{
					ink++;
				}
			}
		}
		return ink;
	}

	// ---- writing ----

	private static List<PortraitGallery.Missing> noMissing()
	{
		return Collections.emptyList();
	}

	@Test
	public void writeMakesOnePngPerSheetPlusTheTwoIndexes() throws IOException
	{
		Path dir = tmp.getRoot().toPath().resolve("out");
		List<PortraitGallery.Sheet> sheets = sheetOf(
			entry(1, "Rat", Portrait.View.SIDE, solid(0xFF0000, 255)),
			entry(2, "Man", Portrait.View.UPRIGHT, solid(0x00FF00, 255)));
		PortraitGallery.Group ghost = new PortraitGallery.Group(null, Collections.singletonList(9), Collections.singletonList("Ghost"));
		List<PortraitGallery.Missing> missing = Collections.singletonList(
			new PortraitGallery.Missing(ghost, PortraitGallery.Why.NO_MODEL_IDS));

		int written = PortraitGalleryFiles.write(dir, sheets, missing);

		assertEquals("2 sheets and 2 indexes", 4, written);
		for (PortraitGallery.Sheet s : sheets)
		{
			BufferedImage png = ImageIO.read(dir.resolve(s.name + ".png").toFile());
			assertNotNull(s.name, png);
			assertEquals(s.width, png.getWidth());
			assertEquals(s.height, png.getHeight());
		}
		assertEquals(PortraitGallery.tsv(sheets), read(dir.resolve("index.tsv")));
		assertEquals(PortraitGallery.missingTsv(missing), read(dir.resolve("unrendered.tsv")));
	}

	@Test
	public void writeWithNothingToDrawStillWritesBothIndexesSoNoOldOneSurvives() throws IOException
	{
		Path dir = tmp.newFolder("empty").toPath();
		Files.write(dir.resolve("index.tsv"), "stale".getBytes(StandardCharsets.UTF_8));
		assertEquals(2, PortraitGalleryFiles.write(dir, Collections.<PortraitGallery.Sheet>emptyList(), noMissing()));
		assertEquals(PortraitGallery.TSV_HEADER + "\n", read(dir.resolve("index.tsv")));
		assertTrue(Files.exists(dir.resolve("unrendered.tsv")));
	}

	@Test
	public void writeRemovesLastRunsSheetsAndIndexesButNothingElse() throws IOException
	{
		Path dir = tmp.newFolder("again").toPath();
		Path stale = dir.resolve("SIDE-07.png");
		Path staleOther = dir.resolve("CHATHEAD-12.png");
		Path notes = dir.resolve("notes.txt");
		Path notOurs = dir.resolve("photo.png");
		Path sub = dir.resolve("filter-dragon");
		Files.createDirectories(sub);
		Path subSheet = sub.resolve("SIDE-01.png");
		for (Path p : new Path[]{stale, staleOther, notes, notOurs, subSheet, dir.resolve("unrendered.tsv")})
		{
			Files.write(p, "old".getBytes(StandardCharsets.UTF_8));
		}

		PortraitGalleryFiles.write(dir, sheetOf(entry(1, "Rat", Portrait.View.SIDE, solid(0xFF0000, 255))), noMissing());

		assertFalse("last run's sheet is gone", Files.exists(stale));
		assertFalse(Files.exists(staleOther));
		assertTrue("this run's sheet is there", Files.exists(dir.resolve("SIDE-01.png")));
		assertTrue("a file that is not a sheet stays", Files.exists(notes));
		assertTrue(Files.exists(notOurs));
		assertTrue("a filtered run's folder stays", Files.exists(subSheet));
		assertEquals(PortraitGallery.missingTsv(noMissing()), read(dir.resolve("unrendered.tsv")));
	}

	@Test
	public void writeCreatesTheFolderAndItsParents() throws IOException
	{
		Path dir = tmp.getRoot().toPath().resolve("a").resolve("b").resolve("c");
		PortraitGalleryFiles.write(dir, Collections.<PortraitGallery.Sheet>emptyList(), noMissing());
		assertTrue(Files.isDirectory(dir));
	}

	private static String read(Path p) throws IOException
	{
		return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
	}
}
