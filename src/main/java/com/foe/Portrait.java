package com.foe;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.Arrays;

/**
 * The portrait renderer (spec addendum 14): a flat software render of a lit model into a square image, with a
 * transparent background. Painter's algorithm (faces sorted far to near, no z-buffer), orthographic, straight on,
 * except a body on all fours, which is seen from the side (addendum 19).
 * A textured face draws grey at its light level. Pure: it takes the model's arrays, never the client, so it is tested
 * without one and can run on any thread.
 */
final class Portrait
{
	/**
	 * A body model is cropped to a square over this share of its height, from the top: the head. A body longer than
	 * it is tall is on all fours, and its head is at its front instead: seen from the side, it is cropped to a square
	 * over this share of its length, from the front (addendum 19).
	 */
	static final double HEAD = 0.3;
	private static final int SUPERSAMPLE = 4;
	/** The client's default brightness [assumed: the middle of its 0.6 to 0.9 brightness steps]. */
	private static final double GAMMA = 0.8;

	/** A lit model's arrays, copied off the client. {@code head}: a chathead, drawn whole; else a body, cropped. */
	static final class Mesh
	{
		final float[] x, y, z;
		final int[] a, b, c;
		/** The client's 16-bit HSL per face (for a textured face, its light level). */
		final int[] colours1;
		/** -2 marks a hidden face; may be null. */
		final int[] colours3;
		/** -1 for an untextured face; null when the model has no textures. */
		final short[] textures;
		final int faces;
		final boolean head;

		Mesh(float[] x, float[] y, float[] z, int[] a, int[] b, int[] c, int[] colours1, int[] colours3,
			short[] textures, int faces, boolean head)
		{
			this.x = x;
			this.y = y;
			this.z = z;
			this.a = a;
			this.b = b;
			this.c = c;
			this.colours1 = colours1;
			this.colours3 = colours3;
			this.textures = textures;
			this.faces = faces;
			this.head = head;
		}
	}

	private Portrait()
	{
	}

	/** The portrait, {@code size} pixels square, or null when no face is drawable. */
	static BufferedImage render(Mesh m, int size)
	{
		Integer[] order = new Integer[m.faces];
		int drawable = 0;
		double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
		double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		for (int f = 0; f < m.faces; f++)
		{
			if (m.colours3 != null && m.colours3[f] == -2)
			{
				continue;
			}
			order[drawable++] = f;
			for (int v : new int[]{m.a[f], m.b[f], m.c[f]})
			{
				minX = Math.min(minX, m.x[v]);
				maxX = Math.max(maxX, m.x[v]);
				minY = Math.min(minY, m.y[v]);
				maxY = Math.max(maxY, m.y[v]);
				minZ = Math.min(minZ, m.z[v]);
				maxZ = Math.max(maxZ, m.z[v]);
			}
		}
		if (drawable == 0)
		{
			return null;
		}
		Integer[] faces = Arrays.copyOf(order, drawable);
		// On all fours (addendum 19): a body longer than it is tall is seen from the side, so the screen's horizontal
		// is the model's z and the depth is its x. Chatheads and upright bodies are seen from the front.
		boolean fours = !m.head && maxZ - minZ > maxY - minY;
		float[] across = fours ? m.z : m.x;
		float[] depth = fours ? m.x : m.z;
		if (fours)
		{
			minX = minZ;
			maxX = maxZ;
		}
		if (!m.head && fours)
		{
			// The front is the smaller-z end (the spike's yaw-0 dumps, docs/probe/portrait.md): crop a square over
			// the front share of the length, centred on the height of what is there.
			double side = (maxX - minX) * HEAD;
			double sum = 0;
			int count = 0;
			for (int f : faces)
			{
				for (int v : new int[]{m.a[f], m.b[f], m.c[f]})
				{
					if (across[v] <= minX + side)
					{
						sum += m.y[v];
						count++;
					}
				}
			}
			double centre = count == 0 ? (minY + maxY) / 2 : sum / count;
			maxX = minX + side;
			minY = centre - side / 2;
			maxY = centre + side / 2;
		}
		else if (!m.head)
		{
			double side = (maxY - minY) * HEAD;
			double sum = 0;
			int count = 0;
			for (int f : faces)
			{
				for (int v : new int[]{m.a[f], m.b[f], m.c[f]})
				{
					if (m.y[v] <= minY + side)
					{
						sum += across[v];
						count++;
					}
				}
			}
			double centre = count == 0 ? (minX + maxX) / 2 : sum / count;
			minX = centre - side / 2;
			maxX = centre + side / 2;
			maxY = minY + side;
		}
		// Far first: the spike's yaw-0 dumps showed every model's front with this order (docs/probe/portrait.md).
		// From the side the depth is x, and the order is the same rule along it.
		Arrays.sort(faces, (p, q) -> Double.compare(
			depth[m.a[q]] + depth[m.b[q]] + depth[m.c[q]],
			depth[m.a[p]] + depth[m.b[p]] + depth[m.c[p]]));

		int big = size * SUPERSAMPLE;
		double span = Math.max(Math.max(maxX - minX, maxY - minY), 1);
		double scale = (big - 2.0 * SUPERSAMPLE) / span;
		double offX = (big - (maxX - minX) * scale) / 2 - minX * scale;
		double offY = (big - (maxY - minY) * scale) / 2 - minY * scale;

		BufferedImage hi = new BufferedImage(big, big, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = hi.createGraphics();
		int[] xs = new int[3];
		int[] ys = new int[3];
		for (int f : faces)
		{
			int[] vs = {m.a[f], m.b[f], m.c[f]};
			for (int k = 0; k < 3; k++)
			{
				xs[k] = (int) Math.round(across[vs[k]] * scale + offX);
				ys[k] = (int) Math.round(m.y[vs[k]] * scale + offY); // the client's y points down: head at the top
			}
			boolean textured = m.textures != null && m.textures[f] != -1;
			g.setColor(new Color(textured ? grey(m.colours1[f]) : hslToRgb(m.colours1[f])));
			g.fillPolygon(xs, ys, 3);
		}
		g.dispose();

		BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D o = out.createGraphics();
		o.drawImage(hi.getScaledInstance(size, size, Image.SCALE_AREA_AVERAGING), 0, 0, null);
		o.dispose();
		return out;
	}

	/** A textured face's colour holds only its light level, 0 to 127. */
	static int grey(int light)
	{
		int v = gamma((light & 127) / 127.0);
		return v << 16 | v << 8 | v;
	}

	/** The client's 16-bit HSL (6 bits hue, 3 saturation, 7 lightness) to RGB. */
	static int hslToRgb(int hsl)
	{
		double h = ((hsl >> 10) & 63) / 64.0 + 0.0078125;
		double s = ((hsl >> 7) & 7) / 8.0 + 0.0625;
		double l = (hsl & 127) / 128.0;
		double q = l < 0.5 ? l * (1 + s) : l + s - l * s;
		double p = 2 * l - q;
		return gamma(hue(p, q, h + 1 / 3.0)) << 16 | gamma(hue(p, q, h)) << 8 | gamma(hue(p, q, h - 1 / 3.0));
	}

	private static double hue(double p, double q, double t)
	{
		if (t < 0)
		{
			t += 1;
		}
		if (t > 1)
		{
			t -= 1;
		}
		if (t < 1 / 6.0)
		{
			return p + (q - p) * 6 * t;
		}
		if (t < 1 / 2.0)
		{
			return q;
		}
		if (t < 2 / 3.0)
		{
			return p + (q - p) * (2 / 3.0 - t) * 6;
		}
		return p;
	}

	private static int gamma(double c)
	{
		return (int) Math.min(255, Math.round(Math.pow(Math.max(0, c), GAMMA) * 256));
	}
}
