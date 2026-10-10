package com.foe;

import java.util.Arrays;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.NPCComposition;

/** Spec addendum 14: a composition's models as a {@link Portrait.Mesh}. Client thread only. */
final class PortraitModels
{
	private PortraitModels()
	{
	}

	/** Its chathead models, else its body models; merged, recoloured, lit and copied. Null when it has none. */
	static Portrait.Mesh load(Client client, NPCComposition c)
	{
		int[] ids = c.getChatheadModels();
		boolean head = ids != null && ids.length > 0;
		if (!head)
		{
			ids = c.getModels();
		}
		if (ids == null || ids.length == 0)
		{
			return null;
		}
		ModelData[] parts = new ModelData[ids.length];
		for (int i = 0; i < ids.length; i++)
		{
			parts[i] = client.loadModelData(ids[i]);
			if (parts[i] == null)
			{
				return null;
			}
		}
		ModelData md = client.mergeModels(parts);
		short[] from = c.getColorToReplace();
		short[] to = c.getColorToReplaceWith();
		if (from != null && to != null)
		{
			for (int i = 0; i < Math.min(from.length, to.length); i++)
			{
				md = md.recolor(from[i], to[i]);
			}
		}
		Model m = md.light();
		// Copied: the render reads them on another thread.
		return new Portrait.Mesh(copy(m.getVerticesX()), copy(m.getVerticesY()), copy(m.getVerticesZ()),
			copy(m.getFaceIndices1()), copy(m.getFaceIndices2()), copy(m.getFaceIndices3()),
			copy(m.getFaceColors1()), copy(m.getFaceColors3()),
			m.getFaceTextures() == null ? null : Arrays.copyOf(m.getFaceTextures(), m.getFaceTextures().length),
			m.getFaceCount(), head);
	}

	private static float[] copy(float[] a)
	{
		return a == null ? null : Arrays.copyOf(a, a.length);
	}

	private static int[] copy(int[] a)
	{
		return a == null ? null : Arrays.copyOf(a, a.length);
	}
}
