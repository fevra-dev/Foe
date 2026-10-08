package com.foe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.runelite.client.ui.overlay.components.ComponentConstants;
import org.junit.Test;

/**
 * FoeOverlay's pure parts (text, bar predicate, widths, alpha) are asserted directly. Its pixels are asserted on a
 * BufferedImage, which proves the colour wiring (which colour the fill and the HP text are drawn in) and not how it
 * looks. Task 9 is the in-game screenshot.
 */
public class FoeOverlayTest
{
	// NPCComposition.getStats() order: ATTACK=0, DEFENCE=1, STRENGTH=2, HITPOINTS=3, RANGED=4, MAGIC=5
	private static final int[] ICE_GIANT = {40, 40, 40, 70, 1, 1};
	// Every slot distinct, so a swapped index or a swapped label shows up.
	private static final int[] DISTINCT = {11, 22, 33, 70, 44, 55};
	private static final Weakness FIRE = new Weakness(Weakness.Element.FIRE);

	/**
	 * FoeConfig with the interface's own defaults, each one overridable, except the layout: the geometry assertions
	 * in this class are written against the one-line strip, so that is spelled out here. The real default (Stacked)
	 * is pinned in FoeConfigTest, and the stacked layout is tested through {@link #stacked()}.
	 */
	private static final class Cfg implements FoeConfig
	{
		Layout layout = Layout.ONE_LINE;
		HpText hpText = FoeConfig.super.hpText();
		HpTextPosition hpTextPosition = FoeConfig.super.hpTextPosition();
		Detail detail = FoeConfig.super.detail();
		boolean showWeakness = FoeConfig.super.showWeakness();
		StaleHpStyle staleHpStyle = FoeConfig.super.staleHpStyle();
		int backgroundOpacity = FoeConfig.super.backgroundOpacity();

		@Override
		public Layout layout()
		{
			return layout;
		}

		@Override
		public HpText hpText()
		{
			return hpText;
		}

		@Override
		public HpTextPosition hpTextPosition()
		{
			return hpTextPosition;
		}

		@Override
		public Detail detail()
		{
			return detail;
		}

		@Override
		public boolean showWeakness()
		{
			return showWeakness;
		}

		@Override
		public StaleHpStyle staleHpStyle()
		{
			return staleHpStyle;
		}

		@Override
		public int backgroundOpacity()
		{
			return backgroundOpacity;
		}
	}

	private static TargetSnapshot snap(int[] stats, int ratio, int scale, boolean stale, Weakness w)
	{
		return SnapshotFactory.build("Ice giant", 53, stats, ratio, scale, stale, null, w);
	}

	/**
	 * A snapshot exactly as the old factory built it, for the tests of the overlay's own predicates on inputs the
	 * factory now flags: (-1, 0) with a known max HP is the unhit state of addendum 4, never a plain "no bar".
	 */
	private static TargetSnapshot raw(int[] stats, int ratio, int scale, boolean stale)
	{
		return new TargetSnapshot("Ice giant", 53, HpEstimate.estimate(ratio, scale, stats[3]), stats[3], ratio, scale,
			stale, false, stats[0], stats[2], stats[1], stats[4], stats[5], null);
	}

	/** The spec's worked example: ratio 22 of 30 on 70 max HP is 52 HP, 74%. */
	private static TargetSnapshot live()
	{
		return snap(ICE_GIANT, 22, 30, false, FIRE);
	}

	private static TargetSnapshot stale()
	{
		return snap(ICE_GIANT, 22, 30, true, FIRE);
	}

	private static List<String> texts(TargetSnapshot s, FoeConfig c)
	{
		List<String> out = new ArrayList<>();
		for (FoeOverlay.Cell cell : FoeOverlay.cells(s, c))
		{
			if (!cell.text.isEmpty())
			{
				out.add(cell.text);
			}
		}
		return out;
	}

	// ---- the bar predicate (plan Task 7 item 8) ----

	@Test
	public void barIsDrawnWhenRatioIsWithinScale()
	{
		assertTrue(FoeOverlay.hasBar(snap(ICE_GIANT, 22, 30, false, null)));
		assertTrue("ratio 0 is a dead monster's empty bar, still a bar", FoeOverlay.hasBar(snap(ICE_GIANT, 0, 30, false, null)));
		assertTrue("ratio == scale is a full bar", FoeOverlay.hasBar(snap(ICE_GIANT, 30, 30, false, null)));
	}

	@Test
	public void noBarWhenTheClientHasNoRatioOrScale()
	{
		assertFalse(FoeOverlay.hasBar(raw(ICE_GIANT, -1, 0, false)));
		assertFalse(FoeOverlay.hasBar(raw(ICE_GIANT, -1, 30, false)));
		assertFalse("scale 0 means no bar however large the ratio", FoeOverlay.hasBar(raw(ICE_GIANT, 5, 0, false)));
		assertFalse("ratio 0 of scale 0 is no bar, not an empty one", FoeOverlay.hasBar(raw(ICE_GIANT, 0, 0, false)));
	}

	@Test
	public void noBarWhenRatioExceedsScale()
	{
		assertFalse(FoeOverlay.hasBar(snap(ICE_GIANT, 31, 30, false, null)));
	}

	@Test
	public void barFillIsProportionalAndNeverExceedsTheBar()
	{
		assertEquals(40, FoeOverlay.barFill(snap(ICE_GIANT, 15, 30, false, null), FoeOverlay.BAR_W));
		assertEquals(FoeOverlay.BAR_W, FoeOverlay.barFill(snap(ICE_GIANT, 30, 30, false, null), FoeOverlay.BAR_W));
		assertEquals(0, FoeOverlay.barFill(snap(ICE_GIANT, 0, 30, false, null), FoeOverlay.BAR_W));
		assertEquals("a living monster never shows an empty bar", 1,
			FoeOverlay.barFill(snap(ICE_GIANT, 1, 255, false, null), FoeOverlay.BAR_W));
		assertEquals("the fill scales with a widened bar", 75, FoeOverlay.barFill(snap(ICE_GIANT, 15, 30, false, null), 150));
		assertEquals("and the extremes clamp to the widened bar", 149,
			FoeOverlay.barFill(snap(ICE_GIANT, 254, 255, false, null), 150));
	}

	@Test
	public void barFillIsZeroWhenThereIsNoBar()
	{
		assertEquals("ratio > scale: unclamped it would be 83", 0,
			FoeOverlay.barFill(snap(ICE_GIANT, 31, 30, false, null), FoeOverlay.BAR_W));
		assertEquals("no scale: the division would be by zero", 0,
			FoeOverlay.barFill(raw(ICE_GIANT, -1, 0, false), FoeOverlay.BAR_W));
	}

	// ---- HP text (requirement 2) ----

	@Test
	public void hpTextFollowsTheHpTextSetting()
	{
		Cfg c = new Cfg();
		c.hpText = FoeConfig.HpText.CURRENT_MAX;
		assertEquals("52/70", FoeOverlay.hpText(live(), c));
		c.hpText = FoeConfig.HpText.CURRENT;
		assertEquals("52", FoeOverlay.hpText(live(), c));
		c.hpText = FoeConfig.HpText.PERCENT;
		assertEquals("74%", FoeOverlay.hpText(live(), c));
		c.hpText = FoeConfig.HpText.NONE;
		assertEquals("", FoeOverlay.hpText(live(), c));
	}

	@Test
	public void hpTextNeverCarriesATildeUnderAnySettingOrStyle()
	{
		// Addendum 3: no "~". The bar already says the value is approximate.
		for (FoeConfig.HpText t : FoeConfig.HpText.values())
		{
			for (FoeConfig.StaleHpStyle style : FoeConfig.StaleHpStyle.values())
			{
				Cfg c = new Cfg();
				c.hpText = t;
				c.staleHpStyle = style;
				assertFalse(t + "/" + style, FoeOverlay.hpText(live(), c).contains("~"));
				assertFalse(t + "/" + style, FoeOverlay.hpText(stale(), c).contains("~"));
			}
		}
	}

	@Test
	public void percentRoundsToTheNearestWholeNumber()
	{
		// ratio 23 of 30 on 70 max HP estimates 55 HP: 78.57%, which rounds to 79 where truncating gives 78
		TargetSnapshot s = snap(ICE_GIANT, 23, 30, false, null);
		assertEquals(55, s.getHp());
		Cfg c = new Cfg();
		c.hpText = FoeConfig.HpText.PERCENT;
		assertEquals("79%", FoeOverlay.hpText(s, c));
	}

	@Test
	public void deadMonsterShowsZeroHp()
	{
		Cfg c = new Cfg();
		TargetSnapshot dead = snap(ICE_GIANT, 0, 30, false, null);
		assertEquals("0/70", FoeOverlay.hpText(dead, c));
		c.hpText = FoeConfig.HpText.CURRENT;
		assertEquals("0", FoeOverlay.hpText(dead, c));
		c.hpText = FoeConfig.HpText.PERCENT;
		assertEquals("0%", FoeOverlay.hpText(dead, c));
	}

	@Test
	public void unknownMaxHpShowsNoTextUnderAnySettingButStillDrawsTheBar()
	{
		int[] noHitpoints = {40, 40, 40, 0, 1, 1};
		TargetSnapshot s = snap(noHitpoints, 22, 30, false, null);
		assertTrue(FoeOverlay.hasBar(s));
		Cfg c = new Cfg();
		for (FoeConfig.HpText t : FoeConfig.HpText.values())
		{
			c.hpText = t;
			assertEquals("setting " + t, "", FoeOverlay.hpText(s, c));
		}
		c.hpText = FoeConfig.HpText.CURRENT_MAX;
		FoeOverlay.Cell hpCell = FoeOverlay.cells(s, c).get(1);
		assertTrue("the bar survives without a max HP", hpCell.hp);
		assertEquals("", hpCell.text);
	}

	@Test
	public void ratioAboveScaleShowsNeitherBarNorText()
	{
		TargetSnapshot s = snap(ICE_GIANT, 31, 30, false, null);
		Cfg c = new Cfg();
		assertFalse(FoeOverlay.hasBar(s));
		assertEquals("", FoeOverlay.hpText(s, c));
		for (FoeOverlay.Cell cell : FoeOverlay.cells(s, c))
		{
			assertFalse("no HP cell at all", cell.hp);
		}
	}

	@Test
	public void textAgreesWithTheBarEvenOnAnInconsistentSnapshot()
	{
		// The factory never builds this (it returns UNKNOWN hp for ratio > scale), but the overlay must not
		// depend on that: a number beside a missing bar is exactly the disagreement plan Task 7 item 8 forbids.
		TargetSnapshot s = new TargetSnapshot("Ice giant", 53, 52, 70, 31, 30, false, false, 40, 40, 40, 1, 1, null);
		assertEquals("", FoeOverlay.hpText(s, new Cfg()));
		for (FoeOverlay.Cell cell : FoeOverlay.cells(s, new Cfg()))
		{
			assertFalse("no HP cell for an inconsistent snapshot", cell.hp);
		}
	}

	// ---- stale HP (requirement 3) ----

	@Test
	public void fadedAndHollowLeaveTheHpTextUntouched()
	{
		Cfg c = new Cfg();
		c.staleHpStyle = FoeConfig.StaleHpStyle.FADED;
		assertEquals("52/70", FoeOverlay.hpText(stale(), c));
		c.staleHpStyle = FoeConfig.StaleHpStyle.HOLLOW;
		assertEquals("52/70", FoeOverlay.hpText(stale(), c));
	}

	@Test
	public void staleHpTextWithNoTextSettingStaysBareAndNothingIsAppendedToTheText()
	{
		// The Marker style (a "?") is gone: with HP text None a stale bar has no text, and with text it has the
		// bare number, in every style that is left.
		for (FoeConfig.StaleHpStyle style : FoeConfig.StaleHpStyle.values())
		{
			Cfg c = new Cfg();
			c.staleHpStyle = style;
			c.hpText = FoeConfig.HpText.NONE;
			FoeOverlay.Cell hpCell = FoeOverlay.cells(stale(), c).get(1);
			assertTrue(hpCell.hp);
			assertEquals(style.name(), "", hpCell.text);
			c.hpText = FoeConfig.HpText.CURRENT_MAX;
			assertEquals(style.name(), "52/70", FoeOverlay.cells(stale(), c).get(1).text);
		}
	}

	// ---- the segments (name, HP, levels, weakness) ----

	@Test
	public void fullDetailShowsEverythingInOrder()
	{
		assertEquals(Arrays.asList("Ice giant  53", "52/70", "Att 40  Str 40  Def 40", "Fire"), texts(live(), new Cfg()));
	}

	@Test
	public void compactDropsTheLevelsOnlyAndKeepsTheCombatLevelBesideTheName()
	{
		Cfg c = new Cfg();
		c.detail = FoeConfig.Detail.COMPACT;
		assertEquals(Arrays.asList("Ice giant  53", "52/70", "Fire"), texts(live(), c));
	}

	@Test
	public void levelsAreLabelledAndOrderedAttStrDefRngMag()
	{
		assertEquals("Att 11  Str 33  Def 22  Rng 44  Mag 55",
			texts(snap(DISTINCT, 22, 30, false, null), new Cfg()).get(2));
	}

	@Test
	public void levelsOfOneOrLessAreNeverDrawn()
	{
		// Addendum 3: "hide irrelevant levels" is always on, so there is no setting that shows a 1 or a 0
		int[] stats = {5, 0, 0, 70, 1, 0};
		TargetSnapshot s = snap(stats, 22, 30, false, null);
		assertEquals("Att 5", texts(s, new Cfg()).get(2));
		int[] twos = {2, 2, 2, 70, 2, 2};
		assertEquals("a 2 is shown", "Att 2  Str 2  Def 2  Rng 2  Mag 2", texts(snap(twos, 22, 30, false, null), new Cfg()).get(2));
	}

	@Test
	public void noLevelsSegmentWhenEveryLevelIsHidden()
	{
		int[] stats = {1, 1, 1, 70, 1, 1};
		// name, HP only: no empty cell, so no gap
		assertEquals(Arrays.asList("Ice giant  53", "52/70"), texts(snap(stats, 22, 30, false, null), new Cfg()));
	}

	@Test
	public void nameAlwaysShowsTheCombatLevelWhenKnownUnderEveryDetailLevel()
	{
		for (FoeConfig.Detail d : FoeConfig.Detail.values())
		{
			Cfg c = new Cfg();
			c.detail = d;
			assertEquals("detail " + d, "Ice giant  53", texts(live(), c).get(0));
		}
		TargetSnapshot noLevel = SnapshotFactory.build("Ice giant", 0, ICE_GIANT, 22, 30, false, null, null);
		assertEquals("combat level 0 is unknown, not shown", "Ice giant", texts(noLevel, new Cfg()).get(0));
	}

	@Test
	public void weaknessIsTheElementNameOnly()
	{
		String text = texts(live(), new Cfg()).get(3);
		assertEquals("Fire", text);
		assertFalse("the client never receives a percent", text.contains("%"));
		for (Weakness.Element e : Weakness.Element.values())
		{
			String t = texts(snap(ICE_GIANT, 22, 30, false, new Weakness(e)), new Cfg()).get(3);
			assertEquals(e.name().charAt(0) + e.name().substring(1).toLowerCase(Locale.ROOT), t);
		}
	}

	@Test
	public void weaknessTextDoesNotDependOnTheUserLocale()
	{
		// "AIR".toLowerCase() in a Turkish locale is "aır" (dotless i), so an unqualified toLowerCase
		// would draw "Aır" for every Turkish user.
		Locale saved = Locale.getDefault();
		try
		{
			Locale.setDefault(Locale.forLanguageTag("tr-TR"));
			assertEquals("control: this JVM really lowercases I as a dotless i here", "aır", "AIR".toLowerCase());
			assertEquals("Air", texts(snap(ICE_GIANT, 22, 30, false, new Weakness(Weakness.Element.AIR)), new Cfg()).get(3));
		}
		finally
		{
			Locale.setDefault(saved);
		}
	}

	@Test
	public void noWeaknessSegmentWhenDisabledOrAbsent()
	{
		Cfg c = new Cfg();
		c.showWeakness = false;
		assertEquals(3, texts(live(), c).size());
		c.showWeakness = true;
		assertEquals("a monster with no weakness leaves no empty cell", 3,
			texts(snap(ICE_GIANT, 22, 30, false, null), c).size());
	}

	@Test
	public void hpTextNoneIsOneCellHoldingTheBarWithNoText()
	{
		Cfg c = new Cfg();
		c.hpText = FoeConfig.HpText.NONE;
		List<FoeOverlay.Cell> cells = FoeOverlay.cells(live(), c);
		// name | bar | levels | weakness: the bar is its own cell, not tied to the name or to a text slot
		assertEquals(4, cells.size());
		assertFalse(cells.get(0).hp);
		assertTrue(cells.get(1).hp);
		assertEquals("", cells.get(1).text);
		assertEquals("Att 40  Str 40  Def 40", cells.get(2).text);
		assertEquals("Fire", cells.get(3).text);
	}

	@Test
	public void theHpCellCarriesTheBarBeforeItsText()
	{
		FoeOverlay.Cell hpCell = FoeOverlay.cells(live(), new Cfg()).get(1);
		assertTrue(hpCell.hp);
		assertEquals("52/70", hpCell.text);
	}

	// ---- background opacity (requirement 5) ----

	@Test
	public void backgroundAlphaMapsPercentToAlphaAndClamps()
	{
		assertEquals(0, FoeOverlay.backgroundAlpha(0));
		assertEquals("61% is RuneLite's standard overlay alpha", 156, FoeOverlay.backgroundAlpha(61));
		assertEquals(ComponentConstants.STANDARD_BACKGROUND_COLOR.getAlpha(), FoeOverlay.backgroundAlpha(61));
		assertEquals(255, FoeOverlay.backgroundAlpha(100));
		assertEquals("101 unclamped is 258, which new Color rejects", 255, FoeOverlay.backgroundAlpha(101));
		assertEquals(255, FoeOverlay.backgroundAlpha(500));
		assertEquals(255, FoeOverlay.backgroundAlpha(Integer.MAX_VALUE));
		assertEquals("negative unclamped is -13", 0, FoeOverlay.backgroundAlpha(-5));
		assertEquals(0, FoeOverlay.backgroundAlpha(Integer.MIN_VALUE));
	}

	@Test
	public void backgroundAlphaIsAlwaysAValidColourAlphaAndNeverDecreases()
	{
		int prev = 0;
		for (int p = -1000; p <= 1000; p++)
		{
			int a = FoeOverlay.backgroundAlpha(p);
			new Color(0, 0, 0, a); // throws IllegalArgumentException outside 0..255
			assertTrue("monotonic at " + p, a >= prev);
			prev = a;
		}
	}

	// ---- drawing, on a BufferedImage ----

	private static final class Painted
	{
		final BufferedImage img;
		final Dimension dim;
		final FontMetrics fm;
		/** x of the bar's left edge, i.e. after the name cell and one gap. */
		final int barX;
		/** y of the bar's middle row. */
		final int barMidY;

		Painted(BufferedImage img, Dimension dim, FontMetrics fm, int barX, int barMidY)
		{
			this.img = img;
			this.dim = dim;
			this.fm = fm;
			this.barX = barX;
			this.barMidY = barMidY;
		}

		int argb(int x, int y)
		{
			return img.getRGB(x, y);
		}
	}

	private static Painted paint(TargetSnapshot s, Cfg c)
	{
		BufferedImage img = new BufferedImage(800, 80, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = graphics(img);
		try
		{
			Dimension d = new FoeOverlay(new FoePlugin(), c).draw(g, s);
			FontMetrics fm = g.getFontMetrics();
			String name = FoeOverlay.cells(s, c).get(0).text;
			int barX = FoeOverlay.PAD + fm.stringWidth(name) + FoeOverlay.GAP;
			int midY = (d.height - FoeOverlay.BAR_H) / 2 + FoeOverlay.BAR_H / 2;
			return new Painted(img, d, fm, barX, midY);
		}
		finally
		{
			g.dispose();
		}
	}

	private static int alpha(int argb)
	{
		return argb >>> 24;
	}

	private static int maxAlpha(BufferedImage img, int x0, int x1)
	{
		int max = 0;
		for (int x = x0; x < x1; x++)
		{
			for (int y = 0; y < img.getHeight(); y++)
			{
				max = Math.max(max, alpha(img.getRGB(x, y)));
			}
		}
		return max;
	}

	/** Opaque BAR_FG as getRGB reports it, and the track: BAR_BG over transparent. */
	private static final int FILL = 0xFF000000 | FoeOverlay.BAR_FG.getRGB();
	private static final int TRACK = (FoeOverlay.BAR_BG.getAlpha() << 24) | (FoeOverlay.BAR_BG.getRGB() & 0xFFFFFF);

	// Task 6 review finding 1: the divider before the bar (spec mockup) was untested; the plan put it after.
	@Test
	public void dividerSitsBeforeTheBarNotBetweenBarAndText()
	{
		Painted p = paint(halfBar(false), flat());
		assertTrue("divider before the bar", alpha(p.argb(p.barX - FoeOverlay.GAP / 2, p.barMidY)) > 0);
		assertEquals("nothing between bar and text", 0,
			alpha(p.argb(p.barX + FoeOverlay.BAR_W + FoeOverlay.BAR_TEXT_GAP / 2, p.barMidY)));
	}

	// Task 6 review finding 3: at large scales a living monster read "0%" and a damaged one drew a full bar at "100%".
	@Test
	public void percentAndFillNeverClaimEmptyOrFullUnlessTheBarIs()
	{
		Cfg c = new Cfg();
		c.hpText = FoeConfig.HpText.PERCENT;
		int[] big = {40, 40, 40, 2000, 1, 1};
		TargetSnapshot nearlyDead = snap(big, 1, 255, false, null);
		TargetSnapshot nearlyFull = snap(big, 254, 255, false, null);
		assertEquals("1%", FoeOverlay.hpText(nearlyDead, c));
		assertEquals("99%", FoeOverlay.hpText(nearlyFull, c));
		assertEquals(1, FoeOverlay.barFill(nearlyDead, FoeOverlay.BAR_W));
		assertEquals(FoeOverlay.BAR_W - 1, FoeOverlay.barFill(nearlyFull, FoeOverlay.BAR_W));
		// the ends themselves are unchanged
		assertEquals("100%", FoeOverlay.hpText(snap(big, 255, 255, false, null), c));
		assertEquals(FoeOverlay.BAR_W, FoeOverlay.barFill(snap(big, 255, 255, false, null), FoeOverlay.BAR_W));
	}

	private static Cfg flat()
	{
		Cfg c = new Cfg();
		c.backgroundOpacity = 0; // transparent backdrop, so sampled pixels are exactly what the bar drew
		return c;
	}

	/** Half the bar: 15 of 30 is a 40px fill. Sample x offsets 10 (inside the fill) and 60 (the empty track). */
	private static TargetSnapshot halfBar(boolean stale)
	{
		return snap(ICE_GIANT, 15, 30, stale, null);
	}

	@Test
	public void liveBarIsAnOpaqueFillOverATrack()
	{
		Painted p = paint(halfBar(false), flat());
		assertEquals(FILL, p.argb(p.barX + 10, p.barMidY));
		assertEquals(TRACK, p.argb(p.barX + 60, p.barMidY));
	}

	@Test
	public void fadedStaleBarDrawsTheFillAtReducedAlpha()
	{
		Cfg c = flat();
		c.staleHpStyle = FoeConfig.StaleHpStyle.FADED;
		Painted p = paint(halfBar(true), c);
		int a = alpha(p.argb(p.barX + 10, p.barMidY));
		assertTrue("fill is translucent, was alpha " + a, a < 255);
		assertTrue("fill is still more visible than the empty track, was alpha " + a, a > FoeOverlay.BAR_BG.getAlpha());
		assertEquals("the track itself is not faded", TRACK, p.argb(p.barX + 60, p.barMidY));
	}

	@Test
	public void hollowStaleBarDrawsAnOutlineAndNoFill()
	{
		Cfg c = flat();
		c.staleHpStyle = FoeConfig.StaleHpStyle.HOLLOW;
		Painted p = paint(halfBar(true), c);
		assertEquals("inside the outline is just the track", TRACK, p.argb(p.barX + 10, p.barMidY));
		assertEquals("the outline's left edge", FILL, p.argb(p.barX, p.barMidY));
		assertEquals("the outline's right edge, at the fill width", FILL, p.argb(p.barX + 39, p.barMidY));
		assertEquals("nothing past the fill width", TRACK, p.argb(p.barX + 60, p.barMidY));
	}

	@Test
	public void staleStyleDoesNothingToALiveBar()
	{
		for (FoeConfig.StaleHpStyle style : FoeConfig.StaleHpStyle.values())
		{
			Cfg c = flat();
			c.staleHpStyle = style;
			Painted p = paint(halfBar(false), c);
			assertEquals("live bar under " + style, FILL, p.argb(p.barX + 10, p.barMidY));
		}
	}

	private static int hpTextMaxAlpha(TargetSnapshot s, Cfg c)
	{
		Painted p = paint(s, c);
		int x0 = p.barX + FoeOverlay.BAR_W + FoeOverlay.BAR_TEXT_GAP;
		int x1 = x0 + p.fm.stringWidth(FoeOverlay.hpText(s, c));
		return maxAlpha(p.img, x0, x1);
	}

	@Test
	public void liveHpTextIsOpaque()
	{
		assertEquals(255, hpTextMaxAlpha(live(), flat()));
	}

	@Test
	public void fadedStaleHpTextIsDrawnAtHalfAlpha()
	{
		Cfg c = flat();
		c.staleHpStyle = FoeConfig.StaleHpStyle.FADED;
		int a = hpTextMaxAlpha(stale(), c);
		assertTrue("the text is drawn (alpha " + a + ")", a > 0);
		assertTrue("and at no more than half alpha, was " + a, a <= 128);
	}

	@Test
	public void hollowLeavesTheHpTextOpaque()
	{
		Cfg c = flat();
		c.staleHpStyle = FoeConfig.StaleHpStyle.HOLLOW;
		assertEquals(255, hpTextMaxAlpha(stale(), c));
	}

	@Test
	public void opacityOutsideZeroToHundredNeverThrowsWhileDrawing()
	{
		for (int opacity : new int[] {-5, 0, 61, 100, 101, 500, Integer.MAX_VALUE, Integer.MIN_VALUE})
		{
			Cfg c = new Cfg();
			c.backgroundOpacity = opacity;
			Painted p = paint(live(), c);
			assertTrue("drew at opacity " + opacity, p.dim.width > 0 && p.dim.height > 0);
		}
	}

	@Test
	public void zeroOpacityDrawsNoBackgroundAndStandardOpacityDoes()
	{
		Cfg c = new Cfg();
		c.backgroundOpacity = 0;
		assertEquals("nothing at the strip's corner", 0, alpha(paint(live(), c).argb(1, 1)));
		c.backgroundOpacity = 61;
		assertNotEquals("the standard background covers the corner", 0, alpha(paint(live(), c).argb(1, 1)));
	}

	@Test
	public void stripWidthReservesTheBarExactlyWhenThereIsOne()
	{
		Cfg c = flat();
		c.detail = FoeConfig.Detail.COMPACT;
		c.hpText = FoeConfig.HpText.NONE;
		c.showWeakness = false;
		int withBar = paint(snap(ICE_GIANT, 22, 30, false, null), c).dim.width;
		int without = paint(snap(ICE_GIANT, 31, 30, false, null), c).dim.width; // ratio > scale: no bar
		Painted p = paint(snap(ICE_GIANT, 31, 30, false, null), c);
		int name = p.fm.stringWidth("Ice giant  53");
		assertEquals("name alone", FoeOverlay.PAD + name + FoeOverlay.PAD, without);
		assertEquals("name, gap, bar", FoeOverlay.PAD + name + FoeOverlay.GAP + FoeOverlay.BAR_W + FoeOverlay.PAD, withBar);
	}

	@Test
	public void stripWidthIsEveryCellPlusOneGapBetweenEach()
	{
		Cfg c = flat();
		Painted p = paint(live(), c);
		int expected = FoeOverlay.PAD
			+ p.fm.stringWidth("Ice giant  53") + FoeOverlay.GAP
			+ FoeOverlay.BAR_W + FoeOverlay.BAR_TEXT_GAP + p.fm.stringWidth("52/70") + FoeOverlay.GAP
			+ p.fm.stringWidth("Att 40  Str 40  Def 40") + FoeOverlay.GAP
			+ p.fm.stringWidth("Fire")
			+ FoeOverlay.PAD;
		assertEquals(expected, p.dim.width);
	}

	// ---- Addendum 3: HP text position, layout, and max HP before the first hit ----

	private static final int WHITE = 0xFFFFFFFF;
	private static final int BLACK = 0xFF000000;

	/** A graphics with text antialiasing off, so glyph pixels are exactly the text colour or the outline colour. */
	private static Graphics2D graphics(BufferedImage img)
	{
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		return g;
	}

	private static FontMetrics fm()
	{
		Graphics2D g = graphics(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));
		try
		{
			return g.getFontMetrics();
		}
		finally
		{
			g.dispose();
		}
	}

	private static FoeOverlay.Frame frame(TargetSnapshot s, Cfg c)
	{
		return FoeOverlay.frame(FoeOverlay.cells(s, c), c.layout(), fm());
	}

	private static Cfg inside()
	{
		Cfg c = flat();
		c.hpTextPosition = FoeConfig.HpTextPosition.INSIDE;
		return c;
	}

	private static Cfg stacked()
	{
		Cfg c = flat();
		c.layout = FoeConfig.Layout.STACKED;
		return c;
	}

	/** The bounding box of every pixel that is the bar's fill or its track colour; null when there is no bar. */
	private static Rectangle barBounds(BufferedImage img)
	{
		int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
		for (int y = 0; y < img.getHeight(); y++)
		{
			for (int x = 0; x < img.getWidth(); x++)
			{
				int p = img.getRGB(x, y);
				if (p == FILL || p == TRACK)
				{
					x0 = Math.min(x0, x);
					y0 = Math.min(y0, y);
					x1 = Math.max(x1, x);
					y1 = Math.max(y1, y);
				}
			}
		}
		return x1 < 0 ? null : new Rectangle(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
	}

	private static int count(BufferedImage img, Rectangle r, int argb)
	{
		int n = 0;
		for (int y = r.y; y < r.y + r.height; y++)
		{
			for (int x = r.x; x < r.x + r.width; x++)
			{
				if (img.getRGB(x, y) == argb)
				{
					n++;
				}
			}
		}
		return n;
	}

	@Test
	public void theHpCellIsInsideOnlyWhenTheSettingSaysSoAndThereIsTextToPlace()
	{
		Cfg c = new Cfg();
		assertFalse("beside is the default", FoeOverlay.cells(live(), c).get(1).inside);
		c.hpTextPosition = FoeConfig.HpTextPosition.INSIDE;
		assertTrue(FoeOverlay.cells(live(), c).get(1).inside);
		c.hpText = FoeConfig.HpText.NONE;
		assertFalse("no text, nothing to centre", FoeOverlay.cells(live(), c).get(1).inside);
		assertFalse("and none for a stale bar either", FoeOverlay.cells(stale(), c).get(1).inside);
		for (FoeOverlay.Cell cell : FoeOverlay.cells(live(), c))
		{
			if (!cell.hp)
			{
				assertFalse("only the HP cell is ever inside", cell.inside);
			}
		}
	}

	@Test
	public void insideTextAddsNothingBesideTheBar()
	{
		TargetSnapshot s = live();
		int beside = frame(s, flat()).width;
		int in = frame(s, inside()).width;
		assertEquals(FoeOverlay.BAR_TEXT_GAP + fm().stringWidth("52/70"), beside - in);
	}

	@Test
	public void insideBarKeepsItsWidthWhenTheTextFits()
	{
		FoeOverlay.Frame f = frame(live(), inside());
		assertTrue("precondition: 52/70 fits in the standard bar",
			fm().stringWidth("52/70") + 2 * FoeOverlay.INSIDE_PAD <= FoeOverlay.BAR_W);
		assertEquals(FoeOverlay.BAR_W, f.bar.width);
	}

	// Overflow rule: widen the bar to fit. Falling back to BESIDE would move the text across the bar under the
	// user's own setting, and clipping would hide digits. Widening keeps both the choice and the number.
	@Test
	public void insideBarWidensToFitTextWiderThanTheBarAndTheFillScales()
	{
		int[] huge = {40, 40, 40, 123456, 1, 1};
		TargetSnapshot s = snap(huge, 15, 30, false, null);
		String text = FoeOverlay.hpText(s, inside());
		int needed = fm().stringWidth(text) + 2 * FoeOverlay.INSIDE_PAD;
		assertTrue("precondition: " + text + " does not fit in the standard bar", needed > FoeOverlay.BAR_W);
		FoeOverlay.Frame f = frame(s, inside());
		assertEquals(needed, f.bar.width);
		assertEquals("beside never widens the bar", FoeOverlay.BAR_W, frame(s, flat()).bar.width);

		Painted p = paint(s, inside());
		Rectangle drawn = barBounds(p.img);
		assertEquals("the pixels agree with the frame", f.bar.width, drawn.width);
		assertEquals(f.bar.x, drawn.x);
		// half full: the fill is half of the WIDENED bar
		assertEquals(FILL, p.argb(drawn.x + 2, drawn.y + 1));
		assertEquals(TRACK, p.argb(drawn.x + drawn.width - 2, drawn.y + 1));
	}

	@Test
	public void insideTextNeverOverhangsTheBarAtAnyMaxHp()
	{
		FontMetrics fm = fm();
		for (int max : new int[] {1, 9, 99, 999, 9999, 99999, 999999, 9999999})
		{
			int[] stats = {40, 40, 40, max, 1, 1};
			for (FoeConfig.HpText t : FoeConfig.HpText.values())
			{
				Cfg c = inside();
				c.hpText = t;
				TargetSnapshot s = snap(stats, 29, 30, false, null);
				String text = FoeOverlay.hpText(s, c);
				FoeOverlay.Frame f = frame(s, c);
				assertTrue("max " + max + " " + t + " '" + text + "'",
					f.bar.width >= (text.isEmpty() ? 0 : fm.stringWidth(text) + 2 * FoeOverlay.INSIDE_PAD));
				assertTrue(f.bar.width >= FoeOverlay.BAR_W);
			}
		}
	}

	@Test
	public void insideTextIsCentredOnTheBar()
	{
		Painted p = paint(halfBar(false), inside());
		Rectangle bar = barBounds(p.img);
		int left = Integer.MAX_VALUE, right = -1;
		for (int y = bar.y; y < bar.y + bar.height; y++)
		{
			for (int x = bar.x; x < bar.x + bar.width; x++)
			{
				if (p.argb(x, y) == WHITE)
				{
					left = Math.min(left, x);
					right = Math.max(right, x);
				}
			}
		}
		assertTrue("the text is drawn on the bar", right > left);
		int leftMargin = left - bar.x;
		int rightMargin = bar.x + bar.width - 1 - right;
		// glyph side bearings make the ink a pixel or two off the advance-width centre, never more
		assertTrue("margins " + leftMargin + " / " + rightMargin, Math.abs(leftMargin - rightMargin) <= 2);
	}

	// A white number that straddles a green fill and a pale track has no single background to contrast with, so it
	// gets RuneLite's own legibility device: TextComponent.renderText (client 1.13.1) draws the text in black at
	// (x+1, y+1) and then in its colour at (x, y). Asserted exactly: the black pixels are the glyph pixels moved one
	// down and one right, minus the glyph pixels themselves, and there is no black on any other side (the previous
	// 8-way outline looked rough once antialiased in the real client).
	@Test
	public void insideTextHasADropShadowOnePixelDownAndRightAndNoOutline()
	{
		Painted p = paint(halfBar(false), inside()); // 15/30: the fill ends at the middle of the text
		Rectangle bar = barBounds(p.img);
		Set<Point> glyphs = new HashSet<>();
		Set<Point> black = new HashSet<>();
		for (int y = bar.y - 2; y < bar.y + bar.height + 2; y++)
		{
			for (int x = bar.x - 2; x < bar.x + bar.width + 2; x++)
			{
				if (p.argb(x, y) == WHITE)
				{
					glyphs.add(new Point(x, y));
				}
				else if (p.argb(x, y) == BLACK)
				{
					black.add(new Point(x, y));
				}
			}
		}
		assertTrue("the text is drawn", glyphs.size() > 20);
		Set<Point> expected = new HashSet<>();
		for (Point g : glyphs)
		{
			Point shadow = new Point(g.x + 1, g.y + 1);
			if (!glyphs.contains(shadow))
			{
				expected.add(shadow);
			}
		}
		assertEquals("black pixels = the glyphs shifted (+1,+1), nothing else", expected, black);

		// the glyphs really do sit over both backgrounds: some fill and some track remain visible around them
		int mid = bar.x + bar.width / 2;
		boolean overFill = false, overTrack = false;
		for (int y = bar.y; y < bar.y + bar.height; y++)
		{
			overFill |= p.argb(mid - 20, y) == FILL || p.argb(mid - 18, y) == FILL;
			overTrack |= p.argb(mid + 20, y) == TRACK || p.argb(mid + 18, y) == TRACK;
		}
		assertTrue("fill visible left of the text", overFill);
		assertTrue("track visible right of the text", overTrack);
	}

	@Test
	public void theShadowIsBlackAndSolidOnBothALiveAndAFadedBar()
	{
		for (boolean stale : new boolean[] {false, true})
		{
			Cfg c = inside();
			c.staleHpStyle = FoeConfig.StaleHpStyle.FADED;
			Painted p = paint(halfBar(stale), c);
			Rectangle bar = barBounds(p.img);
			assertTrue("a shadow is drawn (stale=" + stale + ")", count(p.img, bar, BLACK) > 0);
		}
	}

	@Test
	public void insideTextStaysWithinTheBarsRows()
	{
		Painted p = paint(halfBar(false), inside());
		Rectangle bar = barBounds(p.img);
		for (int y = 0; y < p.img.getHeight(); y++)
		{
			// one column past the bar: the shadow of the last glyph column lands there if the text overhangs
			for (int x = bar.x; x < bar.x + bar.width + 1; x++)
			{
				int argb = p.argb(x, y);
				if (argb == WHITE || argb == BLACK)
				{
					assertTrue("glyph or shadow pixel at y=" + y + " outside bar rows " + bar.y + ".." + (bar.y + bar.height - 1),
						y >= bar.y && y < bar.y + bar.height);
					assertTrue("glyph or shadow pixel at x=" + x + " outside the bar", x < bar.x + bar.width);
				}
			}
		}
	}

	@Test
	public void insideBarIsTallEnoughForTheFont()
	{
		FontMetrics fm = fm();
		FoeOverlay.Frame f = frame(live(), inside());
		// glyphs plus their 1px shadow, and the same pixel above to keep the text centred (settings-redesign review F3:
		// >= ascent alone let a fixed BAR_H survive, since the test font's ascent equals BAR_H)
		assertTrue(f.bar.height >= fm.getAscent() + 2);
		assertTrue(f.bar.height >= FoeOverlay.BAR_H);
		assertEquals("beside keeps the standard bar height", FoeOverlay.BAR_H, frame(live(), flat()).bar.height);
	}

	@Test
	public void fadedInsideTextIsDimmerThanLiveInsideText()
	{
		Cfg c = inside();
		c.staleHpStyle = FoeConfig.StaleHpStyle.FADED;
		Painted live = paint(halfBar(false), c);
		Painted dim = paint(halfBar(true), c);
		Rectangle bar = frame(halfBar(true), c).bar;
		assertTrue("live text has pure white pixels", count(live.img, bar, WHITE) > 0);
		assertEquals("faded text has none", 0, count(dim.img, bar, WHITE));
		assertTrue("but the shadow is still there, solid, so it stays legible", count(dim.img, bar, BLACK) > 0);
	}

	@Test
	public void insideWithNoTextDrawsNothingOnTheBarLiveOrStale()
	{
		Cfg c = inside();
		c.hpText = FoeConfig.HpText.NONE;
		Rectangle bar = barBounds(paint(halfBar(false), c).img);
		assertEquals(0, count(paint(halfBar(false), c).img, bar, WHITE));
		assertEquals(0, count(paint(halfBar(false), c).img, bar, BLACK));
		assertEquals(0, count(paint(halfBar(true), c).img, bar, WHITE));
		assertEquals(0, count(paint(halfBar(true), c).img, bar, BLACK));
	}

	// ---- stacked ----

	@Test
	public void stackedDimensionIsTheWiderRowAndBothHeights()
	{
		FontMetrics fm = fm();
		Cfg c = stacked();
		// full detail: the name line (name | levels | weakness) is wider than bar + text
		TargetSnapshot s = live();
		FoeOverlay.Frame f = frame(s, c);
		int nameLine = FoeOverlay.PAD + fm.stringWidth("Ice giant  53") + FoeOverlay.GAP
			+ fm.stringWidth("Att 40  Str 40  Def 40") + FoeOverlay.GAP + fm.stringWidth("Fire") + FoeOverlay.PAD;
		int barLine = FoeOverlay.PAD + FoeOverlay.BAR_W + FoeOverlay.BAR_TEXT_GAP + fm.stringWidth("52/70") + FoeOverlay.PAD;
		assertTrue("precondition: the name line is the wider one", nameLine > barLine);
		assertEquals(nameLine, f.width);
		int barRow = Math.max(FoeOverlay.BAR_H, fm.getHeight()); // beside text is taller than the bar
		assertEquals(FoeOverlay.PAD + fm.getHeight() + FoeOverlay.ROW_GAP + barRow + FoeOverlay.PAD, f.height);

		// short name, compact, no weakness: the bar line is the wider one
		Cfg narrow = stacked();
		narrow.detail = FoeConfig.Detail.COMPACT;
		narrow.showWeakness = false;
		TargetSnapshot rat = SnapshotFactory.build("Rat", 1, ICE_GIANT, 22, 30, false, null, null);
		FoeOverlay.Frame g = frame(rat, narrow);
		int ratLine = FoeOverlay.PAD + fm.stringWidth("Rat  1") + FoeOverlay.PAD;
		assertTrue("precondition", ratLine < barLine);
		assertEquals(barLine, g.width);
	}

	@Test
	public void stackedDrawnSizeMatchesTheFrame()
	{
		Painted p = paint(live(), stacked());
		FoeOverlay.Frame f = frame(live(), stacked());
		assertEquals(f.width, p.dim.width);
		assertEquals(f.height, p.dim.height);
		assertTrue("taller than the one-line strip", p.dim.height > frame(live(), flat()).height);
	}

	@Test
	public void stackedPutsTheBarUnderTheNameAndStretchesItToTheNameLine()
	{
		FontMetrics fm = fm();
		Painted p = paint(halfBar(false), stacked());
		Rectangle bar = barBounds(p.img);
		assertEquals("left-aligned under the name", FoeOverlay.PAD, bar.x);
		assertTrue("below the name row", bar.y >= FoeOverlay.PAD + fm.getHeight() + FoeOverlay.ROW_GAP);
		int text = FoeOverlay.BAR_TEXT_GAP + fm.stringWidth("52/70");
		assertEquals("stretched to the name line, leaving room for the text beside it",
			p.dim.width - 2 * FoeOverlay.PAD - text, bar.width);
		assertEquals("the fill is half of the stretched bar", FILL, p.argb(bar.x + bar.width / 4, bar.y + 1));
		assertEquals(TRACK, p.argb(bar.x + bar.width * 3 / 4, bar.y + 1));
		// the name is above the bar, not beside it
		boolean nameAbove = false;
		for (int x = 0; x < p.dim.width; x++)
		{
			for (int y = 0; y < bar.y; y++)
			{
				nameAbove |= p.argb(x, y) == WHITE;
			}
		}
		assertTrue(nameAbove);
		assertEquals("nothing white on the bar itself: the text is beside it", 0, count(p.img, bar, WHITE));
	}

	@Test
	public void stackedInsideTextBarFillsTheWholeWidthAndCentresItsText()
	{
		Painted p = paint(halfBar(false), stackedInside());
		Rectangle bar = barBounds(p.img);
		assertEquals(FoeOverlay.PAD, bar.x);
		assertEquals("no text beside, so the bar runs to the right edge", p.dim.width - 2 * FoeOverlay.PAD, bar.width);
		assertTrue("white text on the bar", count(p.img, bar, WHITE) > 0);
	}

	private static Cfg stackedInside()
	{
		Cfg c = stacked();
		c.hpTextPosition = FoeConfig.HpTextPosition.INSIDE;
		return c;
	}

	// Decision: dividers separate the cells of the name line in both layouts. There is none between the two lines
	// (the stacking is the separation) and none inside the bar line (the bar and its text are one cell, as in the
	// one-line strip, where nothing sits between the bar and its text either).
	@Test
	public void stackedDividersSeparateTheNameLineCellsAndNothingElse()
	{
		FontMetrics fm = fm();
		Painted p = paint(live(), stacked());
		int dividerX = FoeOverlay.PAD + fm.stringWidth("Ice giant  53") + FoeOverlay.GAP / 2;
		assertTrue("divider between name and levels", alpha(p.argb(dividerX, FoeOverlay.PAD + fm.getHeight() / 2)) > 0);
		Rectangle bar = barBounds(p.img);
		for (int x = 0; x < p.dim.width; x++)
		{
			for (int y = FoeOverlay.PAD + fm.getHeight(); y < FoeOverlay.PAD + fm.getHeight() + FoeOverlay.ROW_GAP; y++)
			{
				assertEquals("nothing drawn between the two lines at " + x + "," + y, 0, alpha(p.argb(x, y)));
			}
		}
		int betweenBarAndText = bar.x + bar.width + FoeOverlay.BAR_TEXT_GAP / 2;
		for (int y = bar.y; y < bar.y + bar.height; y++)
		{
			assertEquals("no divider between the bar and its text", 0, alpha(p.argb(betweenBarAndText, y)));
		}
		assertEquals("no divider in the bar line left of the bar", 0,
			alpha(p.argb(FoeOverlay.PAD - FoeOverlay.GAP / 2, bar.y + bar.height / 2)));
	}

	@Test
	public void stackedWithNoBarIsJustTheNameLine()
	{
		int[] noHitpoints = {40, 40, 40, 0, 1, 1};
		TargetSnapshot s = raw(noHitpoints, -1, 0, false);
		Painted p = paint(s, stacked());
		assertEquals(FoeOverlay.PAD * 2 + fm().getHeight(), p.dim.height);
		assertEquals(frame(s, flat()).width, p.dim.width);
		assertNull("no bar drawn", barBounds(p.img));
	}

	@Test
	public void oneLineIsStillOneStripWithTheBarBetweenTheNameAndTheLevels()
	{
		Painted p = paint(halfBar(false), flat());
		assertEquals(FoeOverlay.PAD * 2 + Math.max(fm().getHeight(), FoeOverlay.BAR_H), p.dim.height);
		Rectangle bar = barBounds(p.img);
		assertEquals(p.barX, bar.x);
		assertEquals(FoeOverlay.BAR_W, bar.width);
	}

	// ---- before the first hit (addendum 4): an empty outlined bar with the max HP only ----

	/** HpMemory's answer for a target that has never had a bar, with max HP known from the stats. */
	private static TargetSnapshot unhit()
	{
		return snap(ICE_GIANT, -1, 0, false, null);
	}

	@Test
	public void anUnhitMonsterHasABarButNothingInIt()
	{
		TargetSnapshot s = unhit();
		assertTrue(s.isHpUnhit());
		assertTrue("the bar is drawn", FoeOverlay.hasBar(s));
		assertEquals("and has no fill", 0, FoeOverlay.barFill(s, FoeOverlay.BAR_W));
		assertEquals("not even a sliver of one", 0, FoeOverlay.barFill(s, 1));
	}

	@Test
	public void anUnhitMonsterShowsOnlyItsMaxHpWhateverTheHpTextSetting()
	{
		for (FoeConfig.StaleHpStyle style : FoeConfig.StaleHpStyle.values())
		{
			Cfg c = new Cfg();
			c.staleHpStyle = style;
			for (FoeConfig.HpText t : new FoeConfig.HpText[] {FoeConfig.HpText.CURRENT_MAX, FoeConfig.HpText.CURRENT,
				FoeConfig.HpText.PERCENT})
			{
				c.hpText = t;
				assertEquals(t + "/" + style + ": no 70/70 and no 100%, which would be invented current HP", "70",
					FoeOverlay.hpText(unhit(), c));
				FoeOverlay.Cell hpCell = FoeOverlay.cells(unhit(), c).get(1);
				assertTrue(hpCell.hp);
				assertEquals("70", hpCell.text);
			}
			c.hpText = FoeConfig.HpText.NONE;
			assertEquals("None means just the outlined bar", "", FoeOverlay.hpText(unhit(), c));
			assertTrue(FoeOverlay.cells(unhit(), c).get(1).hp);
		}
	}

	@Test
	public void anUnhitMonsterDrawsAnEmptyOutlinedBarInEveryStaleStyle()
	{
		for (FoeConfig.StaleHpStyle style : FoeConfig.StaleHpStyle.values())
		{
			Cfg c = flat();
			c.staleHpStyle = style;
			Painted p = paint(unhit(), c);
			Rectangle bar = barBounds(p.img);
			assertEquals(style + ": the bar is the standard size", FoeOverlay.BAR_W, bar.width);
			assertEquals(style + ": left edge", FILL, p.argb(bar.x, p.barMidY));
			assertEquals(style + ": right edge", FILL, p.argb(bar.x + bar.width - 1, p.barMidY));
			assertEquals(style + ": top edge", FILL, p.argb(bar.x + 40, bar.y));
			assertEquals(style + ": bottom edge", FILL, p.argb(bar.x + 40, bar.y + bar.height - 1));
			for (int off : new int[] {2, 20, 40, 60, FoeOverlay.BAR_W - 3})
			{
				assertEquals(style + ": empty inside at +" + off, TRACK, p.argb(bar.x + off, p.barMidY));
			}
			assertEquals(style + ": the outline is all there is of the green",
				2 * (bar.width + bar.height) - 4, count(p.img, bar, FILL));
		}
	}

	@Test
	public void anUnhitMonsterIsDrawnInFullNotInTheStaleStyle()
	{
		Cfg c = flat();
		c.staleHpStyle = FoeConfig.StaleHpStyle.FADED;
		assertEquals("max HP is a fact, not a dimmed old reading", 255, hpTextMaxAlpha(unhit(), c));
	}

	@Test
	public void anUnhitMonsterWithTheTextInsideCentresItsMaxHpOnTheEmptyBar()
	{
		Cfg c = inside();
		FoeOverlay.Cell cell = FoeOverlay.cells(unhit(), c).get(1);
		assertTrue(cell.inside);
		assertEquals("70", cell.text);
		Painted p = paint(unhit(), c);
		Rectangle bar = barBounds(p.img);
		assertTrue("the number is on the bar", count(p.img, bar, WHITE) > 0);
		assertEquals("and still nothing is filled", 2 * (bar.width + bar.height) - 4, count(p.img, bar, FILL));
	}

	@Test
	public void anUnhitMonsterWithNoHpTextIsJustTheOutlinedBar()
	{
		Cfg c = inside();
		c.hpText = FoeConfig.HpText.NONE;
		FoeOverlay.Cell cell = FoeOverlay.cells(unhit(), c).get(1);
		assertTrue(cell.hp);
		assertEquals("", cell.text);
		assertFalse("no text, nothing to centre", cell.inside);
		Painted p = paint(unhit(), c);
		Rectangle bar = barBounds(p.img);
		assertNotNull(bar);
		assertEquals(0, count(p.img, bar, WHITE));
		assertEquals(0, count(p.img, bar, BLACK));
	}

	@Test
	public void anUnhitMonsterGetsItsBarInTheStackedLayoutToo()
	{
		Painted p = paint(unhit(), stacked());
		Rectangle bar = barBounds(p.img);
		assertNotNull(bar);
		assertEquals(FoeOverlay.PAD, bar.x);
		assertEquals(FILL, p.argb(bar.x, bar.y + bar.height / 2));
		assertEquals(TRACK, p.argb(bar.x + 3, bar.y + bar.height / 2));
		assertEquals("70", frame(unhit(), stacked()).hp.text);
	}

	@Test
	public void aMonsterWithNoKnownMaxHpAndNoBarDrawsNoBarEvenAfterTheFactoryRule()
	{
		int[] noHitpoints = {40, 40, 40, 0, 1, 1};
		TargetSnapshot s = snap(noHitpoints, -1, 0, false, null);
		for (FoeOverlay.Cell cell : FoeOverlay.cells(s, new Cfg()))
		{
			assertFalse(cell.hp);
		}
		assertNull(barBounds(paint(s, flat()).img));
	}

	@Test
	public void renderDrawsNothingWithoutASnapshot()
	{
		BufferedImage img = new BufferedImage(100, 40, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		try
		{
			// A fresh plugin has no snapshot. Returning null is how an overlay says "nothing to draw".
			assertNull(new FoeOverlay(new FoePlugin(), new Cfg()).render(g));
			assertEquals(0, maxAlpha(img, 0, 100));
		}
		finally
		{
			g.dispose();
		}
	}

	@Test
	public void drawReturnsTheStripSizeForTheRenderer()
	{
		BufferedImage img = new BufferedImage(800, 60, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		try
		{
			Dimension d = new FoeOverlay(new FoePlugin(), new Cfg()).draw(g, live());
			assertNotNull(d);
			assertEquals(Math.max(g.getFontMetrics().getHeight(), FoeOverlay.BAR_H) + FoeOverlay.PAD * 2, d.height);
		}
		finally
		{
			g.dispose();
		}
	}
}
