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

	/**
	 * Operator, in game 2026-10-10: a giant rat, a frog and a spider (no chathead) came out as polygon blobs, because
	 * the top of a creature on all fours is its back, not its head. A body longer than it is tall is drawn from the
	 * side and cropped at its front end, where its head is (the front is the smaller-z end: see the test below).
	 *
	 * <p>The rat here lies along z, 400 long and 40 tall, as one flat panel in the y-z plane: red from z -200 to -60
	 * (its head), blue behind. From the front the panel has no width at all, so only a side view can show it.
	 */
	@Test
	public void aCreatureOnAllFoursShowsItsHeadFromTheSide()
	{
		Portrait.Mesh rat = new Portrait.Mesh(new float[8],
			new float[]{-40, -40, 0, 0, -40, -40, 0, 0},
			new float[]{-200, -60, -60, -200, -60, 200, 200, -60},
			new int[]{0, 0, 4, 4}, new int[]{1, 2, 5, 6}, new int[]{2, 3, 6, 7},
			new int[]{RED, RED, BLUE, BLUE}, new int[]{-1, -1, -1, -1}, null, 4, false);
		BufferedImage img = Portrait.render(rat, 60);
		assertNotNull(img);
		int red = 0;
		for (int y = 0; y < 60; y++)
		{
			for (int x = 0; x < 60; x++)
			{
				int c = img.getRGB(x, y);
				if ((c >>> 24) < 128)
				{
					continue;
				}
				assertTrue("only the head, no body, at " + x + "," + y + ": " + Integer.toHexString(c), (c & 255) < 120);
				red++;
			}
		}
		assertTrue("the head fills a good part of the square: " + red + " pixels", red > 600);
	}

	/**
	 * Addendum 20 (operator, in game): the side view fixed the rat and the frog but not the giant spider, whose legs
	 * spread it wider than it is long, so from the side it was a sliver. Low and wider than long is seen from above,
	 * whole. Width x, height y, length z.
	 */
	@Test
	public void theViewFollowsTheBodysShape()
	{
		assertEquals(Portrait.View.CHATHEAD, Portrait.View.of(true, 50, 10, 400));
		assertEquals("a human: tall", Portrait.View.UPRIGHT, Portrait.View.of(false, 60, 200, 40));
		assertEquals("as tall as long: upright", Portrait.View.UPRIGHT, Portrait.View.of(false, 60, 100, 100));
		assertEquals("a rat: low and long", Portrait.View.SIDE, Portrait.View.of(false, 60, 40, 400));
		assertEquals("as long as wide: side", Portrait.View.SIDE, Portrait.View.of(false, 120, 40, 120));
		assertEquals("a spider: low and wide", Portrait.View.TOP, Portrait.View.of(false, 300, 40, 160));
	}

	@Test
	public void aCreatureWiderThanItIsLongIsSeenWholeFromAbove()
	{
		// A spider lying flat in the x-z plane (no height at all, so no front or side view can show it): a red body,
		// x and z from -30 to 30, and blue legs reaching out to x -150 and 150. From above it is drawn whole.
		Portrait.Mesh spider = new Portrait.Mesh(
			new float[]{-30, 30, 30, -30, -150, -30, -30, -150, 30, 150, 150, 30},
			new float[12],
			new float[]{-30, -30, 30, 30, -10, -10, 10, 10, -10, -10, 10, 10},
			new int[]{0, 0, 4, 4, 8, 8}, new int[]{1, 2, 5, 6, 9, 10}, new int[]{2, 3, 6, 7, 10, 11},
			new int[]{RED, RED, BLUE, BLUE, BLUE, BLUE}, new int[]{-1, -1, -1, -1, -1, -1}, null, 6, false);
		assertEquals("precondition", Portrait.View.TOP, Portrait.View.of(false, 300, 0, 60));
		BufferedImage img = Portrait.render(spider, 60);
		assertNotNull(img);
		int centre = img.getRGB(30, 30);
		assertTrue("the body in the middle: " + Integer.toHexString(centre), (centre >>> 24) == 255 && (centre >> 16 & 255) > 150);
		int left = img.getRGB(4, 30), right = img.getRGB(55, 30);
		assertTrue("a leg reaches the left edge: " + Integer.toHexString(left), (left >>> 24) == 255 && (left & 255) > 150);
		assertTrue("and the right: " + Integer.toHexString(right), (right >>> 24) == 255 && (right & 255) > 150);
	}

	@Test
	public void anUprightBodyStillShowsItsTopFromTheFront()
	{
		// The rat's rule must not catch a body that stands: the 400-tall body of the crop test above, but given depth
		// (z from -50 to 50), is still taller than it is long, so it keeps the front view and the top crop.
		Portrait.Mesh body = new Portrait.Mesh(new float[]{-50, 50, 50, -50, 50, -50},
			new float[]{-400, -400, -200, -200, 0, 0}, new float[]{-50, -50, 50, 50, 50, 50},
			new int[]{0, 0, 3, 3}, new int[]{1, 2, 2, 4}, new int[]{2, 3, 4, 5},
			new int[]{RED, RED, BLUE, BLUE}, new int[]{-1, -1, -1, -1}, null, 4, false);
		BufferedImage img = Portrait.render(body, 60);
		assertNotNull(img);
		assertEquals("red reaches the bottom edge, as the top crop draws it", 255, img.getRGB(30, 59) >>> 24);
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
