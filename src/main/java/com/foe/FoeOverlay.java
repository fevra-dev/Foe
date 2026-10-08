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
 * Draws the plugin's snapshot, in one of two layouts (spec addendum 3).
 *
 * <pre>
 * ONE_LINE:  Ice giant  53 | [bar] 52/70 | Att 40  Str 40  Def 40 | Fire
 * STACKED:   Ice giant  53 | Att 40  Str 40  Def 40 | Fire
 *            [bar stretched to the line above] 52/70
 * </pre>
 *
 * <p>Drawing only: it reads {@link FoePlugin#getSnapshot()} and works out nothing about the game. It draws directly
 * rather than through PanelComponent because the strip needs exact widths.
 *
 * <p>Dividers separate the cells of the first line in both layouts. There is none between the two lines of the
 * stacked layout (the stacking is the separation) and none inside the HP cell, where the bar and its text are one
 * cell, as they always were in the one-line strip.
 *
 * <p>The cells, the geometry ({@link #frame}), the bar predicate and the alpha are package-private statics so
 * FoeOverlayTest can assert them without a Graphics2D.
 */
class FoeOverlay extends Overlay
{
	static final int PAD = 4;
	static final int GAP = 8;
	static final int BAR_W = 80;
	static final int BAR_H = 12;
	static final int BAR_TEXT_GAP = 4;
	/** Space kept clear on each side of HP text centred on the bar. */
	static final int INSIDE_PAD = 4;
	/** Space between the first line and the bar line in the stacked layout. */
	static final int ROW_GAP = 2;
	/** Width of the outline round HP text drawn on the bar, in pixels. */
	private static final int OUTLINE = 1;
	static final Color BAR_FG = new Color(82, 161, 82);
	static final Color BAR_BG = new Color(255, 255, 255, 60);
	private static final Color DIVIDER = new Color(255, 255, 255, 70);
	private static final Color DIM = new Color(255, 255, 255, 150);

	/**
	 * One divider-separated cell. The HP cell is the one that carries the bar, and it exists exactly when there is a
	 * bar. Its text is empty when HP text is None and when max HP is unknown, and {@code inside} says the text goes on
	 * the bar rather than beside it (never true for an empty text: there is nothing to centre). Every other cell has
	 * text, or it is not in the list: a missing segment collapses, with no gap or placeholder.
	 */
	static final class Cell
	{
		final String text;
		final boolean hp;
		final boolean inside;

		Cell(String text, boolean hp, boolean inside)
		{
			this.text = text;
			this.hp = hp;
			this.inside = inside;
		}
	}

	/**
	 * Where everything goes, worked out once from the cells, the layout and the font. {@code line} is the first line:
	 * every cell in the one-line layout, every cell but the HP cell when stacked, with {@code x} giving each one's
	 * left edge. {@code bar} is the bar's rectangle, null when there is no HP cell. {@code hpTop}/{@code hpH} are the
	 * rows of the line that holds the bar, which is the first line itself in the one-line layout.
	 */
	static final class Frame
	{
		final int width;
		final int height;
		final List<Cell> line;
		final int[] x;
		final int lineH;
		final Cell hp;
		final Rectangle bar;
		final int hpTop;
		final int hpH;

		Frame(int width, int height, List<Cell> line, int[] x, int lineH, Cell hp, Rectangle bar, int hpTop, int hpH)
		{
			this.width = width;
			this.height = height;
			this.line = line;
			this.x = x;
			this.lineH = lineH;
			this.hp = hp;
			this.bar = bar;
			this.hpTop = hpTop;
			this.hpH = hpH;
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
		Frame f = frame(cells(s, config), config.layout(), fm);

		int alpha = backgroundAlpha(config.backgroundOpacity());
		if (alpha > 0)
		{
			Color base = ComponentConstants.STANDARD_BACKGROUND_COLOR;
			// 1.13.1's BackgroundComponent has a no-arg and a (Color, Rectangle, boolean) constructor and no
			// (Color, Rectangle) one [measured: javap], so use the no-arg one and the setters.
			BackgroundComponent bg = new BackgroundComponent();
			bg.setBackgroundColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha));
			bg.setRectangle(new Rectangle(0, 0, f.width, f.height));
			bg.render(g);
		}

		FoeConfig.StaleHpStyle style = config.staleHpStyle();
		boolean fade = s.isHpStale() && style == FoeConfig.StaleHpStyle.FADED;
		boolean hollow = s.isHpStale() && style == FoeConfig.StaleHpStyle.HOLLOW;

		int baseline = PAD + (f.lineH + fm.getAscent() - fm.getDescent()) / 2;
		for (int i = 0; i < f.line.size(); i++)
		{
			Cell cell = f.line.get(i);
			if (i > 0)
			{
				g.setColor(DIVIDER);
				g.drawLine(f.x[i] - GAP / 2, PAD, f.x[i] - GAP / 2, PAD + f.lineH - 1);
			}
			if (cell.hp)
			{
				drawHp(g, fm, s, f, fade, hollow, baseline);
			}
			else
			{
				// Name and HP are the primary reading, so they are full white; levels and weakness are dimmer.
				g.setColor(i == 0 ? Color.WHITE : DIM);
				g.drawString(cell.text, f.x[i], baseline);
			}
		}
		if (f.hp != null && !f.line.contains(f.hp))
		{
			drawHp(g, fm, s, f, fade, hollow, f.hpTop + (f.hpH + fm.getAscent() - fm.getDescent()) / 2);
		}
		return new Dimension(f.width, f.height);
	}

	/** The bar, then its text: centred on the bar, or after it on {@code besideBaseline}. */
	private static void drawHp(Graphics2D g, FontMetrics fm, TargetSnapshot s, Frame f, boolean fade, boolean hollow,
		int besideBaseline)
	{
		Rectangle bar = f.bar;
		drawBar(g, s, bar, fade, hollow);
		String text = f.hp.text;
		if (text.isEmpty())
		{
			return;
		}
		Color color = fade ? faded(Color.WHITE) : Color.WHITE;
		if (f.hp.inside)
		{
			drawOutlined(g, text, bar.x + (bar.width - fm.stringWidth(text)) / 2,
				bar.y + (bar.height + fm.getAscent() - fm.getDescent()) / 2, color);
		}
		else
		{
			g.setColor(color);
			g.drawString(text, bar.x + bar.width + BAR_TEXT_GAP, besideBaseline);
		}
	}

	/**
	 * White text with a solid black outline on all eight sides. HP text centred on the bar straddles a green fill
	 * and a pale translucent track (and, at 0% background opacity, whatever the game is showing), so there is no one
	 * background to pick a text colour for. The outline gives every glyph pixel a black neighbour instead, whatever
	 * is behind it; RuneLite's own overlay text does the same job with a black drop shadow. The outline is not
	 * faded with the stale style: its job is legibility, and half-alpha copies drawn eight times would compound into
	 * an uneven smear anyway. Only the white fades, so a stale number reads dimmer and stays legible.
	 */
	private static void drawOutlined(Graphics2D g, String text, int x, int y, Color color)
	{
		g.setColor(Color.BLACK);
		for (int dx = -OUTLINE; dx <= OUTLINE; dx++)
		{
			for (int dy = -OUTLINE; dy <= OUTLINE; dy++)
			{
				if (dx != 0 || dy != 0)
				{
					g.drawString(text, x + dx, y + dy);
				}
			}
		}
		g.setColor(color);
		g.drawString(text, x, y);
	}

	private static void drawBar(Graphics2D g, TargetSnapshot s, Rectangle bar, boolean fade, boolean hollow)
	{
		int fill = barFill(s, bar.width);
		g.setColor(BAR_BG);
		g.fillRect(bar.x, bar.y, bar.width, bar.height);
		if (hollow)
		{
			// Last known level, outline only: it must not read as a live bar.
			if (fill > 0)
			{
				g.setColor(BAR_FG);
				g.drawRect(bar.x, bar.y, fill - 1, bar.height - 1);
			}
		}
		else
		{
			g.setColor(fade ? faded(BAR_FG) : BAR_FG);
			g.fillRect(bar.x, bar.y, fill, bar.height);
		}
	}

	private static Color faded(Color c)
	{
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(c.getAlpha() / 2f));
	}

	/**
	 * The geometry for these cells. {@code cells} must start with the name cell, as {@link #cells} always does.
	 *
	 * <p>One line: every cell in a row, the bar centred in the row. Stacked: the first line is the cells without the
	 * HP cell; below it, after {@link #ROW_GAP}, is the bar line. The frame is as wide as the wider line and as tall
	 * as both together, and the bar is stretched so that its line (bar plus any beside text) is as wide as the first
	 * line, which keeps the panel from being a long name line over a short stub of bar.
	 *
	 * <p>Text inside the bar widens the bar to fit ({@link #INSIDE_PAD} clear on each side) instead of falling back
	 * to beside or being clipped, so the setting is honoured and no digit is lost; the bar is also made tall enough
	 * for the font. Only inside text does either, so the widths do not depend on the HP text when it is beside.
	 */
	static Frame frame(List<Cell> cells, FoeConfig.Layout layout, FontMetrics fm)
	{
		boolean stacked = layout == FoeConfig.Layout.STACKED;
		Cell hp = null;
		List<Cell> line = new ArrayList<>(cells.size());
		for (Cell c : cells)
		{
			if (c.hp)
			{
				hp = c;
			}
			if (!(stacked && c.hp))
			{
				line.add(c);
			}
		}
		int barH = hp == null ? 0 : barHeight(hp, fm);
		int natural = hp == null ? 0 : hp.inside ? Math.max(BAR_W, fm.stringWidth(hp.text) + 2 * INSIDE_PAD) : BAR_W;
		int beside = hp == null || hp.inside || hp.text.isEmpty() ? 0 : BAR_TEXT_GAP + fm.stringWidth(hp.text);

		int[] x = new int[line.size()];
		int cursor = PAD;
		int hpIndex = -1;
		for (int i = 0; i < line.size(); i++)
		{
			Cell c = line.get(i);
			x[i] = cursor;
			if (c.hp)
			{
				hpIndex = i;
			}
			cursor += (c.hp ? natural + beside : fm.stringWidth(c.text)) + GAP;
		}
		int lineW = cursor - GAP + PAD;
		int lineH = Math.max(fm.getHeight(), stacked ? 0 : barH);

		if (!stacked)
		{
			Rectangle bar = hp == null ? null : new Rectangle(x[hpIndex], PAD + (lineH - barH) / 2, natural, barH);
			return new Frame(lineW, lineH + PAD * 2, line, x, lineH, hp, bar, PAD, lineH);
		}
		if (hp == null)
		{
			return new Frame(lineW, lineH + PAD * 2, line, x, lineH, null, null, PAD, lineH);
		}
		int width = Math.max(lineW, PAD * 2 + natural + beside);
		int hpH = Math.max(barH, beside > 0 ? fm.getHeight() : 0);
		int hpTop = PAD + lineH + ROW_GAP;
		Rectangle bar = new Rectangle(PAD, hpTop + (hpH - barH) / 2, width - PAD * 2 - beside, barH);
		return new Frame(width, hpTop + hpH + PAD, line, x, lineH, hp, bar, hpTop, hpH);
	}

	/** The standard height, or tall enough that the font's glyphs and their outline sit inside the bar. */
	private static int barHeight(Cell hp, FontMetrics fm)
	{
		return hp.inside ? Math.max(BAR_H, fm.getAscent() + 2 * OUTLINE) : BAR_H;
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

	/**
	 * Filled pixels of a bar {@code barWidth} wide: 0 with no bar, at least 1 while the monster is alive, never more
	 * than {@code barWidth}, and never all of it unless the bar is full.
	 */
	static int barFill(TargetSnapshot s, int barWidth)
	{
		if (!hasBar(s))
		{
			return 0;
		}
		int fill = Math.round(barWidth * (float) s.getHpRatio() / s.getHpScale());
		// Rounding must not claim empty or full when the bar is neither (a 1/255 or 254/255 bar on 80px).
		return partial(s) ? Math.max(1, Math.min(barWidth - 1, fill)) : fill;
	}

	/** Alive and not at full health, by the bar itself. */
	private static boolean partial(TargetSnapshot s)
	{
		return s.getHpRatio() > 0 && s.getHpRatio() < s.getHpScale();
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

	/** The cells in order: name, HP, levels, weakness. */
	static List<Cell> cells(TargetSnapshot s, FoeConfig c)
	{
		List<Cell> out = new ArrayList<>(4);
		// The combat level is always shown beside the name when known (0 is unknown), whatever the detail setting.
		out.add(new Cell(s.getCombatLevel() > 0 ? s.getName() + "  " + s.getCombatLevel() : s.getName(), false, false));
		if (hasBar(s))
		{
			String hp = hpText(s, c);
			if (hp.isEmpty() && s.isHpStale() && c.staleHpStyle() == FoeConfig.StaleHpStyle.MARKER)
			{
				// HP text None has no text to append "?" to, and a stale bar must not read as live.
				hp = "?";
			}
			out.add(new Cell(hp, true, !hp.isEmpty() && c.hpTextPosition() == FoeConfig.HpTextPosition.INSIDE));
		}
		if (c.detail() == FoeConfig.Detail.FULL)
		{
			addIfAny(out, levels(s));
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
			out.add(new Cell(text, false, false));
		}
	}

	/**
	 * HP text per the HP text setting, or "" when there is nothing to say: None, max HP unknown, or no bar. No "~":
	 * the bar already says the value is approximate. A stale value under the MARKER style gets a trailing "?".
	 */
	static String hpText(TargetSnapshot s, FoeConfig c)
	{
		if (!hasBar(s) || s.getMaxHp() <= 0 || s.getHp() < 0)
		{
			return "";
		}
		String text;
		switch (c.hpText())
		{
			case CURRENT_MAX:
				text = s.getHp() + "/" + s.getMaxHp();
				break;
			case CURRENT:
				text = String.valueOf(s.getHp());
				break;
			case PERCENT:
				int pct = Math.round(100f * s.getHp() / s.getMaxHp());
				text = (partial(s) ? Math.max(1, Math.min(99, pct)) : pct) + "%";
				break;
			default:
				return "";
		}
		return s.isHpStale() && c.staleHpStyle() == FoeConfig.StaleHpStyle.MARKER ? text + "?" : text;
	}

	private static String levels(TargetSnapshot s)
	{
		// A level of 1 or less is never drawn: a monster that never uses Ranged or Magic shows neither, and a 0 is
		// unknown. (Addendum 3: "hide irrelevant levels" is always on, so there is no setting for it.)
		List<String> parts = new ArrayList<>(5);
		level(parts, "Att", s.getAttack());
		level(parts, "Str", s.getStrength());
		level(parts, "Def", s.getDefence());
		level(parts, "Rng", s.getRanged());
		level(parts, "Mag", s.getMagic());
		return String.join("  ", parts);
	}

	private static void level(List<String> parts, String label, int v)
	{
		if (v > 1)
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
