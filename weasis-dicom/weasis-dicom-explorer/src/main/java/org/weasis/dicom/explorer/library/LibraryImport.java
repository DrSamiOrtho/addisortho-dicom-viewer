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
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.ui.util.SavedFile;

/**
 * Copies imported DICOM files into the managed store. The originals are only ever read, so a study
 * stays available after the disc is ejected or the folder is moved.
 */
final class LibraryImport {
  private static final Logger LOGGER = LoggerFactory.getLogger(LibraryImport.class);
  private static final int SUBFOLDERS = Integer.MAX_VALUE;

  /**
   * What one import did.
   *
   * @param alreadyHeld the same image was already in the store
   * @param conflicts same identifier as a stored image but a different content or patient: set
   *     aside in the quarantine folder, not added
   * @param notDicom files that are not DICOM, left alone
   * @param firstProblem for the user only: it can name a file, so it is never logged
   * @param outOfSpace the import stopped because the disk is nearly full
   */
  record Report(
      int stored,
      int alreadyHeld,
      int conflicts,
      int failed,
      int notDicom,
      String firstProblem,
      boolean outOfSpace) {}

  private final StudyStore store;
  private final BooleanSupplier cancelled;
  private int stored;
  private int alreadyHeld;
  private int conflicts;
  private int failed;
  private int notDicom;
  private String firstProblem;
  private boolean outOfSpace;

  LibraryImport(StudyStore store, BooleanSupplier cancelled) {
    this.store = store;
    this.cancelled = cancelled;
  }

  /**
   * @param paths files and folders
   * @param recursive also the subfolders of a folder; its own files are always included
   */
  Report run(List<File> paths, boolean recursive) throws IOException {
    store.prepare();
    for (File path : paths) {
      Path start = path.toPath().toAbsolutePath().normalize();
      if (start.startsWith(store.root())) {
        continue; // already in the store: copying it onto itself would only find duplicates
      }
      try (Stream<Path> walk =
          Files.walk(start, Files.isDirectory(start) && !recursive ? 1 : SUBFOLDERS)) {
        for (Path file : (Iterable<Path>) walk::iterator) {
          if (cancelled.getAsBoolean() || outOfSpace) {
            return report();
          }
          if (Files.isRegularFile(file) && !file.startsWith(store.root())) {
            importFile(file);
          }
        }
      } catch (IOException | java.io.UncheckedIOException e) {
        LOGGER.warn("Cannot read an import folder: {}", e.getClass().getSimpleName());
        problem(path.getName() + ": " + SavedFile.reason(e));
      }
    }
    return report();
  }

  private void importFile(Path file) {
    Attributes source = usableHeader(file);
    if (source == null) {
      notDicom++;
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
      StudyStore.Commit commit =
          store.commit(
              temp,
              copied,
              copied.getString(Tag.StudyInstanceUID),
              copied.getString(Tag.SeriesInstanceUID),
              sop);
      switch (commit.outcome()) {
        case STORED -> stored++;
        case ALREADY_HELD -> alreadyHeld++;
        case CONFLICT -> conflicts++;
      }
    } catch (IOException | RuntimeException e) {
      LOGGER.warn("Cannot store an imported image: {}", e.getClass().getSimpleName());
      failed++;
      problem(file.getFileName() + ": " + SavedFile.reason(e));
    } finally {
      deleteQuietly(temp);
    }
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
    return new Report(stored, alreadyHeld, conflicts, failed, notDicom, firstProblem, outOfSpace);
  }
}
