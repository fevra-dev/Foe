// Draws icon.png (48x72, the Plugin Hub maximum). Regenerate from the repo root with:
//   java docs/icon/Icon.java icon.png /tmp/foe-icon-preview
//
// Dieter Rams-style badge in the Wayfarer/Quoth system: a flat dark tile, a white magnifier, and one accent.
// The accent is OSRS goblin olive. A goblin's face fills the lens: frowning, an underbite with fangs pointing up,
// and long ears breaking out over the ring with a tile-coloured knockout, so the white ring never swallows them.
// The magnifier says what Foe does: it looks at the monster you're fighting. Chosen 2026-10-10 from four rounds of
// concepts (docs/icon/concepts/, "G3 olive" on sheet10).
import java.awt.*; import java.awt.geom.*; import java.awt.image.*; import javax.imageio.*; import java.io.*;
public class Icon {
  static final Color TILE = new Color(0x35, 0x32, 0x2D);   // the family tile (Wayfarer, Quoth)
  static final Color WHITE = new Color(0xF2, 0xF0, 0xEB);  // Braun white
  static final Color OLIVE = new Color(156, 166, 58);      // OSRS goblin olive
  static final Color GLASS = new Color(0x45, 0x41, 0x3A);  // the lens: a lifted tile tone, not a new hue

  static final double CX = 23, CY = 32, R = 12, STROKE = 2.8;   // the lens: centre, radius (stroke centre), stroke
  static final double FACE_DY = 0.8, FACE_S = 0.84;             // the face: offset below the lens centre, and scale

  static Path2D tri(double x1, double y1, double x2, double y2, double x3, double y3) {
    Path2D p = new Path2D.Double(); p.moveTo(x1, y1); p.lineTo(x2, y2); p.lineTo(x3, y3); p.closePath(); return p;
  }

  /** A long goblin ear: base on the side of the head, sweeping out and a little up to a point. side = -1 / +1. */
  static Shape ear(double side, double baseX, double baseTop, double baseBottom, double tipX, double tipY) {
    Path2D p = new Path2D.Double();
    p.moveTo(side * baseX, baseTop);
    p.quadTo(side * (baseX + (tipX - baseX) * 0.55), baseTop - 1.2, side * tipX, tipY);
    p.quadTo(side * (baseX + (tipX - baseX) * 0.45), baseBottom - 0.4, side * baseX, baseBottom);
    p.closePath();
    return p;
  }

  /** The face, in face units (origin mid-face); the caller clips it to the glass. */
  static void face(Graphics2D g) {
    g.setColor(OLIVE);
    g.fill(new RoundRectangle2D.Double(-12, 8.0, 24, 14, 10, 10));          // shoulders
    g.fill(new Rectangle2D.Double(-3.2, 5.5, 6.4, 4));                      // neck
    g.fill(new Ellipse2D.Double(-7.0, -8.6, 14.0, 15.6));                   // bald head
    g.fill(new Ellipse2D.Double(-6.2, -1.0, 12.4, 8.4));                    // jaw
    g.setColor(GLASS);
    g.fill(tri(-6.0, -4.6, -0.8, -2.0, -5.0, -0.6));                        // eyes, slanted
    g.fill(tri(6.0, -4.6, 0.8, -2.0, 5.0, -0.6));
    g.fill(tri(-1.0, -4.4, 1.0, -4.4, 0.0, -2.6));                          // the brows' frown
    g.fill(new Ellipse2D.Double(-1.6, 1.4, 1.2, 1.0));                      // nostrils
    g.fill(new Ellipse2D.Double(0.4, 1.4, 1.2, 1.0));
    g.fill(new RoundRectangle2D.Double(-4.6, 3.2, 9.2, 2.6, 2.0, 2.0));     // mouth
    g.setColor(WHITE);
    g.fill(tri(-3.6, 5.8, -1.8, 5.8, -2.7, 2.8));                           // fangs, pointing up
    g.fill(tri(3.6, 5.8, 1.8, 5.8, 2.7, 2.8));
  }

  /** The ears, over the ring: a tile-coloured knockout first, then the olive. */
  static void ears(Graphics2D g) {
    for (int side : new int[]{-1, 1}) {
      Shape e = ear(side, 5.8, -3.6, 1.4, 16.5, -8.0);
      g.setColor(TILE); g.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.draw(e);
      g.setColor(OLIVE); g.fill(e);
    }
  }

  static void draw(Graphics2D g) {
    g.setColor(TILE); g.fill(new RoundRectangle2D.Double(4, 12, 40, 48, 16, 16));
    Shape glass = new Ellipse2D.Double(CX - R + STROKE / 2, CY - R + STROKE / 2, 2 * R - STROKE, 2 * R - STROKE);
    g.setColor(GLASS); g.fill(glass);
    AffineTransform t = g.getTransform();
    Shape clip = g.getClip();
    g.clip(glass);
    g.translate(CX, CY + FACE_DY); g.scale(FACE_S, FACE_S); face(g);
    g.setTransform(t); g.setClip(clip);
    g.setColor(WHITE);
    g.setStroke(new BasicStroke((float) STROKE, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
    g.draw(new Ellipse2D.Double(CX - R, CY - R, 2 * R, 2 * R));
    g.translate(CX, CY + FACE_DY); g.scale(FACE_S, FACE_S); ears(g);
    g.setTransform(t);
    double h = Math.toRadians(45), r0 = R + 1.4;                            // the handle, down and right
    g.setColor(WHITE);
    g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_MITER));
    g.draw(new Line2D.Double(CX + r0 * Math.cos(h), CY + r0 * Math.sin(h), 38.5, 52.5));
  }

  public static void main(String[] a) throws Exception {
    int W = 48, H = 72, K = 8;                            // draw at 8x, area-average down
    BufferedImage big = new BufferedImage(W * K, H * K, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = big.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    g.scale(K, K);
    draw(g);
    g.dispose();
    BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
    Graphics2D o = out.createGraphics(); o.drawImage(big.getScaledInstance(W, H, Image.SCALE_AREA_AVERAGING), 0, 0, null); o.dispose();
    ImageIO.write(out, "png", new File(a[0]));
    // previews: 4x and 1x on RuneLite's dark panel and on light
    for (String[] bg : new String[][]{{"dark", "1E1E1E"}, {"light", "E6E6E6"}}) {
      BufferedImage p = new BufferedImage(W * 5 + 30, H * 4 + 20, BufferedImage.TYPE_INT_RGB);
      Graphics2D pg = p.createGraphics(); pg.setColor(new Color(Integer.parseInt(bg[1], 16))); pg.fillRect(0, 0, p.getWidth(), p.getHeight());
      pg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
      pg.drawImage(out, 10, 10, W * 4, H * 4, null); pg.drawImage(out, W * 4 + 20, 10 + H * 3 / 2, W, H, null); pg.dispose();
      ImageIO.write(p, "png", new File(a[1] + "-" + bg[0] + ".png"));
    }
  }
}
