package com.foe;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

/**
 * Eight settings (spec addendum 3), defaults first in every enum so the settings panel lists the default first.
 *
 * <p>Storage is by {@code Enum.name()} under {@code keyName} (ConfigPanel.createComboBox writes
 * {@code ((Enum) selected).name()}), so the {@code toString} labels below only change what the combo box shows,
 * never what is stored. A stored value that no longer parses makes {@code ConfigInvocationHandler} log a warning and
 * return the interface default, which is why a retired key is retired for good: {@code hpDisplay} (an enum with
 * other constants), {@code showCombatLevel} and {@code hideIrrelevantLevels} are gone and none of the three names
 * may come back with a different meaning. Their old profile entries are simply never read.
 *
 * <p>The same holds for a retired constant: {@code staleHpStyle} = {@code MARKER} (retired 2026-10-08) no longer
 * parses, so a profile that stored it reads as the default, Faded. The handler logs a warning each time it reads such
 * a value (it does not cache the failure), until the setting is changed once.
 */
@ConfigGroup(FoeConfig.GROUP)
public interface FoeConfig extends Config
{
	String GROUP = "foe";

	enum Layout
	{
		STACKED("Stacked"), ONE_LINE("One line");

		private final String label;

		Layout(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	enum HpText
	{
		CURRENT_MAX("Current/max"), CURRENT("Current"), PERCENT("Percent"), NONE("None");

		private final String label;

		HpText(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	enum HpTextPosition
	{
		BESIDE("Beside bar"), INSIDE("Inside bar");

		private final String label;

		HpTextPosition(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	enum Detail
	{
		FULL("Full"), COMPACT("Compact");

		private final String label;

		Detail(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	/** How HP is drawn when it is the last known value because the live bar is missing (spec addendum 2). */
	enum StaleHpStyle
	{
		FADED("Faded"), HOLLOW("Hollow");

		private final String label;

		StaleHpStyle(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	@ConfigItem(keyName = "layout", name = "Layout", position = 0,
		description = "Stacked: the bar goes under the name. One line: everything on one strip.")
	default Layout layout()
	{
		return Layout.STACKED;
	}

	@ConfigItem(keyName = "hpText", name = "HP text", position = 1,
		description = "The number shown with the HP bar: current and max, current only, a percentage, or none. "
			+ "Nothing is shown when the monster's max HP is not known.")
	default HpText hpText()
	{
		return HpText.CURRENT_MAX;
	}

	@ConfigItem(keyName = "hpTextPosition", name = "HP text position", position = 2,
		description = "Beside the bar, or centred on it. The bar widens when the text would not fit inside it.")
	default HpTextPosition hpTextPosition()
	{
		return HpTextPosition.BESIDE;
	}

	@ConfigItem(keyName = "detail", name = "Detail", position = 3,
		description = "Full: also shows the monster's Attack, Strength, Defence, Ranged and Magic levels. "
			+ "Compact: name, HP and weakness only. The combat level next to the name is always shown.")
	default Detail detail()
	{
		return Detail.FULL;
	}

	@ConfigItem(keyName = "staleHpStyle", name = "Stale HP style", position = 4,
		description = "How HP looks when the health bar has gone and the last known value is shown. "
			+ "Faded: bar and text at half opacity. Hollow: bar outline only.")
	default StaleHpStyle staleHpStyle()
	{
		return StaleHpStyle.FADED;
	}

	@ConfigItem(keyName = "showWeakness", name = "Show weakness", position = 5,
		description = "Show the elemental weakness, when the game reports one.")
	default boolean showWeakness()
	{
		return true;
	}

	// min = 0 is written out on purpose: 0 is a real choice (spec: "0-60 s"), not an inherited default.
	// @Range is only enforced by the settings spinner (the sole consumer is ConfigPanel.createIntSpinner in
	// client 1.13.1; ConfigInvocationHandler does not clamp), so a hand-edited profile can still hold any int.
	// Whoever reads this value must clamp it themselves.
	@Range(min = 0, max = 60)
	@Units(Units.SECONDS)
	@ConfigItem(keyName = "lingerSeconds", name = "Linger after combat", position = 6,
		description = "How long the panel stays after the last sign of combat. It always clears when the "
			+ "monster dies. Very short values can flicker between hits.")
	default int lingerSeconds()
	{
		return 10;
	}

	// 61% is RuneLite's standard overlay background: ComponentConstants.STANDARD_BACKGROUND_COLOR has
	// alpha 156, and Math.round(61 * 255 / 100f) == 156. Same caveat as above: not enforced outside the
	// spinner, and a Color with alpha above 255 throws, so clamp to 0-100 before converting.
	@Range(min = 0, max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(keyName = "backgroundOpacity", name = "Background opacity", position = 7,
		description = "0% draws the text alone.")
	default int backgroundOpacity()
	{
		return 61;
	}
}
