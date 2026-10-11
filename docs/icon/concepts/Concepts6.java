// Foe icon, round 2 final pass: horns point UP, apart from a small helm cap. On the busts they break out over the
// ring with a 1px tile-coloured knockout so the white horn never merges into the white ring.
// Run: javac -d /tmp/foeicon *.java && java -cp /tmp/foeicon Concepts6 .
import java.awt.*; import java.awt.geom.*;
public class Concepts6 {
  static final Color TILE = Concepts.TILE, WHITE = Concepts.WHITE, GREEN = Concepts.GREEN, GLASS = Concepts4.GLASS;
  static Path2D tri(double x1, double y1, double x2, double y2, double x3, double y3) { return Concepts4.tri(x1, y1, x2, y2, x3, y3); }

  /** An upright horn: base from x0 to x1 at y, curving out a little and up to a tip. side = -1 left, +1 right. */
  static Shape horn(double side, double x0, double x1, double y, double tipX, double tipY) {
    Path2D p = new Path2D.Double();
    p.moveTo(side * x0, y);
    p.quadTo(side * (x0 + (tipX - x0) * 0.2), y - (y - tipY) * 0.6, side * tipX, tipY);
    p.quadTo(side * (x1 + (tipX - x1) * 0.9), y - (y - tipY) * 0.35, side * x1, y);
    p.closePath();
    return p;
  }

  static void goblin(Graphics2D g, double ox, double oy, double s) {
    AffineTransform t = g.getTransform();
    g.translate(ox, oy); g.scale(s, s);
    g.setColor(WHITE);
    g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    g.draw(new Line2D.Double(-7.2, 2.5, -8.6, -6.0));
    g.fill(new Ellipse2D.Double(-10.4, -10.2, 3.8, 4.8));
    g.setColor(GREEN);
    g.fill(new Rectangle2D.Double(-4.3, 4.0, 3.0, 5.4));
    g.fill(new Rectangle2D.Double(1.3, 4.0, 3.0, 5.4));
    g.fill(new RoundRectangle2D.Double(-5.8, 8.4, 4.5, 1.9, 1.2, 1.2));
    g.fill(new RoundRectangle2D.Double(1.3, 8.4, 4.5, 1.9, 1.2, 1.2));
    g.fill(new RoundRectangle2D.Double(-5.2, -3.2, 10.4, 8.6, 3.4, 3.4));
    g.fill(new Ellipse2D.Double(-8.8, 0.2, 3.4, 3.4));
    g.fill(new Ellipse2D.Double(-3.8, -9.0, 7.6, 7.0));
    g.fill(tri(-3.4, -6.2, -6.6, -7.4, -3.2, -4.2));
    g.fill(tri(3.4, -6.2, 6.6, -7.4, 3.2, -4.2));
    g.setColor(GLASS);
    g.fill(tri(-2.8, -6.2, -0.4, -5.1, -2.5, -4.4));
    g.fill(tri(2.8, -6.2, 0.4, -5.1, 2.5, -4.4));
    g.setColor(WHITE);
    g.fill(new Arc2D.Double(-3.4, -10.2, 6.8, 4.6, 0, 180, Arc2D.CHORD));      // a small cap
    g.fill(horn(-1, 1.6, 3.4, -8.4, 4.6, -13.6));
    g.fill(horn(1, 1.6, 3.4, -8.4, 4.6, -13.6));
    g.fill(new Ellipse2D.Double(2.4, -2.6, 8.4, 8.4));
    g.setColor(GLASS);
    g.setStroke(new BasicStroke(0.8f));
    g.draw(new Ellipse2D.Double(4.0, -1.0, 5.2, 5.2));
    g.setTransform(t);
  }

  /** Bust face (clipped to the glass by the caller); horns are drawn separately so they can break the ring. */
  static void bustFace(Graphics2D g, double ox, double oy, double s) {
    AffineTransform t = g.getTransform();
    g.translate(ox, oy); g.scale(s, s);
    g.setColor(GREEN);
    g.fill(new RoundRectangle2D.Double(-12, 7.5, 24, 14, 10, 10));
    g.fill(tri(-6.0, -1.8, -12.0, -5.2, -5.8, 2.6));
    g.fill(tri(6.0, -1.8, 12.0, -5.2, 5.8, 2.6));
    g.fill(new Ellipse2D.Double(-6.9, -6.8, 13.8, 15.2));
    g.setColor(GLASS);
    g.fill(tri(-5.6, -2.2, -0.8, 0.2, -5.0, 1.2));
    g.fill(tri(5.6, -2.2, 0.8, 0.2, 5.0, 1.2));
    g.fill(new RoundRectangle2D.Double(-4.4, 3.4, 8.8, 2.8, 1.8, 1.8));
    g.setColor(WHITE);
    g.fill(tri(-3.6, 3.4, -1.4, 3.4, -2.5, 6.6));
    g.fill(tri(3.6, 3.4, 1.4, 3.4, 2.5, 6.6));
    g.fill(new Arc2D.Double(-6.0, -8.6, 12.0, 6.4, 0, 180, Arc2D.CHORD));        // cap
    g.setTransform(t);
  }

  /** Bust horns, over everything, with a knockout outline in the tile colour. */
  static void bustHorns(Graphics2D g, double ox, double oy, double s) {
    AffineTransform t = g.getTransform();
    g.translate(ox, oy); g.scale(s, s);
    for (int side : new int[]{-1, 1}) {
      Shape h = horn(side, 2.4, 5.6, -6.0, 9.4, -17.0);
      g.setColor(TILE); g.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.draw(h);
      g.setColor(WHITE); g.fill(h);
    }
    g.setTransform(t);
  }

  static Shape inLens(Graphics2D g, double cx, double cy, double r, double stroke) {
    Shape inner = new Ellipse2D.Double(cx - r + stroke / 2, cy - r + stroke / 2, 2 * r - stroke, 2 * r - stroke);
    g.setColor(GLASS); g.fill(inner); return inner;
  }

  static void bustInLens(Graphics2D g, double cx, double cy, double r, double stroke, double s, double dy) {
    Shape glass = inLens(g, cx, cy, r, stroke);
    Shape c = g.getClip(); g.clip(glass); bustFace(g, cx, cy + dy, s); g.setClip(c);
    Concepts4.ring(g, cx, cy, r, stroke);
    bustHorns(g, cx, cy + dy, s);
  }

  public static void main(String[] a) throws Exception {
    Concepts.NAMES_OVERRIDE = new String[]{"F1c Shrink lens", "F2c magnifier", "F3c bust, horns out", "F4c giant peeks", "F5c reticle"};
    Concepts.DRAWS_OVERRIDE = new Concepts.Draw[]{
      g -> { Concepts.tile(g); double cx = 24, cy = 36, r = 14.5;
             Concepts4.spokes(g, cx, cy, r, new double[]{-90, -30, 30, 90, 150, 210}, 1.4, 4.0);
             Shape glass = inLens(g, cx, cy, r, 2.6);
             Shape c = g.getClip(); g.clip(glass); goblin(g, cx + 0.3, cy + 1.8, 0.92); g.setClip(c);
             Concepts4.ring(g, cx, cy, r, 2.6); },
      g -> { Concepts.tile(g); double cx = 22.5, cy = 31.5, r = 13;
             Shape glass = inLens(g, cx, cy, r, 2.8);
             Shape c = g.getClip(); g.clip(glass); goblin(g, cx + 0.3, cy + 1.6, 0.78); g.setClip(c);
             Concepts4.ring(g, cx, cy, r, 2.8);
             double h = Math.toRadians(45), r0 = r + 1.4;
             g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_MITER));
             g.draw(new Line2D.Double(cx + r0 * Math.cos(h), cy + r0 * Math.sin(h), 38.5, 52.5)); },
      g -> { Concepts.tile(g); bustInLens(g, 24, 38, 13.5, 2.6, 0.9, 1.2); },
      g -> { Concepts.tile(g);
             Shape c = g.getClip(); g.clip(Concepts4.tileShape());
             g.setColor(new Color(82, 161, 82, 95));
             g.fill(tri(8.0, 17.5, 20.0, 23.5, 9.5, 25.0));
             g.fill(tri(40.0, 17.5, 28.0, 23.5, 38.5, 25.0));
             g.setClip(c);
             double cx = 24, cy = 41.5, r = 12.8;
             g.setColor(TILE); g.fill(new Ellipse2D.Double(cx - r - 1.6, cy - r - 1.6, 2 * r + 3.2, 2 * r + 3.2));
             Shape glass = inLens(g, cx, cy, r, 2.4);
             g.clip(glass); goblin(g, cx + 0.3, cy + 1.6, 0.82); g.setClip(c);
             Concepts4.ring(g, cx, cy, r, 2.4); },
      g -> { Concepts.tile(g); double cx = 24, cy = 38, r = 13.5;
             bustInLens(g, cx, cy, r, 2.4, 0.9, 1.2);
             g.setColor(WHITE); g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
             for (int d = 0; d < 180; d += 90) {   // east and south only: north and west would collide with the horns' side
               double q = Math.toRadians(d);
               g.draw(new Line2D.Double(cx + (r + 1.2) * Math.cos(q), cy + (r + 1.2) * Math.sin(q), cx + (r + 4.4) * Math.cos(q), cy + (r + 4.4) * Math.sin(q)));
               g.draw(new Line2D.Double(cx - (r + 1.2) * Math.cos(q), cy + (r + 1.2) * Math.sin(q) * (d == 0 ? 1 : 1), cx - (r + 4.4) * Math.cos(q), cy + (r + 4.4) * Math.sin(q)));
             } },
    };
    Concepts.main(new String[]{a[0], "sheet6"});
  }
}
