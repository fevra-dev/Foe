package com.foe;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.BackgroundComponent;
import net.runelite.client.ui.overlay.components.ComponentConstants;

/**
 * Draws the plugin's snapshot as one horizontal strip: {@code name | bar ~52/70 | Att 40 Str 40 Def 40 | Fire}.
 * Drawing only: it reads {@link FoePlugin#getSnapshot()} and works out nothing about the game. It draws directly
 * rather than through PanelComponent because the strip needs exact widths.
 *
 * <p>The text, the bar predicate, the widths and the alpha are package-private statics so FoeOverlayTest can
 * assert them without a Graphics2D.
 */
class FoeOverlay extends Overlay
{
	static final int PAD = 4;
	static final int GAP = 8;
	static final int BAR_W = 80;
	static final int BAR_H = 12;
	static final int BAR_TEXT_GAP = 4;
	static final Color BAR_FG = new Color(82, 161, 82);
	static final Color BAR_BG = new Color(255, 255, 255, 60);
	private static final Color DIVIDER = new Color(255, 255, 255, 70);
	private static final Color DIM = new Color(255, 255, 255, 150);

	/**
	 * One divider-separated cell of the strip. The HP cell is the one that carries the bar, drawn before its text,
	 * and it exists exactly when there is a bar. Its text is empty in bar-only mode and when max HP is unknown.
	 * Every other cell has text, or it is not in the list: a missing segment collapses, with no gap or placeholder.
	 */
	static final class Cell
	{
		final String text;
		final boolean hp;

		Cell(String text, boolean hp)
		{
			this.text = text;
			this.hp = hp;
		}
	}

	private final FoePlugin plugin;
	private final FoeConfig config;

	@Inject
	FoeOverlay(FoePlugin plugin, FoeConfig config)
	{
		super(plugin);
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		TargetSnapshot s = plugin.getSnapshot();
		return s == null ? null : draw(g, s);
	}

	Dimension draw(Graphics2D g, TargetSnapshot s)
	{
		FontMetrics fm = g.getFontMetrics();
		List<Cell> cells = cells(s, config);
		int w = stripWidth(cells, fm);
		int h = Math.max(fm.getHeight(), BAR_H) + PAD * 2;

		int alpha = backgroundAlpha(config.backgroundOpacity());
		if (alpha > 0)
		{
			Color base = ComponentConstants.STANDARD_BACKGROUND_COLOR;
			// 1.13.1's BackgroundComponent has a no-arg and a (Color, Rectangle, boolean) constructor and no
			// (Color, Rectangle) one [measured: javap], so use the no-arg one and the setters.
			BackgroundComponent bg = new BackgroundComponent();
			bg.setBackgroundColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha));
			bg.setRectangle(new Rectangle(0, 0, w, h));
			bg.render(g);
		}

		FoeConfig.StaleHpStyle style = config.staleHpStyle();
		boolean fade = s.isHpStale() && style == FoeConfig.StaleHpStyle.FADED;
		boolean hollow = s.isHpStale() && style == FoeConfig.StaleHpStyle.HOLLOW;

		int baseline = PAD + (h - PAD * 2 + fm.getAscent() - fm.getDescent()) / 2;
		int x = PAD;
		for (int i = 0; i < cells.size(); i++)
		{
			Cell cell = cells.get(i);
			if (i > 0)
			{
				g.setColor(DIVIDER);
				g.drawLine(x - GAP / 2, PAD, x - GAP / 2, h - PAD);
			}
			if (cell.hp)
			{
				drawBar(g, s, x, (h - BAR_H) / 2, fade, hollow);
				x += BAR_W;
				if (!cell.text.isEmpty())
				{
					x += BAR_TEXT_GAP;
				}
			}
			if (!cell.text.isEmpty())
			{
				// Name and HP are the primary reading, so they are full white; levels and weakness are dimmer.
				Color text = i == 0 ? Color.WHITE : cell.hp ? (fade ? faded(Color.WHITE) : Color.WHITE) : DIM;
				g.setColor(text);
				g.drawString(cell.text, x, baseline);
				x += fm.stringWidth(cell.text);
			}
			x += GAP;
		}
		return new Dimension(w, h);
	}

	private static void drawBar(Graphics2D g, TargetSnapshot s, int x, int y, boolean fade, boolean hollow)
	{
		int fill = barFill(s);
		g.setColor(BAR_BG);
		g.fillRect(x, y, BAR_W, BAR_H);
		if (hollow)
		{
			// Last known level, outline only: it must not read as a live bar.
			if (fill > 0)
			{
				g.setColor(BAR_FG);
				g.drawRect(x, y, fill - 1, BAR_H - 1);
			}
		}
		else
		{
			g.setColor(fade ? faded(BAR_FG) : BAR_FG);
			g.fillRect(x, y, fill, BAR_H);
		}
	}

	private static Color faded(Color c)
	{
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(c.getAlpha() / 2f));
	}

	/**
	 * Whether there is a bar to draw. This one predicate decides the reserved width, the fill and whether HP text
	 * is shown, so they cannot disagree on bad data. It is not "hp is known": HP is also unknown when only max HP
	 * is, which is the spec's bar-only case. ratio above scale is inconsistent data, so it gets no bar and no text.
	 */
	static boolean hasBar(TargetSnapshot s)
	{
		return s.getHpScale() > 0 && s.getHpRatio() >= 0 && s.getHpRatio() <= s.getHpScale();
	}

	/** Filled pixels of the bar: 0 with no bar, at least 1 while the monster is alive, never more than BAR_W. */
	static int barFill(TargetSnapshot s)
	{
		if (!hasBar(s))
		{
			return 0;
		}
		int fill = Math.round(BAR_W * (float) s.getHpRatio() / s.getHpScale());
		return s.getHpRatio() > 0 ? Math.max(1, fill) : fill;
	}

	/**
	 * Background alpha for an opacity setting. {@code @Range} is enforced only by the settings spinner, so a
	 * profile can hold any int, and a Color with alpha outside 0-255 throws on every frame, drawing nothing.
	 * 61 maps to 156, the standard overlay background's own alpha.
	 */
	static int backgroundAlpha(int percent)
	{
		int p = Math.max(0, Math.min(100, percent));
		return Math.round(p * 255 / 100f);
	}

	static int stripWidth(List<Cell> cells, FontMetrics fm)
	{
		int w = PAD * 2 + GAP * (cells.size() - 1);
		for (Cell c : cells)
		{
			w += cellWidth(c, fm);
		}
		return w;
	}

	private static int cellWidth(Cell c, FontMetrics fm)
	{
		int w = c.hp ? BAR_W : 0;
		if (!c.text.isEmpty())
		{
			w += (c.hp ? BAR_TEXT_GAP : 0) + fm.stringWidth(c.text);
		}
		return w;
	}

	/** The cells in order: name, HP, levels, weakness. */
	static List<Cell> cells(TargetSnapshot s, FoeConfig c)
	{
		List<Cell> out = new ArrayList<>(4);
		out.add(new Cell(c.showCombatLevel() && s.getCombatLevel() > 0
			? s.getName() + "  " + s.getCombatLevel() : s.getName(), false));
		if (hasBar(s))
		{
			String hp = hpText(s, c);
			if (hp.isEmpty() && s.isHpStale() && c.staleHpStyle() == FoeConfig.StaleHpStyle.MARKER)
			{
				// Bar-only has no text to append "?" to, and a stale bar must not read as live.
				hp = "?";
			}
			out.add(new Cell(hp, true));
		}
		if (c.detail() == FoeConfig.Detail.FULL)
		{
			addIfAny(out, levels(s, c));
		}
		Weakness wk = s.getWeakness();
		if (c.showWeakness() && wk != null && wk.getElement() != null)
		{
			addIfAny(out, cap(wk.getElement().name()));
		}
		return out;
	}

	private static void addIfAny(List<Cell> out, String text)
	{
		if (!text.isEmpty())
		{
			out.add(new Cell(text, false));
		}
	}

	/**
	 * HP text per the display setting, or "" when there is nothing to say: bar-only, max HP unknown, or no bar.
	 * A stale value under the MARKER style gets a trailing "?".
	 */
	static String hpText(TargetSnapshot s, FoeConfig c)
	{
		if (!hasBar(s) || s.getMaxHp() <= 0 || s.getHp() < 0)
		{
			return "";
		}
		String number = "~" + s.getHp() + "/" + s.getMaxHp();
		String percent = Math.round(100f * s.getHp() / s.getMaxHp()) + "%";
		String text;
		switch (c.hpDisplay())
		{
			case NUMBER:
				text = number;
				break;
			case PERCENT:
				text = percent;
				break;
			case NUMBER_AND_PERCENT:
				text = number + " (" + percent + ")";
				break;
			default:
				return "";
		}
		return s.isHpStale() && c.staleHpStyle() == FoeConfig.StaleHpStyle.MARKER ? text + "?" : text;
	}

	private static String levels(TargetSnapshot s, FoeConfig c)
	{
		// A level of 0 is unknown and never shown. With "hide irrelevant levels" a 1 is hidden too.
		int floor = c.hideIrrelevantLevels() ? 1 : 0;
		List<String> parts = new ArrayList<>(5);
		level(parts, floor, "Att", s.getAttack());
		level(parts, floor, "Str", s.getStrength());
		level(parts, floor, "Def", s.getDefence());
		level(parts, floor, "Rng", s.getRanged());
		level(parts, floor, "Mag", s.getMagic());
		return String.join("  ", parts);
	}

	private static void level(List<String> parts, int floor, String label, int v)
	{
		if (v > floor)
		{
			parts.add(label + " " + v);
		}
	}

	/** FIRE -> Fire. Locale.ROOT: in a Turkish locale "AIR".toLowerCase() is "aır" (dotless i) [measured]. */
	private static String cap(String s)
	{
		return s.charAt(0) + s.substring(1).toLowerCase(Locale.ROOT);
	}
}
