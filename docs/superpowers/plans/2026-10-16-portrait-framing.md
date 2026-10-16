# Portrait Framing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a portrait frames the monster's head, centred and close (head and shoulders), instead of whatever
its bounding box reaches: wingtips, horns, spears, sparks or a shadow.

**Architecture:** a new pure class, `PortraitFrame`, turns the opaque faces of a projected model into a
128-cell outline and returns the square to draw:
- for UPRIGHT and SIDE: head-and-shoulders, around the head it finds;
- for the other views: the whole body, trimmed of stray dots.

`Portrait.render` draws see-through faces see-through and draws the square `PortraitFrame` chose.
`Portrait.View.of` narrows TOP to flat, square bodies. A dev-only mesh dump lets the gallery be redrawn
offline, with no game client, so the constants can be tuned over all 1,988 portraits.

**Tech Stack:** Java 11, RuneLite API 1.13.1 (pinned, ADR-0001), JUnit 4, Gradle, and Java2D for the
outline raster.

**Spec:** `docs/superpowers/specs/2026-10-15-portrait-framing-design.md`. Read the whole file, including
the two revision sections at the end: they override sections 3–5 where they differ.

---

## Conventions for every task

- **Branch.** Work on `feat/portrait-framing`, made from `docs/portrait-review` (it carries the spec).
  The operator pushes; never push.
- **Running tests.** Up-to-date Gradle tasks run no tests, so always use `cleanTest`. Capture the exit
  code on the same line; never pipe a command whose exit code you are about to report:
  ```
  ./gradlew cleanTest test > "$SCRATCH/test.log" 2>&1; RC=$?; echo "rc=$RC"
  ```
  Here `$SCRATCH` is the session scratchpad. Count tests from the JUnit XML, not from the log:
  ```
  uv run python3 - <<'EOF'
  import glob, xml.etree.ElementTree as ET
  t = f = e = s = 0
  for p in glob.glob('build/test-results/test/*.xml'):
      r = ET.parse(p).getroot()
      t += int(r.get('tests')); f += int(r.get('failures')); e += int(r.get('errors')); s += int(r.get('skipped'))
  print(f"tests={t} failures={f} errors={e} skipped={s}")
  EOF
  ```
- **Mutation proof.** Every new rule gets one. Break the rule by hand, run the new test, see it fail
  *for the reason it was written*, then restore with `git checkout -- <file>`. Record each mutant and its
  result in the commit message.
- **Edits.** Make edits to tracked files with `~/Apps/WORKFLOW/scripts/anchored-edit.py` or the Edit
  tool, never with sed.
- **Style.** Match `Portrait.java`: tabs, Allman braces, Javadoc on every constant that says why its
  value is what it is.
- **Commits.** Every commit message ends with:
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`

---

### Task 0: Hostile pass on the spec, and the "before" gallery (DONE in planning, except step 3)

- [x] **Step 1:** Third-person hostile read of the spec's sections 3–5. Findings G1–G6 are appended to
  the spec as "Revision: hostile pass before the plan". Commit `bd17c2d`.
- [x] **Step 2:** Working the plan's test meshes by hand reversed G2 (a spear defeats the quartile) and
  found G7 (the no-head fallback is unreachable). Recorded in the spec as "Corrections made while
  planning". Commit `582dc0a`.
- [ ] **Step 3:** Keep the current gallery as the "before" set, which the operator reviewed:

```bash
cd /Users/fevra/Apps/Runelite/Foe
test -d build/foe-gallery-before && echo "EXISTS, not overwriting" || cp -R build/foe-gallery build/foe-gallery-before
ls build/foe-gallery-before | head -3; wc -l < build/foe-gallery-before/index.tsv
```
Expected: `CHATHEAD-01.png` among the files, and `1989` lines (a header plus 1,988 rows).

- [ ] **Step 4:** Make the branch:

```bash
git switch -c feat/portrait-framing docs/portrait-review
```

---

### Task 1: See-through faces are drawn see-through

**Files:**
- Modify: `src/main/java/com/foe/Portrait.java` (the `Mesh` class, and the fill-colour line in `render`)
- Modify: `src/main/java/com/foe/PortraitModels.java` (the `new Portrait.Mesh(...)` at the end of `load`)
- Test: `src/test/java/com/foe/PortraitTest.java`

- [ ] **Step 1: Write the failing tests.** Add them to `PortraitTest`, before `lightnessRunsBlackToWhite`:

```java
	/**
	 * Spec addendum 23: the operator's gallery showed black and solid slabs (the Moons, the Bee Swarm, Branda, the
	 * grey-green slab under the warband): see-through faces drawn solid. A red chathead square, and in front of it
	 * (z -100) a blue one the same size, 200 of 255 see-through: the red shows through, tinted blue.
	 */
	@Test
	public void aSeeThroughFaceLetsWhatIsBehindItShow()
	{
		Portrait.Mesh m = new Portrait.Mesh(
			new float[]{-50, 50, 50, -50, -50, 50, 50, -50}, new float[]{-100, -100, 0, 0, -100, -100, 0, 0},
			new float[]{0, 0, 0, 0, -100, -100, -100, -100},
			new int[]{0, 0, 4, 4}, new int[]{1, 2, 5, 6}, new int[]{2, 3, 6, 7},
			new int[]{RED, RED, BLUE, BLUE}, new int[]{-1, -1, -1, -1}, null,
			new byte[]{0, 0, (byte) 200, (byte) 200}, 4, true);
		int c = Portrait.render(m, 32).getRGB(16, 16);
		assertEquals("solid where the red is", 255, c >>> 24);
		assertTrue("the red shows through: " + Integer.toHexString(c), (c >> 16 & 255) > (c & 255));
		assertTrue("tinted by the blue: " + Integer.toHexString(c), (c & 255) > 20);
	}

	/** A face alone is as see-through as its transparency says: 200 of 255 leaves about 55 of opacity. */
	@Test
	public void aFaceAloneIsAsSeeThroughAsItsTransparency()
	{
		Portrait.Mesh m = new Portrait.Mesh(new float[]{-50, 50, 50, -50}, new float[]{-100, -100, 0, 0}, new float[4],
			new int[]{0, 0}, new int[]{1, 2}, new int[]{2, 3}, new int[]{RED, RED}, new int[]{-1, -1}, null,
			new byte[]{(byte) 200, (byte) 200}, 2, true);
		int alpha = Portrait.render(m, 32).getRGB(16, 16) >>> 24;
		assertTrue("about 55 of 255: " + alpha, alpha > 40 && alpha < 70);
	}
```

- [ ] **Step 2: Run them; they fail to compile.** There is no 12-argument `Mesh` constructor yet.

```bash
./gradlew cleanTest test --tests com.foe.PortraitTest > "$SCRATCH/t1.log" 2>&1; RC=$?; echo rc=$RC; grep -m3 "error:" "$SCRATCH/t1.log"
```
Expected: `rc=1` and `constructor Mesh in class Mesh cannot be applied`.

- [ ] **Step 3: Add the field and the constructor.** In `Portrait.Mesh`, after `final short[] textures;`:

```java
		/**
		 * Per face, 0 (solid) to 255 (gone), as the game stores it; null when the model has none. Spec addendum 23
		 * [assumed: the direction; the first gallery run shows it, since the Moons' black squares fade or don't].
		 */
		final byte[] transparencies;
```

Replace the constructor with these two:

```java
		Mesh(float[] x, float[] y, float[] z, int[] a, int[] b, int[] c, int[] colours1, int[] colours3,
			short[] textures, int faces, boolean head)
		{
			this(x, y, z, a, b, c, colours1, colours3, textures, null, faces, head);
		}

		Mesh(float[] x, float[] y, float[] z, int[] a, int[] b, int[] c, int[] colours1, int[] colours3,
			short[] textures, byte[] transparencies, int faces, boolean head)
		{
			this.x = x;
			this.y = y;
			this.z = z;
			this.a = a;
			this.b = b;
			this.c = c;
			this.colours1 = colours1;
			this.colours3 = colours3;
			this.textures = textures;
			this.transparencies = transparencies;
			this.faces = faces;
			this.head = head;
		}
```

The 11-argument form keeps the eight other callers (tests and the gallery) unchanged.

- [ ] **Step 4: Draw with alpha.** In `render`, replace

```java
			g.setColor(new Color(textured ? grey(m.colours1[f]) : hslToRgb(m.colours1[f])));
```

with

```java
			int rgb = textured ? grey(m.colours1[f]) : hslToRgb(m.colours1[f]);
			g.setColor(new Color(alpha(m, f) << 24 | rgb, true));
```

and add, after `render`:

```java
	/** A face's opacity, 0 to 255: its transparency turned round (spec addendum 23). */
	static int alpha(Mesh m, int f)
	{
		return m.transparencies == null ? 255 : 255 - (m.transparencies[f] & 255);
	}
```

- [ ] **Step 5: Copy transparencies off the client.** In `PortraitModels.load`, replace the `return new
  Portrait.Mesh(...)` with:

```java
		return new Portrait.Mesh(copy(m.getVerticesX()), copy(m.getVerticesY()), copy(m.getVerticesZ()),
			copy(m.getFaceIndices1()), copy(m.getFaceIndices2()), copy(m.getFaceIndices3()),
			copy(m.getFaceColors1()), copy(m.getFaceColors3()),
			m.getFaceTextures() == null ? null : Arrays.copyOf(m.getFaceTextures(), m.getFaceTextures().length),
			m.getFaceTransparencies() == null ? null
				: Arrays.copyOf(m.getFaceTransparencies(), m.getFaceTransparencies().length),
			m.getFaceCount(), head);
```

`getFaceTransparencies()` is declared on `net.runelite.api.Mesh`, which `Model` extends
(`javap -cp <runelite-api-1.13.1.jar> net.runelite.api.Mesh`).

- [ ] **Step 6: Run the whole suite.**

```bash
./gradlew cleanTest test > "$SCRATCH/t1.log" 2>&1; RC=$?; echo rc=$RC
```
Expected: `rc=0`. The XML count is the previous total plus 2.

- [ ] **Step 7: Mutant.** Make `alpha` return `255` always. Both new tests fail: the first because the red
  no longer shows through, the second because the alpha is 255. Restore with
  `git checkout -- src/main/java/com/foe/Portrait.java`, then re-apply steps 3–4. Better: run the mutant
  before committing, with `~/Apps/WORKFLOW/scripts/mutate.sh --file src/main/java/com/foe/Portrait.java
  --anchor 'return m.transparencies == null ? 255' --inject 'if (true) { return 255; }' --control
  'if (true) { throw new IllegalStateException(); }' --test './gradlew cleanTest test --tests
  com.foe.PortraitTest'`.

- [ ] **Step 8: Commit.**

```bash
git add src/main/java/com/foe/Portrait.java src/main/java/com/foe/PortraitModels.java src/test/java/com/foe/PortraitTest.java
git commit -m "Portrait: see-through faces are drawn see-through (spec addendum 23)

Mutant: alpha() always 255 -> both new tests fail.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: The gallery saves its meshes, and redraws offline

**Files:**
- Create: `src/test/java/com/foe/PortraitMeshFile.java`
- Create: `src/test/java/com/foe/PortraitGalleryOffline.java`
- Modify: `src/test/java/com/foe/PortraitGalleryPlugin.java` (the `new PortraitGalleryRun(...)` in the
  command handler, and two new methods)
- Modify: `build.gradle` (a new `galleryOffline` task after `generateWeaknessTable`)
- Test: `src/test/java/com/foe/PortraitMeshFileTest.java`
- Test: `src/test/java/com/foe/PortraitGalleryOfflineTest.java`

- [ ] **Step 1: Write the failing tests.**

`src/test/java/com/foe/PortraitMeshFileTest.java`:

```java
package com.foe;

import java.nio.file.Path;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PortraitMeshFileTest
{
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void aMeshComesBackAsItWasWritten() throws Exception
	{
		Portrait.Mesh m = new Portrait.Mesh(new float[]{-50, 50.5f, 50}, new float[]{-100, -100, 0},
			new float[]{1, 2, 3}, new int[]{0}, new int[]{1}, new int[]{2}, new int[]{12345}, new int[]{-2},
			new short[]{7}, new byte[]{(byte) 200}, 1, true);
		Path file = tmp.newFile("m.mesh").toPath();
		PortraitMeshFile.write(file, m);
		Portrait.Mesh back = PortraitMeshFile.read(file);
		assertArrayEquals(m.x, back.x, 0f);
		assertArrayEquals(m.y, back.y, 0f);
		assertArrayEquals(m.z, back.z, 0f);
		assertArrayEquals(m.a, back.a);
		assertArrayEquals(m.b, back.b);
		assertArrayEquals(m.c, back.c);
		assertArrayEquals(m.colours1, back.colours1);
		assertArrayEquals(m.colours3, back.colours3);
		assertArrayEquals(m.textures, back.textures);
		assertArrayEquals(m.transparencies, back.transparencies);
		assertEquals(1, back.faces);
		assertTrue(back.head);
	}

	@Test
	public void absentArraysStayAbsent() throws Exception
	{
		Portrait.Mesh m = new Portrait.Mesh(new float[3], new float[3], new float[3], new int[]{0}, new int[]{1},
			new int[]{2}, new int[]{1}, null, null, null, 1, false);
		Path file = tmp.newFile("n.mesh").toPath();
		PortraitMeshFile.write(file, m);
		Portrait.Mesh back = PortraitMeshFile.read(file);
		assertNull(back.colours3);
		assertNull(back.textures);
		assertNull(back.transparencies);
	}
}
```

`src/test/java/com/foe/PortraitGalleryOfflineTest.java`:

```java
package com.foe;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.assertTrue;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PortraitGalleryOfflineTest
{
	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void aRunsMeshesAreRedrawnIntoSheets() throws Exception
	{
		Path in = tmp.newFolder("in").toPath();
		Path out = tmp.getRoot().toPath().resolve("out");
		Files.createDirectories(in.resolve("meshes"));
		// id 7 has its mesh (a chathead square); id 8's mesh was never saved
		PortraitMeshFile.write(in.resolve("meshes").resolve("7.mesh"), new Portrait.Mesh(
			new float[]{-50, 50, 50, -50}, new float[]{-100, -100, 0, 0}, new float[4],
			new int[]{0, 0}, new int[]{1, 2}, new int[]{2, 3}, new int[]{64, 64}, new int[]{-1, -1}, null, 2, true));
		Files.write(in.resolve("index.tsv"), (PortraitGallery.TSV_HEADER + "\n"
			+ "CHATHEAD-01\t1\t7\tGoblin\tCHATHEAD\t100\t100\t0\t9,10\tGoblin | Goblin chief\n"
			+ "UPRIGHT-01\t1\t8\tImp\tUPRIGHT\t95\t84\t66\t\tImp\n").getBytes(StandardCharsets.UTF_8));

		String summary = PortraitGalleryOffline.redraw(in, out);

		assertTrue(Files.isRegularFile(out.resolve("CHATHEAD-01.png")));
		String index = new String(Files.readAllBytes(out.resolve("index.tsv")), StandardCharsets.UTF_8);
		assertTrue(index, index.contains("\t7\tGoblin\tCHATHEAD\t") && index.contains("\t9,10\tGoblin | Goblin chief"));
		String missing = new String(Files.readAllBytes(out.resolve("unrendered.tsv")), StandardCharsets.UTF_8);
		assertTrue(missing, missing.contains("NOT_LOADED\t8\tImp"));
		assertTrue(summary, summary.contains("CHATHEAD 1 (clamped 0)") && summary.contains("not drawn 1"));
	}
}
```

Until Task 3, `clamped` returns 0, so `(clamped 0)` already holds in this task. Task 3 step 7 makes it real
and re-runs this test.

- [ ] **Step 2: Run them; they fail to compile.** `PortraitMeshFile` and `PortraitGalleryOffline` don't
  exist.

```bash
./gradlew cleanTest test --tests 'com.foe.PortraitMeshFileTest' --tests 'com.foe.PortraitGalleryOfflineTest' > "$SCRATCH/t2.log" 2>&1; RC=$?; echo rc=$RC
```
Expected: `rc=1`, `cannot find symbol ... PortraitMeshFile`.

- [ ] **Step 3: Write `src/test/java/com/foe/PortraitMeshFile.java`.**

```java
package com.foe;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dev only (portrait framing plan, Task 2): a {@link Portrait.Mesh} on disk, so a ::foegallery run's models can be
 * redrawn by {@link PortraitGalleryOffline} with no client. Each array is its length (-1 for null) then its values.
 */
final class PortraitMeshFile
{
	/** "FOE1": a file this does not recognise is refused, not misread. */
	private static final int MAGIC = 0x464F4531;

	private PortraitMeshFile()
	{
	}

	static void write(Path file, Portrait.Mesh m) throws IOException
	{
		try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file))))
		{
			out.writeInt(MAGIC);
			floats(out, m.x);
			floats(out, m.y);
			floats(out, m.z);
			ints(out, m.a);
			ints(out, m.b);
			ints(out, m.c);
			ints(out, m.colours1);
			ints(out, m.colours3);
			shorts(out, m.textures);
			bytes(out, m.transparencies);
			out.writeInt(m.faces);
			out.writeBoolean(m.head);
		}
	}

	static Portrait.Mesh read(Path file) throws IOException
	{
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file))))
		{
			if (in.readInt() != MAGIC)
			{
				throw new IOException("not a Foe mesh file: " + file);
			}
			float[] x = floats(in);
			float[] y = floats(in);
			float[] z = floats(in);
			int[] a = ints(in);
			int[] b = ints(in);
			int[] c = ints(in);
			int[] colours1 = ints(in);
			int[] colours3 = ints(in);
			short[] textures = shorts(in);
			byte[] transparencies = bytes(in);
			int faces = in.readInt();
			boolean head = in.readBoolean();
			return new Portrait.Mesh(x, y, z, a, b, c, colours1, colours3, textures, transparencies, faces, head);
		}
	}

	private static void floats(DataOutputStream out, float[] v) throws IOException
	{
		out.writeInt(v == null ? -1 : v.length);
		if (v != null)
		{
			for (float f : v)
			{
				out.writeFloat(f);
			}
		}
	}

	private static void ints(DataOutputStream out, int[] v) throws IOException
	{
		out.writeInt(v == null ? -1 : v.length);
		if (v != null)
		{
			for (int i : v)
			{
				out.writeInt(i);
			}
		}
	}

	private static void shorts(DataOutputStream out, short[] v) throws IOException
	{
		out.writeInt(v == null ? -1 : v.length);
		if (v != null)
		{
			for (short s : v)
			{
				out.writeShort(s);
			}
		}
	}

	private static void bytes(DataOutputStream out, byte[] v) throws IOException
	{
		out.writeInt(v == null ? -1 : v.length);
		if (v != null)
		{
			out.write(v);
		}
	}

	private static float[] floats(DataInputStream in) throws IOException
	{
		int n = in.readInt();
		if (n < 0)
		{
			return null;
		}
		float[] v = new float[n];
		for (int i = 0; i < n; i++)
		{
			v[i] = in.readFloat();
		}
		return v;
	}

	private static int[] ints(DataInputStream in) throws IOException
	{
		int n = in.readInt();
		if (n < 0)
		{
			return null;
		}
		int[] v = new int[n];
		for (int i = 0; i < n; i++)
		{
			v[i] = in.readInt();
		}
		return v;
	}

	private static short[] shorts(DataInputStream in) throws IOException
	{
		int n = in.readInt();
		if (n < 0)
		{
			return null;
		}
		short[] v = new short[n];
		for (int i = 0; i < n; i++)
		{
			v[i] = in.readShort();
		}
		return v;
	}

	private static byte[] bytes(DataInputStream in) throws IOException
	{
		int n = in.readInt();
		if (n < 0)
		{
			return null;
		}
		byte[] v = new byte[n];
		in.readFully(v);
		return v;
	}
}
```

- [ ] **Step 4: Write `src/test/java/com/foe/PortraitGalleryOffline.java`.** It's `public`, so the
  `JavaExec` launcher can start it.

```java
package com.foe;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Dev only (portrait framing plan, Task 2): redraws a ::foegallery run from the meshes it saved, with no client, so
 * the framing constants can be tuned over the whole corpus in seconds:
 * {@code ./gradlew galleryOffline --args="build/foe-gallery build/foe-gallery-offline"}. It reads the run's
 * index.tsv for the groups and meshes/ID.mesh for the models, and writes sheets and indexes as the run does.
 */
public final class PortraitGalleryOffline
{
	private PortraitGalleryOffline()
	{
	}

	public static void main(String[] args) throws IOException
	{
		if (args.length != 2)
		{
			System.err.println("usage: galleryOffline <run folder with index.tsv and meshes/> <output folder>");
			System.exit(2);
		}
		System.out.println(redraw(Paths.get(args[0]), Paths.get(args[1])));
	}

	/** Redraws every row of in/index.tsv from in/meshes into out, and returns the summary it printed. */
	static String redraw(Path in, Path out) throws IOException
	{
		List<String> rows = Files.readAllLines(in.resolve("index.tsv"), StandardCharsets.UTF_8);
		List<PortraitGallery.Entry> entries = new ArrayList<>();
		List<PortraitGallery.Missing> missing = new ArrayList<>();
		Map<Portrait.View, int[]> counts = new EnumMap<>(Portrait.View.class); // {drawn, clamped}
		for (String row : rows.subList(1, rows.size()))
		{
			String[] f = row.split("\t", -1);
			List<Integer> ids = new ArrayList<>();
			ids.add(Integer.parseInt(f[2]));
			for (String s : f[8].isEmpty() ? new String[0] : f[8].split(","))
			{
				ids.add(Integer.parseInt(s));
			}
			PortraitGallery.Group g = new PortraitGallery.Group("offline", ids, Arrays.asList(f[9].split(" \\| ")));
			Path file = in.resolve("meshes").resolve(f[2] + ".mesh");
			if (!Files.isRegularFile(file))
			{
				missing.add(new PortraitGallery.Missing(g, PortraitGallery.Why.NOT_LOADED));
				continue;
			}
			Portrait.Mesh mesh = PortraitMeshFile.read(file);
			PortraitGallery.Shape shape = PortraitGallery.parseDescribe(Portrait.describe(mesh));
			BufferedImage image = shape == null ? null : Portrait.render(mesh, PortraitGallery.RENDER);
			if (image == null)
			{
				missing.add(new PortraitGallery.Missing(g, PortraitGallery.Why.NOT_DRAWABLE));
				continue;
			}
			entries.add(new PortraitGallery.Entry(g, shape, image));
			int[] n = counts.computeIfAbsent(shape.view, v -> new int[2]);
			n[0]++;
			n[1] += clamped(mesh);
		}
		PortraitGalleryFiles.write(out, PortraitGallery.layout(entries), missing);
		StringBuilder s = new StringBuilder("galleryOffline: wrote ").append(out).append('\n');
		for (Map.Entry<Portrait.View, int[]> e : counts.entrySet())
		{
			s.append(e.getKey()).append(' ').append(e.getValue()[0]).append(" (clamped ").append(e.getValue()[1])
				.append(")\n");
		}
		return s.append("not drawn ").append(missing.size()).toString();
	}

	/** 1 when the head-and-shoulders square was cut to the whole body (spec revision G7). Task 3 fills this in. */
	private static int clamped(Portrait.Mesh mesh)
	{
		return 0;
	}
}
```

Until Task 3 the summary prints `(clamped 0)` for every view. Task 3 step 7 replaces `clamped`.

- [ ] **Step 5: Save each mesh during an in-game run.** In `PortraitGalleryPlugin`, replace
  `this::loadMesh` in the `new PortraitGalleryRun(...)` call with `id -> saved(dir, id, loadMesh(id))`,
  and add after `loadMesh`:

```java
	/**
	 * Plan Task 2: hands the mesh on, and saves a copy to dir/meshes on the worker so that
	 * {@code ./gradlew galleryOffline} can redraw the run with no client. Client thread.
	 */
	private Portrait.Mesh saved(Path dir, int id, Portrait.Mesh mesh)
	{
		if (mesh != null)
		{
			try
			{
				worker.execute(() -> saveMesh(dir.resolve("meshes"), id, mesh));
			}
			catch (RuntimeException e) // the worker refused it (shut down)
			{
				log.debug("gallery: mesh {} not saved", id, e);
			}
		}
		return mesh;
	}

	/** On the worker. A failure is logged and the run goes on: the sheets do not depend on it. */
	private void saveMesh(Path meshes, int id, Portrait.Mesh mesh)
	{
		try
		{
			Files.createDirectories(meshes);
			PortraitMeshFile.write(meshes.resolve(id + ".mesh"), mesh);
		}
		catch (IOException e)
		{
			log.warn("gallery: could not save mesh {} to {}", id, meshes, e);
		}
	}
```

Add `import java.nio.file.Files;`. The plugin already imports `IOException`, `Path` and `log`.

- [ ] **Step 6: Add the Gradle task.** Put it after `generateWeaknessTable` in `build.gradle`:

```groovy
// Dev only (portrait framing plan, Task 2): redraws a ::foegallery run from the meshes it saved, with no client.
// ./gradlew galleryOffline --args="build/foe-gallery build/foe-gallery-offline"
tasks.register('galleryOffline', JavaExec) {
	classpath = sourceSets.test.runtimeClasspath
	mainClass = 'com.foe.PortraitGalleryOffline'
	workingDir = projectDir
}
```

- [ ] **Step 7: Run the suite.**

```bash
./gradlew cleanTest test > "$SCRATCH/t2.log" 2>&1; RC=$?; echo rc=$RC
```
Expected: `rc=0`, with 3 more tests.

- [ ] **Step 8: Mutants.**
  - In `PortraitMeshFile.write`, swap the `ints(out, m.a)` and `ints(out, m.b)` lines. The round-trip
    test fails on `a`.
  - In `redraw`, change `NOT_LOADED` to `NOT_DRAWABLE`. The offline test fails on `unrendered.tsv`.

  Restore both.

- [ ] **Step 9: Commit.**

```bash
git add build.gradle src/test/java/com/foe/PortraitMeshFile.java src/test/java/com/foe/PortraitGalleryOffline.java src/test/java/com/foe/PortraitGalleryPlugin.java src/test/java/com/foe/PortraitMeshFileTest.java src/test/java/com/foe/PortraitGalleryOfflineTest.java
git commit -m "Gallery: save each mesh, and redraw a run offline (./gradlew galleryOffline)

Mutants: swapped a/b on write -> round-trip fails; NOT_LOADED->NOT_DRAWABLE -> offline test fails.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 10: CHECKPOINT A, the operator in game.** Ask the operator to run `./gradlew run`, log in and
  type `::foegallery`. This is the only client run the tuning needs: it writes `build/foe-gallery/meshes/`,
  1,988 files.
  - **Check:** `ls build/foe-gallery/meshes | wc -l` gives about 1,988.
  - **Look:** compare `build/foe-gallery/WHOLE-01.png` and `UPRIGHT-*.png` against `build/foe-gallery-before/`.
    The Blood Moon 13011, Blue Moon 13013, Bee Swarm 13658 and Branda 12596 should no longer be solid black.
  - **If they're still solid,** the transparency direction `[assumed]` in Task 1 is wrong. Stop and
    re-plan Task 1. Don't go on.
  - **Copy for the offline tool:** `cp -R build/foe-gallery/meshes build/foe-gallery-before/` puts the
    meshes beside the "before" index, so `galleryOffline` can run against it.

---

### Task 3: The outline, and framing by square

**Files:**
- Create: `src/main/java/com/foe/PortraitFrame.java`
- Modify: `src/main/java/com/foe/Portrait.java` (`render` is rewritten around a new `framing`)
- Modify: `src/test/java/com/foe/PortraitGalleryOffline.java` (`clamped`)
- Test: `src/test/java/com/foe/PortraitFrameTest.java`, `src/test/java/com/foe/PortraitTest.java`

In this task UPRIGHT and SIDE keep their addenda 19–22 crops, moved unchanged into squares. Tasks 5 and
6 replace them. What changes now: the outline exists, the whole-frame views use trimmed bounds, and
see-through faces no longer frame.

- [ ] **Step 1: Write the failing tests.**

`src/test/java/com/foe/PortraitFrameTest.java`:

```java
package com.foe;

import java.util.Arrays;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class PortraitFrameTest
{
	/**
	 * A 1000 square (0..1000) as two triangles, and a 60 square dot far off at 5000..5060. The cell is 5060 / 128 =
	 * 39.5, so the dot fills 1 to 4 cells, under the 1% trim of the square's 676 (6 cells): trimmed, the dot is gone;
	 * untrimmed, it sets the bounds. It must fill at least one cell, or the trim mutant survives for the wrong reason.
	 */
	private static final float[] ACROSS = {0, 1000, 1000, 0, 5000, 5060, 5060, 5000};
	private static final float[] DOWN = {0, 0, 1000, 1000, 5000, 5000, 5060, 5060};
	private static final int[] A = {0, 0, 4, 4};
	private static final int[] B = {1, 2, 5, 6};
	private static final int[] C = {2, 3, 6, 7};

	@Test
	public void aSquareFillsItsOutline()
	{
		PortraitFrame.Outline o = PortraitFrame.outline(ACROSS, DOWN, A, B, C, Arrays.asList(0, 1));
		assertNotNull(o);
		assertEquals(PortraitFrame.GRID, o.cols);
		assertEquals(PortraitFrame.GRID, o.rows);
		assertTrue("nearly every cell: " + o.count, o.count > PortraitFrame.GRID * PortraitFrame.GRID * 9 / 10);
	}

	@Test
	public void noFaceIsNoOutline()
	{
		assertNull(PortraitFrame.outline(ACROSS, DOWN, A, B, C, Arrays.asList()));
	}

	/** Spec section 2: a few stray dots far off don't set the bounds; the whole square is the body's. */
	@Test
	public void aStrayDotIsTrimmedFromTheBounds()
	{
		PortraitFrame.Outline o = PortraitFrame.outline(ACROSS, DOWN, A, B, C, Arrays.asList(0, 1, 2, 3));
		int right = 0;
		for (int r = 0; r < o.rows; r++)
		{
			right = o.filled[r][o.cols - 1] ? 1 : right;
		}
		assertEquals("precondition: the dot fills a cell", 1, right);
		PortraitFrame.Square s = PortraitFrame.whole(o);
		assertEquals("left", 0, s.left, 50);
		assertEquals("top", 0, s.top, 50);
		assertEquals("side: the body's 1000, not the dot's 5060", 1000, s.side, 50);
		assertFalse(s.clamped);
	}

	@Test
	public void aWholeSquareIsCentredOnTheLongerSide()
	{
		// a 200 wide, 100 tall rectangle: the square is 200, centred down on the rectangle
		float[] across = {0, 200, 200, 0};
		float[] down = {0, 0, 100, 100};
		PortraitFrame.Outline o = PortraitFrame.outline(across, down, new int[]{0, 0}, new int[]{1, 2},
			new int[]{2, 3}, Arrays.asList(0, 1));
		PortraitFrame.Square s = PortraitFrame.whole(o);
		assertEquals(200, s.side, 4);
		assertEquals(0, s.left, 4);
		assertEquals("centred down: 50 - 100", -50, s.top, 4);
	}
}
```

Add to `PortraitTest`, before `lightnessRunsBlackToWhite`:

```java
	/**
	 * Spec addendum 23: a see-through face is drawn, but does not frame. A blue slab twice the red square's size, in
	 * front of it, 200 see-through: the frame is the red square's, exactly as without the slab.
	 */
	@Test
	public void aSeeThroughSlabDoesNotMoveTheFrame()
	{
		Portrait.Mesh bare = square(RED, -1, true);
		Portrait.Mesh slab = new Portrait.Mesh(
			new float[]{-50, 50, 50, -50, -100, 100, 100, -100}, new float[]{-100, -100, 0, 0, -150, -150, 50, 50},
			new float[]{0, 0, 0, 0, -100, -100, -100, -100},
			new int[]{0, 0, 4, 4}, new int[]{1, 2, 5, 6}, new int[]{2, 3, 6, 7},
			new int[]{RED, RED, BLUE, BLUE}, new int[]{-1, -1, -1, -1}, null,
			new byte[]{0, 0, (byte) 200, (byte) 200}, 4, true);
		PortraitFrame.Square a = Portrait.framing(bare).square;
		PortraitFrame.Square b = Portrait.framing(slab).square;
		assertEquals(a.left, b.left, 1e-9);
		assertEquals(a.top, b.top, 1e-9);
		assertEquals(a.side, b.side, 1e-9);
	}

	/** A model that is see-through all over (a ghost) still frames, on all its faces, and still draws. */
	@Test
	public void aWhollySeeThroughModelStillFrames()
	{
		Portrait.Mesh m = new Portrait.Mesh(new float[]{-50, 50, 50, -50}, new float[]{-100, -100, 0, 0}, new float[4],
			new int[]{0, 0}, new int[]{1, 2}, new int[]{2, 3}, new int[]{RED, RED}, new int[]{-1, -1}, null,
			new byte[]{(byte) 200, (byte) 200}, 2, true);
		assertNotNull(Portrait.framing(m));
		assertEquals(100, Portrait.framing(m).square.side, 4);
	}
```

- [ ] **Step 2: Run them; they fail to compile.** `PortraitFrame` and `Portrait.framing` don't exist.

- [ ] **Step 3: Write `src/main/java/com/foe/PortraitFrame.java`.** Tasks 5 and 6 add `upright` and
  `side`. This step writes the whole file as it stands after Task 3.

```java
package com.foe;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.util.List;

/**
 * Spec addendum 23 (docs/superpowers/specs/2026-10-15-portrait-framing-design.md): where a portrait's square goes,
 * found from an outline of the model's faces rather than from its outer extremes, so that a wingtip, horn, spear,
 * spark or shadow no longer decides it. Pure, like {@link Portrait}: arrays in, a square out, any thread.
 */
final class PortraitFrame
{
	/** The outline's longer side, in cells: fine enough for a head, coarse enough to be cheap. */
	static final int GRID = 128;
	/** The share of the outline's filled cells cut from each side when bounding it: a few stray dots move nothing. */
	static final double TRIM = 0.01;

	/** A square, in the view's projected model units. */
	static final class Square
	{
		final double left, top, side;
		/** True when a head-and-shoulders square came out larger than the whole body and was cut to it. */
		final boolean clamped;

		Square(double left, double top, double side, boolean clamped)
		{
			this.left = left;
			this.top = top;
			this.side = side;
			this.clamped = clamped;
		}
	}

	/** The filled cells of a projection: [row][col], row 0 the smallest down, col 0 the smallest across. */
	static final class Outline
	{
		final boolean[][] filled;
		final int rows, cols, count;
		/** Projected units at col 0 and row 0, and per cell (cells are square). */
		final double minX, minY, cell;

		Outline(boolean[][] filled, int rows, int cols, int count, double minX, double minY, double cell)
		{
			this.filled = filled;
			this.rows = rows;
			this.cols = cols;
			this.count = count;
			this.minX = minX;
			this.minY = minY;
			this.cell = cell;
		}

		double x(double col)
		{
			return minX + col * cell;
		}

		double y(double row)
		{
			return minY + row * cell;
		}
	}

	private PortraitFrame()
	{
	}

	/** The outline of these faces, projected by across and down; null when they fill no cell at all. */
	static Outline outline(float[] across, float[] down, int[] a, int[] b, int[] c, List<Integer> faces)
	{
		double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
		for (int f : faces)
		{
			for (int v : new int[]{a[f], b[f], c[f]})
			{
				minX = Math.min(minX, across[v]);
				maxX = Math.max(maxX, across[v]);
				minY = Math.min(minY, down[v]);
				maxY = Math.max(maxY, down[v]);
			}
		}
		double cell = Math.max(maxX - minX, maxY - minY) / GRID;
		if (faces.isEmpty() || !(cell > 0))
		{
			return null;
		}
		// less a hair: 100 / (100 / 128) can come out as 128.00000000000003, and must be 128 cells, not 129
		int cols = Math.max(1, (int) Math.ceil((maxX - minX) / cell - 1e-9));
		int rows = Math.max(1, (int) Math.ceil((maxY - minY) / cell - 1e-9));
		BufferedImage mask = new BufferedImage(cols, rows, BufferedImage.TYPE_BYTE_BINARY);
		Graphics2D g = mask.createGraphics();
		g.setColor(Color.WHITE);
		Path2D.Double p = new Path2D.Double();
		for (int f : faces)
		{
			p.reset();
			p.moveTo((across[a[f]] - minX) / cell, (down[a[f]] - minY) / cell);
			p.lineTo((across[b[f]] - minX) / cell, (down[b[f]] - minY) / cell);
			p.lineTo((across[c[f]] - minX) / cell, (down[c[f]] - minY) / cell);
			p.closePath();
			g.fill(p);
		}
		g.dispose();
		Raster r = mask.getRaster();
		boolean[][] filled = new boolean[rows][cols];
		int count = 0;
		for (int row = 0; row < rows; row++)
		{
			for (int col = 0; col < cols; col++)
			{
				if (r.getSample(col, row, 0) != 0)
				{
					filled[row][col] = true;
					count++;
				}
			}
		}
		return count == 0 ? null : new Outline(filled, rows, cols, count, minX, minY, cell);
	}

	/** {left, top, right, bottom}: the filled cells' bounds with {@link #TRIM} of them cut from each side. */
	static double[] trimmed(Outline o)
	{
		int[] perCol = new int[o.cols];
		int[] perRow = new int[o.rows];
		for (int r = 0; r < o.rows; r++)
		{
			for (int c = 0; c < o.cols; c++)
			{
				if (o.filled[r][c])
				{
					perCol[c]++;
					perRow[r]++;
				}
			}
		}
		int cut = (int) (o.count * TRIM);
		return new double[]{o.x(first(perCol, cut)), o.y(first(perRow, cut)), o.x(last(perCol, cut) + 1),
			o.y(last(perRow, cut) + 1)};
	}

	/** The whole body: the trimmed bounds, made square around their centre. */
	static Square whole(Outline o)
	{
		double[] t = trimmed(o);
		double side = Math.max(t[2] - t[0], t[3] - t[1]);
		return new Square((t[0] + t[2] - side) / 2, (t[1] + t[3] - side) / 2, side, false);
	}

	/** The first index at which the running total passes cut. */
	private static int first(int[] counts, int cut)
	{
		int sum = 0;
		for (int i = 0; i < counts.length; i++)
		{
			sum += counts[i];
			if (sum > cut)
			{
				return i;
			}
		}
		return counts.length - 1;
	}

	/** The last index at which the running total from the end passes cut. */
	private static int last(int[] counts, int cut)
	{
		int sum = 0;
		for (int i = counts.length - 1; i >= 0; i--)
		{
			sum += counts[i];
			if (sum > cut)
			{
				return i;
			}
		}
		return 0;
	}
}
```

- [ ] **Step 4: Rewrite `Portrait.render` around `framing`.**
  - Replace the whole `render` method, from `/** The portrait, {@code size} pixels square ... */` to its
    closing brace, with the code below.
  - Add `import java.util.ArrayList;` and `import java.util.List;`.
  - Keep `extents`, `describe`, `grey`, `hslToRgb`, `hue`, `gamma` and Task 1's `alpha` as they are.

```java
	/** What {@link #render} draws: the view, its faces, their projection, and the square it shows. */
	static final class Framing
	{
		final View view;
		final Integer[] faces;
		final float[] across, down, depth;
		final PortraitFrame.Square square;

		Framing(View view, Integer[] faces, float[] across, float[] down, float[] depth, PortraitFrame.Square square)
		{
			this.view = view;
			this.faces = faces;
			this.across = across;
			this.down = down;
			this.depth = depth;
			this.square = square;
		}
	}

	/** How this mesh is framed (spec addendum 23), or null when it gets no portrait. */
	static Framing framing(Mesh m)
	{
		double[] e = extents(m);
		if (e == null)
		{
			return null;
		}
		List<Integer> drawable = new ArrayList<>();
		List<Integer> opaque = new ArrayList<>();
		for (int f = 0; f < m.faces; f++)
		{
			if (m.colours3 != null && m.colours3[f] == -2)
			{
				continue;
			}
			drawable.add(f);
			if (alpha(m, f) == 255)
			{
				opaque.add(f);
			}
		}
		// The view (addenda 19 and 20). From the side the screen's horizontal is the model's z and the depth its x;
		// from above the screen's vertical is z (the front, smaller z, at the top) and the depth is y.
		View view = View.of(m.head, e[1] - e[0], e[3] - e[2], e[5] - e[4]);
		float[] across = view == View.SIDE ? m.z : m.x;
		float[] down = view == View.TOP ? m.z : m.y;
		float[] depth = view == View.SIDE ? m.x : view == View.TOP ? m.y : m.z;
		// See-through faces don't frame; a model see-through all over (a ghost) frames on all of them.
		PortraitFrame.Outline o = PortraitFrame.outline(across, down, m.a, m.b, m.c, opaque.isEmpty() ? drawable : opaque);
		if (o == null)
		{
			return null;
		}
		PortraitFrame.Square square;
		if (view == View.UPRIGHT)
		{
			square = uprightCrop(m, drawable, across, e);
		}
		else if (view == View.SIDE)
		{
			square = sideCrop(m, drawable, e);
		}
		else
		{
			square = PortraitFrame.whole(o);
		}
		return new Framing(view, drawable.toArray(new Integer[0]), across, down, depth, square);
	}

	/** Addendum 21's upright crop, as a square. Task 5 replaces it with {@code PortraitFrame.upright}. */
	private static PortraitFrame.Square uprightCrop(Mesh m, List<Integer> faces, float[] across, double[] e)
	{
		double side = (e[3] - e[2]) * HEAD;
		double sum = 0;
		int count = 0;
		for (int f : faces)
		{
			for (int v : new int[]{m.a[f], m.b[f], m.c[f]})
			{
				if (m.y[v] <= e[2] + side)
				{
					sum += across[v];
					count++;
				}
			}
		}
		double centre = count == 0 ? (e[0] + e[1]) / 2 : sum / count;
		return new PortraitFrame.Square(centre - side / 2, e[2], side, false);
	}

	/** Addendum 22's side crop, as a square. Task 6 replaces it with {@code PortraitFrame.side}. */
	private static PortraitFrame.Square sideCrop(Mesh m, List<Integer> faces, double[] e)
	{
		double side = (e[5] - e[4]) * FRONT;
		double top = Double.MAX_VALUE;
		for (int f : faces)
		{
			for (int v : new int[]{m.a[f], m.b[f], m.c[f]})
			{
				if (m.z[v] <= e[4] + side)
				{
					top = Math.min(top, m.y[v]);
				}
			}
		}
		return new PortraitFrame.Square(e[4], top == Double.MAX_VALUE ? e[2] : top, side, false);
	}

	/** The portrait, {@code size} pixels square, or null when the mesh gets none. */
	static BufferedImage render(Mesh m, int size)
	{
		Framing fr = framing(m);
		if (fr == null)
		{
			return null;
		}
		Integer[] faces = fr.faces;
		float[] across = fr.across, down = fr.down, depth = fr.depth;
		// Far first: the spike's yaw-0 dumps showed every model's front with this order (docs/probe/portrait.md).
		// From the side the depth is x, from above it is y (smaller y is up, nearer the eye): the same rule along it.
		Arrays.sort(faces, (p, q) -> Double.compare(
			depth[m.a[q]] + depth[m.b[q]] + depth[m.c[q]],
			depth[m.a[p]] + depth[m.b[p]] + depth[m.c[p]]));

		int big = size * SUPERSAMPLE;
		double scale = (big - 2.0 * SUPERSAMPLE) / Math.max(fr.square.side, 1);
		double offX = SUPERSAMPLE - fr.square.left * scale;
		double offY = SUPERSAMPLE - fr.square.top * scale;

		BufferedImage hi = new BufferedImage(big, big, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = hi.createGraphics();
		int[] xs = new int[3];
		int[] ys = new int[3];
		for (int f : faces)
		{
			int[] vs = {m.a[f], m.b[f], m.c[f]};
			for (int k = 0; k < 3; k++)
			{
				xs[k] = (int) Math.round(across[vs[k]] * scale + offX);
				ys[k] = (int) Math.round(down[vs[k]] * scale + offY); // the client's y points down: head at the top
			}
			boolean textured = m.textures != null && m.textures[f] != -1;
			int rgb = textured ? grey(m.colours1[f]) : hslToRgb(m.colours1[f]);
			g.setColor(new Color(alpha(m, f) << 24 | rgb, true));
			g.fillPolygon(xs, ys, 3);
		}
		g.dispose();

		BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D o = out.createGraphics();
		o.drawImage(hi.getScaledInstance(size, size, Image.SCALE_AREA_AVERAGING), 0, 0, null);
		o.dispose();
		return out;
	}
```

  **Check that the port matches the old code.** The old code drew a non-square box centred in the
  image. The new `whole` square is centred the same way, so offsets are equivalent: `(big − side·scale)/2
  = SUPERSAMPLE` when the box is square. For the UPRIGHT and SIDE crops, the old code already made a
  square, `[centre ± side/2] × [minY, minY + side]` and `[minZ, minZ + side] × [top, top + side]`, and
  the two `…Crop` methods return exactly those squares.

- [ ] **Step 5: Run the whole suite.** Every old test must still pass. The whole-frame views now trim 1%,
  which moves a frame by at most about a cell, and no existing test is that tight.

```bash
./gradlew cleanTest test > "$SCRATCH/t3.log" 2>&1; RC=$?; echo rc=$RC
```
Expected: `rc=0`. **If an old test fails, stop and find out why. Don't rewrite it**: this task is meant to
change no picture beyond the 1% trim.

- [ ] **Step 6: Mutants.**
  - In `framing`, pass `drawable` instead of the opaque-or-drawable choice:
    `aSeeThroughSlabDoesNotMoveTheFrame` fails.
  - In `trimmed`, set `cut` to `0`: `aStrayDotIsTrimmedFromTheBounds` fails.

  Restore both.

- [ ] **Step 7: Count clamped frames offline.**
  - In `PortraitGalleryOffline`, replace the body of `clamped` with:

```java
		Portrait.Framing fr = Portrait.framing(mesh);
		return fr != null && fr.square.clamped ? 1 : 0;
```

  - Update its Javadoc: drop "Task 3 fills this in".
  - Run the suite again: `rc=0`.

- [ ] **Step 8: Commit.**

```bash
git add src/main/java/com/foe/PortraitFrame.java src/main/java/com/foe/Portrait.java src/test/java/com/foe/PortraitFrameTest.java src/test/java/com/foe/PortraitTest.java src/test/java/com/foe/PortraitGalleryOffline.java src/test/java/com/foe/PortraitGalleryOfflineTest.java
git commit -m "Portrait: frame from an outline of the opaque faces (PortraitFrame); whole views trim stray dots

UPRIGHT and SIDE keep their addenda 21-22 crops as squares until Tasks 5-6.
Mutants: framing on drawable -> slab test fails; TRIM cut 0 -> stray-dot test fails.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 9: First offline run.**

```bash
./gradlew galleryOffline --args="build/foe-gallery build/foe-gallery-offline" > "$SCRATCH/off3.log" 2>&1; RC=$?; echo rc=$RC; tail -8 "$SCRATCH/off3.log"
```
Expected: `rc=0`, a count per view, and `not drawn` around 1. Look at `build/foe-gallery-offline/TOP-01.png`:
the Scarab Swarm's dot grid and the Rocks should be framed on the body, not the field.

---

### Task 4: View rules — TOP only for flat, square bodies; a flat model gets no portrait

**Files:**
- Modify: `src/main/java/com/foe/Portrait.java` (`View.of`, the constants, `describe` and `framing`)
- Test: `src/test/java/com/foe/PortraitTest.java`

- [ ] **Step 1: Update the view test to the new rule.** In `theViewFollowsTheBodysShape`, replace

```java
		assertEquals("a spider: low and wide", Portrait.View.TOP, Portrait.View.of(false, 300, 40, 160));
```

with:

```java
		// Spec addendum 23 (G4): TOP only for a flat, square body; every other low body is seen from the side, where its
		// face is. Measured in build/foe-gallery/index.tsv (width, height, length).
		assertEquals("low and wide but not square: side", Portrait.View.SIDE, Portrait.View.of(false, 300, 40, 160));
		assertEquals("spined kraken 15208: top", Portrait.View.TOP, Portrait.View.of(false, 1360, 414, 1292));
		assertEquals("scorpion 2479: top", Portrait.View.TOP, Portrait.View.of(false, 192, 59, 369));
		assertEquals("cockatrice 419: side", Portrait.View.SIDE, Portrait.View.of(false, 238, 187, 225));
		assertEquals("gryphon 14857: side", Portrait.View.SIDE, Portrait.View.of(false, 1244, 419, 798));
		assertEquals("giant frog 477: side", Portrait.View.SIDE, Portrait.View.of(false, 468, 63, 156));
		assertEquals("albatross 15224: side", Portrait.View.SIDE, Portrait.View.of(false, 376, 132, 168));
```

The scorpion check: 59 < 0.32 × 192 = 61.4, and 369 ≥ 0.85 × 192, so TOP. That's the measured
selection in the spec revision, G4. The existing `giant spider 3017: top` line stays, and still passes.

Add a flat-model test:

```java
	/** Spec addendum 23: a model with no height (a ground shadow, a hidden decal) gets no portrait. */
	@Test
	public void aFlatModelGetsNoPortrait()
	{
		Portrait.Mesh shadow = new Portrait.Mesh(new float[]{-50, 50, 50, -50}, new float[4],
			new float[]{-50, -50, 50, 50}, new int[]{0, 0}, new int[]{1, 2}, new int[]{2, 3}, new int[]{RED, RED},
			new int[]{-1, -1}, null, 2, false);
		assertNull(Portrait.render(shadow, 32));
		assertTrue(Portrait.describe(shadow), Portrait.describe(shadow).startsWith("flat"));
	}
```

The existing `aCreatureWiderThanItIsLongIsSeenWholeFromAbove` spider is height 0 and 300 by 60, so it would
become flat (null) and fail the new square rule (G5). Give it height, and make it square:
- In its `new Portrait.Mesh(` call, replace `new float[12],` (the y array) with
  `new float[]{-2, -2, -2, -2, 0, 0, 0, 0, 0, 0, 0, 0},`.
- Replace its z array with
  `new float[]{-130, -130, 130, 130, -10, -10, 10, 10, -10, -10, 10, 10},`.
- Replace the precondition line with
  `assertEquals("precondition", Portrait.View.TOP, Portrait.View.of(false, 300, 2, 260));`.
- Update its comment to say the body is now 260 long, and why (spec revision G5).

- [ ] **Step 2: Run them; the new lines fail.** The old TOP rule calls `(300, 40, 160)` TOP, and
  `aFlatModelGetsNoPortrait` gets a portrait.

```bash
./gradlew cleanTest test --tests com.foe.PortraitTest > "$SCRATCH/t4.log" 2>&1; RC=$?; echo rc=$RC
```
Expected: `rc=1`, failures in `theViewFollowsTheBodysShape` and `aFlatModelGetsNoPortrait`.

- [ ] **Step 3: Change the rule.** Next to `COMPACT`, add:

```java
	/**
	 * Spec addendum 23 (G4): a low body is seen from above only when it is flat (height under this share of its
	 * width): spiders 0.16 to 0.24, krakens 0.21 to 0.30, and 0.32 keeps the spined kraken (0.304). Birds, gryphons,
	 * frogs and cockatrices are 0.13 to 0.79 but fail SQUARE, and go to the side, where their face is.
	 */
	static final double FLAT = 0.32;
	/** ...and square (length at least this share of its width): legs spread all round, not wings or a long body. */
	static final double SQUARE = 0.85;
```

In `View.of`, replace `return length >= width ? SIDE : TOP;` with:

```java
			return height < FLAT * width && length >= SQUARE * width ? TOP : SIDE;
```

Update the `TOP` and `SIDE` enum Javadocs to match: TOP is "flat and square: from above, whole", and
SIDE is "every other low body: from the side, at its head".

- [ ] **Step 4: No portrait for a flat model.**
  - In `framing`, change `if (e == null)` to `if (e == null || e[3] - e[2] <= 0)`.
  - In `describe`, after the `e == null` check, add:

```java
		if (e[3] - e[2] <= 0)
		{
			return "flat (width " + Math.round(e[1] - e[0]) + ", height 0, length " + Math.round(e[5] - e[4]) + ")";
		}
```

The lower-case `flat` doesn't match the gallery's `[A-Z]+` view pattern, so `parseDescribe` returns null
and the gallery counts it as not drawable. That's checked in step 5.

- [ ] **Step 5: Run the whole suite.**

```bash
./gradlew cleanTest test > "$SCRATCH/t4.log" 2>&1; RC=$?; echo rc=$RC
```
Expected: `rc=0`. If a gallery test fails on `describe`, read it before changing anything: the
`flat` line must parse to null.

- [ ] **Step 6: Mutants.**
  - `FLAT = 0.30`: the spined kraken line fails.
  - Drop `&& length >= SQUARE * width`: the frog and gryphon lines fail.
  - Remove the `e[3] - e[2] <= 0` check from `framing`: `aFlatModelGetsNoPortrait` fails.

  Restore all three.

- [ ] **Step 7: Commit, then run offline.**

```bash
git add src/main/java/com/foe/Portrait.java src/test/java/com/foe/PortraitTest.java
git commit -m "Portrait views: TOP only for flat square bodies (FLAT 0.32, SQUARE 0.85); flat models get none

Measured selection in spec revision G4. Mutants: FLAT 0.30, no SQUARE clause, no flat check -> each fails.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
./gradlew galleryOffline --args="build/foe-gallery build/foe-gallery-offline" > "$SCRATCH/off4.log" 2>&1; RC=$?; echo rc=$RC; tail -8 "$SCRATCH/off4.log"
```
Expected: TOP about 34 rows, SIDE larger than before, and the Shadow 4004, Suspicious water 5946 and
Me? 12235 in `unrendered.tsv` as `NOT_DRAWABLE`.

---

### Task 5: UPRIGHT frames the head

**Files:**
- Modify: `src/main/java/com/foe/PortraitFrame.java` (add constants and `upright`, plus helpers)
- Modify: `src/main/java/com/foe/Portrait.java` (call `PortraitFrame.upright`; delete `uprightCrop` and `HEAD`)
- Test: `src/test/java/com/foe/PortraitTest.java`

- [ ] **Step 1: Write the failing tests.** Add them to `PortraitTest`, with these helpers:

```java
	/** Adds an axis-aligned rectangle in the x-y plane (z 0) as two triangles. */
	private static void rect(java.util.List<float[]> v, java.util.List<int[]> f, java.util.List<Integer> colours,
		float x0, float y0, float x1, float y1, int colour)
	{
		int i = v.size();
		v.add(new float[]{x0, y0, 0});
		v.add(new float[]{x1, y0, 0});
		v.add(new float[]{x1, y1, 0});
		v.add(new float[]{x0, y1, 0});
		f.add(new int[]{i, i + 1, i + 2});
		f.add(new int[]{i, i + 2, i + 3});
		colours.add(colour);
		colours.add(colour);
	}

	/** Adds a triangle in the x-y plane (z 0). */
	private static void tri(java.util.List<float[]> v, java.util.List<int[]> f, java.util.List<Integer> colours,
		float[] p, float[] q, float[] r, int colour)
	{
		int i = v.size();
		v.add(new float[]{p[0], p[1], 0});
		v.add(new float[]{q[0], q[1], 0});
		v.add(new float[]{r[0], r[1], 0});
		f.add(new int[]{i, i + 1, i + 2});
		colours.add(colour);
	}

	private static Portrait.Mesh mesh(java.util.List<float[]> v, java.util.List<int[]> f,
		java.util.List<Integer> colours)
	{
		int n = v.size(), m = f.size();
		float[] x = new float[n], y = new float[n], z = new float[n];
		for (int i = 0; i < n; i++)
		{
			x[i] = v.get(i)[0];
			y[i] = v.get(i)[1];
			z[i] = v.get(i)[2];
		}
		int[] a = new int[m], b = new int[m], c = new int[m], col = new int[m], c3 = new int[m];
		for (int i = 0; i < m; i++)
		{
			a[i] = f.get(i)[0];
			b[i] = f.get(i)[1];
			c[i] = f.get(i)[2];
			col[i] = colours.get(i);
			c3[i] = -1;
		}
		return new Portrait.Mesh(x, y, z, a, b, c, col, c3, null, m, false);
	}

	/** Pixels of the portrait that are solid and red (the head, in these tests). */
	private static int redPixels(BufferedImage img)
	{
		int n = 0;
		for (int y = 0; y < img.getHeight(); y++)
		{
			for (int x = 0; x < img.getWidth(); x++)
			{
				int c = img.getRGB(x, y);
				if ((c >>> 24) >= 128 && (c >> 16 & 255) > 150 && (c & 255) < 100)
				{
					n++;
				}
			}
		}
		return n;
	}

	private static boolean red(int c)
	{
		return (c >>> 24) >= 128 && (c >> 16 & 255) > 150 && (c & 255) < 100;
	}
```

And these tests. Red is the head and blue is everything else. Each body is a flat panel at z 0, so the
view is UPRIGHT (length 0).

```java
	/** Operator's gallery: pyrefiends, antelopes and unicorns showed only horns. A tall thin horn is not the head. */
	@Test
	public void aHornDoesNotSetTheTop()
	{
		java.util.List<float[]> v = new java.util.ArrayList<>();
		java.util.List<int[]> f = new java.util.ArrayList<>();
		java.util.List<Integer> col = new java.util.ArrayList<>();
		rect(v, f, col, -50, -300, 50, 0, BLUE);                                  // torso
		rect(v, f, col, -40, -380, 40, -300, RED);                                // head
		tri(v, f, col, new float[]{-3, -380}, new float[]{3, -380}, new float[]{0, -560}, BLUE); // horn
		Portrait.Mesh m = mesh(v, f, col);
		BufferedImage img = Portrait.render(m, 60);
		assertTrue("the head is near the top, under a small margin", red(img.getRGB(30, 8)));
		assertTrue("and fills the frame well: " + redPixels(img), redPixels(img) > 600);
	}

	/** Operator: winged demons sat low with empty space above. Wingtips rising over the head don't set the top. */
	@Test
	public void wingtipsDoNotSetTheTop()
	{
		java.util.List<float[]> v = new java.util.ArrayList<>();
		java.util.List<int[]> f = new java.util.ArrayList<>();
		java.util.List<Integer> col = new java.util.ArrayList<>();
		rect(v, f, col, -50, -300, 50, 0, BLUE);                                  // torso
		rect(v, f, col, -25, -380, 25, -300, RED);                                // head
		tri(v, f, col, new float[]{-50, -300}, new float[]{-50, -250}, new float[]{-200, -460}, BLUE); // left wing
		tri(v, f, col, new float[]{50, -300}, new float[]{50, -250}, new float[]{200, -460}, BLUE);    // right wing
		BufferedImage img = Portrait.render(mesh(v, f, col), 60);
		assertTrue("the head is near the top, not under the wingtips", red(img.getRGB(30, 8)));
		assertTrue("and close: " + redPixels(img), redPixels(img) > 600);
	}

	/** Operator: Zalcano, Hespori, Eldric: sparks around the body shrank it. Far dots don't move the frame. */
	@Test
	public void farDotsDoNotMoveTheUprightFrame()
	{
		java.util.List<float[]> v = new java.util.ArrayList<>();
		java.util.List<int[]> f = new java.util.ArrayList<>();
		java.util.List<Integer> col = new java.util.ArrayList<>();
		rect(v, f, col, -50, -300, 50, 0, BLUE);
		rect(v, f, col, -40, -380, 40, -300, RED);
		PortraitFrame.Square bare = Portrait.framing(mesh(v, f, col)).square;
		for (float[] d : new float[][]{{-300, -700}, {300, -700}, {-300, 300}, {300, 300}})
		{
			rect(v, f, col, d[0], d[1], d[0] + 4, d[1] + 4, BLUE);
		}
		PortraitFrame.Square dotted = Portrait.framing(mesh(v, f, col)).square;
		assertEquals("side within 15%", bare.side, dotted.side, bare.side * 0.15);
		assertEquals("top within 15 units", bare.top, dotted.top, 15);
		assertEquals("centre within 15 units", bare.left + bare.side / 2, dotted.left + dotted.side / 2, 15);
	}
```

**Checking the expected values by hand** (the spec's algorithm):
- **Horn test:** the cell is 560/128 = 4.4. The central runs are torso 23, head 18 and horn 1 to 2, so
  the median is 23 and the threshold 4.6, which skips the horn. The head top is −380. The band stops
  after about 18 rows, with a size of 18 cells (80 units), so S = 176 and the top is −397.6. Row 8 of
  60 is −397.6 + 8.5 × 2.93 = −372.7, inside the head, so red. The red area is about 27 × 27 px, well
  over 600.
- **Wing test:** the median central run is the torso's 100 units, the threshold 20, and the head is 50
  wide, so the head top is −380. With the old crop, the box top is the wingtip at −460 and the head sits
  at rows 30 to 60, so row 8 isn't red.

- [ ] **Step 2: Run them; the horn and wing tests fail on the old crop.**

```bash
./gradlew cleanTest test --tests com.foe.PortraitTest > "$SCRATCH/t5.log" 2>&1; RC=$?; echo rc=$RC
```
Expected: `rc=1`, with `aHornDoesNotSetTheTop` and `wingtipsDoNotSetTheTop` failing on the row-8 check.
`farDotsDoNotMoveTheUprightFrame` may also fail: the old crop's height share moves with the dots.

- [ ] **Step 3: Add `upright` to `PortraitFrame`.** Add `import java.util.ArrayList;` and
  `import java.util.Collections;`, the constants after `TRIM`:

```java
	/** A row is head, not horn or spike, once its central run is this share of the median run (spec section 4). */
	static final double HEAD_MIN = 0.2;
	/** Head and shoulders: the square is this many times the head's size across. */
	static final double SHOULDERS = 2.2;
	/** Space over the head, as a share of the square. */
	static final double MARGIN = 0.1;
```

and these methods after `whole`:

```java
	/**
	 * An upright body (spec section 4, revisions G1 and G3): its centre line is the median column of the filled
	 * cells; each row's run is the filled stretch across that line; the head starts at the first row whose run is
	 * HEAD_MIN of the median run, so a horn or spike above it (thin) and wingtips (off the line) are skipped.
	 */
	static Square upright(Outline o)
	{
		int c0 = medianColumn(o);
		int[] runs = new int[o.rows];
		double[] mids = new double[o.rows];
		for (int r = 0; r < o.rows; r++)
		{
			if (!o.filled[r][c0])
			{
				continue;
			}
			int left = c0;
			int right = c0;
			while (left > 0 && o.filled[r][left - 1])
			{
				left--;
			}
			while (right < o.cols - 1 && o.filled[r][right + 1])
			{
				right++;
			}
			runs[r] = right - left + 1;
			mids[r] = (left + right + 1) / 2.0;
		}
		int top = headStart(runs);
		List<Integer> band = band(runs, top);
		double centre = 0;
		List<Integer> sizes = new ArrayList<>();
		for (int r : band)
		{
			centre += mids[r];
			sizes.add(runs[r]);
		}
		return around(o, o.x(centre / band.size()), o.y(top), median(sizes) * o.cell);
	}

	/**
	 * The column at which the running count of filled cells reaches half: the body's centre line (G3: the median,
	 * so a shield on one side does not pull it off the head). It always holds a filled cell: the count rises there.
	 */
	static int medianColumn(Outline o)
	{
		int sum = 0;
		for (int c = 0; c < o.cols; c++)
		{
			for (int r = 0; r < o.rows; r++)
			{
				if (o.filled[r][c])
				{
					sum++;
				}
			}
			if (2 * sum >= o.count)
			{
				return c;
			}
		}
		return o.cols - 1;
	}

	/**
	 * The first index whose run is at least HEAD_MIN of the median non-zero run. A non-empty outline always has one
	 * (the median run passes its own threshold; spec revision G7), so there is no not-found case.
	 */
	static int headStart(int[] runs)
	{
		List<Integer> nonzero = new ArrayList<>();
		for (int r : runs)
		{
			if (r > 0)
			{
				nonzero.add(r);
			}
		}
		double min = HEAD_MIN * median(nonzero);
		int i = 0;
		while (runs[i] == 0 || runs[i] < min)
		{
			i++;
		}
		return i;
	}

	/**
	 * The head's band (revision G1): the non-empty runs from start on, one step at a time, stopping once the band is
	 * as long as the median run in it, so a head about as tall as it is wide stops at the neck, before the shoulders
	 * and a demon's wings.
	 */
	static List<Integer> band(int[] runs, int start)
	{
		List<Integer> in = new ArrayList<>();
		List<Integer> sizes = new ArrayList<>();
		for (int i = start; i < runs.length; i++)
		{
			if (runs[i] > 0)
			{
				in.add(i);
				sizes.add(runs[i]);
			}
			if (i - start + 1 >= median(sizes))
			{
				break;
			}
		}
		return in;
	}

	/** Head and shoulders: SHOULDERS times the head, centred across on it, MARGIN of it above the head's top. */
	private static Square around(Outline o, double centre, double top, double head)
	{
		double[] t = trimmed(o);
		double most = Math.max(t[2] - t[0], t[3] - t[1]);
		double side = SHOULDERS * head;
		boolean clamped = side > most;
		if (clamped)
		{
			side = most;
		}
		return new Square(centre - side / 2, top - MARGIN * side, side, clamped);
	}

	/** The upper median of a non-empty list. */
	private static int median(List<Integer> values)
	{
		List<Integer> sorted = new ArrayList<>(values);
		Collections.sort(sorted);
		return sorted.get(sorted.size() / 2);
	}
```

**Why `headStart`'s loop ends:** the median value is in the list, it is non-zero, and it is at least
`HEAD_MIN` times itself. So some index satisfies the condition before `runs.length`. `band` always adds
`start`, because `runs[start] > 0`, so `median(sizes)` never sees an empty list.

- [ ] **Step 4: Use it.** In `Portrait.framing`, replace

```java
			square = uprightCrop(m, drawable, across, e);
```

with

```java
			square = PortraitFrame.upright(o);
```

Delete the `uprightCrop` method, and the `HEAD` constant with its Javadoc.

- [ ] **Step 5: Run the whole suite. Expected fallout** (spec section 5 supersedes addendum 21's crop):
  - **`anUprightCropReachesPastAThirdOfTheHeight` fails.** It pinned "the crop covers 35% of the height".
    A plain box has no head, so the new frame (2.2 × its width) shows less of it. Its intent, a demon's
    head in frame below the wingtips, is now `wingtipsDoNotSetTheTop`. Delete it, and say so in the
    commit.
  - **`aBodyIsCroppedToASquareOverItsTopThirtyPercent`** should still pass. The 100 × 400 box frames to
    a 220 square from −422 to −202, which is red only, red at the bottom edge, and clear at the sides.
    Rename it to `aPlainBodyIsFramedHeadAndShouldersFromTheTop`, and change its first comment line to
    describe the 220 square.
  - **`anUprightBodyStillShowsItsTopFromTheFront`** should still pass, for the same reason.
  - **Any other failure: stop and investigate.** Don't rewrite the test.

```bash
./gradlew cleanTest test > "$SCRATCH/t5.log" 2>&1; RC=$?; echo rc=$RC
```
Expected after the delete and the rename: `rc=0`.

- [ ] **Step 6: Mutants.** Run each, see the named test fail, and restore.
  - `HEAD_MIN = 0`: `aHornDoesNotSetTheTop` fails, because the horn becomes the head top.
  - `medianColumn` returns `0`: the wing test fails, because column 0 holds only a wingtip.
  - In `band`, remove the `break`: `wingtipsDoNotSetTheTop` fails on the red count, because the band
    reaches the wings and the frame clamps to the whole body.

- [ ] **Step 7: Commit, then run offline.**

```bash
git add src/main/java/com/foe/PortraitFrame.java src/main/java/com/foe/Portrait.java src/test/java/com/foe/PortraitTest.java
git commit -m "Portrait: UPRIGHT frames the head and shoulders (PortraitFrame.upright)

Replaces addendum 21's 35% crop; anUprightCropReachesPastAThirdOfTheHeight deleted (its intent is
wingtipsDoNotSetTheTop). Mutants: HEAD_MIN 0, medianColumn 0, band without break -> each fails.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
./gradlew galleryOffline --args="build/foe-gallery build/foe-gallery-offline" > "$SCRATCH/off5.log" 2>&1; RC=$?; echo rc=$RC; tail -8 "$SCRATCH/off5.log"
```

Look at the offline UPRIGHT sheets for the black demons (240, 1432, 2049–2052), the Greater demons
(2026–2029), the Pyrefiend 433, the Sunlight antelope 13133, the Hobgoblin Champion 3354, the Zalcano
9050 and the Hespori 8583, and note the clamped count for UPRIGHT. Write the findings into Task 7's
table. Don't tune yet.

---

### Task 6: SIDE frames the head

**Files:**
- Modify: `src/main/java/com/foe/PortraitFrame.java` (add `side`)
- Modify: `src/main/java/com/foe/Portrait.java` (call `PortraitFrame.side`; delete `sideCrop` and `FRONT`)
- Test: `src/test/java/com/foe/PortraitTest.java`

- [ ] **Step 1: Write the failing tests.** Side-on bodies are panels in the y-z plane (x = 0); the front
  is the smaller z. Add this helper beside `rect`:

```java
	/** Adds an axis-aligned rectangle in the z-y plane (x 0) as two triangles: a side-on panel. */
	private static void sideRect(java.util.List<float[]> v, java.util.List<int[]> f, java.util.List<Integer> colours,
		float z0, float y0, float z1, float y1, int colour)
	{
		int i = v.size();
		v.add(new float[]{0, y0, z0});
		v.add(new float[]{0, y0, z1});
		v.add(new float[]{0, y1, z1});
		v.add(new float[]{0, y1, z0});
		f.add(new int[]{i, i + 1, i + 2});
		f.add(new int[]{i, i + 2, i + 3});
		colours.add(colour);
		colours.add(colour);
	}
```

and these tests:

```java
	/** Operator: tribesmen, goblins and aviansies showed only a spear. A spear held forward is not the head. */
	@Test
	public void aSpearHeldForwardIsNotTheHead()
	{
		java.util.List<float[]> v = new java.util.ArrayList<>();
		java.util.List<int[]> f = new java.util.ArrayList<>();
		java.util.List<Integer> col = new java.util.ArrayList<>();
		sideRect(v, f, col, -100, -100, 300, 0, BLUE);    // body
		sideRect(v, f, col, -200, -150, -100, -60, RED);  // head
		sideRect(v, f, col, -500, -121, -100, -119, BLUE); // spear, 2 thick
		Portrait.Mesh m = mesh(v, f, col);
		assertEquals("precondition", Portrait.View.SIDE, Portrait.View.of(false, 0, 150, 800));
		assertTrue("the head fills the frame: " + redPixels(Portrait.render(m, 60)),
			redPixels(Portrait.render(m, 60)) > 400);
	}

	/** Operator: almost every dragon showed only a wing. A tall wing behind the head is not the head. */
	@Test
	public void aDragonsWingIsNotItsHead()
	{
		java.util.List<float[]> v = new java.util.ArrayList<>();
		java.util.List<int[]> f = new java.util.ArrayList<>();
		java.util.List<Integer> col = new java.util.ArrayList<>();
		sideRect(v, f, col, -100, -100, 500, 0, BLUE);    // body
		sideRect(v, f, col, -250, -130, -100, -70, RED);  // head and snout
		int i = v.size();                                   // wing: z -150..100 at the root, up to y -500 at z -50
		v.add(new float[]{0, -100, -150});
		v.add(new float[]{0, -100, 100});
		v.add(new float[]{0, -500, -50});
		f.add(new int[]{i, i + 1, i + 2});
		col.add(BLUE);
		Portrait.Mesh m = mesh(v, f, col);
		assertEquals("precondition", Portrait.View.SIDE, Portrait.View.of(false, 0, 500, 750));
		assertTrue("the head, not the wing: " + redPixels(Portrait.render(m, 60)),
			redPixels(Portrait.render(m, 60)) > 400);
	}

	/** Operator: snakes were a sliver at the top of the frame. A long thin body's head is drawn large. */
	@Test
	public void aSnakesHeadIsDrawnLarge()
	{
		java.util.List<float[]> v = new java.util.ArrayList<>();
		java.util.List<int[]> f = new java.util.ArrayList<>();
		java.util.List<Integer> col = new java.util.ArrayList<>();
		sideRect(v, f, col, -400, -20, -340, 0, RED);  // head
		sideRect(v, f, col, -340, -20, 400, 0, BLUE);  // body
		assertTrue("the head is large: " + redPixels(Portrait.render(mesh(v, f, col), 60)),
			redPixels(Portrait.render(mesh(v, f, col), 60)) > 600);
	}
```

**Checking the expected values by hand:**
- **Spear:** the cell is 800/128 = 6.25. Spear columns are 0 or 1 cell, head columns 15, body columns 16,
  so the median is 15 or 16 and the threshold about 3.1, which skips the spear. The head front is
  z −200, the band is 15 columns (94 units), S = 206, and the head is about 29 × 26 px, over 400. With
  the old crop, the front 30% of 800 is z −500 to −260, which is spear only, so 0 red.
- **Dragon:** the cell is 750/128 = 5.86. Head columns are 10 cells and body-only columns 17, so the
  median is 17 and the threshold 3.4. The front is z −250 and the band about 10 columns (59 units), so
  S = 129 at z −285 to −156. The head shows about 44 × 28 px. With the old crop, the slab's top is the
  wing apex at y −500, so the frame is all wing and 0 red.
- **Snake:** about 3 rows tall, so S is about 41 units and the head about 44 × 29 px. With the old crop
  (front 30% = 240 units), the head is about 15 × 5 px.

- [ ] **Step 2: Run them; all three fail on the old crop.**

```bash
./gradlew cleanTest test --tests com.foe.PortraitTest > "$SCRATCH/t6.log" 2>&1; RC=$?; echo rc=$RC
```
Expected: `rc=1` with the three new tests failing on their red counts.

- [ ] **Step 3: Add `side` to `PortraitFrame`.** Put it after `upright`:

```java
	/**
	 * A body seen from the side, the front on the left (spec section 4): each column's run is its tallest filled
	 * stretch; the head starts at the first column from the front whose run is HEAD_MIN of the median run, so a spear
	 * or lance held forward (thin) is skipped, and a dragon's wing (behind the head) never sets the frame.
	 */
	static Square side(Outline o)
	{
		int[] runs = new int[o.cols];
		int[] tops = new int[o.cols];
		for (int c = 0; c < o.cols; c++)
		{
			int r = 0;
			while (r < o.rows)
			{
				if (!o.filled[r][c])
				{
					r++;
					continue;
				}
				int start = r;
				while (r < o.rows && o.filled[r][c])
				{
					r++;
				}
				if (r - start > runs[c])
				{
					runs[c] = r - start;
					tops[c] = start;
				}
			}
		}
		int front = headStart(runs);
		List<Integer> band = band(runs, front);
		double centre = 0;
		int top = Integer.MAX_VALUE;
		List<Integer> sizes = new ArrayList<>();
		for (int c : band)
		{
			centre += c + 0.5;
			top = Math.min(top, tops[c]);
			sizes.add(runs[c]);
		}
		return around(o, o.x(centre / band.size()), o.y(top), median(sizes) * o.cell);
	}
```

- [ ] **Step 4: Use it.** In `Portrait.framing`, replace `square = sideCrop(m, drawable, e);` with
  `square = PortraitFrame.side(o);`. Delete `sideCrop` and the `FRONT` constant with its Javadoc. Update
  the `SIDE` enum Javadoc's "cropped to the front" to "framed on its head".

- [ ] **Step 5: Run the whole suite. Expected fallout:**
  - **`aCreatureOnAllFoursShowsItsHeadFromTheSide` (the rat) should still pass.** By hand: S = 89 at
    z −225 to −136, all red, about 1,170 red pixels.
  - **`theSideCropStartsAtTheTopOfTheFront` (the horse) should still pass.** By hand: S = 220 at
    y −322 to −102, which shows the head and body but not the legs.
  - **Any failure: stop and investigate.** Don't rewrite the test.

```bash
./gradlew cleanTest test > "$SCRATCH/t6.log" 2>&1; RC=$?; echo rc=$RC
```
Expected: `rc=0`.

- [ ] **Step 6: Mutants.** Run each, see the named test fail, and restore.
  - In `side`, set `tops[c] = 0;` instead of `tops[c] = start;`. The dragon test fails, because the frame
    top goes to the outline's top, the wing apex. (Checked by hand: "first run instead of tallest" would
    *survive*, because the dragon's front columns hold only the head, so it is not a useful mutant.)
  - `HEAD_MIN = 0`: the spear test fails.
  - Make `around` ignore `MARGIN` (set the top to `top`): no test may fail. That's a known gap. Write it
    down in the commit as "MARGIN is tuned by eye in Task 7, not pinned". Don't add a decorative test.

- [ ] **Step 7: Commit, then run offline.**

```bash
git add src/main/java/com/foe/PortraitFrame.java src/main/java/com/foe/Portrait.java src/test/java/com/foe/PortraitTest.java
git commit -m "Portrait: SIDE frames the head (PortraitFrame.side): spears, wings and snakes

Replaces addendum 22's front-slab crop. Mutants: tops[c] = 0 -> dragon test fails;
HEAD_MIN 0 -> spear test fails. MARGIN is not pinned by a test; tuned by eye in Task 7.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
./gradlew galleryOffline --args="build/foe-gallery build/foe-gallery-offline" > "$SCRATCH/off6.log" 2>&1; RC=$?; echo rc=$RC; tail -8 "$SCRATCH/off6.log"
```

---

### Task 7: Tune over the whole corpus, then the operator's look in game

**Files:**
- Modify: `src/main/java/com/foe/PortraitFrame.java` and `Portrait.java` (constants only)
- Modify: `docs/superpowers/specs/2026-10-15-portrait-framing-design.md` (a tuning table, appended)

- [ ] **Step 1: Score the checklist.** For each group in the review note's cause table (causes 1–5 and 7–11;
  cause 6 is out of scope), open the offline sheet that holds its ids. Mark each id better, same or
  worse against `build/foe-gallery-before/`. Do the same for the reference cells: Imp 14181, Jungle
  horror 1042, Dagannoth Prime 2266 and Supreme 2265, Spinolyp 5947, Apparition 4196, Defiler 1724, and
  the goblin chathead 660. Use the Read tool on the PNGs; a sheet is large, so crop with Pillow first:
  `uv run --with pillow python3 -c "from PIL import Image; im=Image.open('build/foe-gallery-offline/UPRIGHT-01.png'); print(im.size)"`.
  Each cell's position is the `cell` column in `index.tsv`. Cells run 10 across, so cell n is at row
  (n−1)//10 and column (n−1)%10; the cell sizes are `PortraitGallery.CELL_W` and `CELL_H`.
- [ ] **Step 2: Tune one constant at a time.** Change it, re-run `galleryOffline`, and re-score.
  - **Candidates:** `HEAD_MIN` (0.15 to 0.3), `SHOULDERS` (1.8 to 2.6), `MARGIN` (0.05 to 0.15).
  - **Keep a change only if** no reference cell gets worse and the checklist count improves.
  - **After each kept change,** run the whole suite. A unit test that breaks means the change broke a
    rule that a test pins. Read the test before touching the constant again.
- [ ] **Step 3: Record it.** Append to the spec a "Tuning (YYYY-MM-DD)" table: each constant's final
  value, the checklist counts before and after (better, same, worse), the clamped count per view, and
  the remaining worse cells by id.
- [ ] **Step 4: Commit** the constants and the table, with the counts in the message.
- [ ] **Step 5: CHECKPOINT B, the operator in game.** `./gradlew run`, log in, `::foegallery`. The
  operator reviews the sheets against their screenshot folder. Their verdicts decide whether to tune
  once more (back to step 2) or go on. Also ask them to fight one demon and one dragon, to see the
  portrait in the panel itself.

---

### Task 8: Fresh-context review, records, and the PR

- [ ] **Step 1: Review.** Spawn the `reviewer` agent with:
  - the spec path;
  - this plan's path;
  - the branch diff against `main`.

  Tell it the code is a proposal, not a spec. Ask it to attack these questions:
  - Can `headStart` loop past the end?
  - Can `band` see an empty list?
  - Does `outline` miss faces thinner than a cell, and does that matter?
  - Is the transparency direction tested in the way the game uses it?
  - Can any portrait render without a frame, or with a negative side?

  Require it to give a reproducing test for every claim. Fix confirmed findings test-first, with mutants.
- [ ] **Step 2: Records.**
  - Append to the main spec's addendum 23 one paragraph naming the final constants and the PR.
  - Update PICKUP's START HERE: framing done; the ribbon bosses (cause 6) wait for a probe; v1.1 is the
    monster browser.
  - Append a plain-English lesson to `LESSONS.md` if the repo has one. Otherwise ask the operator
    whether to start one. Its subject: fixing framing one monster at a time regressed others three
    times, and a whole-corpus harness is what ended that.
- [ ] **Step 3: Gate and PR.**
  - Run the whole suite one more time and capture `rc`.
  - Ask the operator to `! git push -u origin feat/portrait-framing`.
  - `gh pr create`; its body ends with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
  - Then check that `gh pr view --json mergeable` is `MERGEABLE`, and that a CI run exists for the
    head SHA: `gh run list --branch feat/portrait-framing`.

---

## Self-review against the spec

**Spec coverage:**
- Section 1, transparency: Task 1 draws it, Task 3 keeps it out of framing.
- Section 2, the outline and trimmed bounds: Task 3.
- Section 3, the views: TOP and flat in Task 4; UPRIGHT and SIDE keep their rules.
- Section 4, the head: UPRIGHT in Task 5, SIDE in Task 6. Revisions G1 (band), G3 (median column) and
  the median-not-quartile correction are in the Task 5 code.
- Section 5, the frame: `around` (Task 5) for head-and-shoulders, `whole` (Task 3) for the rest. G7
  (no fallback) holds: there is no fallback code. `clamped` is reported (Tasks 3 and 7).
- Testing: every spec table row has a test in Tasks 1, 3, 4, 5 or 6. The "no head" row is dropped per G7.
- Acceptance: Task 0 step 3 (the before set), checkpoints A and B, and Task 7.

**Types:**
- `PortraitFrame.Square(left, top, side, clamped)` and
  `Outline(filled, rows, cols, count, minX, minY, cell)` are defined in Task 3 and used in Tasks 5, 6
  and 7.
- `Portrait.Framing.square` is used by the offline tool (Task 3, step 7) and the tests.
- `Portrait.Mesh`'s 12-argument constructor (Task 1) is used by `PortraitMeshFile` (Task 2).

**Known gap:** no test pins `MARGIN` (Task 6, step 6). It is tuned by eye, deliberately.
