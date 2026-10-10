package com.foe;

import java.awt.image.BufferedImage;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class PortraitTest
{
	private static final int RED = 0 << 10 | 7 << 7 | 64; // hue 0, full saturation, mid lightness
	private static final int BLUE = 43 << 10 | 7 << 7 | 64;

	/** One 100 wide square facing the camera, from y -100 (up) to 0, as two triangles. */
	private static Portrait.Mesh square(int colour, int colours3, boolean head)
	{
		return new Portrait.Mesh(new float[]{-50, 50, 50, -50}, new float[]{-100, -100, 0, 0}, new float[4],
			new int[]{0, 0}, new int[]{1, 2}, new int[]{2, 3}, new int[]{colour, colour},
			new int[]{colours3, colours3}, null, 2, head);
	}

	@Test
	public void aHeadIsFittedWholeInTheFaceColour()
	{
		BufferedImage img = Portrait.render(square(RED, -1, true), 32);
		assertNotNull(img);
		assertEquals(32, img.getWidth());
		int centre = img.getRGB(16, 16);
		assertEquals(255, centre >>> 24);
		assertTrue("red dominates: " + Integer.toHexString(centre), (centre >> 16 & 255) > 150 && (centre >> 8 & 255) < 80);
	}

	@Test
	public void aBodyIsCroppedToASquareOverItsTopThirtyPercent()
	{
		// 100 wide, 400 tall: red on the top half, blue below. The crop is a 120 square from the top, centred on
		// the body: only red, red down to the bottom edge, and clear beyond the body's sides.
		Portrait.Mesh body = new Portrait.Mesh(new float[]{-50, 50, 50, -50, 50, -50},
			new float[]{-400, -400, -200, -200, 0, 0}, new float[6],
			new int[]{0, 0, 3, 3}, new int[]{1, 2, 2, 4}, new int[]{2, 3, 4, 5},
			new int[]{RED, RED, BLUE, BLUE}, new int[]{-1, -1, -1, -1}, null, 4, false);
		BufferedImage img = Portrait.render(body, 60);
		assertNotNull(img);
		for (int y = 0; y < 60; y++)
		{
			int c = img.getRGB(30, y);
			assertTrue("no blue at y " + y + ": " + Integer.toHexString(c), (c >>> 24) < 128 || (c & 255) < 120);
		}
		assertEquals("red reaches the bottom edge", 255, img.getRGB(30, 59) >>> 24);
		assertTrue("clear beyond the body's sides", (img.getRGB(2, 30) >>> 24) < 128);
	}

	@Test
	public void theNearerFaceIsDrawnOverTheFartherOne()
	{
		// Review finding 4 (M1): two squares in the same place, red at z +100 and blue at z -100. Smaller z is nearer
		// the viewer: that is the order the spike's yaw-0 dumps showed as every model's front (docs/probe/portrait.md).
		Portrait.Mesh m = new Portrait.Mesh(
			new float[]{-50, 50, 50, -50, -50, 50, 50, -50}, new float[]{-100, -100, 0, 0, -100, -100, 0, 0},
			new float[]{100, 100, 100, 100, -100, -100, -100, -100},
			new int[]{0, 0, 4, 4}, new int[]{1, 2, 5, 6}, new int[]{2, 3, 6, 7},
			new int[]{RED, RED, BLUE, BLUE}, new int[]{-1, -1, -1, -1}, null, 4, true);
		int c = Portrait.render(m, 32).getRGB(16, 16);
		assertTrue("blue, the nearer, on top: " + Integer.toHexString(c), (c & 255) > 150 && (c >> 16 & 255) < 80);
	}

	@Test
	public void hiddenFacesDrawNothing()
	{
		assertNull(Portrait.render(square(RED, -2, true), 32));
	}

	@Test
	public void aTexturedFaceIsGreyAtItsLightLevel()
	{
		Portrait.Mesh m = new Portrait.Mesh(new float[]{-50, 50, 50, -50}, new float[]{-100, -100, 0, 0},
			new float[4], new int[]{0, 0}, new int[]{1, 2}, new int[]{2, 3}, new int[]{RED, RED},
			new int[]{-1, -1}, new short[]{5, 5}, 2, true);
		int c = Portrait.render(m, 32).getRGB(16, 16);
		assertEquals("grey: " + Integer.toHexString(c), c >> 16 & 255, c & 255);
	}

	@Test
	public void lightnessRunsBlackToWhite()
	{
		assertEquals(0, Portrait.hslToRgb(0));
		int white = Portrait.hslToRgb(127);
		assertTrue(Integer.toHexString(white), (white >> 16 & 255) > 240 && (white & 255) > 240);
	}
}
