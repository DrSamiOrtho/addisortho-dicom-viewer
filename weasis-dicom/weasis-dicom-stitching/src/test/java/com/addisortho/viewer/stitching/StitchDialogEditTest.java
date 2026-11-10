/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.stitching;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.weasis.dicom.explorer.library.StoredInstance;
import org.weasis.dicom.explorer.library.StudyStore;

/**
 * The stitch window itself, driven by its own buttons, tables and mouse, with a project store in a
 * temporary folder in place of the viewer's. The windows are built but never shown, and every
 * message the window would show goes to the test instead. Skipped where there is no display, and
 * with {@code -Dstitch.window.tests=false}.
 *
 * <p>That switch is there because of one run on macOS with JDK 27 (10 Nov 2026) in which the test
 * JVM itself died in a native display thread (CVDisplayLink) while these tests ran against a build
 * broken on purpose. It was not seen in any other run and could not be made to happen again with
 * windows built and disposed in a loop. If the JVM dies here, that is what to look at first.
 */
class StitchDialogEditTest {

  private static final long WAIT_MS = 120_000;

  @TempDir Path dir;

  @BeforeAll
  static void needsADisplay() {
    System.setProperty("apple.awt.UIElement", "true"); // no Dock icon while the tests run
    assumeFalse(
        "false".equals(System.getProperty("stitch.window.tests")), "the window tests are off");
    assumeFalse(GraphicsEnvironment.isHeadless(), "the stitch window needs a display");
  }

  /** The viewer around the window: temporary stores, and what the window told or asked. */
  private class TestHost implements StitchDialog.Host {
    final StudyStore library = new StudyStore(dir.resolve("library"), true);
    final FolderProjectStore folder = new FolderProjectStore(dir.resolve("projects"));
    final StitchProjects projects;
    final List<String> told = new CopyOnWriteArrayList<>();
    final List<String[]> asked = new CopyOnWriteArrayList<>();
    final List<StoredInstance> opened = new CopyOnWriteArrayList<>();
    final List<String> questions = new CopyOnWriteArrayList<>();
    final List<String[]> buttons = new CopyOnWriteArrayList<>();
    volatile int pick;
    // the button pressed when the window asks about unsaved work, by its message key
    volatile String answer = "StitchDialog.cancel";

    TestHost(boolean keepsProjects) {
      projects = keepsProjects ? new StitchProjects(folder) : null;
    }

    @Override
    public StitchProjects projects() {
      return projects;
    }

    @Override
    public StitchService.Saved save(
        Stitch s,
        List<SeamRecipe> recipes,
        StitchResume.Lineage lineage,
        Compositor.Progress progress)
        throws IOException {
      return StitchService.save(library, projects, s, recipes, lineage, progress);
    }

    @Override
    public void open(StoredInstance saved, Runnable notOpened) {
      opened.add(saved);
    }

    @Override
    public void tell(String message, int type) {
      told.add(message);
    }

    @Override
    public int choose(String question, String[] options) {
      asked.add(options);
      return pick;
    }

    @Override
    public int ask(String question, String[] offered) {
      questions.add(question);
      buttons.add(offered);
      return List.of(offered).indexOf(Messages.getString(answer));
    }
  }

  /** One stitch window and the hands that drive it. */
  private static final class Window {
    final StitchDialog dialog;
    final TestHost host;

    Window(TestHost host, List<Piece> open) throws Exception {
      this.host = host;
      List<StitchService.Candidate> candidates =
          open.stream().map(p -> new StitchService.Candidate(p, List.of(), "")).toList();
      dialog =
          onEdt(
              () ->
                  new StitchDialog(
                      null, new StitchService.Study(null, candidates, List.of()), host));
    }

    <T extends Component> List<T> all(Class<T> type) {
      List<T> out = new ArrayList<>();
      collect(dialog.getContentPane(), type, out);
      return out;
    }

    private static <T extends Component> void collect(Component c, Class<T> type, List<T> out) {
      if (type.isInstance(c)) {
        out.add(type.cast(c));
      }
      if (c instanceof Container parent) {
        for (Component child : parent.getComponents()) {
          collect(child, type, out);
        }
      }
    }

    AbstractButton button(String key) {
      String text = Messages.getString(key);
      return all(AbstractButton.class).stream()
          .filter(b -> text.equals(b.getText()))
          .findFirst()
          .orElse(null);
    }

    boolean enabled(String key) throws Exception {
      return onEdt(() -> button(key) != null && button(key).isEnabled());
    }

    void click(String key) throws Exception {
      assertTrue(enabled(key), key + " can be pressed");
      onEdt(
          () -> {
            button(key).doClick(0);
            return null;
          });
    }

    JTable pieces() {
      return all(JTable.class).get(0);
    }

    JTable seams() {
      return all(JTable.class).get(1);
    }

    /** Ticks the first {@code n} pieces and confirms everything, as a user must before Line up. */
    void tickAndConfirm(int n) throws Exception {
      onEdt(
          () -> {
            for (int i = 0; i < n; i++) {
              pieces().getModel().setValueAt(true, i, 0);
            }
            all(JCheckBox.class).get(0).doClick(0);
            for (JComboBox<?> choice : combos()) {
              choice.setSelectedIndex(1);
            }
            return null;
          });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    List<JComboBox<?>> combos() {
      return (List) all(JComboBox.class);
    }

    void close() throws Exception {
      onEdt(
          () -> {
            dialog.dispose();
            return null;
          });
    }
  }

  private static <T> T onEdt(Callable<T> call) throws Exception {
    AtomicReference<T> out = new AtomicReference<>();
    AtomicReference<Exception> failed = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            out.set(call.call());
          } catch (Exception e) {
            failed.set(e);
          }
        });
    if (failed.get() != null) {
      throw failed.get();
    }
    return out.get();
  }

  private static void await(String what, Callable<Boolean> done) throws Exception {
    long end = System.currentTimeMillis() + WAIT_MS;
    while (!done.call()) {
      assertTrue(System.currentTimeMillis() < end, "waited too long for: " + what);
      Thread.sleep(50);
    }
  }

  private static void mouse(Component c, int id, int x, int y) throws Exception {
    onEdt(
        () -> {
          c.dispatchEvent(new MouseEvent(c, id, System.currentTimeMillis(), 0, x, y, 1, false));
          return null;
        });
  }

  @Test
  void aStitchIsSavedReopenedEditedAndSavedAgainFromTheWindow() throws Exception {
    StitchFixture fixture = new StitchFixture();

    // ---- a new stitch: tick, confirm, Line up, Save
    TestHost host = new TestHost(true);
    Window first = new Window(host, fixture.pieces());
    assertFalse(first.enabled("StitchDialog.edit"), "nothing is saved yet");
    assertFalse(first.enabled("StitchDialog.lineUp"), "nothing is confirmed yet");
    first.tickAndConfirm(3);
    first.click("StitchDialog.lineUp");
    await("Line up", () -> first.enabled("StitchDialog.save"));
    assertFalse(first.enabled("StitchDialog.saveUnverified"));
    first.click("StitchDialog.save");
    await("the save", () -> host.opened.size() == 1);
    await("the list of versions", () -> first.enabled("StitchDialog.edit"));
    List<StitchProjects.Listed> saved = host.projects.list(StitchFixture.STUDY);
    assertEquals(1, saved.size());
    StitchProject one = saved.get(0).project();
    assertNull(one.parentVersionId());
    assertEquals(host.opened.get(0).sopUid(), one.output().sopInstanceUid());
    assertEquals(SeamRecipe.match(SeamMatcher.Band.WHOLE), one.seams().get(0).decision());
    byte[] oneBytes = Files.readAllBytes(host.folder.file(StitchFixture.STUDY, one.versionId()));
    byte[] onePicture = Files.readAllBytes(host.opened.get(0).file());
    first.close();

    // ---- the viewer restarted: a new window on the study opened again
    TestHost later = new TestHost(true);
    List<Piece> open = fixture.pieces();
    Window second = new Window(later, open);
    await("the list of versions", () -> second.enabled("StitchDialog.edit"));
    assertFalse(second.enabled("StitchDialog.save"));
    second.click("StitchDialog.edit");
    await("the saved stitch to open", () -> second.enabled("StitchDialog.save"));
    assertEquals(1, later.asked.size());
    assertTrue(later.asked.get(0)[0].contains("3 pieces, automatic, check"), later.asked.get(0)[0]);
    assertEquals(List.of(), later.told, "every seam came out as it was saved");
    onEdt(
        () -> {
          assertTrue(second.dialog.getTitle().contains("editing the stitch saved"));
          assertEquals(2, second.seams().getRowCount());
          for (int i = 0; i < 3; i++) {
            assertEquals(true, second.pieces().getValueAt(i, 0));
          }
          assertEquals("check", second.seams().getValueAt(0, 2));
          assertEquals("AP", second.combos().get(0).getSelectedItem());
          assertEquals(Stitch.Leg.RIGHT, second.combos().get(2).getSelectedItem());
          assertEquals(SeamMatcher.Band.WHOLE, second.combos().get(3).getSelectedItem());
          return null;
        });

    // ---- edit 1: seam 1's cut is dragged 10 mm down in the preview, then Save
    StitchResume.Resumed asSaved =
        StitchResume.recheck(one, StitchResume.sources(one, open), (d, t) -> true);
    Stitch before = asSaved.stitch();
    int seamPx = (int) Math.round((before.seamY(0) - before.bounds()[1]) / MatchImage.COARSE_MM);
    ImagePanel preview = second.all(ImagePanel.class).get(0);
    mouse(preview, MouseEvent.MOUSE_PRESSED, 20, seamPx);
    mouse(preview, MouseEvent.MOUSE_DRAGGED, 20, seamPx + 5);
    second.click("StitchDialog.save");
    await("the second save", () -> later.opened.size() == 1);
    await(
        "the second version",
        () ->
            later.projects.list(StitchFixture.STUDY).size() == 2
                && second.enabled("StitchDialog.edit"));
    StitchProject two = later.projects.list(StitchFixture.STUDY).get(1).project();
    assertEquals(one.versionId(), two.parentVersionId());
    assertEquals(one.projectId(), two.projectId());
    assertEquals(one.sources(), two.sources());
    assertEquals("cut placed by hand", two.seams().get(0).cut().note());
    assertEquals(one.seams().get(0).cut().yMm() + 10, two.seams().get(0).cutRequestedMm(), 1e-6);
    assertEquals(
        one.seams().get(0).result(), two.seams().get(0).result(), "the seam itself is as it was");

    // ---- edit 2: seam 2 nudged in the seam window and refined again, then Save
    Seam seam = two.seams().get(1).result();
    SeamEditor editor = onEdt(() -> second.dialog.seamWindow(1));
    AbstractButton refine = editorButton(editor, "SeamEditor.refine");
    onEdt(
        () -> {
          JComponent root = (JComponent) editor.getContentPane();
          for (int i = 0; i < 8; i++) {
            root.getActionMap().get("right").actionPerformed(null);
          }
          refine.doClick(0);
          return null;
        });
    await("Refine automatically from here", () -> onEdt(refine::isEnabled));
    onEdt(
        () -> {
          editorButton(editor, "SeamEditor.apply").doClick(0);
          return null;
        });
    SeamRecipe.Made made = editor.result().orElseThrow();
    assertEquals(SeamRecipe.Kind.REFINE, made.recipe().kind());
    assertEquals(
        0.8,
        made.recipe().start().apply(0, 0)[0] - seam.transform().apply(0, 0)[0],
        1e-9,
        "refined from where the eight nudges had put it");
    assertEquals(made.seam(), made.recipe().compute(open.get(1).match(), open.get(2).match()));
    assertEquals(Seam.Status.CHECK, made.seam().status(), () -> made.seam().reasons().toString());
    onEdt(
        () -> {
          second.dialog.useSeam(1, made);
          assertEquals("semi-automatic", second.seams().getValueAt(1, 1));
          return null;
        });
    second.click("StitchDialog.save");
    await("the third save", () -> later.opened.size() == 2);
    await("the third version", () -> later.projects.list(StitchFixture.STUDY).size() == 3);
    List<StitchProjects.Listed> all = later.projects.list(StitchFixture.STUDY);
    assertEquals(
        List.of(one.versionId(), two.versionId()),
        all.subList(0, 2).stream().map(StitchProjects.Listed::versionId).toList(),
        "listed in the order they were made");
    StitchProject three = all.get(2).project();
    assertEquals(two.versionId(), three.parentVersionId(), "each save continues from the last");
    assertEquals(made.recipe(), three.seams().get(1).decision());
    assertEquals(made.seam(), three.seams().get(1).result());
    assertEquals(two.seams().get(0).cutRequestedMm(), three.seams().get(0).cutRequestedMm());
    second.close();

    // ---- nothing saved earlier was touched, and all three versions reopen
    assertArrayEquals(
        oneBytes, Files.readAllBytes(later.folder.file(StitchFixture.STUDY, one.versionId())));
    assertArrayEquals(onePicture, Files.readAllBytes(host.opened.get(0).file()));
    assertNotEquals(host.opened.get(0).file(), later.opened.get(1).file());
    for (StitchProjects.Listed l : later.projects.list(StitchFixture.STUDY)) {
      StitchResume.Resumed r =
          StitchResume.recheck(
              l.project(), StitchResume.sources(l.project(), fixture.pieces()), (d, t) -> true);
      assertEquals(List.of(), r.notes());
    }
  }

  @Test
  void aMissingOrChangedPieceGivesAClearRefusalAndNothingIsOpened() throws Exception {
    StitchFixture fixture = new StitchFixture();
    TestHost host = new TestHost(true);
    Window first = new Window(host, fixture.pieces());
    first.tickAndConfirm(3);
    first.click("StitchDialog.lineUp");
    await("Line up", () -> first.enabled("StitchDialog.save"));
    first.click("StitchDialog.save");
    await("the save", () -> host.opened.size() == 1);
    first.close();

    // the study opened without its third piece
    TestHost partial = new TestHost(true);
    Window missing = new Window(partial, fixture.pieces().subList(0, 2));
    await("the list of versions", () -> missing.enabled("StitchDialog.edit"));
    missing.click("StitchDialog.edit");
    await("the refusal", () -> partial.told.size() == 1);
    assertTrue(partial.told.get(0).contains("piece 3 of 3 is not among"), partial.told.get(0));
    assertTrue(partial.told.get(0).contains("Nothing was put in their place"));
    onEdt(
        () -> {
          assertEquals(0, missing.seams().getRowCount(), "nothing was opened on two of three");
          assertEquals(false, missing.pieces().getValueAt(0, 0), "and nothing was ticked");
          assertFalse(missing.dialog.getTitle().contains("editing"));
          return null;
        });
    assertFalse(missing.enabled("StitchDialog.save"));
    assertFalse(missing.enabled("StitchDialog.saveUnverified"));
    missing.close();

    // the study with one piece's pixels changed, under the same identity
    TestHost altered = new TestHost(true);
    List<Piece> open = new ArrayList<>(fixture.pieces());
    open.set(1, fixture.pieceWithOnePixelChanged(1));
    Window changed = new Window(altered, open);
    await("the list of versions", () -> changed.enabled("StitchDialog.edit"));
    changed.click("StitchDialog.edit");
    await("the refusal", () -> altered.told.size() == 1);
    assertTrue(altered.told.get(0).contains("piece 2 of 3 has changed"), altered.told.get(0));
    await("the window to settle", () -> changed.enabled("StitchDialog.edit"));
    onEdt(
        () -> {
          assertEquals(0, changed.seams().getRowCount());
          assertFalse(changed.dialog.getTitle().contains("editing"));
          assertEquals(false, changed.pieces().getValueAt(0, 0), "nothing is left ticked");
          assertEquals(0, changed.combos().get(2).getSelectedIndex(), "nor confirmed");
          return null;
        });
    assertFalse(changed.enabled("StitchDialog.save"));
    assertFalse(changed.enabled("StitchDialog.lineUp"));
    assertEquals(1, altered.projects.list(StitchFixture.STUDY).size(), "the saved version stays");
    changed.close();
  }

  @Test
  void aSeamThatNoLongerPassesReopensFailedAndOnlyTheUnverifiedSaveIsOffered() throws Exception {
    StitchFixture fixture = new StitchFixture();
    TestHost host = new TestHost(true);
    // a version whose record says "check" for a seam its decision cannot give, and one of a schema
    // this viewer does not read
    List<Piece> pieces = fixture.pieces();
    List<Seam> seams = StitchFixture.seams(pieces, StitchFixture.lineUp(2));
    double[] a = seams.get(0).transform().apply(40, 10);
    double[] b = seams.get(0).transform().apply(160, 20);
    SeamRecipe twoPairs =
        SeamRecipe.points(
            List.of(new Rigid.Pair(40, 10, a[0], a[1]), new Rigid.Pair(160, 20, b[0], b[1])));
    Seam failed = twoPairs.compute(null, null);
    Seam claimed =
        new Seam(
            failed.transform(),
            failed.method(),
            Seam.Status.CHECK,
            failed.rmsMm(),
            failed.rotationSigmaDeg(),
            failed.shiftSigmaMm(),
            failed.scale(),
            failed.uniqueness(),
            failed.holdoutMm(),
            failed.pairs(),
            failed.reasons());
    Stitch lying = new Stitch(pieces, List.of(claimed, seams.get(1)), StitchFixture.CHOICES);
    host.projects.save(
        StitchFixture.capture(
            lying,
            List.of(twoPairs, SeamRecipe.match(SeamMatcher.Band.WHOLE)),
            StitchResume.Lineage.NEW));
    host.folder.put(
        StitchFixture.STUDY,
        UUID.randomUUID().toString(),
        "{\"format\":\"addisortho-stitch-project\",\"schemaVersion\":9}"
            .getBytes(StandardCharsets.UTF_8));

    Window w = new Window(host, fixture.pieces());
    await("the list of versions", () -> w.enabled("StitchDialog.edit"));

    // the version of the unknown schema is listed, and refused when chosen
    host.pick = 0;
    w.click("StitchDialog.edit");
    assertEquals(2, host.asked.get(0).length);
    assertTrue(host.asked.get(0)[0].contains("cannot be opened"), host.asked.get(0)[0]);
    await("the refusal", () -> host.told.size() == 1);
    assertTrue(host.told.get(0).contains("schema version 9"), host.told.get(0));

    // the other reopens with seam 1 failed
    host.pick = 1;
    w.click("StitchDialog.edit");
    await("the saved stitch to open", () -> w.enabled("StitchDialog.saveUnverified"));
    assertFalse(w.enabled("StitchDialog.save"), "a failed seam blocks the ordinary save");
    assertEquals(2, host.told.size());
    assertTrue(host.told.get(1).contains("Seam 1: reopened, this seam did not come out"));
    onEdt(
        () -> {
          assertEquals("failed", w.seams().getValueAt(0, 2));
          assertEquals("check", w.seams().getValueAt(1, 2));
          return null;
        });
    w.close();
  }

  /** Opens the seam window on a seam, lets {@code hands} work in it, and presses Use this seam. */
  private static SeamRecipe.Made inSeamWindow(
      List<Piece> pieces, SeamRecipe.Made start, Callable<Void> hands, SeamEditor[] window)
      throws Exception {
    window[0] =
        onEdt(
            () ->
                new SeamEditor(
                    null,
                    0,
                    pieces.get(0),
                    pieces.get(1),
                    start,
                    SeamMatcher.Band.WHOLE,
                    SeamRecipe.match(SeamMatcher.Band.WHOLE),
                    null));
    hands.call();
    onEdt(
        () -> {
          editorButton(window[0], "SeamEditor.apply").doClick(0);
          return null;
        });
    return window[0].result().orElseThrow();
  }

  @Test
  void theSeamWindowReportsHowEachSeamWasSet() throws Exception {
    List<Piece> pieces = new StitchFixture().pieces();
    SeamRecipe matched = SeamRecipe.match(SeamMatcher.Band.WHOLE);
    Seam seam = StitchFixture.seams(pieces, List.of(matched)).get(0);
    SeamRecipe.Made start = new SeamRecipe.Made(matched, seam);
    MatchImage upper = pieces.get(0).match();
    MatchImage lower = pieces.get(1).match();
    SeamEditor[] w = new SeamEditor[1];

    // untouched: the seam and its decision as they came
    assertEquals(start, inSeamWindow(pieces, start, () -> null, w));

    // nudged with the arrow keys and turned: moved by hand, and nothing more is claimed
    SeamRecipe.Made nudged =
        inSeamWindow(
            pieces,
            start,
            () ->
                onEdt(
                    () -> {
                      JComponent root = (JComponent) w[0].getContentPane();
                      root.getActionMap().get("down").actionPerformed(null);
                      root.getActionMap().get("left1").actionPerformed(null);
                      root.getActionMap().get("turnRight").actionPerformed(null);
                      return null;
                    }),
            w);
    assertEquals(SeamRecipe.Kind.MATCH, nudged.recipe().kind());
    assertEquals(nudged.seam().transform(), nudged.recipe().movedTo());
    assertNotEquals(seam.transform(), nudged.seam().transform());
    assertEquals(nudged.seam(), nudged.recipe().compute(upper, lower), "it can be made again");
    assertEquals(Seam.Method.SEMI_AUTOMATIC, nudged.seam().method());
    assertTrue(Double.isNaN(nudged.seam().rmsMm()));

    // three matching points picked in the two pieces, then Fit to the points
    SeamRecipe.Made fitted =
        inSeamWindow(
            pieces,
            start,
            () -> {
              List<ImagePanel> views = new ArrayList<>();
              Window.collect(w[0].getContentPane(), ImagePanel.class, views);
              ImagePanel upperView = views.get(1);
              ImagePanel lowerView = views.get(2);
              // the upper view begins 20 mm above the overlap
              double top =
                  Math.max(
                      0,
                      SeamMatcher.overlap(
                              pieces.get(0).widthMm(),
                              pieces.get(0).heightMm(),
                              seam.transform(),
                              lower)[1]
                          - 20);
              onEdt(
                  () -> {
                    editorButton(w[0], "SeamEditor.pick").doClick(0);
                    return null;
                  });
              for (double[] q : new double[][] {{40, 12}, {110, 30}, {165, 18}}) {
                double[] to = seam.transform().apply(q[0], q[1]);
                mouse(
                    upperView,
                    MouseEvent.MOUSE_PRESSED,
                    (int) Math.round(to[0]),
                    (int) Math.round(to[1] - top));
                mouse(lowerView, MouseEvent.MOUSE_PRESSED, (int) q[0], (int) q[1]);
              }
              return onEdt(
                  () -> {
                    editorButton(w[0], "SeamEditor.fit").doClick(0);
                    return null;
                  });
            },
            w);
    assertEquals(SeamRecipe.Kind.POINTS, fitted.recipe().kind());
    assertEquals(3, fitted.recipe().points().size());
    assertEquals(Seam.Method.MANUAL, fitted.seam().method());
    assertEquals(fitted.seam(), fitted.recipe().compute(upper, lower));
    double[] byPoints = fitted.seam().transform().apply(105, 20);
    double[] byMatch = seam.transform().apply(105, 20);
    assertTrue(
        Math.hypot(byPoints[0] - byMatch[0], byPoints[1] - byMatch[1]) < 1,
        "points picked to the nearest millimetre put the pieces together within that");

    // Automatic match again, from a seam that had been moved
    SeamRecipe.Made again =
        inSeamWindow(
            pieces,
            nudged,
            () -> {
              AbstractButton auto = editorButton(w[0], "SeamEditor.auto");
              onEdt(
                  () -> {
                    auto.doClick(0);
                    return null;
                  });
              await("Automatic match again", () -> onEdt(auto::isEnabled));
              return null;
            },
            w);
    assertEquals(start, again, "the hand move is gone with the new match");
  }

  /** Presses a button of the seam window that works in the background, and waits for it. */
  private static void pressAndWait(SeamEditor editor, String key) throws Exception {
    AbstractButton button = editorButton(editor, key);
    onEdt(
        () -> {
          button.doClick(0);
          return null;
        });
    await(key, () -> onEdt(button::isEnabled));
  }

  private static SeamRecipe.Made use(SeamEditor editor) throws Exception {
    onEdt(
        () -> {
          editorButton(editor, "SeamEditor.apply").doClick(0);
          return null;
        });
    return editor.result().orElseThrow();
  }

  @Test
  void changingTheTargetLegDiscardsTheSeamsUntilLineUpIsRepeated() throws Exception {
    TestHost host = new TestHost(true);
    Window w = new Window(host, new StitchFixture().pieces());
    w.tickAndConfirm(3);
    w.click("StitchDialog.lineUp");
    await("Line up", () -> w.enabled("StitchDialog.save"));

    // seams matched for the right leg are not kept for another target leg
    onEdt(
        () -> {
          assertEquals(Stitch.Leg.RIGHT, w.combos().get(2).getSelectedItem());
          w.combos().get(2).setSelectedItem(Stitch.Leg.BOTH);
          assertEquals(0, w.seams().getRowCount());
          return null;
        });
    assertFalse(w.enabled("StitchDialog.save"), "nothing can be saved under the new leg");
    assertFalse(w.enabled("StitchDialog.saveUnverified"));
    assertFalse(w.enabled("StitchDialog.inspect"));
    assertTrue(w.enabled("StitchDialog.lineUp"), "until Line up is pressed again");
    assertEquals(List.of(), host.opened);

    // choosing the leg already chosen, or another projection, changes no seam
    onEdt(
        () -> {
          w.combos().get(2).setSelectedItem(Stitch.Leg.LEFT);
          return null;
        });
    w.click("StitchDialog.lineUp");
    await("Line up again", () -> w.enabled("StitchDialog.save"));
    onEdt(
        () -> {
          w.combos().get(2).setSelectedItem(Stitch.Leg.LEFT);
          w.combos().get(0).setSelectedIndex(2);
          assertEquals(2, w.seams().getRowCount());
          return null;
        });
    assertTrue(w.enabled("StitchDialog.save"));
    w.click("StitchDialog.save");
    await("the save", () -> host.opened.size() == 1);
    await("the version", () -> host.projects.list(StitchFixture.STUDY).size() == 1);
    assertEquals(
        Stitch.Leg.LEFT,
        host.projects.list(StitchFixture.STUDY).get(0).project().choices().leg(),
        "the leg saved is the leg the seams were matched for");
    w.close();
  }

  @Test
  void aFilmOfBothLegsIsMatchedLegByLegThroughRematchSaveAndReopen() throws Exception {
    StitchFixture fixture = StitchFixture.twoLegs();
    TestHost host = new TestHost(true);
    Window first = new Window(host, fixture.pieces());
    first.tickAndConfirm(2);
    onEdt(
        () -> {
          first.combos().get(2).setSelectedItem(Stitch.Leg.BOTH);
          assertEquals(SeamMatcher.Band.WHOLE, first.combos().get(3).getSelectedItem());
          return null;
        });
    first.click("StitchDialog.lineUp");
    await("Line up", () -> first.enabled("StitchDialog.save"));

    // Automatic match again in the seam window matches as Line up did: each leg on its own too
    SeamEditor editor = onEdt(() -> first.dialog.seamWindow(0));
    pressAndWait(editor, "SeamEditor.auto");
    SeamRecipe.Made again = use(editor);
    assertEquals(SeamRecipe.matchBothLegs(), again.recipe());
    assertTrue(
        again.seam().reasons().get(0).contains("left and right legs, matched one at a time"),
        again.seam().reasons().toString());
    onEdt(
        () -> {
          first.dialog.useSeam(0, again);
          return null;
        });
    first.click("StitchDialog.save");
    await("the save", () -> host.opened.size() == 1);
    await("the list of versions", () -> first.enabled("StitchDialog.edit"));
    StitchProject saved = host.projects.list(StitchFixture.STUDY).get(0).project();
    assertEquals(Stitch.Leg.BOTH, saved.choices().leg());
    assertEquals(SeamRecipe.matchBothLegs(), saved.seams().get(0).decision());
    assertEquals(again.seam(), saved.seams().get(0).result());
    first.close();

    // reopened, it is checked leg by leg again, and a rematch there is still leg by leg
    TestHost later = new TestHost(true);
    Window second = new Window(later, fixture.pieces());
    await("the list of versions", () -> second.enabled("StitchDialog.edit"));
    second.click("StitchDialog.edit");
    await("the saved stitch to open", () -> second.enabled("StitchDialog.save"));
    assertEquals(List.of(), later.told);
    SeamEditor reopened = onEdt(() -> second.dialog.seamWindow(0));
    pressAndWait(reopened, "SeamEditor.auto");
    assertEquals(SeamRecipe.matchBothLegs(), use(reopened).recipe());

    // one target leg on its half of the film is the ordinary match on that half
    onEdt(
        () -> {
          second.combos().get(2).setSelectedItem(Stitch.Leg.LEFT);
          second.combos().get(3).setSelectedItem(SeamMatcher.Band.LEFT_HALF);
          return null;
        });
    second.click("StitchDialog.lineUp");
    await("Line up on one leg", () -> second.enabled("StitchDialog.inspect"));
    SeamEditor oneLeg = onEdt(() -> second.dialog.seamWindow(0));
    pressAndWait(oneLeg, "SeamEditor.auto");
    assertEquals(SeamRecipe.match(SeamMatcher.Band.LEFT_HALF), use(oneLeg).recipe());
    second.close();
  }

  @Test
  void unsavedWorkIsAskedAboutAndOutlivesAReopenThatDoesNotHappen() throws Exception {
    StitchFixture fixture = new StitchFixture();
    TestHost host = new TestHost(true);
    // a version saved earlier
    List<Piece> earlier = fixture.pieces();
    List<SeamRecipe> recipes = StitchFixture.lineUp(2);
    StitchProject kept =
        StitchService.save(
                host.library,
                host.projects,
                new Stitch(earlier, StitchFixture.seams(earlier, recipes), StitchFixture.CHOICES),
                recipes,
                StitchResume.Lineage.NEW,
                (d, t) -> true)
            .version();

    // a new stitch lined up in the window and not saved
    Window w = new Window(host, fixture.pieces());
    await("the list of versions", () -> w.enabled("StitchDialog.edit"));
    w.tickAndConfirm(3);
    w.click("StitchDialog.lineUp");
    await("Line up", () -> w.enabled("StitchDialog.save"));

    // Edit stitch asks first; Cancel leaves everything
    host.answer = "StitchDialog.cancel";
    w.click("StitchDialog.edit");
    assertEquals(1, host.questions.size());
    assertEquals(
        List.of(
            Messages.getString("StitchDialog.editSaveFirst"),
            Messages.getString("StitchDialog.editDiscard"),
            Messages.getString("StitchDialog.cancel")),
        List.of(host.buttons.get(0)));
    assertTrue(w.enabled("StitchDialog.save"), "nothing was started");
    onEdt(
        () -> {
          assertEquals(2, w.seams().getRowCount());
          assertFalse(w.dialog.getTitle().contains("editing"));
          return null;
        });

    // Discard, then the reopen is cancelled while it is checked: the stitch is still there
    host.answer = "StitchDialog.editDiscard";
    onEdt(
        () -> {
          w.button("StitchDialog.edit").doClick(0);
          assertTrue(w.button("StitchDialog.cancel").isEnabled(), "the check can be stopped");
          w.button("StitchDialog.cancel").doClick(0);
          return null;
        });
    await("the cancelled reopen to stop", () -> w.enabled("StitchDialog.save"));
    onEdt(
        () -> {
          assertEquals(2, w.seams().getRowCount());
          assertEquals(true, w.pieces().getValueAt(2, 0));
          assertEquals(Stitch.Leg.RIGHT, w.combos().get(2).getSelectedItem());
          assertFalse(w.dialog.getTitle().contains("editing"));
          return null;
        });
    assertEquals(List.of(), host.opened);

    // Save it first: the stitch is saved as its own project, then the chosen version opens
    host.answer = "StitchDialog.editSaveFirst";
    w.click("StitchDialog.edit");
    await("the save before opening", () -> host.opened.size() == 1);
    await(
        "the saved stitch to open",
        () ->
            onEdt(() -> w.dialog.getTitle().contains("editing")) && w.enabled("StitchDialog.save"));
    List<StitchProjects.Listed> all = host.projects.list(StitchFixture.STUDY);
    assertEquals(2, all.size());
    StitchProject mine =
        all.stream()
            .map(StitchProjects.Listed::project)
            .filter(p -> !p.versionId().equals(kept.versionId()))
            .findFirst()
            .orElseThrow();
    assertNull(mine.parentVersionId(), "the unsaved stitch was a new project of its own");
    assertNotEquals(kept.projectId(), mine.projectId());

    // what is open now is the version chosen, saved: opening another asks nothing
    int asked = host.questions.size();
    await("the list of versions", () -> w.enabled("StitchDialog.edit"));
    w.click("StitchDialog.edit");
    await("the saved stitch to open again", () -> w.enabled("StitchDialog.save"));
    assertEquals(asked, host.questions.size());
    w.close();
  }

  @Test
  void aRefusedReopenLeavesTheUnsavedStitchAsItWas() throws Exception {
    StitchFixture fixture = new StitchFixture();
    TestHost host = new TestHost(true);
    List<Piece> earlier = fixture.pieces();
    List<SeamRecipe> recipes = StitchFixture.lineUp(2);
    StitchService.save(
        host.library,
        host.projects,
        new Stitch(earlier, StitchFixture.seams(earlier, recipes), StitchFixture.CHOICES),
        recipes,
        StitchResume.Lineage.NEW,
        (d, t) -> true);

    // the study with one piece changed: a new stitch of what is there, lined up and not saved
    List<Piece> open = new ArrayList<>(fixture.pieces());
    open.set(1, fixture.pieceWithOnePixelChanged(1));
    Window w = new Window(host, open);
    await("the list of versions", () -> w.enabled("StitchDialog.edit"));
    w.tickAndConfirm(3);
    w.click("StitchDialog.lineUp");
    await("Line up", () -> w.enabled("StitchDialog.save"));

    host.answer = "StitchDialog.editDiscard";
    w.click("StitchDialog.edit");
    await("the refusal", () -> host.told.size() == 1);
    assertTrue(host.told.get(0).contains("piece 2 of 3 has changed"), host.told.get(0));
    assertTrue(host.told.get(0).contains("This window"), host.told.get(0));
    await("the window to settle", () -> w.enabled("StitchDialog.save"));
    onEdt(
        () -> {
          assertEquals(2, w.seams().getRowCount(), "the seams lined up here are still here");
          assertEquals("check", w.seams().getValueAt(0, 2));
          for (int i = 0; i < 3; i++) {
            assertEquals(true, w.pieces().getValueAt(i, 0));
          }
          assertEquals("AP", w.combos().get(0).getSelectedItem());
          assertFalse(w.dialog.getTitle().contains("editing"));
          return null;
        });

    // and it can still be saved, as a new project of its own
    w.click("StitchDialog.save");
    await("the save", () -> host.opened.size() == 1);
    await("the version", () -> host.projects.list(StitchFixture.STUDY).size() == 2);
    w.close();
  }

  @Test
  void aSaveThatFailsIsToldAndTheStitchStaysInTheWindow() throws Exception {
    TestHost host =
        new TestHost(true) {
          @Override
          public StitchService.Saved save(
              Stitch s,
              List<SeamRecipe> recipes,
              StitchResume.Lineage lineage,
              Compositor.Progress progress)
              throws IOException {
            throw new IOException("the disk is full");
          }
        };
    Window w = new Window(host, new StitchFixture().pieces());
    w.tickAndConfirm(3);
    w.click("StitchDialog.lineUp");
    await("Line up", () -> w.enabled("StitchDialog.save"));
    w.click("StitchDialog.save");
    await("the failure to be told", () -> host.told.size() == 1);
    assertTrue(host.told.get(0).contains("the disk is full"), host.told.get(0));
    await("the window to settle", () -> w.enabled("StitchDialog.save"));
    onEdt(
        () -> {
          assertEquals(2, w.seams().getRowCount(), "the stitch is still there to save again");
          return null;
        });
    assertEquals(List.of(), host.opened);
    w.close();
  }

  @Test
  void withoutAProjectStoreThereIsNoEditStitchAndSaveIsAsBefore() throws Exception {
    TestHost host = new TestHost(false);
    Window w = new Window(host, new StitchFixture().pieces());
    assertNull(onEdt(() -> w.button("StitchDialog.edit")), "the button is not in the window");
    w.tickAndConfirm(3);
    w.click("StitchDialog.lineUp");
    await("Line up", () -> w.enabled("StitchDialog.save"));
    w.click("StitchDialog.save");
    await("the save", () -> host.opened.size() == 1);
    assertTrue(Files.isRegularFile(host.opened.get(0).file()));
    assertEquals(List.of(), host.told);
    assertFalse(Files.exists(dir.resolve("projects")));
    w.close();
  }

  private static AbstractButton editorButton(SeamEditor editor, String key) {
    List<AbstractButton> out = new ArrayList<>();
    Window.collect(editor.getContentPane(), AbstractButton.class, out);
    String text = Messages.getString(key);
    return out.stream().filter(b -> text.equals(b.getText())).findFirst().orElseThrow();
  }
}
