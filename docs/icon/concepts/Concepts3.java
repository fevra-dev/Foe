// Foe icon, last pass on E: fuller ears. Three ear shapes side by side, plus A2 and D2 for comparison.
import java.awt.*; import java.awt.geom.*;
public class Concepts3 {
  static final double CX = Concepts.CX, CY = Concepts.CY, R = Concepts.R;
  /** Ear: base on the ring from y0 to y1 (relative to CY), tip out by span and up by lift from the ring edge. */
  static void ears(Graphics2D g, double y0, double y1, double span, double lift) {
    for (int side : new int[]{-1, 1}) {
      double x = CX + side * (R - 1.2);
      Path2D p = new Path2D.Double();
      p.moveTo(x, CY + y0);
      p.lineTo(CX + side * (R + span), CY + y0 - lift);
      p.lineTo(x, CY + y1);
      p.closePath();
      g.setColor(Concepts.GREEN); g.fill(p);
    }
  }
  public static void main(String[] a) throws Exception {
    Concepts.NAMES_OVERRIDE = new String[]{"E4 ears out", "E5 ears up", "E6 long ears", "A2", "D2"};
    Concepts.DRAWS_OVERRIDE = new Concepts.Draw[]{
      g -> { Concepts.tile(g); ears(g, -5.5, 2.5, 5.0, 4.5); g.setColor(Concepts.GREEN); g.fill(Concepts.eyes(CX, CY + 0.2, 1.25)); Concepts.lens(g); },
      g -> { Concepts.tile(g); ears(g, -6.5, 1.5, 3.8, 7.5); g.setColor(Concepts.GREEN); g.fill(Concepts.eyes(CX, CY + 0.2, 1.25)); Concepts.lens(g); },
      g -> { Concepts.tile(g); ears(g, -5.0, 2.0, 4.4, 6.2); g.setColor(Concepts.GREEN); g.fill(Concepts.eyes(CX, CY + 0.6, 1.3)); Concepts.lens(g); },
      Concepts2.DRAWS[2], Concepts2.DRAWS[3],
    };
    Concepts.main(new String[]{a[0], "sheet3"});
  }
}
