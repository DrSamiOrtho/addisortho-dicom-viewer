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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.ui.util.SavedFile;

/**
 * Pictures that are not DICOM, kept unchanged once a person has said whose they are. They have no
 * DICOM identifiers and none are made up: each is its own local object, known by the digest of its
 * bytes, with a record beside it saying that the patient and date came from a person and not from a
 * scanner. They live apart from the study folders, so nothing can mistake one for a DICOM image.
 */
public final class PictureLibrary {
  private static final Logger LOGGER = LoggerFactory.getLogger(PictureLibrary.class);

  /** Marks an identifier as local. DICOM identifiers hold only digits and dots. */
  public static final String ID_PREFIX = "local-picture-"; // NON-NLS

  private static final String FOLDER = ".pictures"; // NON-NLS
  private static final String STAGING = ".staging-"; // NON-NLS
  private static final String RECORD = "record.properties"; // NON-NLS
  private static final String PICTURE = "picture"; // NON-NLS
  private static final String OBJECT_TYPE = "LOCAL_NON_DICOM_PICTURE"; // NON-NLS
  private static final String CONFIRMED = "USER_CONFIRMED"; // NON-NLS
  private static final String UNKNOWN = "UNKNOWN"; // NON-NLS
  private static final int ID_HEX_CHARS = 40;

  public enum Format {
    JPEG("jpg"), // NON-NLS
    PNG("png"), // NON-NLS
    TIFF("tif"), // NON-NLS
    BMP("bmp"), // NON-NLS
    GIF("gif"); // NON-NLS

    final String extension;

    Format(String extension) {
      this.extension = extension;
    }
  }

  public enum Outcome {
    KEPT,
    /** The same picture, for the same patient and date, was already kept. */
    ALREADY_HELD,
    /** The same picture is kept with another patient or date: left as it was, and reported. */
    CONFLICT
  }

  /**
   * @param confirmed when a person confirmed the identity, ms
   */
  public record LocalPicture(
      String localId,
      Path file,
      Format format,
      String originalName,
      PictureIdentity identity,
      long confirmed) {}

  /**
   * What became of a batch.
   *
   * @param declined the user chose not to keep them, so nothing was stored
   * @param firstProblem for the user only: it can name a file, so it is never logged
   */
  public record Result(
      int kept,
      int alreadyHeld,
      int conflicts,
      int failed,
      int declined,
      String firstProblem,
      boolean outOfSpace) {
    static final Result NONE = new Result(0, 0, 0, 0, 0, null, false);

    static Result declined(int count) {
      return new Result(0, 0, 0, 0, count, null, false);
    }
  }

  private final StudyStore store;
  private final Path folder;
  private final Clock clock;
  // Guarded by this. Null until first read.
  private List<LocalPicture> pictures;
  private boolean leftoversRemoved;

  public PictureLibrary(StudyStore store, Clock clock) {
    this.store = store;
    this.folder = store.root().resolve(FOLDER);
    this.clock = clock;
  }

  /** The kind of picture a file holds, judged by its content, or null when it is not one. */
  public static Format formatOf(Path file) {
    try (InputStream in = Files.newInputStream(file)) {
      byte[] b = in.readNBytes(8);
      if (startsWith(b, 0xFF, 0xD8, 0xFF)) {
        return Format.JPEG;
      }
      if (startsWith(b, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
        return Format.PNG;
      }
      if (startsWith(b, 'I', 'I', 42, 0) || startsWith(b, 'M', 'M', 0, 42)) {
        return Format.TIFF;
      }
      if (startsWith(b, 'G', 'I', 'F', '8')) {
        return Format.GIF;
      }
      // Two letters alone say little, so a bitmap must also state its own true length.
      if (startsWith(b, 'B', 'M')
          && b.length >= 6
          && ByteBuffer.wrap(b, 2, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() == Files.size(file)) {
        return Format.BMP;
      }
      return null;
    } catch (IOException e) {
      return null;
    }
  }

  private static boolean startsWith(byte[] bytes, int... expected) {
    if (bytes.length < expected.length) {
      return false;
    }
    for (int i = 0; i < expected.length; i++) {
      if ((bytes[i] & 0xFF) != expected[i]) {
        return false;
      }
    }
    return true;
  }

  /** Keeps copies of the pictures for the person confirmed. A full disk stops the batch. */
  public Result keepAll(List<Path> sources, PictureIdentity identity) {
    int kept = 0;
    int held = 0;
    int conflicts = 0;
    int failed = 0;
    String problem = null;
    for (Path source : sources) {
      try {
        if (!store.hasFreeSpace(Files.size(source))) {
          return new Result(kept, held, conflicts, failed, 0, problem, true);
        }
        switch (keep(source, identity)) {
          case KEPT -> kept++;
          case ALREADY_HELD -> held++;
          case CONFLICT -> conflicts++;
        }
      } catch (IOException | RuntimeException e) {
        LOGGER.warn("Cannot keep a picture: {}", e.getClass().getSimpleName());
        failed++;
        if (problem == null) {
          problem = source.getFileName() + ": " + SavedFile.reason(e);
        }
      }
    }
    return new Result(kept, held, conflicts, failed, 0, problem, false);
  }

  /**
   * Copies one picture in. The folder appears under its final name in a single rename, with picture
   * and record already complete, so an interruption leaves all of it or none of it.
   */
  public synchronized Outcome keep(Path source, PictureIdentity identity) throws IOException {
    Format format = formatOf(source);
    if (format == null) {
      throw new IOException("Not a picture this library can keep");
    }
    removeLeftovers();
    Path temp = store.newTempFile();
    Path staging = folder.resolve(STAGING + UUID.randomUUID());
    try {
      String digest = copyVerified(source, temp);
      String localId = ID_PREFIX + digest.substring(0, ID_HEX_CHARS);
      Path target = folder.resolve(localId);
      if (Files.isDirectory(target)) {
        LocalPicture existing = read(target);
        return existing != null && existing.identity().equals(identity)
            ? Outcome.ALREADY_HELD
            : Outcome.CONFLICT;
      }
      Files.createDirectories(staging);
      Path picture = staging.resolve(PICTURE + "." + format.extension);
      Files.move(temp, picture, StandardCopyOption.ATOMIC_MOVE);
      Path record = staging.resolve(RECORD);
      writeRecord(record, localId, digest, format, source, identity);
      StudyStore.flushFile(picture);
      StudyStore.flushFile(record);
      StudyStore.flushFolder(staging);
      Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
      StudyStore.flushFolder(folder);
      // The system's picture viewer can save in place, and the kept copy must stay as it came.
      if (!target.resolve(picture.getFileName()).toFile().setReadOnly()) {
        LOGGER.debug("Could not mark a kept picture read-only");
      }
      pictures = null;
      return Outcome.KEPT;
    } finally {
      Files.deleteIfExists(temp);
      deleteFolderQuietly(staging);
    }
  }

  /**
   * @return the digest of the source, once the copy is proved to have the same bytes
   */
  private String copyVerified(Path source, Path temp) throws IOException {
    MessageDigest digest = sha256();
    try (InputStream in = new DigestInputStream(Files.newInputStream(source), digest);
        OutputStream out = store.openTempFile(temp)) {
      in.transferTo(out);
    }
    String sourceDigest = HexFormat.of().formatHex(digest.digest());
    if (!sourceDigest.equals(digestOf(temp))) {
      throw new IOException("The copy does not match the original");
    }
    return sourceDigest;
  }

  private static String digestOf(Path file) throws IOException {
    MessageDigest digest = sha256();
    try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
      in.transferTo(OutputStream.nullOutputStream());
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256"); // NON-NLS
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private void writeRecord(
      Path record,
      String localId,
      String digest,
      Format format,
      Path source,
      PictureIdentity identity)
      throws IOException {
    Properties p = new Properties();
    p.setProperty("objectType", OBJECT_TYPE); // NON-NLS
    p.setProperty("schema", "1"); // NON-NLS
    p.setProperty("localId", localId); // NON-NLS
    p.setProperty("sha256", digest); // NON-NLS
    p.setProperty("format", format.name()); // NON-NLS
    p.setProperty("originalName", source.getFileName().toString()); // NON-NLS
    p.setProperty("patientName", identity.patientName()); // NON-NLS
    p.setProperty("patientNameSource", sourceOf(identity.patientName())); // NON-NLS
    p.setProperty("patientId", identity.patientId()); // NON-NLS
    p.setProperty("patientIdSource", sourceOf(identity.patientId())); // NON-NLS
    String date = identity.dateTaken() == null ? "" : identity.dateTaken().toString();
    p.setProperty("dateTaken", date); // NON-NLS
    p.setProperty("dateTakenSource", sourceOf(date)); // NON-NLS
    p.setProperty("confirmedAt", Instant.now(clock).toString()); // NON-NLS
    try (Writer out = Files.newBufferedWriter(record, StandardCharsets.UTF_8)) {
      p.store(out, null);
    }
  }

  private static String sourceOf(String value) {
    return value.isEmpty() ? UNKNOWN : CONFIRMED;
  }

  /** The kept pictures, newest first. One whose record cannot be read is left out, not deleted. */
  public synchronized List<LocalPicture> list() {
    if (pictures == null) {
      List<LocalPicture> found = new ArrayList<>();
      if (Files.isDirectory(folder)) {
        try (DirectoryStream<Path> folders = Files.newDirectoryStream(folder, ID_PREFIX + "*")) {
          for (Path entry : folders) {
            LocalPicture picture = read(entry);
            if (picture != null) {
              found.add(picture);
            }
          }
        } catch (IOException e) {
          LOGGER.warn("Cannot list the kept pictures: {}", e.getClass().getSimpleName());
        }
      }
      found.sort(Comparator.comparingLong(LocalPicture::confirmed).reversed());
      pictures = List.copyOf(found);
    }
    return pictures;
  }

  private static LocalPicture read(Path entry) {
    Properties p = new Properties();
    try (Reader in = Files.newBufferedReader(entry.resolve(RECORD), StandardCharsets.UTF_8)) {
      p.load(in);
      String localId = p.getProperty("localId", ""); // NON-NLS
      Format format = Format.valueOf(p.getProperty("format", "")); // NON-NLS
      Path file = entry.resolve(PICTURE + "." + format.extension);
      if (!OBJECT_TYPE.equals(p.getProperty("objectType")) // NON-NLS
          || !localId.equals(entry.getFileName().toString())
          || !Files.isRegularFile(file)) {
        return null;
      }
      String date = p.getProperty("dateTaken", ""); // NON-NLS
      return new LocalPicture(
          localId,
          file,
          format,
          p.getProperty("originalName", ""), // NON-NLS
          new PictureIdentity(
              p.getProperty("patientName", ""), // NON-NLS
              p.getProperty("patientId", ""), // NON-NLS
              date.isEmpty() ? null : LocalDate.parse(date)),
          Instant.parse(p.getProperty("confirmedAt")).toEpochMilli()); // NON-NLS
    } catch (IOException | RuntimeException e) {
      LOGGER.warn("Cannot read a kept picture's record: {}", e.getClass().getSimpleName());
      return null;
    }
  }

  /** What an interruption left behind, once, before any staging of this session exists. */
  private void removeLeftovers() throws IOException {
    if (leftoversRemoved || !Files.isDirectory(folder)) {
      leftoversRemoved = true;
      return;
    }
    leftoversRemoved = true;
    try (DirectoryStream<Path> stale = Files.newDirectoryStream(folder, STAGING + "*")) {
      for (Path entry : stale) {
        deleteFolderQuietly(entry);
      }
    }
  }

  private static void deleteFolderQuietly(Path entry) {
    if (!Files.isDirectory(entry)) {
      return;
    }
    try (Stream<Path> files = Files.list(entry)) {
      for (Path file : (Iterable<Path>) files::iterator) {
        Files.deleteIfExists(file);
      }
      Files.deleteIfExists(entry);
    } catch (IOException e) {
      LOGGER.debug("Staging folder not removed: {}", e.getClass().getSimpleName());
    }
  }

  /** The file name of a record, for tests that inspect what was written. */
  static List<String> recordLines(LocalPicture picture) throws IOException {
    return Arrays.asList(
        Files.readString(picture.file().resolveSibling(RECORD), StandardCharsets.UTF_8)
            .split("\\R"));
  }
}
