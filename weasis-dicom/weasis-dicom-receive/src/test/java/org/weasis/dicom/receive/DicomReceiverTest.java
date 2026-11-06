/*
 * Copyright (c) 2009-2020 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.receive;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.io.DicomOutputStream;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.pdu.AAssociateRJ;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DicomReceiverTest {
  private static final String AE = "AOVIEWER";
  private static final String XRAY = "XRAY";
  private static final long LIMIT = 1024L * 1024L;
  private static final String STUDY = "1.2.3.100";

  @TempDir Path root;

  private final List<ReceivedInstance> stored = new CopyOnWriteArrayList<>();
  private final List<ReceiveProblem> problems = new CopyOnWriteArrayList<>();
  private final AtomicLong freeBytes = new AtomicLong(Long.MAX_VALUE);
  private final AtomicBoolean failWrites = new AtomicBoolean();
  private int maxPathChars = Integer.MAX_VALUE;
  private int port;
  private DicomReceiver receiver;

  private final ReceiveListener listener =
      new ReceiveListener() {
        @Override
        public void onStored(ReceivedInstance instance) {
          stored.add(instance);
        }

        @Override
        public void onProblem(ReceiveProblem problem, String detail) {
          problems.add(problem);
        }
      };

  @BeforeEach
  void pickPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
  }

  @AfterEach
  void stop() {
    if (receiver != null) {
      receiver.stop();
    }
  }

  private ReceiveSettings settings(boolean includeName, int maxAssociations) {
    return new ReceiveSettings(
        true,
        AE,
        port,
        "127.0.0.1",
        List.of(new AllowedSender(XRAY, "127.0.0.1")),
        root,
        maxAssociations,
        LIMIT,
        includeName);
  }

  private void start() throws Exception {
    start(settings(true, 4));
  }

  /** A free port can be taken again before the bind, by another test running in parallel. */
  private void start(ReceiveSettings settings) throws Exception {
    ReceiveSettings attempt = settings;
    for (int tries = 5; ; tries--) {
      try {
        startOnce(attempt);
        return;
      } catch (ReceiveStartException e) {
        if (tries == 0 || !"receive.start.port".equals(e.messageKey())) {
          throw e;
        }
        pickPort();
        attempt = withPort(attempt, port);
      }
    }
  }

  private static ReceiveSettings withPort(ReceiveSettings s, int newPort) {
    return new ReceiveSettings(
        s.enabled(),
        s.aeTitle(),
        newPort,
        s.listenAddress(),
        s.allowedSenders(),
        s.storageFolder(),
        s.maxAssociations(),
        s.maxFileSizeBytes(),
        s.includeNameInFileNames());
  }

  private void startOnce(ReceiveSettings settings) throws Exception {
    ReceivedStudyStore store =
        new ReceivedStudyStore(
            root,
            settings.includeNameInFileNames(),
            Clock.fixed(Instant.parse("2026-11-06T10:00:00Z"), ZoneOffset.UTC),
            folder -> freeBytes.get(),
            file -> {
              if (failWrites.get()) {
                return new OutputStream() {
                  @Override
                  public void write(int b) throws IOException {
                    throw new IOException("No space left on device");
                  }
                };
              }
              return Files.newOutputStream(file);
            },
            maxPathChars);
    receiver = new DicomReceiver(settings, listener, store);
    receiver.start();
  }

  private static Attributes image(String sopUid) {
    Attributes a = new Attributes();
    a.setString(Tag.SOPClassUID, VR.UI, TestScu.SOP_CLASS);
    a.setString(Tag.SOPInstanceUID, VR.UI, sopUid);
    a.setString(Tag.StudyInstanceUID, VR.UI, STUDY);
    a.setString(Tag.SeriesInstanceUID, VR.UI, "1.2.3.200");
    a.setString(Tag.PatientName, VR.PN, "ABEBE^KEBEDE");
    a.setString(Tag.PatientID, VR.LO, "MRN77");
    a.setString(Tag.PatientBirthDate, VR.DA, "19800315");
    a.setString(Tag.StudyDate, VR.DA, "20260101");
    a.setInt(Tag.Rows, VR.US, 2);
    a.setInt(Tag.Columns, VR.US, 2);
    a.setBytes(Tag.PixelData, VR.OW, new byte[8]);
    return a;
  }

  private static byte[] toBytes(Attributes dataset) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DicomOutputStream out = new DicomOutputStream(bytes, UID.ExplicitVRLittleEndian)) {
      out.writeDataset(null, dataset);
    }
    return bytes.toByteArray();
  }

  private List<Path> storedFiles() throws IOException {
    try (Stream<Path> files = Files.walk(root)) {
      return files
          .filter(Files::isRegularFile)
          .filter(f -> !f.getParent().getFileName().toString().equals(".incoming"))
          .toList();
    }
  }

  private List<Path> tempFiles() throws IOException {
    try (Stream<Path> files = Files.list(root.resolve(".incoming"))) {
      return files.toList();
    }
  }

  @Test
  void anAllowedSenderStoresAnImageNamedAsAsked() throws Exception {
    start();
    try (TestScu scu = new TestScu(XRAY, AE, port)) {
      assertEquals(Status.Success, scu.echo());
      assertEquals(Status.Success, scu.store(image("1.2.3.1")));
    }
    List<Path> files = storedFiles();
    assertEquals(1, files.size());
    Path file = files.getFirst();
    assertEquals("ABEBE KEBEDE_45Y_MRN77_2026-11-06_1.2.3.1.dcm", file.getFileName().toString());
    assertEquals(root, file.getParent().getParent());
    assertEquals(
        "ABEBE KEBEDE_45Y_MRN77_2026-11-06_" + ReceiveNaming.studyKey(STUDY),
        file.getParent().getFileName().toString());
    try (DicomInputStream in = new DicomInputStream(file.toFile())) {
      Attributes back = in.readDataset();
      assertEquals("1.2.3.1", back.getString(Tag.SOPInstanceUID));
      assertEquals("ABEBE^KEBEDE", back.getString(Tag.PatientName));
    }
    assertEquals(1, stored.size());
    assertEquals(STUDY, stored.getFirst().studyUid());
    assertTrue(problems.isEmpty());
    assertTrue(tempFiles().isEmpty());
  }

  @Test
  void imagesOfOneStudyShareAFolderAndOtherStudiesDoNot() throws Exception {
    start();
    Attributes other = image("1.2.3.3");
    other.setString(Tag.StudyInstanceUID, VR.UI, "1.2.3.101");
    try (TestScu scu = new TestScu(XRAY, AE, port)) {
      assertEquals(Status.Success, scu.store(image("1.2.3.1")));
      assertEquals(Status.Success, scu.store(image("1.2.3.2")));
      assertEquals(Status.Success, scu.store(other));
    }
    assertEquals(2, receiver.store().listStudies().size());
    assertEquals(3, storedFiles().size());
  }

  @Test
  void anUnknownAeTitleIsRejected() throws Exception {
    start();
    AAssociateRJ rejected = assertThrows(AAssociateRJ.class, () -> new TestScu("OTHER", AE, port));
    assertEquals(AAssociateRJ.REASON_CALLING_AET_NOT_RECOGNIZED, rejected.getReason());
    assertEquals(List.of(ReceiveProblem.SENDER_REJECTED), problems);
    assertTrue(storedFiles().isEmpty());
  }

  @Test
  void anUnknownAddressIsRejected() throws Exception {
    ReceiveSettings base = settings(true, 4);
    start(
        new ReceiveSettings(
            true,
            AE,
            port,
            "127.0.0.1",
            List.of(new AllowedSender(XRAY, "10.9.8.7")),
            root,
            4,
            LIMIT,
            base.includeNameInFileNames()));
    assertThrows(AAssociateRJ.class, () -> new TestScu(XRAY, AE, port));
    assertEquals(List.of(ReceiveProblem.SENDER_REJECTED), problems);
  }

  @Test
  void anEmptyAllowedListRejectsEveryone() throws Exception {
    start(new ReceiveSettings(true, AE, port, "127.0.0.1", List.of(), root, 4, LIMIT, true));
    assertThrows(AAssociateRJ.class, () -> new TestScu(XRAY, AE, port));
  }

  @Test
  void aWrongCalledAeTitleIsRejected() throws Exception {
    start();
    assertThrows(AAssociateRJ.class, () -> new TestScu(XRAY, "NOTME", port));
  }

  @Test
  void somethingThatIsNotDicomIsRefused() throws Exception {
    start();
    byte[] noise = "this is a text file, not a DICOM image".getBytes();
    try (TestScu scu = new TestScu(XRAY, AE, port)) {
      assertEquals(Status.CannotUnderstand, scu.storeRaw("1.2.3.9", noise));
      assertEquals(Status.Success, scu.store(image("1.2.3.1")));
    }
    assertEquals(List.of(ReceiveProblem.NOT_DICOM), problems);
    assertEquals(1, storedFiles().size());
    assertTrue(tempFiles().isEmpty());
  }

  @Test
  void aDataSetWithoutTheSopInstanceUidOfTheRequestIsRefused() throws Exception {
    start();
    Attributes noStudy = image("1.2.3.1");
    noStudy.remove(Tag.StudyInstanceUID);
    Attributes otherUid = image("1.2.3.2");
    otherUid.setString(Tag.SOPInstanceUID, VR.UI, "1.2.3.3");
    try (TestScu scu = new TestScu(XRAY, AE, port)) {
      assertEquals(Status.CannotUnderstand, scu.store(noStudy));
      assertEquals(Status.CannotUnderstand, scu.storeRaw("1.2.3.2", toBytes(otherUid)));
    }
    assertTrue(storedFiles().isEmpty());
  }

  @Test
  void anOversizeImageIsRefusedAndTheNextOneStillArrives() throws Exception {
    start();
    Attributes big = image("1.2.3.8");
    big.setBytes(Tag.PixelData, VR.OW, new byte[(int) (3 * LIMIT)]);
    try (TestScu scu = new TestScu(XRAY, AE, port)) {
      assertEquals(Status.OutOfResources, scu.store(big));
      assertEquals(Status.Success, scu.store(image("1.2.3.1")));
    }
    assertEquals(List.of(ReceiveProblem.FILE_TOO_LARGE), problems);
    assertEquals(1, storedFiles().size());
    assertTrue(tempFiles().isEmpty());
  }

  @Test
  void aDuplicateKeepsTheFirstCopyAndIsNotCountedTwice() throws Exception {
    start();
    Attributes changed = image("1.2.3.1");
    changed.setString(Tag.PatientID, VR.LO, "CHANGED");
    try (TestScu scu = new TestScu(XRAY, AE, port)) {
      assertEquals(Status.Success, scu.store(image("1.2.3.1")));
      assertEquals(Status.Success, scu.store(changed));
    }
    List<Path> files = storedFiles();
    assertEquals(1, files.size());
    assertEquals(1, stored.size());
    try (DicomInputStream in = new DicomInputStream(files.getFirst().toFile())) {
      assertEquals("MRN77", in.readDataset().getString(Tag.PatientID));
    }
    assertTrue(tempFiles().isEmpty());
  }

  @Test
  void aStudyContinuedAfterARestartGoesToItsFolderAndDuplicatesAreStillFound() throws Exception {
    start();
    try (TestScu scu = new TestScu(XRAY, AE, port)) {
      assertEquals(Status.Success, scu.store(image("1.2.3.1")));
    }
    receiver.stop();
    start();
    try (TestScu scu = new TestScu(XRAY, AE, port)) {
      assertEquals(Status.Success, scu.store(image("1.2.3.1")));
      assertEquals(Status.Success, scu.store(image("1.2.3.2")));
    }
    assertEquals(1, receiver.store().listStudies().size());
    assertEquals(2, storedFiles().size());
    assertEquals(2, stored.size());
  }

  @Test
  void aHostilePatientNameStaysInsideTheStorageFolder() throws Exception {
    start();
    Attributes hostile = image("1.2.3.1");
    hostile.setString(Tag.PatientName, VR.PN, "../../../evil<>:\"|?*\\name^..\\..\\x");
    hostile.setString(Tag.PatientID, VR.LO, "../../MRN/..\\7");
    try (TestScu scu = new TestScu(XRAY, AE, port)) {
      assertEquals(Status.Success, scu.store(hostile));
    }
    List<Path> files = storedFiles();
    assertEquals(1, files.size());
    Path file = files.getFirst().toRealPath();
    assertEquals(root.toRealPath(), file.getParent().getParent());
    for (Path part : List.of(file.getFileName(), file.getParent().getFileName())) {
      String text = part.toString();
      assertAll(
          text,
          () -> assertFalse(text.contains("..")),
          () -> assertTrue(text.chars().noneMatch(c -> "<>:\"/\\|?*".indexOf(c) >= 0)),
          () -> assertTrue(text.contains("evil")));
    }
  }

  @Test
  void aHostileSopInstanceUidIsRefused() throws Exception {
    start();
    try (TestScu scu = new TestScu(XRAY, AE, port)) {
      assertEquals(Status.CannotUnderstand, scu.storeRaw("../../evil", new byte[16]));
    }
    assertTrue(storedFiles().isEmpty());
  }

  @Test
  void missingPatientFieldsFallBackToTheUids() throws Exception {
    start();
    Attributes bare = image("1.2.3.1");
    bare.remove(Tag.PatientName);
    bare.remove(Tag.PatientID);
    bare.remove(Tag.PatientBirthDate);
    bare.remove(Tag.StudyDate);
    try (TestScu scu = new TestScu(XRAY, AE, port)) {
      assertEquals(Status.Success, scu.store(bare));
    }
    Path file = storedFiles().getFirst();
    assertEquals("2026-11-06_1.2.3.1.dcm", file.getFileName().toString());
    assertEquals(
        "2026-11-06_" + STUDY + "_" + ReceiveNaming.studyKey(STUDY),
        file.getParent().getFileName().toString());
  }

  @Test
  void theNameCanBeLeftOutOfTheNames() throws Exception {
    start(settings(false, 4));
    try (TestScu scu = new TestScu(XRAY, AE, port)) {
      assertEquals(Status.Success, scu.store(image("1.2.3.1")));
    }
    Path file = storedFiles().getFirst();
    assertFalse(file.toString().contains("ABEBE"));
    assertEquals("45Y_MRN77_2026-11-06_1.2.3.1.dcm", file.getFileName().toString());
  }

  @Test
  void aLongPathOnWindowsShortensTheNameAndKeepsTheUid() throws Exception {
    maxPathChars = root.toString().length() + 90;
    start();
    try (TestScu scu = new TestScu(XRAY, AE, port)) {
      assertEquals(Status.Success, scu.store(image("1.2.3.1")));
    }
    Path file = storedFiles().getFirst();
    assertTrue(file.toString().length() <= maxPathChars);
    assertTrue(file.getFileName().toString().endsWith("1.2.3.1.dcm"));
  }

  @Test
  void aLongStorageFolderKeepsThePatientInTheNameWhereThereIsNoPathLimit() throws Exception {
    start();
    try (TestScu scu = new TestScu(XRAY, AE, port)) {
      assertEquals(Status.Success, scu.store(image("1.2.3.1")));
    }
    assertTrue(storedFiles().getFirst().getFileName().toString().startsWith("ABEBE KEBEDE_"));
  }

  @Test
  void aFullDiskRefusesWithOutOfResources() throws Exception {
    start();
    freeBytes.set(ReceivedStudyStore.MIN_FREE_BYTES - 1);
    try (TestScu scu = new TestScu(XRAY, AE, port)) {
      assertEquals(Status.OutOfResources, scu.store(image("1.2.3.1")));
      freeBytes.set(Long.MAX_VALUE);
      assertEquals(Status.Success, scu.store(image("1.2.3.1")));
    }
    assertEquals(List.of(ReceiveProblem.DISK_FULL), problems);
    assertEquals(1, storedFiles().size());
  }

  @Test
  void aWriteFailureRefusesWithOutOfResources() throws Exception {
    start();
    failWrites.set(true);
    try (TestScu scu = new TestScu(XRAY, AE, port)) {
      assertEquals(Status.OutOfResources, scu.store(image("1.2.3.1")));
      failWrites.set(false);
      assertEquals(Status.Success, scu.store(image("1.2.3.2")));
    }
    assertEquals(List.of(ReceiveProblem.FOLDER_NOT_WRITABLE), problems);
    assertEquals(1, storedFiles().size());
    assertTrue(tempFiles().isEmpty());
  }

  @Test
  void connectionsAboveTheLimitAreRefused() throws Exception {
    start(settings(true, 1));
    try (TestScu first = new TestScu(XRAY, AE, port)) {
      assertEquals(Status.Success, first.echo());
      assertThrows(AAssociateRJ.class, () -> new TestScu(XRAY, AE, port));
    }
  }

  @Test
  void aPortInUseGivesAPlainError() throws Exception {
    try (ServerSocket busy = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {
      ReceiveStartException e =
          assertThrows(ReceiveStartException.class, () -> startOnce(settings(true, 4)));
      assertEquals("receive.start.port", e.messageKey());
    }
    assertFalse(receiver.isRunning());
  }

  @Test
  void anUnusableFolderGivesAPlainError() throws Exception {
    Path file = Files.createFile(root.resolve("not-a-folder"));
    ReceiveSettings base = settings(true, 4);
    ReceiveSettings s =
        new ReceiveSettings(
            true, AE, port, "127.0.0.1", base.allowedSenders(), file, 4, LIMIT, true);
    DicomReceiver bad = new DicomReceiver(s, listener);
    ReceiveStartException e = assertThrows(ReceiveStartException.class, bad::start);
    assertEquals("receive.start.folder", e.messageKey());
  }

  @Test
  void leftoverTemporaryFilesAreRemovedAtStart() throws Exception {
    start();
    receiver.stop();
    Files.createFile(root.resolve(".incoming").resolve("crash.part"));
    start();
    assertTrue(tempFiles().isEmpty());
  }

  @Test
  void aStudyCanBeDeletedButNothingOutsideTheStorageFolder() throws Exception {
    start();
    try (TestScu scu = new TestScu(XRAY, AE, port)) {
      assertEquals(Status.Success, scu.store(image("1.2.3.1")));
    }
    ReceivedStudy study = receiver.store().listStudies().getFirst();
    assertEquals(1, study.images());
    assertTrue(study.sizeBytes() > 0);

    Path outside = Files.createTempDirectory("outside_abcdef123456");
    try {
      assertThrows(IOException.class, () -> receiver.store().deleteStudy(outside));
      assertThrows(IOException.class, () -> receiver.store().deleteStudy(root));
      assertThrows(
          IOException.class,
          () -> receiver.store().deleteStudy(root.resolve("..").resolve(root.getFileName())));
      assertTrue(Files.isDirectory(outside));
    } finally {
      Files.deleteIfExists(outside);
    }

    receiver.store().deleteStudy(study.folder());
    assertTrue(storedFiles().isEmpty());
    assertTrue(receiver.store().listStudies().isEmpty());

    // The folder comes back when the machine resends.
    try (TestScu scu = new TestScu(XRAY, AE, port)) {
      assertEquals(Status.Success, scu.store(image("1.2.3.1")));
    }
    assertEquals(1, storedFiles().size());
  }
}
