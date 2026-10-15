// Foe icon finalists. Same tile, palette and lens as Concepts.java.
import java.awt.*; import java.awt.geom.*; import java.awt.image.*; import javax.imageio.*; import java.io.*;
public class Concepts2 {
  static final Color TILE = Concepts.TILE, WHITE = Concepts.WHITE;
  static Color GREEN = Concepts.GREEN;
  static final double CX = Concepts.CX, CY = Concepts.CY, R = Concepts.R;

  /** Ears leave the ring at its sides and point out and up: span outward, lift upward. Tips stay 3px inside the tile. */
  static void ears(Graphics2D g, double span, double lift) {
    Path2D l = new Path2D.Double(); l.moveTo(CX - R + 0.6, CY - 3.4); l.lineTo(CX - R - span, CY - 3.4 - lift); l.lineTo(CX - R + 0.2, CY + 2.4); l.closePath();
    Path2D r = new Path2D.Double(); r.moveTo(CX + R - 0.6, CY - 3.4); r.lineTo(CX + R + span, CY - 3.4 - lift); r.lineTo(CX + R - 0.2, CY + 2.4); r.closePath();
    g.setColor(GREEN); g.fill(l); g.fill(r);
  }

  static void fangs(Graphics2D g, double y) {
    Path2D f1 = new Path2D.Double(); f1.moveTo(CX - 4.2, y); f1.lineTo(CX - 1.8, y); f1.lineTo(CX - 3.0, y + 3.2); f1.closePath();
    Path2D f2 = new Path2D.Double(); f2.moveTo(CX + 4.2, y); f2.lineTo(CX + 1.8, y); f2.lineTo(CX + 3.0, y + 3.2); f2.closePath();
    g.setColor(WHITE); g.fill(f1); g.fill(f2);
  }

  static final String[] NAMES = {"E2 lens is the face", "E3 face + fangs", "A2 goblin in lens", "D2 eyes only"};
  static final Concepts.Draw[] DRAWS = {
    g -> { Concepts.tile(g); ears(g, 3.6, 5.2); g.setColor(GREEN); g.fill(Concepts.eyes(CX, CY + 0.2, 1.25)); Concepts.lens(g); },
    g -> { Concepts.tile(g); ears(g, 3.6, 5.2); g.setColor(GREEN); g.fill(Concepts.eyes(CX, CY - 2.0, 1.2)); fangs(g, CY + 2.6); Concepts.lens(g); },
    g -> { Concepts.tile(g); Area h = Concepts.head(CX, CY + 0.8, 0.95); h.intersect(new Area(Concepts.glass()));
           g.setColor(GREEN); g.fill(h); g.setColor(TILE); g.fill(Concepts.eyes(CX, CY + 0.4, 0.85)); Concepts.lens(g); },
    g -> { Concepts.tile(g); g.setColor(GREEN); g.fill(Concepts.eyes(CX, CY, 1.45)); Concepts.lens(g); },
  };

  public static void main(String[] a) throws Exception {
    Concepts.DRAWS_OVERRIDE = DRAWS; Concepts.NAMES_OVERRIDE = NAMES; Concepts.main(new String[]{a[0], "sheet2"});
  }
}
