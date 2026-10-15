// Foe icon, final round: OSRS goblin olive (operator), applied to the designs the operator picked out:
// G1 and G3 (round 3 faces), G4 and A2 ("best": the bald body in a magnifier, the head in the round-1 lens),
// H1b (the chathead) for reference, and one synthesis: A2's composition with the round-3 face and white eyes.
// Run: javac -d /tmp/foeicon *.java && java -cp /tmp/foeicon Concepts9 .
import java.awt.*; import java.awt.geom.*;
public class Concepts9 {
  static final Color OLIVE = Concepts8.OLIVE, WHITE = Concepts.WHITE, GLASS = Concepts4.GLASS;

  public static void main(String[] a) throws Exception {
    Concepts.GREEN = OLIVE; Concepts2.GREEN = OLIVE; Concepts7.GREEN = OLIVE;
    Concepts.NAMES_OVERRIDE = new String[]{"G1 olive", "G3 olive", "G4 olive", "A2 olive", "H1b chathead", "S synthesis"};
    Concepts.DRAWS_OVERRIDE = new Concepts.Draw[]{
      g -> { Concepts.tile(g); Concepts7.faceInLens(g, 24, 37, 13.0, 2.6, 0.92, 0.8, GLASS, 17.0, -9.0); },
      g -> { Concepts.tile(g); double cx = 23, cy = 32, r = 12.0;
             Concepts7.faceInLens(g, cx, cy, r, 2.8, 0.84, 0.8, GLASS, 16.5, -8.0); Concepts7.handle(g, cx, cy, r); },
      g -> { Concepts.tile(g); double cx = 22.5, cy = 31.5, r = 13;
             Shape glass = Concepts7.inLens(g, cx, cy, r, 2.8);
             Shape c = g.getClip(); g.clip(glass); Concepts7.body(g, cx + 0.3, cy + 1.2, 0.8); g.setClip(c);
             Concepts4.ring(g, cx, cy, r, 2.8); Concepts7.handle(g, cx, cy, r); },
      Concepts2.DRAWS[2],
      g -> { Concepts.tile(g); double cx = 24, cy = 37, r = 13;
             Shape glass = Concepts7.inLens(g, cx, cy, r, 2.6);
             Shape c = g.getClip(); g.clip(glass); Concepts8.profile(g, cx - 0.6, cy + 0.4, 1.0, false); g.setClip(c);
             Concepts4.ring(g, cx, cy, r, 2.6); Concepts8.profileEarOver(g, cx - 0.6, cy + 0.4, 1.0, false); },
      g -> { Concepts.tile(g); double cx = Concepts.CX, cy = Concepts.CY, r = Concepts.R;
             Shape glass = Concepts7.inLens(g, cx, cy, r + 1.0, 3.0);
             Shape c = g.getClip(); g.clip(glass);
             Concepts7.face(g, cx, cy + 2.6, 0.62, WHITE);
             Concepts7.ears(g, cx, cy + 2.6, 0.62, 15.0, -8.0);
             g.setClip(c);
             Concepts.lens(g); },
    };
    Concepts.main(new String[]{a[0], "sheet10"});
  }
}
