package com.foe;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/**
 * The gallery's pixels and files (test source set; never ships). {@link #paint} draws one sheet from its
 * {@link PortraitGallery.Sheet} layout; {@link #write} puts the sheets and the two indexes in a folder. Plain AWT and
 * java.nio: no client, so it can run on any thread and {@code PortraitGalleryFilesTest} runs it for real.
 */
final class PortraitGalleryFiles
{
	/** Mid-grey: dark and light models both show against it. */
	private static final Color BACKGROUND = new Color(0x808080);
	/** A cell's outline, a little darker than the background, so a pale model's extent can still be judged. */
	private static final Color FRAME = new Color(0x6E6E6E);
	private static final Color TEXT = new Color(0x101010);
	private static final int FONT_SIZE = 10;
	/** Room kept clear at each side of a label. */
	private static final int INSET = 4;
	/** Which files in the output folder are a previous run's sheets: a view's name and a number. */
	private static final Pattern SHEET_FILE = sheetFile();

	private PortraitGalleryFiles()
	{
	}

	private static Pattern sheetFile()
	{
		StringBuilder views = new StringBuilder();
		for (Portrait.View v : Portrait.View.values())
		{
			views.append(views.length() == 0 ? "" : "|").append(v.name());
		}
		return Pattern.compile("(" + views + ")-\\d+\\.png");
	}

	/** One sheet as an image: the cells on a grey ground, each a picture at 2x and three label lines. */
	static BufferedImage paint(PortraitGallery.Sheet sheet)
	{
		BufferedImage img = new BufferedImage(sheet.width, sheet.height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		try
		{
			g.setColor(BACKGROUND);
			g.fillRect(0, 0, sheet.width, sheet.height);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			Font plain = new Font(Font.SANS_SERIF, Font.PLAIN, FONT_SIZE);
			Font bold = plain.deriveFont(Font.BOLD);
			for (PortraitGallery.Cell c : sheet.cells)
			{
				paint(g, c, plain, bold);
			}
		}
		finally
		{
			g.dispose();
		}
		return img;
	}

	private static void paint(Graphics2D g, PortraitGallery.Cell c, Font plain, Font bold)
	{
		g.setColor(FRAME);
		g.drawRect(c.x, c.y, PortraitGallery.CELL_W - 1, PortraitGallery.CELL_H - 1);
		PortraitGallery.Entry e = c.entry;
		if (e.image != null)
		{
			g.drawImage(e.image, c.x + (PortraitGallery.CELL_W - PortraitGallery.PICTURE) / 2,
				c.y + PortraitGallery.PAD, PortraitGallery.PICTURE, PortraitGallery.PICTURE, null);
		}
		int top = c.y + PortraitGallery.PAD + PortraitGallery.PICTURE + 4;
		String[] lines = {e.group.name, PortraitGallery.idLabel(e.group), PortraitGallery.viewLabel(e.shape)};
		g.setColor(TEXT);
		for (int i = 0; i < lines.length; i++)
		{
			g.setFont(i == 0 ? bold : plain);
			FontMetrics fm = g.getFontMetrics();
			String text = fit(lines[i], fm, PortraitGallery.CELL_W - 2 * INSET);
			g.drawString(text, c.x + INSET, top + i * PortraitGallery.LINE + fm.getAscent());
		}
	}

	/** The text cut with "..." to fit the width; whole when it already does. */
	static String fit(String text, FontMetrics fm, int width)
	{
		if (fm.stringWidth(text) <= width)
		{
			return text;
		}
		String dots = "...";
		int end = text.length();
		while (end > 0 && fm.stringWidth(text.substring(0, end) + dots) > width)
		{
			end--;
		}
		return text.substring(0, end) + dots;
	}

	/**
	 * Writes a PNG per sheet and the two indexes into {@code dir}, made if missing, and returns how many files it
	 * wrote. A previous run's sheets (view name, dash, number, .png) are removed first so a smaller run leaves no
	 * stale sheet behind; nothing else in the folder is touched, and a sub-folder (a filtered run) is not entered. Both
	 * indexes are always written, so no old one survives either. Sheets are painted and written one at a time, since
	 * a full run is dozens of 10 MB images.
	 */
	static int write(Path dir, List<PortraitGallery.Sheet> sheets, List<PortraitGallery.Missing> missing)
		throws IOException
	{
		Files.createDirectories(dir);
		removeOldSheets(dir);
		int written = 0;
		for (PortraitGallery.Sheet s : sheets)
		{
			Path file = dir.resolve(s.name + ".png");
			if (!ImageIO.write(paint(s), "png", file.toFile()))
			{
				throw new IOException("no PNG writer for " + file);
			}
			written++;
		}
		Files.write(dir.resolve("index.tsv"), PortraitGallery.tsv(sheets).getBytes(StandardCharsets.UTF_8));
		Files.write(dir.resolve("unrendered.tsv"), PortraitGallery.missingTsv(missing).getBytes(StandardCharsets.UTF_8));
		return written + 2;
	}

	private static void removeOldSheets(Path dir) throws IOException
	{
		try (DirectoryStream<Path> old = Files.newDirectoryStream(dir))
		{
			for (Path p : old)
			{
				if (SHEET_FILE.matcher(p.getFileName().toString()).matches()
					&& Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
				{
					Files.delete(p);
				}
			}
		}
	}
}
