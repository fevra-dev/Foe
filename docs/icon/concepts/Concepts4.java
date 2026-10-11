// Foe icon, round 2: the whole goblin inside the lens, after the "Shrink" card. One rule throughout:
// green is the goblin, white is its gear (helm, horns, club, shield) and the lens. Same tile as Wayfarer/Quoth.
// Run: javac -d /tmp/foeicon *.java && java -cp /tmp/foeicon Concepts4 .
import java.awt.*; import java.awt.geom.*;
public class Concepts4 {
  static final Color TILE = Concepts.TILE, WHITE = Concepts.WHITE, GREEN = Concepts.GREEN;
  /** The glass: a lifted tile tone, so the goblin sits on a lens rather than on the tile. Tone, not a new hue. */
  static final Color GLASS = new Color(0x45, 0x41, 0x3A);
  /** The big goblin behind the lens (F4): the accent at low strength. */
  static final Color GHOST = new Color(82, 161, 82, 70);

  static Shape tileShape() { return new RoundRectangle2D.Double(4, 12, 40, 48, 16, 16); }

  /** A lens: glass disc, white ring. Returns the glass shape (inside the ring) to clip the figure to. */
  static Shape lens(Graphics2D g, double cx, double cy, double r, double stroke, boolean glass) {
    Shape inner = new Ellipse2D.Double(cx - r + stroke / 2, cy - r + stroke / 2, 2 * r - stroke, 2 * r - stroke);
    if (glass) { g.setColor(GLASS); g.fill(inner); }
    return inner;
  }

  static void ring(Graphics2D g, double cx, double cy, double r, double stroke) {
    g.setColor(WHITE);
    g.setStroke(new BasicStroke((float) stroke, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
    g.draw(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
  }

  /** Spokes from just outside the ring to the tile edge (less a margin), like the card's frame. */
  static void spokes(Graphics2D g, double cx, double cy, double r, double[] degrees, double width, double margin) {
    Shape clip = g.getClip();
    g.clip(new RoundRectangle2D.Double(4 + margin, 12 + margin, 40 - 2 * margin, 48 - 2 * margin, 16 - margin, 16 - margin));
    g.setColor(WHITE);
    g.setStroke(new BasicStroke((float) width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
    for (double d : degrees) {
      double a = Math.toRadians(d);
      g.draw(new Line2D.Double(cx + r * Math.cos(a), cy + r * Math.sin(a), cx + 40 * Math.cos(a), cy + 40 * Math.sin(a)));
    }
    g.setClip(clip);
  }

  static Path2D tri(double x1, double y1, double x2, double y2, double x3, double y3) {
    Path2D p = new Path2D.Double(); p.moveTo(x1, y1); p.lineTo(x2, y2); p.lineTo(x3, y3); p.closePath(); return p;
  }

  /**
   * The full goblin, origin at the middle of its body, about 23 units tall at s = 1: horned helm, angry eyes,
   * club raised on its right (our left), round shield on its left. flesh/gear/cut are the three tones.
   */
  static void goblin(Graphics2D g, double ox, double oy, double s, Color flesh, Color gear, Color cut) {
    AffineTransform t = g.getTransform();
    g.translate(ox, oy); g.scale(s, s);
    // club, behind the body: haft and knob
    g.setColor(gear);
    g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    g.draw(new Line2D.Double(-7.2, 2.5, -8.6, -7.2));
    g.fill(new Ellipse2D.Double(-10.4, -11.4, 3.8, 4.8));
    // legs and feet: a wide stance
    g.setColor(flesh);
    g.fill(new Rectangle2D.Double(-4.3, 4.0, 3.0, 5.4));
    g.fill(new Rectangle2D.Double(1.3, 4.0, 3.0, 5.4));
    g.fill(new RoundRectangle2D.Double(-5.8, 8.4, 4.5, 1.9, 1.2, 1.2));
    g.fill(new RoundRectangle2D.Double(1.3, 8.4, 4.5, 1.9, 1.2, 1.2));
    // body, fist on the club
    g.fill(new RoundRectangle2D.Double(-5.2, -3.2, 10.4, 8.6, 3.4, 3.4));
    g.fill(new Ellipse2D.Double(-8.8, 0.2, 3.4, 3.4));
    // head
    g.fill(new Ellipse2D.Double(-3.7, -9.2, 7.4, 6.9));
    // eyes, cut out: slanted, inner corners low
    g.setColor(cut);
    g.fill(tri(-2.6, -6.6, -0.5, -5.6, -2.4, -5.0));
    g.fill(tri(2.6, -6.6, 0.5, -5.6, 2.4, -5.0));
    // helm and horns
    g.setColor(gear);
    g.fill(new Arc2D.Double(-4.3, -11.4, 8.6, 7.6, 0, 180, Arc2D.CHORD));
    g.fill(tri(-3.9, -8.4, -2.4, -10.8, -6.6, -13.6));
    g.fill(tri(3.9, -8.4, 2.4, -10.8, 6.6, -13.6));
    // shield, in front: a disc with a cut ring
    g.fill(new Ellipse2D.Double(2.4, -2.6, 8.4, 8.4));
    g.setColor(cut);
    g.setStroke(new BasicStroke(0.8f));
    g.draw(new Ellipse2D.Double(4.0, -1.0, 5.2, 5.2));
    g.setTransform(t);
  }

  /** A goblin bust: helm, horns, ears, eyes, fangs, shoulders. Origin at the middle of the face. */
  static void bust(Graphics2D g, double ox, double oy, double s, Color flesh, Color gear, Color cut) {
    AffineTransform t = g.getTransform();
    g.translate(ox, oy); g.scale(s, s);
    g.setColor(flesh);
    g.fill(new RoundRectangle2D.Double(-12, 6.5, 24, 14, 10, 10));               // shoulders
    g.fill(tri(-6.2, -2.5, -11.5, -6.5, -6.0, 2.0));                          // ears
    g.fill(tri(6.2, -2.5, 11.5, -6.5, 6.0, 2.0));
    g.fill(new Ellipse2D.Double(-6.8, -7.5, 13.6, 15.5));                        // head
    g.setColor(cut);
    g.fill(tri(-5.0, -2.6, -1.0, -0.8, -4.6, 0.4));                           // eyes
    g.fill(tri(5.0, -2.6, 1.0, -0.8, 4.6, 0.4));
    g.fill(new RoundRectangle2D.Double(-3.8, 2.6, 7.6, 2.6, 1.6, 1.6));         // mouth
    g.setColor(gear);
    g.fill(tri(-3.0, 2.6, -1.2, 2.6, -2.1, 5.0));                             // fangs
    g.fill(tri(3.0, 2.6, 1.2, 2.6, 2.1, 5.0));
    g.fill(new Arc2D.Double(-7.6, -11.5, 15.2, 13.0, 0, 180, Arc2D.CHORD));     // helm
    g.fill(tri(-6.8, -5.4, -4.4, -9.6, -10.4, -13.0));                        // horns
    g.fill(tri(6.8, -5.4, 4.4, -9.6, 10.4, -13.0));
    g.setTransform(t);
  }

  static final double[] SIX = {-90, -30, 30, 90, 150, 210};
  static final double[] WEB = {-112, -68, -22, 22, 68, 112, 158, 202};

  public static void main(String[] a) throws Exception {
    Concepts.NAMES_OVERRIDE = new String[]{"F1 Shrink lens", "F2 magnifier", "F3 bust", "F4 big & small", "F5 reticle"};
    Concepts.DRAWS_OVERRIDE = new Concepts.Draw[]{
      g -> { Concepts.tile(g); double cx = 24, cy = 36, r = 14.5;
             spokes(g, cx, cy, r, WEB, 1.4, 2.5);
             Shape glass = lens(g, cx, cy, r, 2.6, true);
             Shape c = g.getClip(); g.clip(glass); goblin(g, cx + 0.3, cy + 0.8, 0.95, GREEN, WHITE, GLASS); g.setClip(c);
             ring(g, cx, cy, r, 2.6); },
      g -> { Concepts.tile(g); double cx = 22.5, cy = 31.5, r = 13;
             Shape glass = lens(g, cx, cy, r, 2.8, true);
             Shape c = g.getClip(); g.clip(glass); goblin(g, cx + 0.3, cy + 0.6, 0.8, GREEN, WHITE, GLASS); g.setClip(c);
             ring(g, cx, cy, r, 2.8);
             double h = Math.toRadians(45), r0 = r + 1.4;
             g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_MITER));
             g.draw(new Line2D.Double(cx + r0 * Math.cos(h), cy + r0 * Math.sin(h), 38.5, 52.5)); },
      g -> { Concepts.tile(g); double cx = 24, cy = 36, r = 14.5;
             Shape glass = lens(g, cx, cy, r, 2.6, true);
             Shape c = g.getClip(); g.clip(glass); bust(g, cx, cy - 0.5, 1.0, GREEN, WHITE, GLASS); g.setClip(c);
             ring(g, cx, cy, r, 2.6); },
      g -> { Concepts.tile(g);
             Shape c = g.getClip(); g.clip(tileShape());
             bust(g, 24, 22, 2.3, GHOST, new Color(242, 240, 235, 60), TILE);   // the big one, looming
             g.setClip(c);
             double cx = 24, cy = 41, r = 12.5;
             Shape glass = lens(g, cx, cy, r, 2.4, false);
             g.setColor(TILE); g.fill(new Ellipse2D.Double(cx - r - 1.5, cy - r - 1.5, 2 * r + 3, 2 * r + 3));
             g.setColor(GLASS); g.fill(glass);
             g.clip(glass); goblin(g, cx + 0.3, cy + 0.8, 0.82, GREEN, WHITE, GLASS); g.setClip(c);
             ring(g, cx, cy, r, 2.4); },
      g -> { Concepts.tile(g); double cx = 24, cy = 36, r = 14.5;
             Shape glass = lens(g, cx, cy, r, 2.4, true);
             Shape c = g.getClip(); g.clip(glass); bust(g, cx, cy, 0.92, GREEN, WHITE, GLASS); g.setClip(c);
             ring(g, cx, cy, r, 2.4);
             g.setColor(WHITE); g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
             for (int d = 0; d < 360; d += 90) {
               double q = Math.toRadians(d);
               g.draw(new Line2D.Double(cx + (r + 1.2) * Math.cos(q), cy + (r + 1.2) * Math.sin(q), cx + (r + 4.6) * Math.cos(q), cy + (r + 4.6) * Math.sin(q)));
             } },
    };
    Concepts.main(new String[]{a[0], "sheet4"});
  }
}
