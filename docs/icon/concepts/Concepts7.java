// Foe icon, round 3 (operator: "more goblin like, no horns"). A bald goblin: long pointed ears, heavy brow, slanted
// eyes, a nose, an underbite with fangs pointing up. The ears take over the horns' job as the silhouette: on the busts
// they break out over the ring, with a 1px tile-coloured knockout so they never merge into it.
// Run: javac -d /tmp/foeicon *.java && java -cp /tmp/foeicon Concepts7 .
import java.awt.*; import java.awt.geom.*;
public class Concepts7 {
  static final Color TILE = Concepts.TILE, WHITE = Concepts.WHITE, GREEN = Concepts.GREEN, GLASS = Concepts4.GLASS;
  static Path2D tri(double x1, double y1, double x2, double y2, double x3, double y3) { return Concepts4.tri(x1, y1, x2, y2, x3, y3); }

  /** A long goblin ear: base on the side of the head, sweeping out and a little up to a point. side = -1 / +1. */
  static Shape ear(double side, double baseX, double baseTop, double baseBottom, double tipX, double tipY) {
    Path2D p = new Path2D.Double();
    p.moveTo(side * baseX, baseTop);
    p.quadTo(side * (baseX + (tipX - baseX) * 0.55), baseTop - 1.2, side * tipX, tipY);
    p.quadTo(side * (baseX + (tipX - baseX) * 0.45), baseBottom - 0.4, side * baseX, baseBottom);
    p.closePath();
    return p;
  }

  /** The face (clipped to the glass by the caller). eyes = the eye colour: GLASS for cut, WHITE for glowing. */
  static void face(Graphics2D g, double ox, double oy, double s, Color eyes) {
    AffineTransform t = g.getTransform();
    g.translate(ox, oy); g.scale(s, s);
    g.setColor(GREEN);
    g.fill(new RoundRectangle2D.Double(-12, 8.0, 24, 14, 10, 10));             // shoulders
    g.fill(new Rectangle2D.Double(-3.2, 5.5, 6.4, 4));                         // neck
    g.fill(new Ellipse2D.Double(-7.0, -8.6, 14.0, 15.6));                       // bald head
    g.fill(new Ellipse2D.Double(-6.2, -1.0, 12.4, 8.4));                        // jaw, wide
    // eyes under a heavy brow: slanted slits, inner corners low
    g.setColor(eyes);
    g.fill(tri(-6.0, -4.6, -0.8, -2.0, -5.0, -0.6));
    g.fill(tri(6.0, -4.6, 0.8, -2.0, 5.0, -0.6));
    // brow: a notch of glass where the brows meet, so they read as frowning
    g.setColor(GLASS);
    g.fill(tri(-1.0, -4.4, 1.0, -4.4, 0.0, -2.6));
    // nose: two nostrils under the brow's meeting point
    g.setColor(GLASS);
    g.fill(new Ellipse2D.Double(-1.6, 1.4, 1.2, 1.0));
    g.fill(new Ellipse2D.Double(0.4, 1.4, 1.2, 1.0));
    // underbite: a wide mouth, fangs pointing UP from the lower jaw
    g.fill(new RoundRectangle2D.Double(-4.6, 3.2, 9.2, 2.6, 2.0, 2.0));
    g.setColor(WHITE);
    g.fill(tri(-3.6, 5.8, -1.8, 5.8, -2.7, 2.8));
    g.fill(tri(3.6, 5.8, 1.8, 5.8, 2.7, 2.8));
    g.setTransform(t);
  }

  /** Ears over everything, knockout first. */
  static void ears(Graphics2D g, double ox, double oy, double s, double tipX, double tipY) {
    AffineTransform t = g.getTransform();
    g.translate(ox, oy); g.scale(s, s);
    for (int side : new int[]{-1, 1}) {
      Shape e = ear(side, 5.8, -3.6, 1.4, tipX, tipY);
      g.setColor(TILE); g.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.draw(e);
      g.setColor(GREEN); g.fill(e);
    }
    g.setTransform(t);
  }

  static Shape inLens(Graphics2D g, double cx, double cy, double r, double stroke) {
    Shape inner = new Ellipse2D.Double(cx - r + stroke / 2, cy - r + stroke / 2, 2 * r - stroke, 2 * r - stroke);
    g.setColor(GLASS); g.fill(inner); return inner;
  }

  static void faceInLens(Graphics2D g, double cx, double cy, double r, double stroke, double s, double dy, Color eyes,
                         double tipX, double tipY) {
    Shape glass = inLens(g, cx, cy, r, stroke);
    Shape c = g.getClip(); g.clip(glass); face(g, cx, cy + dy, s, eyes); g.setClip(c);
    Concepts4.ring(g, cx, cy, r, stroke);
    ears(g, cx, cy + dy, s, tipX, tipY);
  }

  /** The full goblin, bald: big ears, club, shield, no helm. Origin mid-body, about 21 units tall. */
  static void body(Graphics2D g, double ox, double oy, double s) {
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
    g.fill(new Ellipse2D.Double(-3.9, -10.6, 7.8, 8.6));                       // bald head
    g.fill(ear(-1, 3.2, -8.0, -5.4, 8.2, -10.6));                              // long ears
    g.fill(ear(1, 3.2, -8.0, -5.4, 8.2, -10.6));
    g.setColor(GLASS);
    g.fill(tri(-2.9, -7.4, -0.4, -6.2, -2.6, -5.5));
    g.fill(tri(2.9, -7.4, 0.4, -6.2, 2.6, -5.5));
    g.fill(new RoundRectangle2D.Double(-2.2, -4.4, 4.4, 1.3, 0.9, 0.9));        // mouth
    g.setColor(WHITE);
    g.fill(new Ellipse2D.Double(2.4, -2.6, 8.4, 8.4));                          // shield
    g.setColor(GLASS);
    g.setStroke(new BasicStroke(0.8f));
    g.draw(new Ellipse2D.Double(4.0, -1.0, 5.2, 5.2));
    g.setTransform(t);
  }

  static void handle(Graphics2D g, double cx, double cy, double r) {
    double h = Math.toRadians(45), r0 = r + 1.4;
    g.setColor(WHITE);
    g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_MITER));
    g.draw(new Line2D.Double(cx + r0 * Math.cos(h), cy + r0 * Math.sin(h), 38.5, 52.5));
  }

  public static void main(String[] a) throws Exception {
    Concepts.NAMES_OVERRIDE = new String[]{"G1 face, ears out", "G2 glowing eyes", "G3 face, magnifier", "G4 body, magnifier", "G5 reticle"};
    Concepts.DRAWS_OVERRIDE = new Concepts.Draw[]{
      g -> { Concepts.tile(g); faceInLens(g, 24, 37, 13.0, 2.6, 0.92, 0.8, GLASS, 17.0, -9.0); },
      g -> { Concepts.tile(g); faceInLens(g, 24, 37, 13.0, 2.6, 0.92, 0.8, WHITE, 17.0, -9.0); },
      g -> { Concepts.tile(g); double cx = 23, cy = 32, r = 12.0;
             faceInLens(g, cx, cy, r, 2.8, 0.84, 0.8, GLASS, 16.5, -8.0); handle(g, cx, cy, r); },
      g -> { Concepts.tile(g); double cx = 22.5, cy = 31.5, r = 13;
             Shape glass = inLens(g, cx, cy, r, 2.8);
             Shape c = g.getClip(); g.clip(glass); body(g, cx + 0.3, cy + 1.2, 0.8); g.setClip(c);
             Concepts4.ring(g, cx, cy, r, 2.8); handle(g, cx, cy, r); },
      g -> { Concepts.tile(g); double cx = 24, cy = 37, r = 13.0;
             faceInLens(g, cx, cy, r, 2.4, 0.92, 0.8, GLASS, 17.0, -9.0);
             g.setColor(WHITE); g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
             g.draw(new Line2D.Double(cx, cy - r - 1.2, cx, cy - r - 4.4));
             g.draw(new Line2D.Double(cx, cy + r + 1.2, cx, cy + r + 4.4)); },
    };
    Concepts.main(new String[]{a[0], "sheet7"});
  }
}
