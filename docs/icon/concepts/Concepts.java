// Foe icon concepts: five reductions of "goblin under a magnifying glass" on the Wayfarer/Quoth tile.
// Sheet: each concept at 4x and 1x, on RuneLite's dark panel and on light.
import java.awt.*; import java.awt.geom.*; import java.awt.image.*; import javax.imageio.*; import java.io.*;
public class Concepts {
  static final Color TILE = new Color(0x35, 0x32, 0x2D);
  static final Color WHITE = new Color(0xF2, 0xF0, 0xEB);
  static final Color GREEN = new Color(82, 161, 82);        // FoeOverlay.BAR_FG: the HP bar, and a goblin
  static final int W = 48, H = 72, K = 8;
  static final double CX = 22, CY = 33, R = 11.5;            // lens centre and radius (stroke centre)

  interface Draw { void on(Graphics2D g); }

  static void tile(Graphics2D g) { g.setColor(TILE); g.fill(new RoundRectangle2D.Double(4, 12, 40, 48, 16, 16)); }

  static void lens(Graphics2D g) {
    g.setColor(WHITE);
    g.setStroke(new BasicStroke(3f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
    g.draw(new Ellipse2D.Double(CX - R, CY - R, 2 * R, 2 * R));
    double a = Math.toRadians(45), r0 = R + 1.5;            // handle leaves the ring at 45 degrees, down-right
    g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_MITER));
    g.draw(new Line2D.Double(CX + r0 * Math.cos(a), CY + r0 * Math.sin(a), 37.5, 51.5));
  }

  static Shape glass() { return new Ellipse2D.Double(CX - R + 1.5, CY - R + 1.5, 2 * R - 3, 2 * R - 3); }

  /** A goblin head: round skull, two ears pointing out and slightly up. */
  static Area head(double cx, double cy, double s) {
    Area a = new Area(new Ellipse2D.Double(cx - 5.2 * s, cy - 5 * s, 10.4 * s, 10.5 * s));
    Path2D l = new Path2D.Double(); l.moveTo(cx - 4.2 * s, cy - 2.2 * s); l.lineTo(cx - 10 * s, cy - 4.6 * s); l.lineTo(cx - 4.4 * s, cy + 1.4 * s); l.closePath();
    Path2D r = new Path2D.Double(); r.moveTo(cx + 4.2 * s, cy - 2.2 * s); r.lineTo(cx + 10 * s, cy - 4.6 * s); r.lineTo(cx + 4.4 * s, cy + 1.4 * s); r.closePath();
    a.add(new Area(l)); a.add(new Area(r));
    return a;
  }

  /** Two angry eyes: slanted slits, inner corners low. */
  static Area eyes(double cx, double cy, double s) {
    Path2D l = new Path2D.Double(); l.moveTo(cx - 4.6 * s, cy - 1.6 * s); l.lineTo(cx - 0.9 * s, cy + 0.2 * s); l.lineTo(cx - 1.2 * s, cy + 1.6 * s); l.lineTo(cx - 4.4 * s, cy + 0.6 * s); l.closePath();
    Path2D r = new Path2D.Double(); r.moveTo(cx + 4.6 * s, cy - 1.6 * s); r.lineTo(cx + 0.9 * s, cy + 0.2 * s); r.lineTo(cx + 1.2 * s, cy + 1.6 * s); r.lineTo(cx + 4.4 * s, cy + 0.6 * s); r.closePath();
    Area a = new Area(l); a.add(new Area(r)); return a;
  }

  static final String[] NAMES = {"A goblin in the lens", "B horned helm", "C HP bar", "D eyes only", "E lens is the face"};
  static final Draw[] DRAWS = {
    g -> { tile(g); Area h = head(CX, CY + 0.8, 0.95); h.intersect(new Area(glass())); g.setColor(GREEN); g.fill(h);
           Area e = eyes(CX, CY + 0.6, 0.62); g.setColor(TILE); g.fill(e); lens(g); },
    g -> { tile(g); Area dome = new Area(new Arc2D.Double(CX - 7, CY - 5, 14, 14, 0, 180, Arc2D.CHORD));
           dome.add(new Area(new Rectangle2D.Double(CX - 7, CY + 2, 14, 2.2)));
           g.setColor(GREEN); g.fill(dome);
           Path2D hl = new Path2D.Double(); hl.moveTo(CX - 6, CY - 0.5); hl.lineTo(CX - 8.6, CY - 7.5); hl.lineTo(CX - 3.6, CY - 3.5); hl.closePath();
           Path2D hr = new Path2D.Double(); hr.moveTo(CX + 6, CY - 0.5); hr.lineTo(CX + 8.6, CY - 7.5); hr.lineTo(CX + 3.6, CY - 3.5); hr.closePath();
           g.setColor(WHITE); g.fill(hl); g.fill(hr);
           g.setColor(TILE); g.fill(new Rectangle2D.Double(CX - 4.5, CY + 4.2, 9, 1.4)); lens(g); },
    g -> { tile(g); g.setColor(new Color(255, 255, 255, 60)); g.fill(new Rectangle2D.Double(CX - 7, CY - 1.5, 14, 3));
           g.setColor(GREEN); g.fill(new Rectangle2D.Double(CX - 7, CY - 1.5, 8.5, 3)); lens(g); },
    g -> { tile(g); g.setColor(GREEN); g.fill(eyes(CX, CY, 1.25)); lens(g); },
    g -> { tile(g); double s = 0.98;
           Path2D l = new Path2D.Double(); l.moveTo(CX - R + 0.5, CY - 3); l.lineTo(CX - R - 6.5, CY - 7.5); l.lineTo(CX - R + 0.2, CY + 2.5); l.closePath();
           Path2D r = new Path2D.Double(); r.moveTo(CX + R - 0.5, CY - 3); r.lineTo(CX + R + 6.5, CY - 7.5); r.lineTo(CX + R - 0.2, CY + 2.5); r.closePath();
           g.setColor(GREEN); g.fill(l); g.fill(r); g.fill(eyes(CX, CY + 0.2, s)); lens(g); },
  };

  static BufferedImage icon(Draw d) {
    BufferedImage big = new BufferedImage(W * K, H * K, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = big.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    g.scale(K, K); d.on(g); g.dispose();
    BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
    Graphics2D o = out.createGraphics(); o.drawImage(big.getScaledInstance(W, H, Image.SCALE_AREA_AVERAGING), 0, 0, null); o.dispose();
    return out;
  }

  static Draw[] DRAWS_OVERRIDE; static String[] NAMES_OVERRIDE;

  public static void main(String[] a) throws Exception {
    Draw[] DRAWS = DRAWS_OVERRIDE != null ? DRAWS_OVERRIDE : Concepts.DRAWS;
    String[] NAMES = NAMES_OVERRIDE != null ? NAMES_OVERRIDE : Concepts.NAMES;
    String name = a.length > 1 ? a[1] : "sheet";
    int n = DRAWS.length, cell = W * 4 + 30;
    BufferedImage sheet = new BufferedImage(n * cell + 20, (H * 4 + 60) * 2 + 20, BufferedImage.TYPE_INT_RGB);
    Graphics2D s = sheet.createGraphics();
    s.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    String[] bgs = {"1E1E1E", "E6E6E6"};
    for (int b = 0; b < 2; b++) {
      int y0 = 10 + b * (H * 4 + 60);
      Color bg = new Color(Integer.parseInt(bgs[b], 16));
      s.setColor(bg); s.fillRect(0, y0 - 10, sheet.getWidth(), H * 4 + 60);
      for (int i = 0; i < n; i++) {
        BufferedImage ic = icon(DRAWS[i]);
        if (b == 0) ImageIO.write(ic, "png", new File(a[0] + "/" + name + "-" + (char) ('a' + i) + ".png"));
        int x = 10 + i * cell;
        s.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        s.drawImage(ic, x, y0, W * 4, H * 4, null);
        s.drawImage(ic, x + W * 4 - W, y0 + H * 4 - H + 4, null); // 1x, bottom-right corner
        s.setColor(b == 0 ? WHITE : TILE); s.setFont(new Font("SansSerif", Font.PLAIN, 12));
        s.drawString(NAMES[i], x, y0 + H * 4 + 22);
      }
    }
    s.dispose();
    ImageIO.write(sheet, "png", new File(a[0] + "/" + name + ".png"));
  }
}
