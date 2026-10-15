// Foe icon, round 2 refinement: smaller helm domes, longer horns (the signature), bigger eyes and fangs on the bust,
// fewer spokes, and F4 keeps the family tile with only the giant's eyes and horns peeking over the lens.
// Run: javac -d /tmp/foeicon *.java && java -cp /tmp/foeicon Concepts5 .
import java.awt.*; import java.awt.geom.*;
public class Concepts5 {
  static final Color TILE = Concepts.TILE, WHITE = Concepts.WHITE, GREEN = Concepts.GREEN, GLASS = Concepts4.GLASS;

  static Path2D tri(double x1, double y1, double x2, double y2, double x3, double y3) { return Concepts4.tri(x1, y1, x2, y2, x3, y3); }

  /** Horns: a curved blade from the helm's side, sweeping out and up to a point. */
  static Shape horn(double side, double baseX, double baseY, double tipX, double tipY, double w) {
    Path2D p = new Path2D.Double();
    p.moveTo(side * baseX, baseY);
    p.quadTo(side * (tipX + 0.6), baseY - 0.4, side * tipX, tipY);
    p.quadTo(side * (tipX - 1.4), baseY - w, side * (baseX - w * 0.6), baseY - w);
    p.closePath();
    return p;
  }

  /** The full goblin, refined: a low helm, long horns, the rest as round 2. Origin mid-body, about 24 units tall. */
  static void goblin(Graphics2D g, double ox, double oy, double s, Color flesh, Color gear, Color cut) {
    AffineTransform t = g.getTransform();
    g.translate(ox, oy); g.scale(s, s);
    g.setColor(gear);
    g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    g.draw(new Line2D.Double(-7.2, 2.5, -8.6, -6.6));
    g.fill(new Ellipse2D.Double(-10.4, -10.8, 3.8, 4.8));
    g.setColor(flesh);
    g.fill(new Rectangle2D.Double(-4.3, 4.0, 3.0, 5.4));
    g.fill(new Rectangle2D.Double(1.3, 4.0, 3.0, 5.4));
    g.fill(new RoundRectangle2D.Double(-5.8, 8.4, 4.5, 1.9, 1.2, 1.2));
    g.fill(new RoundRectangle2D.Double(1.3, 8.4, 4.5, 1.9, 1.2, 1.2));
    g.fill(new RoundRectangle2D.Double(-5.2, -3.2, 10.4, 8.6, 3.4, 3.4));
    g.fill(new Ellipse2D.Double(-8.8, 0.2, 3.4, 3.4));
    g.fill(new Ellipse2D.Double(-3.8, -9.0, 7.6, 7.0));
    g.fill(tri(-3.4, -6.4, -6.4, -8.0, -3.2, -4.4));   // ears
    g.fill(tri(3.4, -6.4, 6.4, -8.0, 3.2, -4.4));
    g.setColor(cut);
    g.fill(tri(-2.8, -6.4, -0.4, -5.3, -2.5, -4.6));
    g.fill(tri(2.8, -6.4, 0.4, -5.3, 2.5, -4.6));
    g.setColor(gear);
    g.fill(new Arc2D.Double(-3.9, -10.4, 7.8, 5.2, 0, 180, Arc2D.CHORD));
    g.fill(horn(-1, 3.0, -8.4, 7.4, -14.2, 1.6));
    g.fill(horn(1, 3.0, -8.4, 7.4, -14.2, 1.6));
    g.fill(new Ellipse2D.Double(2.4, -2.6, 8.4, 8.4));
    g.setColor(cut);
    g.setStroke(new BasicStroke(0.8f));
    g.draw(new Ellipse2D.Double(4.0, -1.0, 5.2, 5.2));
    g.setTransform(t);
  }

  /** The bust, refined: low helm with long horns, big slanted eyes, wide mouth with two fangs. Origin mid-face. */
  static void bust(Graphics2D g, double ox, double oy, double s, Color flesh, Color gear, Color cut) {
    AffineTransform t = g.getTransform();
    g.translate(ox, oy); g.scale(s, s);
    g.setColor(flesh);
    g.fill(new RoundRectangle2D.Double(-12, 7.5, 24, 14, 10, 10));
    g.fill(tri(-6.0, -1.8, -12.0, -5.2, -5.8, 2.6));
    g.fill(tri(6.0, -1.8, 12.0, -5.2, 5.8, 2.6));
    g.fill(new Ellipse2D.Double(-6.9, -6.8, 13.8, 15.2));
    g.setColor(cut);
    g.fill(tri(-5.6, -2.2, -0.8, 0.2, -5.0, 1.2));
    g.fill(tri(5.6, -2.2, 0.8, 0.2, 5.0, 1.2));
    g.fill(new RoundRectangle2D.Double(-4.4, 3.4, 8.8, 2.8, 1.8, 1.8));
    g.setColor(gear);
    g.fill(tri(-3.6, 3.4, -1.4, 3.4, -2.5, 6.6));
    g.fill(tri(3.6, 3.4, 1.4, 3.4, 2.5, 6.6));
    g.fill(new Arc2D.Double(-7.2, -9.6, 14.4, 8.4, 0, 180, Arc2D.CHORD));
    g.fill(horn(-1, 5.6, -5.6, 12.6, -13.0, 2.4));
    g.fill(horn(1, 5.6, -5.6, 12.6, -13.0, 2.4));
    g.setTransform(t);
  }

  static Shape inLens(Graphics2D g, double cx, double cy, double r, double stroke) {
    Shape inner = new Ellipse2D.Double(cx - r + stroke / 2, cy - r + stroke / 2, 2 * r - stroke, 2 * r - stroke);
    g.setColor(GLASS); g.fill(inner); return inner;
  }

  public static void main(String[] a) throws Exception {
    Concepts.NAMES_OVERRIDE = new String[]{"F1b Shrink lens", "F2b magnifier", "F3b bust", "F4b giant peeks", "F5b reticle"};
    Concepts.DRAWS_OVERRIDE = new Concepts.Draw[]{
      g -> { Concepts.tile(g); double cx = 24, cy = 36, r = 14.5;
             Concepts4.spokes(g, cx, cy, r, new double[]{-90, -30, 30, 90, 150, 210}, 1.4, 4.0);
             Shape glass = inLens(g, cx, cy, r, 2.6);
             Shape c = g.getClip(); g.clip(glass); goblin(g, cx + 0.3, cy + 1.6, 0.92, GREEN, WHITE, GLASS); g.setClip(c);
             Concepts4.ring(g, cx, cy, r, 2.6); },
      g -> { Concepts.tile(g); double cx = 22.5, cy = 31.5, r = 13;
             Shape glass = inLens(g, cx, cy, r, 2.8);
             Shape c = g.getClip(); g.clip(glass); goblin(g, cx + 0.3, cy + 1.4, 0.78, GREEN, WHITE, GLASS); g.setClip(c);
             Concepts4.ring(g, cx, cy, r, 2.8);
             double h = Math.toRadians(45), r0 = r + 1.4;
             g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_MITER));
             g.draw(new Line2D.Double(cx + r0 * Math.cos(h), cy + r0 * Math.sin(h), 38.5, 52.5)); },
      g -> { Concepts.tile(g); double cx = 24, cy = 36, r = 14.5;
             Shape glass = inLens(g, cx, cy, r, 2.6);
             Shape c = g.getClip(); g.clip(glass); bust(g, cx, cy + 0.6, 1.0, GREEN, WHITE, GLASS); g.setClip(c);
             Concepts4.ring(g, cx, cy, r, 2.6); },
      g -> { Concepts.tile(g);
             Shape c = g.getClip(); g.clip(Concepts4.tileShape());
             Color ghost = new Color(82, 161, 82, 95), ghostGear = new Color(242, 240, 235, 70);
             g.setColor(ghost);                                   // the giant's slanted eyes, peeking over the lens
             g.fill(tri(8.0, 17.5, 20.0, 23.5, 9.5, 25.0));
             g.fill(tri(40.0, 17.5, 28.0, 23.5, 38.5, 25.0));
             g.setColor(ghostGear);
             g.fill(horn(-1, -6.0, 16.5, -1.0, 9.0, 3.0));
             g.fill(horn(1, 42.0, 16.5, 49.0, 9.0, 3.0));
             g.setClip(c);
             double cx = 24, cy = 41.5, r = 12.8;
             g.setColor(TILE); g.fill(new Ellipse2D.Double(cx - r - 1.6, cy - r - 1.6, 2 * r + 3.2, 2 * r + 3.2));
             Shape glass = inLens(g, cx, cy, r, 2.4);
             g.clip(glass); goblin(g, cx + 0.3, cy + 1.4, 0.82, GREEN, WHITE, GLASS); g.setClip(c);
             Concepts4.ring(g, cx, cy, r, 2.4); },
      g -> { Concepts.tile(g); double cx = 24, cy = 36, r = 14.5;
             Shape glass = inLens(g, cx, cy, r, 2.4);
             Shape c = g.getClip(); g.clip(glass); bust(g, cx, cy + 1.0, 0.94, GREEN, WHITE, GLASS); g.setClip(c);
             Concepts4.ring(g, cx, cy, r, 2.4);
             g.setColor(WHITE); g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
             for (int d = 0; d < 360; d += 90) {
               double q = Math.toRadians(d);
               g.draw(new Line2D.Double(cx + (r + 1.2) * Math.cos(q), cy + (r + 1.2) * Math.sin(q), cx + (r + 4.6) * Math.cos(q), cy + (r + 4.6) * Math.sin(q)));
             } },
    };
    Concepts.main(new String[]{a[0], "sheet5"});
  }
}
