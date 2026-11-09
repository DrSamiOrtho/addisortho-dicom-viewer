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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.ui.util.SavedFile;
import org.weasis.dicom.codec.DicomCodec;
import org.weasis.dicom.explorer.Messages;
import org.weasis.dicom.explorer.imp.DicomZipMediaIO;

/**
 * Copies imported DICOM files, and the DICOM objects inside ZIP archives, into the managed store.
 * The originals are only ever read, so a study stays available after the disc is ejected or the
 * archive is moved.
 */
final class LibraryImport {
  private static final Logger LOGGER = LoggerFactory.getLogger(LibraryImport.class);
  private static final int SUBFOLDERS = Integer.MAX_VALUE;
  private static final int BUFFER = 64 * 1024;
  private static final long SPACE_CHECK_BYTES = 64L << 20;
  private static final int DICM_OFFSET = 128;
  private static final byte[] DICM = {'D', 'I', 'C', 'M'};

  /**
   * What one import did.
   *
   * @param alreadyHeld the same image was already in the store
   * @param conflicts same identifier as a stored image but a different content or patient: set
   *     aside in the quarantine folder, not added
   * @param notDicom files that are not DICOM, left alone
   * @param firstProblem for the user only: it can name a file, so it is never logged
   * @param outOfSpace the import stopped because the disk is nearly full
   * @param archivesRefused archives that were not unpacked, or held no DICOM when chosen directly
   * @param nestedArchives archives found inside an archive, which are not opened
   * @param cancelled the user stopped it, so what is counted here is all that was kept
   * @param pictures every picture found, for the user to review: a folder can mix patients, and a
   *     picture beside DICOM can be clinical as easily as a disc's artwork
   */
  record Report(
      int stored,
      int alreadyHeld,
      int conflicts,
      int failed,
      int notDicom,
      String firstProblem,
      boolean outOfSpace,
      int archivesRefused,
      int nestedArchives,
      boolean cancelled,
      List<Path> pictures) {

    int dicomObjects() {
      return stored + alreadyHeld + conflicts + failed;
    }

    static Report failure(String problem) {
      return new Report(0, 0, 0, 1, 0, problem, false, 0, 0, false, List.of());
    }
  }

  private final StudyStore store;
  private final BooleanSupplier cancelled;
  private final ArchiveGuard.Limits limits;
  private final IntConsumer progress;
  private int stored;
  private int alreadyHeld;
  private int conflicts;
  private int failed;
  private int notDicom;
  private String firstProblem;
  private boolean outOfSpace;
  private int archivesRefused;
  private int nestedArchives;
  private boolean wasCancelled;
  private final List<Path> pictures = new ArrayList<>();

  LibraryImport(StudyStore store, BooleanSupplier cancelled) {
    this(store, cancelled, ArchiveGuard.DEFAULT, _ -> {});
  }

  /**
   * @param progress told how many images have been dealt with so far
   */
  LibraryImport(
      StudyStore store,
      BooleanSupplier cancelled,
      ArchiveGuard.Limits limits,
      IntConsumer progress) {
    this.store = store;
    this.cancelled = cancelled;
    this.limits = limits;
    this.progress = progress;
  }

  /**
   * @param paths files and folders
   * @param recursive also the subfolders of a folder; its own files are always included
   */
  Report run(List<File> paths, boolean recursive) throws IOException {
    store.prepare();
    for (File path : paths) {
      Path start = path.toPath().toAbsolutePath().normalize();
      if (insideStore(start)) {
        continue; // already in the store: copying it onto itself would only find duplicates
      }
      try (Stream<Path> walk =
          Files.walk(start, Files.isDirectory(start) && !recursive ? 1 : SUBFOLDERS)) {
        for (Path file : (Iterable<Path>) walk::iterator) {
          if (stopped()) {
            return report();
          }
          if (Files.isRegularFile(file) && !file.startsWith(store.root())) {
            importFile(file, file.equals(start));
          }
        }
      } catch (IOException | java.io.UncheckedIOException e) {
        LOGGER.warn("Cannot read an import folder: {}", e.getClass().getSimpleName());
        problem(path.getName() + ": " + SavedFile.reason(e));
        failed++;
      }
    }
    return report();
  }

  /** Also through a link or a differently written path, which comparing the text would miss. */
  private boolean insideStore(Path start) {
    if (start.startsWith(store.root())) {
      return true;
    }
    try {
      return Files.isDirectory(store.root())
          && start.toRealPath().startsWith(store.root().toRealPath());
    } catch (IOException e) {
      return false; // a source that cannot be resolved is reported when it is read
    }
  }

  private boolean stopped() {
    if (cancelled.getAsBoolean()) {
      wasCancelled = true;
    }
    return wasCancelled || outOfSpace;
  }

  /**
   * @param chosen the user picked this very file, rather than a folder it happens to be in
   */
  private void importFile(Path file, boolean chosen) {
    Attributes source = usableHeader(file);
    if (source == null) {
      if (isArchive(file) || hasZipName(file)) {
        importArchive(file, chosen);
        return;
      }
      notDicom++;
      if (PictureLibrary.formatOf(file) != null) {
        pictures.add(file);
      }
      return;
    }
    Path temp = store.newTempFile();
    try {
      if (!store.hasFreeSpace()) {
        outOfSpace = true;
        return;
      }
      // Into a file of our own: a copy of a disc's file would inherit its read-only permission.
      try (OutputStream out = store.openTempFile(temp)) {
        Files.copy(file, out);
      }
      Attributes copied = usableHeader(temp);
      String sop = source.getString(Tag.SOPInstanceUID);
      if (copied != null && !StudyStore.isComplete(temp)) {
        throw new IOException("The image file is incomplete");
      }
      if (copied == null
          || Files.size(temp) != Files.size(file)
          || !sop.equals(copied.getString(Tag.SOPInstanceUID))) {
        throw new IOException("The copy does not match the original");
      }
      commit(temp, copied, sop);
    } catch (IOException | RuntimeException e) {
      copyFailed(e, file.getFileName().toString());
    } finally {
      deleteQuietly(temp);
    }
  }

  private void commit(Path temp, Attributes header, String sop) throws IOException {
    StudyStore.Commit commit =
        store.commit(
            temp,
            header,
            header.getString(Tag.StudyInstanceUID),
            header.getString(Tag.SeriesInstanceUID),
            sop);
    switch (commit.outcome()) {
      case STORED -> stored++;
      case ALREADY_HELD -> alreadyHeld++;
      case CONFLICT -> conflicts++;
    }
    progress.accept(dicomSeen());
  }

  /** A full disk stops the import; anything else loses this one image and goes on. */
  private void copyFailed(Exception e, String label) {
    LOGGER.warn("Cannot store an imported image: {}", e.getClass().getSimpleName());
    if (diskIsFull()) {
      outOfSpace = true;
      return;
    }
    failed++;
    problem(label + ": " + SavedFile.reason(e));
  }

  private boolean diskIsFull() {
    try {
      return !store.hasFreeSpace();
    } catch (IOException e) {
      return false;
    }
  }

  private int dicomSeen() {
    return stored + alreadyHeld + conflicts + failed;
  }

  private static boolean hasZipName(Path file) {
    return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"); // NON-NLS
  }

  /** By its first bytes, the only thing about a file that cannot be misnamed. */
  private static boolean isArchive(Path file) {
    try (InputStream in = Files.newInputStream(file)) {
      byte[] magic = in.readNBytes(4);
      return magic.length == 4
          && magic[0] == 'P'
          && magic[1] == 'K'
          && ((magic[2] == 3 && magic[3] == 4) || (magic[2] == 5 && magic[3] == 6));
    } catch (IOException e) {
      return false;
    }
  }

  private static boolean isEncrypted(Path archive) {
    try (net.lingala.zip4j.ZipFile zip = new net.lingala.zip4j.ZipFile(archive.toFile())) {
      return zip.isEncrypted();
    } catch (IOException e) {
      return false; // the reader below says what is wrong with it
    }
  }

  private void refuse(String archive, String reason) {
    archivesRefused++;
    problem(archive + ": " + reason);
  }

  /**
   * Entry names are only used to skip a bundled viewer and to tell the user which entry failed: no
   * path is ever built from one, so an entry named {@code ../../x} cannot write anywhere.
   */
  private void importArchive(Path archive, boolean chosen) {
    String name = archive.getFileName().toString();
    if (isEncrypted(archive)) {
      refuse(name, Messages.getString("library.archive.encrypted"));
      return;
    }
    int before = dicomSeen();
    int nestedBefore = nestedArchives;
    // Latin-1 decodes any name: archives from older systems are often not UTF-8.
    try (ZipFile zip =
        new ZipFile(archive.toFile(), ZipFile.OPEN_READ, StandardCharsets.ISO_8859_1)) {
      if (ArchiveGuard.inspect(zip, Files.size(archive), limits) != ArchiveGuard.Verdict.SAFE) {
        refuse(name, Messages.getString("library.archive.unsafe"));
        return;
      }
      // One at a time: the reader finds an entry by its name unless it is the one just listed, so
      // two entries of the same name read from a finished list would both give the same object.
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        if (stopped()) {
          return;
        }
        if (!entry.isDirectory() && worthUnpacking(zip, entry)) {
          importEntry(zip, entry, name);
        }
      }
    } catch (IOException | RuntimeException e) {
      LOGGER.warn("Cannot read an import archive: {}", e.getClass().getSimpleName());
      refuse(name, Messages.getString("library.archive.damaged"));
      return;
    }
    if (chosen && dicomSeen() == before && nestedArchives == nestedBefore && !stopped()) {
      refuse(name, Messages.getString("library.archive.empty"));
    }
  }

  /**
   * A name can say a bundled viewer's file is not worth unpacking, but it cannot say a file is not
   * an image: anything named so is still looked at, and kept out only if its first bytes are not
   * those of DICOM.
   */
  private static boolean worthUnpacking(ZipFile zip, ZipEntry entry) {
    if (!DicomZipMediaIO.isSkipped(entry.getName())) {
      return true;
    }
    try (InputStream in = zip.getInputStream(entry)) {
      return looksLikeDicom(in.readNBytes(DICM_OFFSET + DICM.length));
    } catch (IOException | RuntimeException e) {
      return true; // unpacking it will report what is wrong
    }
  }

  /**
   * Whether a file with no usable identifiers is a damaged image rather than something else. Uses
   * the same signs as the viewer's loader, whose read errors for an archive are left to this
   * report. A DICOMDIR is valid DICOM with no image identifiers of its own.
   */
  private static boolean claimsToBeDicom(Path file, String entryName) throws IOException {
    Attributes header = StudyStore.readHeader(file);
    if (header != null && header.contains(Tag.DirectoryRecordSequence)) {
      return false;
    }
    String name = entryName.toLowerCase(Locale.ROOT);
    if (Arrays.stream(DicomCodec.FILE_EXTENSIONS).anyMatch(e -> name.endsWith("." + e))) {
      return true;
    }
    try (InputStream in = Files.newInputStream(file)) {
      return looksLikeDicom(in.readNBytes(DICM_OFFSET + DICM.length));
    }
  }

  /** The DICM mark of a DICOM file, or the first group of a data set that has no file header. */
  static boolean looksLikeDicom(byte[] start) {
    if (start.length >= DICM_OFFSET + DICM.length
        && Arrays.equals(Arrays.copyOfRange(start, DICM_OFFSET, DICM_OFFSET + DICM.length), DICM)) {
      return true;
    }
    return start.length >= 2
        && ((start[1] == 0 && (start[0] == 8 || start[0] == 2))
            || (start[0] == 0 && (start[1] == 8 || start[1] == 2)));
  }

  private void importEntry(ZipFile zip, ZipEntry entry, String archive) {
    String label = archive + " > " + entry.getName();
    Path temp = store.newTempFile();
    try {
      if (!store.hasFreeSpace(entry.getSize())) {
        outOfSpace = true;
        return;
      }
      if (!copyEntry(zip, entry, temp)) {
        return; // stopped part way: the partial copy goes with the temporary file
      }
      Attributes header = usableHeader(temp);
      if (header == null) {
        if (isArchive(temp)) {
          nestedArchives++;
        } else if (claimsToBeDicom(temp, entry.getName())) {
          throw new IOException("The image file cannot be read");
        } else {
          notDicom++;
        }
        return;
      }
      if (!StudyStore.isComplete(temp)) {
        throw new IOException("The image file is incomplete");
      }
      commit(temp, header, header.getString(Tag.SOPInstanceUID));
    } catch (IOException | RuntimeException e) {
      copyFailed(e, label);
    } finally {
      deleteQuietly(temp);
    }
  }

  /**
   * Unpacks one entry, stopping at the size it declared, and checks it against its checksum, which
   * the JDK's random-access reader does not do by itself.
   *
   * @return false when the import was stopped meanwhile
   */
  private boolean copyEntry(ZipFile zip, ZipEntry entry, Path temp) throws IOException {
    CRC32 crc = new CRC32();
    long written = 0;
    long nextSpaceCheck = SPACE_CHECK_BYTES;
    byte[] buffer = new byte[BUFFER];
    try (InputStream in = zip.getInputStream(entry);
        OutputStream out = store.openTempFile(temp)) {
      for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
        written += n;
        if (written > entry.getSize()) {
          throw new IOException("The archive entry is larger than it declares");
        }
        out.write(buffer, 0, n);
        crc.update(buffer, 0, n);
        if (written >= nextSpaceCheck) {
          nextSpaceCheck = written + SPACE_CHECK_BYTES;
          if (stopped()) {
            return false;
          }
          if (!store.hasFreeSpace()) {
            outOfSpace = true;
            return false;
          }
        }
      }
    }
    if (written != entry.getSize() || crc.getValue() != entry.getCrc()) {
      throw new IOException("The archive entry is damaged");
    }
    return true;
  }

  private static Attributes usableHeader(Path file) {
    Attributes header = StudyStore.readHeader(file);
    return header != null
            && hasText(header, Tag.SOPInstanceUID)
            && hasText(header, Tag.StudyInstanceUID)
            && hasText(header, Tag.SeriesInstanceUID)
        ? header
        : null;
  }

  private static boolean hasText(Attributes header, int tag) {
    String value = header.getString(tag);
    return value != null && !value.isBlank();
  }

  private void problem(String text) {
    if (firstProblem == null) {
      firstProblem = text;
    }
  }

  private static void deleteQuietly(Path temp) {
    try {
      Files.deleteIfExists(temp);
    } catch (IOException e) {
      LOGGER.debug("Temporary file not removed: {}", e.getClass().getSimpleName());
    }
  }

  private Report report() {
    return new Report(
        stored,
        alreadyHeld,
        conflicts,
        failed,
        notDicom,
        firstProblem,
        outOfSpace,
        archivesRefused,
        nestedArchives,
        wasCancelled,
        List.copyOf(pictures));
  }
}
