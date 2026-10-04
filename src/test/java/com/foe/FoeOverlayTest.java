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
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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

	/** FoeConfig with the interface's own defaults, each one overridable. */
	private static final class Cfg implements FoeConfig
	{
		Detail detail = FoeConfig.super.detail();
		HpDisplay hpDisplay = FoeConfig.super.hpDisplay();
		boolean hideIrrelevantLevels = FoeConfig.super.hideIrrelevantLevels();
		boolean showCombatLevel = FoeConfig.super.showCombatLevel();
		boolean showWeakness = FoeConfig.super.showWeakness();
		StaleHpStyle staleHpStyle = FoeConfig.super.staleHpStyle();
		int backgroundOpacity = FoeConfig.super.backgroundOpacity();

		@Override
		public Detail detail()
		{
			return detail;
		}

		@Override
		public HpDisplay hpDisplay()
		{
			return hpDisplay;
		}

		@Override
		public boolean hideIrrelevantLevels()
		{
			return hideIrrelevantLevels;
		}

		@Override
		public boolean showCombatLevel()
		{
			return showCombatLevel;
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

	/** The spec's worked example: ratio 22 of 30 on 70 max HP is ~52 HP, 74%. */
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
		assertFalse(FoeOverlay.hasBar(snap(ICE_GIANT, -1, 0, false, null)));
		assertFalse(FoeOverlay.hasBar(snap(ICE_GIANT, -1, 30, false, null)));
		assertFalse("scale 0 means no bar however large the ratio", FoeOverlay.hasBar(snap(ICE_GIANT, 5, 0, false, null)));
		assertFalse("ratio 0 of scale 0 is no bar, not an empty one", FoeOverlay.hasBar(snap(ICE_GIANT, 0, 0, false, null)));
	}

	@Test
	public void noBarWhenRatioExceedsScale()
	{
		assertFalse(FoeOverlay.hasBar(snap(ICE_GIANT, 31, 30, false, null)));
	}

	@Test
	public void barFillIsProportionalAndNeverExceedsTheBar()
	{
		assertEquals(40, FoeOverlay.barFill(snap(ICE_GIANT, 15, 30, false, null)));
		assertEquals(FoeOverlay.BAR_W, FoeOverlay.barFill(snap(ICE_GIANT, 30, 30, false, null)));
		assertEquals(0, FoeOverlay.barFill(snap(ICE_GIANT, 0, 30, false, null)));
		assertEquals("a living monster never shows an empty bar", 1, FoeOverlay.barFill(snap(ICE_GIANT, 1, 255, false, null)));
	}

	@Test
	public void barFillIsZeroWhenThereIsNoBar()
	{
		assertEquals("ratio > scale: unclamped it would be 83", 0, FoeOverlay.barFill(snap(ICE_GIANT, 31, 30, false, null)));
		assertEquals("no scale: the division would be by zero", 0, FoeOverlay.barFill(snap(ICE_GIANT, -1, 0, false, null)));
	}

	// ---- HP text (requirement 2) ----

	@Test
	public void hpTextFollowsTheDisplaySetting()
	{
		Cfg c = new Cfg();
		c.hpDisplay = FoeConfig.HpDisplay.NUMBER;
		assertEquals("~52/70", FoeOverlay.hpText(live(), c));
		c.hpDisplay = FoeConfig.HpDisplay.PERCENT;
		assertEquals("74%", FoeOverlay.hpText(live(), c));
		c.hpDisplay = FoeConfig.HpDisplay.NUMBER_AND_PERCENT;
		assertEquals("~52/70 (74%)", FoeOverlay.hpText(live(), c));
		c.hpDisplay = FoeConfig.HpDisplay.BAR_ONLY;
		assertEquals("", FoeOverlay.hpText(live(), c));
	}

	@Test
	public void percentRoundsToTheNearestWholeNumber()
	{
		// ratio 23 of 30 on 70 max HP estimates 55 HP: 78.57%, which rounds to 79 where truncating gives 78
		TargetSnapshot s = snap(ICE_GIANT, 23, 30, false, null);
		assertEquals(55, s.getHp());
		Cfg c = new Cfg();
		c.hpDisplay = FoeConfig.HpDisplay.PERCENT;
		assertEquals("79%", FoeOverlay.hpText(s, c));
	}

	@Test
	public void deadMonsterShowsZeroHp()
	{
		Cfg c = new Cfg();
		TargetSnapshot dead = snap(ICE_GIANT, 0, 30, false, null);
		assertEquals("~0/70", FoeOverlay.hpText(dead, c));
		c.hpDisplay = FoeConfig.HpDisplay.PERCENT;
		assertEquals("0%", FoeOverlay.hpText(dead, c));
	}

	@Test
	public void unknownMaxHpShowsNoTextUnderAnyDisplayButStillDrawsTheBar()
	{
		int[] noHitpoints = {40, 40, 40, 0, 1, 1};
		TargetSnapshot s = snap(noHitpoints, 22, 30, false, null);
		assertTrue(FoeOverlay.hasBar(s));
		Cfg c = new Cfg();
		for (FoeConfig.HpDisplay d : FoeConfig.HpDisplay.values())
		{
			c.hpDisplay = d;
			assertEquals("display " + d, "", FoeOverlay.hpText(s, c));
		}
		c.hpDisplay = FoeConfig.HpDisplay.NUMBER;
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
		TargetSnapshot s = new TargetSnapshot("Ice giant", 53, 52, 70, 31, 30, false, 40, 40, 40, 1, 1, null);
		assertEquals("", FoeOverlay.hpText(s, new Cfg()));
	}

	// ---- stale HP (requirement 3) ----

	@Test
	public void markerAppendsAQuestionMarkToStaleTextOnly()
	{
		Cfg c = new Cfg();
		c.staleHpStyle = FoeConfig.StaleHpStyle.MARKER;
		c.hpDisplay = FoeConfig.HpDisplay.NUMBER;
		assertEquals("~52/70?", FoeOverlay.hpText(stale(), c));
		c.hpDisplay = FoeConfig.HpDisplay.PERCENT;
		assertEquals("74%?", FoeOverlay.hpText(stale(), c));
		c.hpDisplay = FoeConfig.HpDisplay.NUMBER_AND_PERCENT;
		assertEquals("~52/70 (74%)?", FoeOverlay.hpText(stale(), c));
		assertEquals("live HP carries no marker", "~52/70 (74%)", FoeOverlay.hpText(live(), c));
	}

	@Test
	public void fadedAndHollowLeaveTheHpTextUntouched()
	{
		Cfg c = new Cfg();
		c.staleHpStyle = FoeConfig.StaleHpStyle.FADED;
		assertEquals("~52/70", FoeOverlay.hpText(stale(), c));
		c.staleHpStyle = FoeConfig.StaleHpStyle.HOLLOW;
		assertEquals("~52/70", FoeOverlay.hpText(stale(), c));
	}

	@Test
	public void markerStillMarksAStaleBarWhenThereIsNoText()
	{
		// Bar-only has no HP text to append "?" to, and a stale bar must not read as live.
		Cfg c = new Cfg();
		c.staleHpStyle = FoeConfig.StaleHpStyle.MARKER;
		c.hpDisplay = FoeConfig.HpDisplay.BAR_ONLY;
		FoeOverlay.Cell hpCell = FoeOverlay.cells(stale(), c).get(1);
		assertTrue(hpCell.hp);
		assertEquals("?", hpCell.text);
		assertEquals("a live bar-only HP cell stays bare", "", FoeOverlay.cells(live(), c).get(1).text);
	}

	// ---- the segments (name, HP, levels, weakness) ----

	@Test
	public void fullDetailShowsEverythingInOrder()
	{
		assertEquals(Arrays.asList("Ice giant  53", "~52/70", "Att 40  Str 40  Def 40", "Fire"), texts(live(), new Cfg()));
	}

	@Test
	public void compactDropsTheLevelsOnly()
	{
		Cfg c = new Cfg();
		c.detail = FoeConfig.Detail.COMPACT;
		assertEquals(Arrays.asList("Ice giant  53", "~52/70", "Fire"), texts(live(), c));
	}

	@Test
	public void levelsAreLabelledAndOrderedAttStrDefRngMag()
	{
		assertEquals("Att 11  Str 33  Def 22  Rng 44  Mag 55",
			texts(snap(DISTINCT, 22, 30, false, null), new Cfg()).get(2));
	}

	@Test
	public void hideIrrelevantLevelsHidesOnesAndAlwaysHidesZeros()
	{
		int[] stats = {5, 0, 0, 70, 1, 0};
		TargetSnapshot s = snap(stats, 22, 30, false, null);
		Cfg c = new Cfg();
		c.hideIrrelevantLevels = true;
		assertEquals("Att 5", texts(s, c).get(2));
		c.hideIrrelevantLevels = false;
		assertEquals("a 1 is shown, a 0 (unknown) never is", "Att 5  Rng 1", texts(s, c).get(2));
	}

	@Test
	public void noLevelsSegmentWhenEveryLevelIsHidden()
	{
		int[] stats = {1, 1, 1, 70, 1, 1};
		// name, HP only: no empty cell, so no gap
		assertEquals(Arrays.asList("Ice giant  53", "~52/70"), texts(snap(stats, 22, 30, false, null), new Cfg()));
	}

	@Test
	public void nameShowsTheCombatLevelOnlyWhenEnabledAndKnown()
	{
		Cfg c = new Cfg();
		c.showCombatLevel = false;
		assertEquals("Ice giant", texts(live(), c).get(0));
		c.showCombatLevel = true;
		TargetSnapshot noLevel = SnapshotFactory.build("Ice giant", 0, ICE_GIANT, 22, 30, false, null, null);
		assertEquals("combat level 0 is unknown, not shown", "Ice giant", texts(noLevel, c).get(0));
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
	public void barOnlyHpIsOneCellHoldingTheBarWithNoText()
	{
		Cfg c = new Cfg();
		c.hpDisplay = FoeConfig.HpDisplay.BAR_ONLY;
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
		assertEquals("~52/70", hpCell.text);
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
		BufferedImage img = new BufferedImage(800, 60, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
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
		c.hpDisplay = FoeConfig.HpDisplay.PERCENT;
		int[] big = {40, 40, 40, 2000, 1, 1};
		TargetSnapshot nearlyDead = snap(big, 1, 255, false, null);
		TargetSnapshot nearlyFull = snap(big, 254, 255, false, null);
		assertEquals("1%", FoeOverlay.hpText(nearlyDead, c));
		assertEquals("99%", FoeOverlay.hpText(nearlyFull, c));
		assertEquals(1, FoeOverlay.barFill(nearlyDead));
		assertEquals(FoeOverlay.BAR_W - 1, FoeOverlay.barFill(nearlyFull));
		// the ends themselves are unchanged
		assertEquals("100%", FoeOverlay.hpText(snap(big, 255, 255, false, null), c));
		assertEquals(FoeOverlay.BAR_W, FoeOverlay.barFill(snap(big, 255, 255, false, null)));
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
	public void markerStaleBarKeepsTheOpaqueFill()
	{
		Cfg c = flat();
		c.staleHpStyle = FoeConfig.StaleHpStyle.MARKER;
		Painted p = paint(halfBar(true), c);
		assertEquals(FILL, p.argb(p.barX + 10, p.barMidY));
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
	public void hollowAndMarkerLeaveTheHpTextOpaque()
	{
		Cfg c = flat();
		c.staleHpStyle = FoeConfig.StaleHpStyle.HOLLOW;
		assertEquals(255, hpTextMaxAlpha(stale(), c));
		c.staleHpStyle = FoeConfig.StaleHpStyle.MARKER;
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
		c.hpDisplay = FoeConfig.HpDisplay.BAR_ONLY;
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
			+ FoeOverlay.BAR_W + FoeOverlay.BAR_TEXT_GAP + p.fm.stringWidth("~52/70") + FoeOverlay.GAP
			+ p.fm.stringWidth("Att 40  Str 40  Def 40") + FoeOverlay.GAP
			+ p.fm.stringWidth("Fire")
			+ FoeOverlay.PAD;
		assertEquals(expected, p.dim.width);
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
