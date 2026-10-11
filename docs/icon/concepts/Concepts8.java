// Foe icon, round 4: the actual OSRS goblin (operator's references: the goblin models and its chathead).
// OSRS goblins are a yellow olive, low-poly: a long nose jutting down, a heavy brow over white eyes, ears swept back
// and up, a toothy grin, a hunched body with grey shoulder pads and a spiked mace. Olive is the one accent, in two
// tones for the facets (one hue, so still one accent); white is gear, eyes and teeth; the lens is white as before.
// Run: javac -d /tmp/foeicon *.java && java -cp /tmp/foeicon Concepts8 .
import java.awt.*; import java.awt.geom.*;
public class Concepts8 {
  static final Color TILE = Concepts.TILE, WHITE = Concepts.WHITE, GLASS = Concepts4.GLASS;
  static final Color OLIVE = new Color(156, 166, 58);       // lit facets, from the reference renders
  static final Color OLIVE_DARK = new Color(108, 116, 38);  // shadowed facets

  static Path2D poly(double... xy) {
    Path2D p = new Path2D.Double(); p.moveTo(xy[0], xy[1]);
    for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
    p.closePath(); return p;
  }

  static void knockout(Graphics2D g, Shape s, Color fill) {
    g.setColor(TILE); g.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.draw(s);
    g.setColor(fill); g.fill(s);
  }

  // ---- profile: the chathead, looking right ----

  static Shape profileEar() { return poly(-5.0, -2.0, -13.0, -15.4, -2.0, -7.2); }

  /** The chathead, three-quarter view looking right and down, clipped by the caller. flat = no facets. */
  static void profile(Graphics2D g, double ox, double oy, double s, boolean flat) {
    AffineTransform t = g.getTransform();
    g.translate(ox, oy); g.scale(s, s);
    Color dark = flat ? OLIVE : OLIVE_DARK;
    g.setColor(OLIVE);
    g.fill(poly(-7.0, 2.0, -7.6, -4.0, -4.6, -9.2, 0.6, -10.8, 5.2, -8.8, 7.4, -5.2, 8.4, -3.4,  // skull to brow
                5.8, -2.8, 11.8, 5.8, 6.6, 3.8,                                                     // the long nose
                7.8, 6.6, 4.4, 9.2, -1.0, 8.2, -4.6, 9.8, -8.2, 9.8, -7.0, 2.0));                   // grin, chin, neck
    g.setColor(dark);
    g.fill(poly(2.0, -3.0, 8.4, -3.4, 5.6, -1.6));                                     // under the brow
    g.fill(poly(6.6, 3.8, 11.8, 5.8, 7.2, 0.8));                                       // nose, shadow side
    g.fill(poly(-1.0, 8.2, 4.4, 9.2, 2.4, 6.0, -2.0, 5.0));                            // jaw
    g.fill(poly(-7.0, 2.0, -8.2, 9.8, -4.6, 9.8, -3.4, 4.0));                          // neck
    g.setColor(WHITE);
    g.fill(poly(2.4, -2.6, 6.2, -2.2, 3.4, -1.0));                                     // eye
    g.setColor(GLASS);
    g.fill(poly(3.4, 5.6, 7.6, 6.2, 6.6, 7.6, 3.0, 6.8));                              // grin
    g.setColor(WHITE);
    g.fill(poly(4.2, 5.7, 5.4, 5.9, 4.8, 7.1));                                        // teeth
    g.fill(poly(5.8, 6.0, 6.9, 6.1, 6.3, 7.2));
    g.setTransform(t);
  }

  static void profileEarOver(Graphics2D g, double ox, double oy, double s, boolean flat) {
    AffineTransform t = g.getTransform();
    g.translate(ox, oy); g.scale(s, s);
    knockout(g, profileEar(), OLIVE);
    if (!flat) { g.setColor(OLIVE_DARK); g.fill(poly(-4.4, -2.9, -11.6, -13.4, -2.9, -6.5)); }
    g.setTransform(t);
  }

  // ---- front: the face, looking at you ----

  static Shape frontEar(int side) { return poly(side * 5.6, -3.4, side * 15.0, -13.0, side * 5.0, 1.0); }

  static void front(Graphics2D g, double ox, double oy, double s, boolean flat) {
    AffineTransform t = g.getTransform();
    g.translate(ox, oy); g.scale(s, s);
    Color dark = flat ? OLIVE : OLIVE_DARK;
    g.setColor(OLIVE);
    g.fill(poly(-6.2, -2.0, -5.2, -7.8, 0.0, -11.2, 5.2, -7.8, 6.2, -2.0, 5.6, 3.2, 3.0, 7.6, -3.0, 7.6, -5.6, 3.2));
    g.fill(new RoundRectangle2D.Double(-12, 8.4, 24, 14, 9, 9));                       // shoulders
    g.setColor(dark);
    g.fill(poly(-6.2, -2.0, -5.6, 3.2, -3.0, 7.6, -2.0, 4.0, -4.6, -1.4));            // left cheek, in shadow
    g.fill(poly(0.0, -2.0, -1.5, 4.6, 0.0, 5.4));                                     // nose, shadow side
    g.setColor(flat ? GLASS : OLIVE_DARK);                                            // frowning brows, a V
    g.fill(poly(-6.0, -4.8, -0.3, -2.2, -0.7, -1.3, -5.6, -3.2));
    g.fill(poly(6.0, -4.8, 0.3, -2.2, 0.7, -1.3, 5.6, -3.2));
    g.setColor(WHITE);
    g.fill(poly(-4.8, -2.4, -1.2, -1.4, -4.0, -0.6));                                 // eyes, white
    g.fill(poly(4.8, -2.4, 1.2, -1.4, 4.0, -0.6));
    g.setColor(OLIVE);
    g.fill(poly(0.0, -2.0, 1.5, 4.6, 0.0, 5.4));                                      // nose, lit side
    g.setColor(GLASS);
    g.fill(poly(-4.4, 4.6, 4.4, 4.6, 3.0, 6.6, -3.0, 6.6));                           // grin
    g.setColor(WHITE);
    for (double x = -3.2; x <= 3.3; x += 1.6) g.fill(poly(x - 0.55, 4.7, x + 0.55, 4.7, x, 5.9));   // teeth
    g.setTransform(t);
  }

  static void frontEarsOver(Graphics2D g, double ox, double oy, double s, boolean flat) {
    AffineTransform t = g.getTransform();
    g.translate(ox, oy); g.scale(s, s);
    for (int side : new int[]{-1, 1}) {
      knockout(g, frontEar(side), OLIVE);
      if (!flat) { g.setColor(OLIVE_DARK); g.fill(poly(side * 5.6, -1.0, side * 15.0, -13.0, side * 5.0, 1.0)); }
    }
    g.setTransform(t);
  }

  // ---- body: hunched, long arms, shoulder pads, spiked mace ----

  static void body(Graphics2D g, double ox, double oy, double s) {
    AffineTransform t = g.getTransform();
    g.translate(ox, oy); g.scale(s, s);
    g.setColor(OLIVE_DARK);
    g.fill(poly(-4.6, 3.0, -7.0, 8.0, -5.6, 11.0, -3.4, 11.0, -2.6, 6.0, -1.2, 4.0));    // legs, crouched
    g.fill(poly(4.6, 3.0, 7.0, 8.0, 5.6, 11.0, 3.4, 11.0, 2.6, 6.0, 1.2, 4.0));
    g.setColor(OLIVE);
    g.fill(poly(-8.6, 11.0, -3.4, 11.0, -3.6, 12.4, -9.6, 12.4));                      // big pointed feet
    g.fill(poly(3.4, 11.0, 8.6, 11.0, 9.6, 12.4, 3.6, 12.4));
    g.fill(poly(-5.0, -5.0, 5.0, -5.0, 4.4, 3.6, -4.4, 3.6));                          // torso
    g.fill(poly(-6.6, -3.6, -9.0, 4.0, -8.4, 7.6, -6.8, 7.6, -6.6, 3.4, -4.6, -2.0));  // long arms
    g.fill(poly(6.6, -3.6, 9.0, 4.0, 8.4, 7.6, 6.8, 7.6, 6.6, 3.4, 4.6, -2.0));
    g.setColor(OLIVE_DARK);
    g.fill(poly(-3.4, 2.6, 3.4, 2.6, 2.6, 7.0, -2.6, 7.0));                            // loincloth
    g.setColor(OLIVE);                                                                  // head, low and forward
    g.fill(poly(-3.0, -5.2, -3.4, -9.6, 0.0, -11.4, 3.4, -9.6, 3.0, -5.2, 0.0, -2.8));
    g.fill(poly(-3.0, -8.4, -8.6, -13.6, -2.4, -6.4));                                 // ears, back and up
    g.fill(poly(3.0, -8.4, 8.6, -13.6, 2.4, -6.4));
    g.setColor(OLIVE_DARK);
    g.fill(poly(0.0, -8.6, -1.0, -4.2, 0.0, -3.4));                                    // nose
    g.setColor(WHITE);
    g.fill(poly(-2.6, -8.2, -0.6, -7.6, -2.2, -7.0));                                  // eyes
    g.fill(poly(2.6, -8.2, 0.6, -7.6, 2.2, -7.0));
    g.fill(poly(-7.8, -5.6, -4.6, -5.6, -4.8, -3.4, -8.2, -3.4));                      // shoulder pads
    g.fill(poly(7.8, -5.6, 4.6, -5.6, 4.8, -3.4, 8.2, -3.4));
    g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); // mace, hanging
    g.draw(new Line2D.Double(-8.0, 7.0, -9.8, 11.6));
    g.fill(poly(-10.4, 2.2, -8.0, 4.6, -9.6, 7.8, -12.0, 5.4));
    g.fill(poly(-11.0, 1.0, -10.0, 3.0, -11.6, 3.0));                                  // its spike
    g.setTransform(t);
  }

  public static void main(String[] a) throws Exception {
    Concepts.NAMES_OVERRIDE = new String[]{"H1b chathead", "H2b front, faceted", "H3b chathead, magnifier", "H4b body, magnifier", "H5b front, flat"};
    Concepts.DRAWS_OVERRIDE = new Concepts.Draw[]{
      g -> { Concepts.tile(g); double cx = 24, cy = 37, r = 13;
             Shape glass = Concepts7.inLens(g, cx, cy, r, 2.6);
             Shape c = g.getClip(); g.clip(glass); profile(g, cx - 0.6, cy + 0.4, 1.0, false); g.setClip(c);
             Concepts4.ring(g, cx, cy, r, 2.6); profileEarOver(g, cx - 0.6, cy + 0.4, 1.0, false); },
      g -> { Concepts.tile(g); double cx = 24, cy = 38, r = 13;
             Shape glass = Concepts7.inLens(g, cx, cy, r, 2.6);
             Shape c = g.getClip(); g.clip(glass); front(g, cx, cy, 0.95, false); g.setClip(c);
             Concepts4.ring(g, cx, cy, r, 2.6); frontEarsOver(g, cx, cy, 0.95, false); },
      g -> { Concepts.tile(g); double cx = 23, cy = 32.5, r = 12;
             Shape glass = Concepts7.inLens(g, cx, cy, r, 2.8);
             Shape c = g.getClip(); g.clip(glass); profile(g, cx - 0.4, cy + 0.4, 0.9, false); g.setClip(c);
             Concepts4.ring(g, cx, cy, r, 2.8); profileEarOver(g, cx - 0.4, cy + 0.4, 0.9, false);
             Concepts7.handle(g, cx, cy, r); },
      g -> { Concepts.tile(g); double cx = 22.5, cy = 31.5, r = 13;
             Shape glass = Concepts7.inLens(g, cx, cy, r, 2.8);
             Shape c = g.getClip(); g.clip(glass); body(g, cx + 0.4, cy + 0.6, 0.86); g.setClip(c);
             Concepts4.ring(g, cx, cy, r, 2.8); Concepts7.handle(g, cx, cy, r); },
      g -> { Concepts.tile(g); double cx = 24, cy = 38, r = 13;
             Shape glass = Concepts7.inLens(g, cx, cy, r, 2.6);
             Shape c = g.getClip(); g.clip(glass); front(g, cx, cy, 0.95, true); g.setClip(c);
             Concepts4.ring(g, cx, cy, r, 2.6); frontEarsOver(g, cx, cy, 0.95, true); },
    };
    Concepts.main(new String[]{a[0], "sheet9"});
  }
}
