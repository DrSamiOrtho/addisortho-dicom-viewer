/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer.library;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.weasis.dicom.explorer.index.IndexScanner;

/**
 * Documents kept beside the studies: written whole and once, also against another process, and
 * never seen by anything that walks the studies. Synthetic identifiers only.
 */
class ProjectDocumentsTest {
  private static final String STUDY = "1.2.826.0.1.3680043.8.498.10";
  private static final String SERIES = "1.2.826.0.1.3680043.8.498.11";

  @TempDir Path tmp;
  private Path root;

  @BeforeEach
  void setUp() {
    root = tmp.resolve("library");
  }

  private ProjectDocuments docs() {
    return new ProjectDocuments(root, "stitch");
  }

  private Path studyFolder() {
    return root.resolve(ProjectDocuments.FOLDER).resolve("stitch").resolve(STUDY);
  }

  private static String id() {
    return UUID.randomUUID().toString();
  }

  private static byte[] doc(String text) {
    return ("{\"synthetic\":\"" + text + "\"}").getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void aDocumentIsKeptUnderTheStudyUidOnlyAndNeverUnderAPatientName() throws IOException {
    String id = id();
    docs().put(STUDY, id, doc("a"));

    Path file = studyFolder().resolve(id + ".json");
    assertTrue(Files.isRegularFile(file));
    assertEquals(root.resolve(".projects/stitch/" + STUDY + "/" + id + ".json"), file);
    assertArrayEquals(doc("a"), docs().get(STUDY, id));
  }

  @Test
  void anInterruptedWriteLeavesNothingListedAndTheIdentifierFree() throws IOException {
    String id = id();
    ProjectDocuments stopping = docs();
    stopping.step =
        step -> {
          if (step.equals("written")) {
            throw new IOException("the viewer stopped");
          }
        };

    IOException e = assertThrows(IOException.class, () -> stopping.put(STUDY, id, doc("a")));
    assertFalse(e.getMessage().contains(tmp.toString()), "no path in the message");

    assertEquals(List.of(), docs().list(STUDY));
    assertThrows(NoSuchFileException.class, () -> docs().get(STUDY, id));
    docs().put(STUDY, id, doc("b"));
    assertArrayEquals(doc("b"), docs().get(STUDY, id));
  }

  @Test
  void aStopAfterTheNameIsGivenLeavesTheDocumentWhole() throws IOException {
    String id = id();
    ProjectDocuments stopping = docs();
    stopping.step =
        step -> {
          if (step.equals("named")) {
            throw new IOException("the viewer stopped");
          }
        };

    assertThrows(IOException.class, () -> stopping.put(STUDY, id, doc("a")));

    assertEquals(List.of(id), docs().list(STUDY));
    assertArrayEquals(doc("a"), docs().get(STUDY, id));
  }

  @Test
  void whatAnUncleanStopLeavesIsNeverListedAndIsClearedByTheNextWrite() throws IOException {
    String id = id();
    Files.createDirectories(studyFolder());
    // a write cut off by a power cut: half a document under its temporary name
    Path part = studyFolder().resolve("." + id + "-" + UUID.randomUUID() + ".part");
    Files.write(part, "{\"synth".getBytes(StandardCharsets.UTF_8));

    ProjectDocuments afterRestart = docs();
    assertEquals(List.of(), afterRestart.list(STUDY));
    assertThrows(NoSuchFileException.class, () -> afterRestart.get(STUDY, id));

    afterRestart.put(STUDY, id, doc("whole"));
    assertFalse(Files.exists(part), "the leftover is cleared");
    assertEquals(List.of(id), afterRestart.list(STUDY));
    assertArrayEquals(doc("whole"), afterRestart.get(STUDY, id));
  }

  @Test
  void aKeptDocumentIsNeverReplacedNorTouched() throws IOException {
    String id = id();
    docs().put(STUDY, id, doc("first"));
    Path file = studyFolder().resolve(id + ".json");
    long modified = Files.getLastModifiedTime(file).toMillis();

    FileAlreadyExistsException e =
        assertThrows(FileAlreadyExistsException.class, () -> docs().put(STUDY, id, doc("second")));
    assertEquals(id, e.getMessage(), "the identifier, not the path");

    assertArrayEquals(doc("first"), docs().get(STUDY, id));
    assertEquals(modified, Files.getLastModifiedTime(file).toMillis());
  }

  @Test
  void withoutHardLinksTheMoveUnderTheLocksStillRefusesAReplacement() throws IOException {
    ProjectDocuments noLinks = docs();
    noLinks.namer =
        (target, temp) -> {
          if (Files.exists(target)) {
            throw new FileAlreadyExistsException(target.getFileName().toString());
          }
          Files.move(temp, target);
        };
    String id = id();
    noLinks.put(STUDY, id, doc("first"));

    assertThrows(FileAlreadyExistsException.class, () -> noLinks.put(STUDY, id, doc("second")));
    assertArrayEquals(doc("first"), docs().get(STUDY, id));
  }

  @Test
  void twoThreadsPuttingOneIdentifierLeaveExactlyOneWinner() throws Exception {
    for (int round = 0; round < 20; round++) {
      String id = id();
      int callers = 8;
      CountDownLatch go = new CountDownLatch(1);
      ExecutorService pool = Executors.newFixedThreadPool(callers);
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < callers; i++) {
        byte[] mine = doc("caller " + i);
        results.add(
            pool.submit(
                () -> {
                  go.await();
                  try {
                    new ProjectDocuments(root, "stitch").put(STUDY, id, mine);
                    return true;
                  } catch (FileAlreadyExistsException e) {
                    return false;
                  }
                }));
      }
      go.countDown();
      int winners = 0;
      for (Future<Boolean> r : results) {
        winners += r.get(30, TimeUnit.SECONDS) ? 1 : 0;
      }
      pool.shutdown();
      assertEquals(1, winners, "round " + round);
      String kept = new String(docs().get(STUDY, id), StandardCharsets.UTF_8);
      assertTrue(kept.matches("\\{\"synthetic\":\"caller \\d\"}"), kept);
    }
    try (Stream<Path> left = Files.list(studyFolder())) {
      assertEquals(
          Set.of(),
          Set.copyOf(
              left.map(p -> p.getFileName().toString()).filter(n -> !n.endsWith(".json")).toList()),
          "no temporary file is left");
    }
  }

  @Test
  void twoProcessesPuttingOneIdentifierLeaveExactlyOneWinner() throws Exception {
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    String classPath = System.getProperty("java.class.path");
    for (int round = 0; round < 5; round++) {
      String id = id();
      Path start = tmp.resolve("start-" + round);
      List<Process> racers = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        racers.add(
            new ProcessBuilder(
                    java,
                    "-cp",
                    classPath,
                    ProjectDocumentsRacer.class.getName(),
                    root.toString(),
                    STUDY,
                    id,
                    "process " + i,
                    start.toString())
                .redirectErrorStream(true)
                .start());
      }
      Thread.sleep(1500); // let every process load and wait for the start file
      Files.createFile(start);
      int winners = 0;
      int refused = 0;
      for (Process p : racers) {
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "a racer did not finish");
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        switch (p.exitValue()) {
          case 0 -> winners++;
          case 3 -> refused++;
          default -> throw new AssertionError("racer failed: " + out);
        }
      }
      assertEquals(1, winners, "round " + round);
      assertEquals(3, refused);
      String kept = new String(docs().get(STUDY, id), StandardCharsets.UTF_8);
      assertTrue(kept.matches("\\{\"synthetic\":\"process \\d\"}"), kept);
    }
  }

  @Test
  void everyKeptDocumentIsListedAndNothingElse() throws IOException {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      String id = id();
      ids.add(id);
      docs().put(STUDY, id, doc("v" + i));
    }
    Files.writeString(studyFolder().resolve("notes.txt"), "not a document");
    Files.writeString(studyFolder().resolve("NOT-A-UUID.json"), "{}");

    ProjectDocuments afterRestart = docs();
    assertEquals(Set.copyOf(ids), Set.copyOf(afterRestart.list(STUDY)));
    assertEquals(List.of(), afterRestart.list("1.2.3.999"));
    for (int i = 0; i < ids.size(); i++) {
      assertArrayEquals(doc("v" + i), afterRestart.get(STUDY, ids.get(i)));
    }
  }

  @Test
  void identifiersThatCouldLeaveTheFolderAreRefused() {
    for (String study : new String[] {"..", "1/../2", "a.b", "", "1..2", "1.2.", ".1", null}) {
      assertThrows(IOException.class, () -> docs().put(study, id(), doc("a")), study);
      assertThrows(IOException.class, () -> docs().list(study), study);
    }
    for (String version : new String[] {"../x", "ABCDEF01-0000-0000-0000-000000000000", "x"}) {
      assertThrows(IOException.class, () -> docs().put(STUDY, version, doc("a")), version);
      assertThrows(IOException.class, () -> docs().get(STUDY, version), version);
    }
    assertThrows(IllegalArgumentException.class, () -> new ProjectDocuments(root, "../x"));
  }

  @Test
  void aFolderThatCannotBeWrittenGivesAPlainReason() throws IOException {
    Files.createDirectories(root);
    Files.writeString(root.resolve(ProjectDocuments.FOLDER), "a file where the folder goes");

    IOException e = assertThrows(IOException.class, () -> docs().put(STUDY, id(), doc("a")));

    assertTrue(e.getMessage().startsWith("It could not be written to the study library"));
    assertFalse(e.getMessage().contains(tmp.toString()), "no path in the message");
  }

  // ---- the studies, the catalog, the index and deletion never see a document ----

  private static Attributes image(String sop) {
    Attributes a = new Attributes();
    a.setString(Tag.SOPClassUID, VR.UI, UID.ComputedRadiographyImageStorage);
    a.setString(Tag.SOPInstanceUID, VR.UI, sop);
    a.setString(Tag.StudyInstanceUID, VR.UI, STUDY);
    a.setString(Tag.SeriesInstanceUID, VR.UI, SERIES);
    a.setString(Tag.PatientName, VR.PN, "Synthetic^Test");
    a.setString(Tag.PatientID, VR.LO, "SYN001");
    a.setString(Tag.Modality, VR.CS, "CR");
    a.setString(Tag.StudyDate, VR.DA, "20260110");
    a.setBytes(Tag.PixelData, VR.OB, new byte[] {1, 2, 3, 4});
    return a;
  }

  private static void store(StudyStore store, String sop) throws IOException {
    Attributes a = image(sop);
    Path temp = store.newTempFile();
    try (DicomOutputStream out = new DicomOutputStream(temp.toFile())) {
      out.writeDataset(a.createFileMetaInformation(UID.ExplicitVRLittleEndian), a);
    }
    store.commit(temp, a, STUDY, SERIES, sop);
  }

  @Test
  void studiesCatalogCountsAndIndexIgnoreTheDocuments() throws IOException {
    StudyStore store = new StudyStore(root, true);
    StudyCatalog catalog = new StudyCatalog(root.resolve(".catalog/studies.tsv"));
    catalog.load();
    store.attachCatalog(catalog);
    store.prepare();
    store(store, "1.2.826.0.1.3680043.8.498.20");
    store(store, "1.2.826.0.1.3680043.8.498.21");
    catalog.save();
    long bytesBefore = store.listStudies().getFirst().sizeBytes();
    for (int i = 0; i < 3; i++) {
      store.projects("stitch").put(STUDY, id(), doc("v" + i));
    }

    // a restart: a new store, the catalog reconciled from the files
    StudyStore again = new StudyStore(root, true);
    StudyCatalog reread = new StudyCatalog(root.resolve(".catalog/studies.tsv"));
    reread.load();
    again.attachCatalog(reread);
    again.prepare();
    StudyCatalog.Reconciled r = reread.reconcile(root);
    assertEquals(0, r.rebuilt(), "the documents change no study");
    assertEquals(0, r.removed());
    assertEquals(1, reread.studies().size());
    assertEquals(2, reread.studies().getFirst().imageCount());

    List<StoredStudy> studies = again.listStudies();
    assertEquals(1, studies.size(), "the documents' folder is not a study");
    assertEquals(2, studies.getFirst().images());
    assertEquals(bytesBefore, studies.getFirst().sizeBytes());

    IndexScanner.Result scanned =
        new IndexScanner(List.of(), Long.MAX_VALUE / 2, () -> false, null).scan(List.of(root));
    assertTrue(
        scanned.files().stream().noneMatch(f -> f.location().contains(ProjectDocuments.FOLDER))
            && scanned.notIndexed().stream().noneMatch(n -> n.contains(ProjectDocuments.FOLDER)),
        "the index never reads a document");
    assertEquals(3, again.projects("stitch").list(STUDY).size());
  }

  @Test
  void deletingAStudyDeletesItsDocumentsAndNoOtherStudys() throws IOException {
    StudyStore store = new StudyStore(root, true);
    store.prepare();
    store(store, "1.2.826.0.1.3680043.8.498.20");
    String other = "1.2.826.0.1.3680043.8.498.99";
    String kept = id();
    store.projects("stitch").put(other, kept, doc("another study"));
    for (int i = 0; i < 3; i++) {
      store.projects("stitch").put(STUDY, id(), doc("v" + i));
    }
    Files.write(studyFolder().resolve("." + id() + "-x.part"), new byte[] {1, 2});

    store.deleteStudy(store.listStudies().getFirst().folder());

    assertEquals(List.of(), store.listStudies());
    assertEquals(List.of(), store.projects("stitch").list(STUDY));
    assertFalse(Files.exists(studyFolder()), "the study's projects folder is gone");
    assertArrayEquals(doc("another study"), store.projects("stitch").get(other, kept));
    assertThrows(IOException.class, () -> store.deleteStudy(root.resolve(".projects")));
  }

  @Test
  void deletingAStudyWithNoProjectsStillWorks() throws IOException {
    StudyStore store = new StudyStore(root, true);
    store.prepare();
    store(store, "1.2.826.0.1.3680043.8.498.20");

    store.deleteStudy(store.listStudies().getFirst().folder());

    assertEquals(List.of(), store.listStudies());
  }

  @Test
  void aNewVersionCanBeSavedForTheStudyAfterItsProjectsWereDeleted() throws IOException {
    StudyStore store = new StudyStore(root, true);
    store.prepare();
    store(store, "1.2.826.0.1.3680043.8.498.20");
    store.projects("stitch").put(STUDY, id(), doc("old"));
    store.deleteStudy(store.listStudies().getFirst().folder());

    String id = id();
    store.projects("stitch").put(STUDY, id, doc("new"));

    assertEquals(List.of(id), store.projects("stitch").list(STUDY));
  }

  // ---- Codex review of 10 Nov 2026: each finding, reproduced ----

  @Test
  void everyWriteFlushesEveryFolderUpToTheRootAlsoAfterAFailedFlush() throws IOException {
    Path rootReal = root.toAbsolutePath().normalize();
    Path kind = rootReal.resolve(ProjectDocuments.FOLDER).resolve("stitch");
    List<Path> chain =
        List.of(kind.resolve(STUDY), kind, rootReal.resolve(ProjectDocuments.FOLDER), rootReal);

    ProjectDocuments failing = docs();
    failing.folderFlush =
        f -> {
          throw new IOException("flush failed");
        };
    assertThrows(IOException.class, () -> failing.put(STUDY, id(), doc("a")));

    List<Path> flushed = new ArrayList<>();
    ProjectDocuments retry = docs();
    retry.folderFlush = flushed::add;
    retry.put(STUDY, id(), doc("b"));
    assertEquals(chain, flushed, "the retry flushes the whole path, not only the study's folder");
  }

  /** Codex recheck, 10 Nov 2026: a flush that failed after the name was reported as not kept. */
  @Test
  void aFlushThatFailsAfterTheNameSaysTheDocumentIsKept() throws IOException {
    ProjectDocuments failing = docs();
    failing.folderFlush =
        f -> {
          throw new IOException("flush failed in " + f);
        };
    String id = id();

    IOException e =
        assertThrows(
            ProjectDocuments.KeptUnconfirmedException.class,
            () -> failing.put(STUDY, id, doc("whole")));

    assertFalse(e.getMessage().contains(root.toString()), "no path for the user");
    assertEquals(List.of(id), docs().list(STUDY));
    assertArrayEquals(doc("whole"), docs().get(STUDY, id));
  }

  @Test
  void aDocumentThatDoesNotReadBackIsNeverKept() throws IOException {
    ProjectDocuments corrupting = docs();
    corrupting.step =
        step -> {
          if (step.equals("forced")) {
            try (Stream<Path> parts = Files.list(studyFolder())) {
              for (Path p : parts.filter(f -> f.toString().endsWith(".part")).toList()) {
                Files.write(p, new byte[] {1});
              }
            }
          }
        };
    String id = id();

    assertThrows(IOException.class, () -> corrupting.put(STUDY, id, doc("whole")));

    assertEquals(List.of(), docs().list(STUDY));
    assertThrows(NoSuchFileException.class, () -> docs().get(STUDY, id));
  }

  @Test
  void aDeletionWaitsForAWriteUnderWayAndKeepsTheLockFile() throws Exception {
    StudyStore store = new StudyStore(root, true);
    store.prepare();
    store(store, "1.2.826.0.1.3680043.8.498.20");
    store.projects("stitch").put(STUDY, id(), doc("first"));
    CountDownLatch writing = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ProjectDocuments slow = store.projects("stitch");
    slow.step =
        step -> {
          if (step.equals("written")) {
            writing.countDown();
            try {
              release.await();
            } catch (InterruptedException e) {
              throw new IOException(e);
            }
          }
        };
    ExecutorService pool = Executors.newFixedThreadPool(2);
    String late = id();
    Future<?> write =
        pool.submit(
            () -> {
              slow.put(STUDY, late, doc("late"));
              return null;
            });
    assertTrue(writing.await(10, TimeUnit.SECONDS));
    Path folder = store.listStudies().getFirst().folder();
    Future<?> deletion =
        pool.submit(
            () -> {
              store.deleteStudy(folder);
              return null;
            });
    Thread.sleep(300);
    assertFalse(deletion.isDone(), "the deletion waits for the write under way");
    release.countDown();
    write.get(10, TimeUnit.SECONDS);
    deletion.get(10, TimeUnit.SECONDS);
    pool.shutdown();

    assertEquals(List.of(), store.projects("stitch").list(STUDY), "the late write went too");
    assertTrue(
        Files.exists(root.resolve(".projects/stitch/.lock")), "the lock file is never deleted");
  }

  @Test
  void aStudyWhoseImagesCannotBeReadStillTakesItsProjects() throws IOException {
    StudyStore store = new StudyStore(root, true);
    store.prepare();
    store(store, "1.2.826.0.1.3680043.8.498.20");
    store.projects("stitch").put(STUDY, id(), doc("v"));
    Path folder = store.listStudies().getFirst().folder();
    try (Stream<Path> images = Files.list(folder)) {
      for (Path image : images.toList()) {
        Files.write(image, new byte[] {0, 1, 2}); // no readable header left
      }
    }

    store.deleteStudy(folder);

    assertEquals(List.of(), store.projects("stitch").list(STUDY));
    assertFalse(Files.exists(studyFolder()));
  }

  @Test
  void whenTheProjectsCannotBeDeletedTheStudyStaysAndCanBeDeletedAgain() throws IOException {
    StudyStore store = new StudyStore(root, true);
    store.prepare();
    store(store, "1.2.826.0.1.3680043.8.498.20");
    store.projects("stitch").put(STUDY, id(), doc("v"));
    Path blocker = studyFolder().resolve("not-empty");
    Files.createDirectories(blocker);
    Files.writeString(blocker.resolve("x"), "x");
    Path folder = store.listStudies().getFirst().folder();

    IOException e = assertThrows(IOException.class, () -> store.deleteStudy(folder));

    assertTrue(e.getMessage().startsWith("The study was not deleted"), e.getMessage());
    assertFalse(e.getMessage().contains(tmp.toString()), "no path in the message");
    assertEquals(1, store.listStudies().size(), "the study is whole and still listed");
    assertEquals(1, store.listStudies().getFirst().images());

    Files.delete(blocker.resolve("x"));
    Files.delete(blocker);
    store.deleteStudy(folder);
    assertEquals(List.of(), store.listStudies());
    assertEquals(List.of(), store.projects("stitch").list(STUDY));
  }

  @Test
  void onlyAFileSystemWithoutHardLinksFallsBackToAMove() {
    assertTrue(
        ProjectDocuments.linksUnsupported(
            new java.nio.file.FileSystemException("a", null, "Operation not supported")));
    assertTrue(
        ProjectDocuments.linksUnsupported(
            new java.nio.file.FileSystemException("a", null, "Operation not permitted")));
    assertFalse(
        ProjectDocuments.linksUnsupported(
            new java.nio.file.AccessDeniedException("a", null, "Permission denied")));
    assertFalse(
        ProjectDocuments.linksUnsupported(
            new java.nio.file.FileSystemException("a", null, "No space left on device")));
  }
}
