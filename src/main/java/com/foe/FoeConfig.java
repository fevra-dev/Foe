package com.foe;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(FoeConfig.GROUP)
public interface FoeConfig extends Config
{
	String GROUP = "foe";

	enum Detail
	{
		COMPACT, FULL
	}

	enum HpDisplay
	{
		NUMBER, PERCENT, NUMBER_AND_PERCENT, BAR_ONLY
	}

	/** How HP is drawn when it is the last known value because the live bar is missing (spec addendum 2). */
	enum StaleHpStyle
	{
		FADED, HOLLOW, MARKER
	}

	@ConfigItem(keyName = "detail", name = "Detail", position = 0,
		description = "Compact: name, HP and weakness. Full: also combat levels.")
	default Detail detail()
	{
		return Detail.FULL;
	}

	@ConfigItem(keyName = "hpDisplay", name = "HP display", position = 1,
		description = "Text beside the HP bar: estimated HP, percentage, both, or none.")
	default HpDisplay hpDisplay()
	{
		return HpDisplay.NUMBER;
	}

	@ConfigItem(keyName = "hideIrrelevantLevels", name = "Hide irrelevant levels", position = 2,
		description = "Hide levels of 1 or less: a monster that never uses Ranged or Magic doesn't show them.")
	default boolean hideIrrelevantLevels()
	{
		return true;
	}

	@ConfigItem(keyName = "showCombatLevel", name = "Show combat level", position = 3,
		description = "Show the combat level next to the name.")
	default boolean showCombatLevel()
	{
		return true;
	}

	@ConfigItem(keyName = "showWeakness", name = "Show weakness", position = 4,
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
	@ConfigItem(keyName = "lingerSeconds", name = "Linger after combat", position = 5,
		description = "How long the panel stays after the last sign of combat. It always clears when the "
			+ "monster dies. Very short values can flicker between hits.")
	default int lingerSeconds()
	{
		return 10;
	}

	@ConfigItem(keyName = "staleHpStyle", name = "Stale HP style", position = 6,
		description = "How HP looks when the health bar has gone and the last known value is shown. "
			+ "Faded: bar and text at half opacity. Hollow: bar outline only. Marker: a ? after the HP text (or the bar).")
	default StaleHpStyle staleHpStyle()
	{
		return StaleHpStyle.FADED;
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
