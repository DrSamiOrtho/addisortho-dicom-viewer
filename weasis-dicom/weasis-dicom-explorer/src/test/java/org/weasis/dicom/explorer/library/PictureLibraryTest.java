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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.weasis.dicom.explorer.index.StudyEntry;

class PictureLibraryTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC);
  private static final LocalDate TODAY = LocalDate.of(2026, 3, 1);
  private static final PictureIdentity SOMEONE =
      new PictureIdentity("ሳሙኤል Synthetic", "000123", LocalDate.of(2026, 1, 31));

  @TempDir Path tmp;
  private Path root;
  private StudyStore store;
  private PictureLibrary library;

  @BeforeEach
  void open() throws IOException {
    root = tmp.resolve("library");
    store = new StudyStore(root, true);
    store.prepare();
    library = new PictureLibrary(store, CLOCK);
  }

  private Path jpeg(String name, byte last) throws IOException {
    Path file = tmp.resolve(name);
    Files.write(file, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x10, 1, 2, 3, last});
    return file;
  }

  @Test
  void identityWithUnicodeAndLeadingZerosSurvivesARestart() throws IOException {
    library.keep(jpeg("a.jpg", (byte) 1), SOMEONE);

    PictureLibrary.LocalPicture back = new PictureLibrary(store, CLOCK).list().getFirst();

    assertEquals(SOMEONE, back.identity());
    assertEquals("000123", back.identity().patientId());
    assertEquals(PictureLibrary.Format.JPEG, back.format());
  }

  @Test
  void theKeptPictureIsTheSameBytesAndTheOriginalIsUntouched() throws IOException {
    Path source = jpeg("a.jpg", (byte) 1);
    byte[] before = Files.readAllBytes(source);

    library.keep(source, SOMEONE);

    assertArrayEquals(before, Files.readAllBytes(library.list().getFirst().file()));
    assertArrayEquals(before, Files.readAllBytes(source));
    assertFalse(library.list().getFirst().file().toFile().canWrite());
  }

  @Test
  void theRecordSaysAPersonStatedItAndCarriesNoDicomIdentifiers() throws IOException {
    library.keep(jpeg("a.jpg", (byte) 1), SOMEONE);

    PictureLibrary.LocalPicture p = library.list().getFirst();
    List<String> record = PictureLibrary.recordLines(p);

    assertTrue(p.localId().startsWith(PictureLibrary.ID_PREFIX));
    assertTrue(record.contains("objectType=LOCAL_NON_DICOM_PICTURE"));
    assertTrue(record.contains("patientNameSource=USER_CONFIRMED"));
    assertTrue(record.contains("dateTakenSource=USER_CONFIRMED"));
    assertTrue(record.stream().noneMatch(line -> line.contains("InstanceUID")));
  }

  @Test
  void whatIsUnknownStaysUnknown() throws IOException {
    library.keep(jpeg("a.jpg", (byte) 1), new PictureIdentity("Synthetic", "", null));

    PictureLibrary.LocalPicture p = library.list().getFirst();

    assertNull(p.identity().dateTaken());
    assertTrue(PictureLibrary.recordLines(p).contains("dateTakenSource=UNKNOWN"));
    assertTrue(PictureLibrary.recordLines(p).contains("patientIdSource=UNKNOWN"));
    StudyEntry row = StudyEntry.of(p, "Unknown", "note", "Picture");
    assertEquals("Unknown", row.patientId());
    assertEquals("Unknown", row.studyDate());
  }

  @Test
  void theRowIsMarkedAsAPictureAndNeverLooksLikeADicomStudy() throws IOException {
    library.keep(jpeg("a.jpg", (byte) 1), SOMEONE);

    StudyEntry row = StudyEntry.of(library.list().getFirst(), "Unknown", "note", "Picture");

    assertTrue(row.isLocalPicture());
    assertEquals("20260131", row.studyDate());
    assertFalse(row.studyUid().matches("[0-9.]+"));
  }

  @Test
  void theSamePictureForTheSamePatientIsKeptOnce() throws IOException {
    Path source = jpeg("a.jpg", (byte) 1);
    assertEquals(PictureLibrary.Outcome.KEPT, library.keep(source, SOMEONE));

    assertEquals(PictureLibrary.Outcome.ALREADY_HELD, library.keep(source, SOMEONE));
    assertEquals(1, library.list().size());
  }

  @Test
  void theSamePictureForAnotherPatientIsRefusedAndTheFirstStands() throws IOException {
    Path source = jpeg("a.jpg", (byte) 1);
    library.keep(source, SOMEONE);

    assertEquals(
        PictureLibrary.Outcome.CONFLICT,
        library.keep(source, new PictureIdentity("Other", "999", null)));
    assertEquals(SOMEONE, library.list().getFirst().identity());
  }

  @Test
  void aFileMerelyNamedLikeAPictureIsRefused() throws IOException {
    Path fake = tmp.resolve("fake.jpg");
    Files.writeString(fake, "not a picture at all");

    assertNull(PictureLibrary.formatOf(fake));
    assertThrows(IOException.class, () -> library.keep(fake, SOMEONE));
    assertTrue(library.list().isEmpty());
  }

  @Test
  void anInterruptedKeepLeavesNothingListedAndIsCleanedUp() throws IOException {
    Path staging = root.resolve(".pictures/.staging-old");
    Files.createDirectories(staging);
    Files.writeString(staging.resolve("record.properties"), "objectType=x");

    assertTrue(library.list().isEmpty());
    library.keep(jpeg("a.jpg", (byte) 1), SOMEONE);

    assertFalse(Files.exists(staging));
    assertEquals(1, library.list().size());
  }

  @Test
  void aFullDiskStopsTheBatchWithoutStoringAnything() throws IOException {
    StudyStore full =
        new StudyStore(
            root,
            true,
            Clock.systemUTC(),
            _ -> StudyStore.MIN_FREE_BYTES,
            Files::newOutputStream,
            Integer.MAX_VALUE);

    PictureLibrary.Result result =
        new PictureLibrary(full, CLOCK).keepAll(List.of(jpeg("a.jpg", (byte) 1)), SOMEONE);

    assertTrue(result.outOfSpace());
    assertEquals(0, result.kept());
  }

  @Test
  void aDateMustBeRealAndNotInTheFuture() throws PictureIdentity.Invalid {
    assertNull(PictureIdentity.parse("a", "", "  ", TODAY).dateTaken());
    assertEquals(TODAY, PictureIdentity.parse("a", "", "2026-03-01", TODAY).dateTaken());
    assertThrows(
        PictureIdentity.Invalid.class, () -> PictureIdentity.parse("a", "", "2026-02-30", TODAY));
    assertThrows(
        PictureIdentity.Invalid.class, () -> PictureIdentity.parse("a", "", "2026-03-02", TODAY));
    assertThrows(
        PictureIdentity.Invalid.class, () -> PictureIdentity.parse("a", "", "31/01/2026", TODAY));
  }

  @Test
  void textIsKeptAsTypedApartFromTheEdges() throws PictureIdentity.Invalid {
    PictureIdentity who = PictureIdentity.parse("  ሳሙኤል  ", " 000123 ", "", TODAY);

    assertEquals("ሳሙኤል", who.patientName());
    assertEquals("000123", who.patientId());
    assertFalse(who.isAnonymous());
    assertTrue(PictureIdentity.parse(" ", null, null, TODAY).isAnonymous());
    assertThrows(PictureIdentity.Invalid.class, () -> PictureIdentity.parse("a\nb", "", "", TODAY));
  }

  private void writeDicom(Path file) throws IOException {
    Attributes a = new Attributes();
    a.setString(Tag.SOPClassUID, VR.UI, UID.CTImageStorage);
    a.setString(Tag.SOPInstanceUID, VR.UI, "1.2.3.1");
    a.setString(Tag.StudyInstanceUID, VR.UI, "1.2.3.2");
    a.setString(Tag.SeriesInstanceUID, VR.UI, "1.2.3.3");
    a.setString(Tag.PatientName, VR.PN, "Synthetic^Test");
    a.setString(Tag.PatientID, VR.LO, "001");
    a.setBytes(Tag.PixelData, VR.OB, new byte[] {1, 2, 3, 4});
    try (DicomOutputStream out = new DicomOutputStream(file.toFile())) {
      out.writeDataset(a.createFileMetaInformation(UID.ExplicitVRLittleEndian), a);
    }
  }

  private LibraryImport.Report importing(Path path) throws IOException {
    return new LibraryImport(store, () -> false).run(List.of(path.toFile()), true);
  }

  @Test
  void aChosenPictureIsOfferedButNothingIsKeptBeforeConfirmation() throws IOException {
    Path picture = jpeg("a.jpg", (byte) 1);

    LibraryImport.Report report = importing(picture);

    assertEquals(List.of(picture.toAbsolutePath().normalize()), report.pictures());
    assertFalse(Files.exists(root.resolve(".pictures")));
  }

  @Test
  void aFolderOfOnlyPicturesIsOffered() throws IOException {
    Path folder = Files.createDirectories(tmp.resolve("photos"));
    Files.move(jpeg("a.jpg", (byte) 1), folder.resolve("a.jpg"));

    assertEquals(1, importing(folder).pictures().size());
  }

  @Test
  void aPictureBesideDicomIsNotOffered() throws IOException {
    Path disc = Files.createDirectories(tmp.resolve("disc"));
    writeDicom(disc.resolve("IM1"));
    Files.move(jpeg("logo.jpg", (byte) 1), disc.resolve("logo.jpg"));

    LibraryImport.Report report = importing(disc);

    assertEquals(1, report.stored());
    assertTrue(report.pictures().isEmpty());
  }
}
