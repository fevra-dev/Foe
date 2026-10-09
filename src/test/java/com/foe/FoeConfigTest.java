package com.foe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.client.config.ConfigItem;
import org.junit.Test;

/** Spec addendum 3: eight settings, in this order, with these keys and defaults. */
public class FoeConfigTest
{
	private static List<Method> items()
	{
		List<Method> out = new ArrayList<>();
		for (Method m : FoeConfig.class.getDeclaredMethods())
		{
			if (m.getAnnotation(ConfigItem.class) != null)
			{
				out.add(m);
			}
		}
		out.sort((a, b) -> Integer.compare(a.getAnnotation(ConfigItem.class).position(),
			b.getAnnotation(ConfigItem.class).position()));
		return out;
	}

	@Test
	public void eightSettingsAtPositionsZeroToSevenInTheSpecOrder()
	{
		List<String> keys = new ArrayList<>();
		List<Integer> positions = new ArrayList<>();
		for (Method m : items())
		{
			keys.add(m.getAnnotation(ConfigItem.class).keyName());
			positions.add(m.getAnnotation(ConfigItem.class).position());
		}
		assertEquals(Arrays.asList("layout", "hpText", "hpTextPosition", "detail", "staleHpStyle", "showWeakness",
			"lingerSeconds", "backgroundOpacity"), keys);
		assertEquals(Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7), positions);
		assertEquals("keyNames are unique", keys.size(), new HashSet<>(keys).size());
	}

	// Spec addendum 5: what was learned is saved under one key of the foe group that is not a setting. A declared item
	// would show in the panel (or, hidden, be wiped by its Reset button, which unsets every declared key).
	@Test
	public void theLearnedWeaknessesKeyIsNotASetting()
	{
		for (Method m : items())
		{
			assertNotEquals(WeaknessStore.KEY, m.getAnnotation(ConfigItem.class).keyName());
			assertNotEquals(WeaknessStore.KEY, m.getName());
		}
		assertEquals(FoeConfig.GROUP, "foe");
	}

	@Test
	public void retiredSettingsAreGoneAndTheirKeysAreNotReused()
	{
		Set<String> methods = new HashSet<>();
		Set<String> keys = new HashSet<>();
		for (Method m : items())
		{
			methods.add(m.getName());
			keys.add(m.getAnnotation(ConfigItem.class).keyName());
		}
		for (String retired : new String[] {"hpDisplay", "showCombatLevel", "hideIrrelevantLevels"})
		{
			assertFalse("method " + retired, methods.contains(retired));
			assertFalse("key " + retired + ": an old stored value must never be read as a new type", keys.contains(retired));
		}
		for (Class<?> c : FoeConfig.class.getDeclaredClasses())
		{
			assertNotEquals("HpDisplay", c.getSimpleName());
		}
	}

	@Test
	public void defaultsAreTheFirstConstantOfEachEnum()
	{
		FoeConfig d = new FoeConfig()
		{
		};
		assertEquals("Stacked since 2026-10-08 (spec addendum 4)", FoeConfig.Layout.STACKED, d.layout());
		assertEquals(FoeConfig.HpText.CURRENT_MAX, d.hpText());
		assertEquals(FoeConfig.HpTextPosition.BESIDE, d.hpTextPosition());
		assertEquals(FoeConfig.Detail.FULL, d.detail());
		assertEquals(FoeConfig.StaleHpStyle.FADED, d.staleHpStyle());
		assertTrue(d.showWeakness());
		assertEquals(10, d.lingerSeconds());
		assertEquals(61, d.backgroundOpacity());
		for (Class<?> e : new Class<?>[] {FoeConfig.Layout.class, FoeConfig.HpText.class,
			FoeConfig.HpTextPosition.class, FoeConfig.Detail.class, FoeConfig.StaleHpStyle.class})
		{
			Object first = e.getEnumConstants()[0];
			boolean isDefault = false;
			for (Method m : items())
			{
				if (m.getReturnType() == e)
				{
					try
					{
						isDefault = m.invoke(d) == first;
					}
					catch (ReflectiveOperationException x)
					{
						throw new AssertionError(x);
					}
				}
			}
			assertTrue(e.getSimpleName() + " default is its first constant, so the panel lists it first", isDefault);
		}
	}

	// Operator decision 2026-10-08: the Marker style (a "?") is gone. A profile that stored MARKER holds a name that no
	// longer parses: Enum.valueOf throws IllegalArgumentException (ConfigManager.stringToObject, client 1.13.1), which
	// ConfigInvocationHandler catches, logs, and answers with the interface default (Faded). Retired for good, like the
	// retired keys above: the constant name must not come back meaning something else.
	@Test
	public void theStaleStylesAreFadedAndHollowAndMarkerIsRetired()
	{
		List<String> names = new ArrayList<>();
		for (FoeConfig.StaleHpStyle style : FoeConfig.StaleHpStyle.values())
		{
			names.add(style.name());
		}
		assertEquals(Arrays.asList("FADED", "HOLLOW"), names);
		try
		{
			FoeConfig.StaleHpStyle.valueOf("MARKER");
			throw new AssertionError("a stored MARKER must not parse");
		}
		catch (IllegalArgumentException expected)
		{
			// that is what makes the profile fall back to the default
		}
		assertEquals(FoeConfig.StaleHpStyle.FADED, new FoeConfig()
		{
		}.staleHpStyle());
	}

	@Test
	public void theSettingsPanelListsStackedFirstBecauseItIsTheDefault()
	{
		assertEquals(FoeConfig.Layout.STACKED, FoeConfig.Layout.values()[0]);
		assertEquals(FoeConfig.Layout.ONE_LINE, FoeConfig.Layout.values()[1]);
		assertEquals("Stacked", FoeConfig.Layout.STACKED.toString());
	}

	@Test
	public void theSettingsPanelShowsReadableLabelsButStoresTheConstantName()
	{
		assertEquals("Current/max", FoeConfig.HpText.CURRENT_MAX.toString());
		assertEquals("Inside bar", FoeConfig.HpTextPosition.INSIDE.toString());
		assertEquals("One line", FoeConfig.Layout.ONE_LINE.toString());
		// what ConfigPanel stores is name(), which is what the JSON profile and valueOf() round-trip
		assertEquals("CURRENT_MAX", FoeConfig.HpText.CURRENT_MAX.name());
		assertEquals(FoeConfig.HpText.CURRENT_MAX, FoeConfig.HpText.valueOf(FoeConfig.HpText.CURRENT_MAX.name()));
	}
}
