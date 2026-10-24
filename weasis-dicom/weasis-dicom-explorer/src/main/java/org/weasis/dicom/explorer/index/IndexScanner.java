/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
// Modified by Addis Ortho Trading PLC, 2026.
package org.weasis.dicom.explorer.index;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.io.DicomInputStream;

/**
 * Walks folders for DICOM files and reads their headers only (never the pixels), loose or inside
 * zip archives. A file unchanged since the previous scan (same size and date) is not read again.
 *
 * <p>For safety and speed: symbolic links and junctions are not followed, hidden and system folders
 * are skipped, a file still being written (changed in the last seconds, e.g. a Telegram download in
 * progress) waits for the next scan, files over 2 GB are skipped and at most 2 MB of a header is
 * read.
 */
public final class IndexScanner {

  /** Largest file looked at. */
  static final long MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024;

  /** Most bytes read for one header. */
  static final int MAX_HEADER_BYTES = 2 * 1024 * 1024;

  /** A file changed more recently than this may still be downloading. */
  static final long SETTLE_MS = 10_000;

  /** Folder names never walked into (system, application and package folders). */
  private static final List<String> SKIPPED_NAMES =
      List.of(
          "library",
          "appdata",
          "program files",
          "program files (x86)",
          "windows",
          "system32",
          "applications",
          "system",
          "node_modules",
          "$recycle.bin",
          "system volume information",
          ".trash",
          ".trashes"); // NON-NLS

  /** What a scan reports while it runs. */
  public interface Progress {
    void update(int filesSeen, int dicomFound, Path folder);
  }

  /** The result: every DICOM instance found, and the archives that could not be read. */
  public record Result(List<IndexedFile> files, List<String> notIndexed) {}

  private final Map<String, List<IndexedFile>> previous = new HashMap<>();
  private final long now;
  private final BooleanSupplier cancelled;
  private final Progress progress;
  private final List<IndexedFile> found = new ArrayList<>();
  private final List<String> notIndexed = new ArrayList<>();
  private int seen;

  /**
   * @param previous the instances of the previous scan, reused for unchanged files
   * @param now the time of the scan, in ms
   */
  public IndexScanner(
      Collection<IndexedFile> previous, long now, BooleanSupplier cancelled, Progress progress) {
    for (IndexedFile f : previous) {
      this.previous.computeIfAbsent(f.container(), _ -> new ArrayList<>()).add(f);
    }
    this.now = now;
    this.cancelled = cancelled;
    this.progress = progress == null ? (_, _, _) -> {} : progress;
  }

  /** Scans the folders (missing ones are skipped) and returns what is in them now. */
  public Result scan(List<Path> roots) throws IOException {
    for (Path root : roots) {
      if (cancelled.getAsBoolean()) {
        break;
      }
      if (Files.isDirectory(root)) {
        walk(root);
      }
    }
    return new Result(List.copyOf(found), List.copyOf(notIndexed));
  }

  private void walk(Path root) throws IOException {
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            if (cancelled.getAsBoolean()) {
              return FileVisitResult.TERMINATE;
            }
            if (!dir.equals(root) && (attrs.isSymbolicLink() || attrs.isOther() || skipped(dir))) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            progress.update(seen, found.size(), dir);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (cancelled.getAsBoolean()) {
              return FileVisitResult.TERMINATE;
            }
            if (attrs.isRegularFile() && !attrs.isSymbolicLink()) {
              seen++;
              visit(file, attrs.size(), attrs.lastModifiedTime().toMillis());
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exc) {
            return FileVisitResult.CONTINUE; // unreadable: skip it
          }
        });
  }

  /** Hidden folders, and system or application folders by name. */
  static boolean skipped(Path dir) {
    Path name = dir.getFileName();
    if (name == null) {
      return false;
    }
    String n = name.toString().toLowerCase(Locale.ROOT);
    return n.startsWith(".") || n.endsWith(".app") || SKIPPED_NAMES.contains(n);
  }

  private void visit(Path file, long size, long modified) {
    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
    if (size <= 0 || size > MAX_FILE_BYTES || now - modified < SETTLE_MS) {
      return;
    }
    String key = file.toString();
    List<IndexedFile> old = previous.get(key);
    if (old != null
        && !old.isEmpty()
        && old.getFirst().size() == size
        && old.getFirst().modified() == modified) {
      found.addAll(old);
      return;
    }
    if (name.endsWith(".zip")) { // NON-NLS
      readZip(file, size, modified);
    } else if (name.endsWith(".rar") || name.endsWith(".7z")) { // NON-NLS
      notIndexed.add(key);
    } else if (!name.equals("dicomdir")) { // NON-NLS
      try (InputStream in = Files.newInputStream(file)) {
        IndexedFile f = read(in, key, size, modified);
        if (f != null) {
          found.add(f);
        }
      } catch (IOException | RuntimeException e) {
        // not DICOM, or unreadable: nothing to index
      }
    }
  }

  private void readZip(Path zip, long size, long modified) {
    try (ZipFile z = new ZipFile(zip.toFile())) {
      var entries = z.entries();
      while (entries.hasMoreElements() && !cancelled.getAsBoolean()) {
        ZipEntry e = entries.nextElement();
        if (e.isDirectory() || e.getName().toLowerCase(Locale.ROOT).endsWith("dicomdir")) {
          continue;
        }
        seen++;
        try (InputStream in = z.getInputStream(e)) {
          IndexedFile f = read(in, zip + IndexedFile.ZIP_SEPARATOR + e.getName(), size, modified);
          if (f != null) {
            found.add(f);
          }
        } catch (IOException | RuntimeException ex) {
          // an entry that is not DICOM
        }
      }
    } catch (IOException | RuntimeException e) {
      notIndexed.add(zip.toString()); // encrypted or damaged archive
    }
  }

  /**
   * The header of a Part 10 DICOM file ({@code DICM} at byte 128), or {@code null} for anything
   * else.
   */
  static IndexedFile read(InputStream raw, String location, long size, long modified)
      throws IOException {
    byte[] head = raw.readNBytes(132);
    if (head.length < 132
        || !"DICM".equals(new String(head, 128, 4, StandardCharsets.US_ASCII))) { // NON-NLS
      return null;
    }
    InputStream rest = new LimitedInputStream(raw, MAX_HEADER_BYTES);
    try (DicomInputStream dis =
        new DicomInputStream(new SequenceInputStream(new ByteArrayInputStream(head), rest))) {
      Attributes a = dis.readDatasetUntilPixelData();
      String study = a.getString(Tag.StudyInstanceUID);
      if (study == null || study.isBlank()) {
        return null;
      }
      return new IndexedFile(
          location,
          size,
          modified,
          study,
          a.getString(Tag.SeriesInstanceUID),
          a.getString(Tag.SOPInstanceUID),
          a.getString(Tag.Modality),
          a.getString(Tag.PatientName),
          a.getString(Tag.PatientID),
          a.getString(Tag.PatientBirthDate),
          a.getString(Tag.PatientSex),
          a.getString(Tag.StudyDate),
          a.getString(Tag.StudyDescription),
          a.getString(Tag.AccessionNumber));
    }
  }

  /** Stops reading after a number of bytes, so a damaged header cannot read a whole file. */
  private static final class LimitedInputStream extends FilterInputStream {
    private long left;

    LimitedInputStream(InputStream in, long limit) {
      super(in);
      this.left = limit;
    }

    @Override
    public int read() throws IOException {
      if (left <= 0) {
        return -1;
      }
      int b = super.read();
      if (b >= 0) {
        left--;
      }
      return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      if (left <= 0) {
        return -1;
      }
      int n = super.read(b, off, (int) Math.min(len, left));
      if (n > 0) {
        left -= n;
      }
      return n;
    }

    @Override
    public void close() {
      // the caller closes the file
    }
  }
}
