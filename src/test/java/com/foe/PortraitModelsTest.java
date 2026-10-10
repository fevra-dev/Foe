package com.foe;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.NPCComposition;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The client glue, against fakes that record what is called on the merged model. Review finding 2: the model data
 * {@code loadModelData} returns "shares data such as ... face colors ... with other models. If you want to mutate
 * these you MUST call the relevant cloneX method" (API Javadoc), so a recolour must come after {@code cloneColors}.
 */
public class PortraitModelsTest
{
	private final List<String> mergedCalls = new ArrayList<>();
	private final Map<String, Object> comp = new HashMap<>();
	private boolean modelLoaded = true;

	private static <T> T fake(Class<T> type, Map<String, Object> values)
	{
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) ->
		{
			if (!values.containsKey(m.getName()))
			{
				throw new UnsupportedOperationException(type.getSimpleName() + "." + m.getName());
			}
			return values.get(m.getName());
		}));
	}

	private Model lit()
	{
		Map<String, Object> v = new HashMap<>();
		v.put("getVerticesX", new float[]{0, 1, 0});
		v.put("getVerticesY", new float[]{0, 0, 1});
		v.put("getVerticesZ", new float[3]);
		v.put("getFaceIndices1", new int[]{0});
		v.put("getFaceIndices2", new int[]{1});
		v.put("getFaceIndices3", new int[]{2});
		v.put("getFaceColors1", new int[]{64});
		v.put("getFaceColors3", new int[]{-1});
		v.put("getFaceTextures", null);
		v.put("getFaceCount", 1);
		return fake(Model.class, v);
	}

	/** The merged model: records each call, and every mutator returns itself. */
	private ModelData merged()
	{
		Model lit = lit();
		return (ModelData) Proxy.newProxyInstance(ModelData.class.getClassLoader(), new Class<?>[]{ModelData.class},
			(p, m, a) ->
			{
				mergedCalls.add(m.getName());
				switch (m.getName())
				{
					case "cloneColors":
					case "recolor":
						return p;
					case "light":
						return lit;
					default:
						throw new UnsupportedOperationException("ModelData." + m.getName());
				}
			});
	}

	private Client client()
	{
		ModelData part = fake(ModelData.class, new HashMap<>());
		ModelData merged = merged();
		return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(p, m, a) ->
			{
				switch (m.getName())
				{
					case "loadModelData":
						return modelLoaded ? part : null;
					case "mergeModels":
						return merged;
					default:
						throw new UnsupportedOperationException("Client." + m.getName());
				}
			});
	}

	private NPCComposition composition(int[] chatheads, int[] body)
	{
		comp.put("getChatheadModels", chatheads);
		comp.put("getModels", body);
		comp.put("getColorToReplace", new short[]{10, 20});
		comp.put("getColorToReplaceWith", new short[]{11, 21});
		return fake(NPCComposition.class, comp);
	}

	@Test
	public void coloursAreClonedBeforeTheRecolourTouchesThem()
	{
		Portrait.Mesh m = PortraitModels.load(client(), composition(new int[]{5}, new int[]{6, 7}));
		assertNotNull(m);
		assertEquals(Arrays.asList("cloneColors", "recolor", "recolor", "light"), mergedCalls);
	}

	@Test
	public void aChatheadIsAHeadAndABodyIsNot()
	{
		assertTrue(PortraitModels.load(client(), composition(new int[]{5}, new int[]{6})).head);
		assertFalse(PortraitModels.load(client(), composition(new int[0], new int[]{6})).head);
	}

	@Test
	public void aModelStillLoadingIsNull()
	{
		modelLoaded = false;
		assertNull(PortraitModels.load(client(), composition(new int[]{5}, new int[]{6})));
	}
}
