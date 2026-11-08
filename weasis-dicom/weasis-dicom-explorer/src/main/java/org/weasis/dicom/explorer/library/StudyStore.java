/*
 * Copyright (c) 2009-2020 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer.library;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.io.DicomInputStream;

/**
 * The managed store: a folder per study, an incoming folder for files still arriving, and a
 * quarantine for images that clash with what is stored. Exceptions of this class can carry paths
 * that hold patient names, so callers must not log them. Get the shared one from {@link
 * StudyLibrary}.
 */
public class StudyStore {

  /** Free space below which an image is refused, so the computer never fills up completely. */
  public static final long MIN_FREE_BYTES = 200L * 1024L * 1024L;

  /** What became of an image given to the store. */
  public enum Outcome {
    STORED,
    /** The same image was already stored, so the first copy is kept. */
    ALREADY_HELD,
    /** Same identifier as a stored image, other content or patient: quarantined, not added. */
    CONFLICT
  }

  /**
   * @param instance the stored image, or null unless the outcome is {@link Outcome#STORED}
   */
  public record Commit(Outcome outcome, StoredInstance instance) {}

  private static final String INCOMING_FOLDER = ".incoming";
  private static final String QUARANTINE_FOLDER = ".quarantine";
  private static final String TEMP_EXTENSION = ".part";

  /**
   * The classic Windows path limit, above which Explorer cannot open a file. No limit elsewhere.
   */
  private static final int WINDOWS_MAX_PATH_CHARS = 250;

  private static final int MAX_FILE_NAME_CHARS = 200;
  private static final int DIGEST_BUFFER = 64 * 1024;

  /** Reports the free bytes of a folder. */
  @FunctionalInterface
  public interface FreeSpace {
    long of(Path folder) throws IOException;
  }

  /** Opens a temporary file for writing. */
  @FunctionalInterface
  public interface TempOpener {
    OutputStream open(Path file) throws IOException;
  }

  private final Path root;
  private final Path incoming;
  private final Path quarantine;
  private volatile boolean includeName;
  private volatile StudyCatalog catalog;
  private boolean leftoversRemoved;
  private final Clock clock;
  private final FreeSpace freeSpace;
  private final TempOpener tempOpener;
  private final int maxPathChars;

  // Guarded by this. Study key to folder, and folder to the images it holds by SOP Instance UID.
  private final Map<String, Path> folders = new HashMap<>();
  private final Map<Path, Map<String, Path>> imagesOfFolder = new HashMap<>();
  private final Map<Path, String> identityOfFolder = new HashMap<>();

  public StudyStore(Path root, boolean includeName) {
    this(
        root,
        includeName,
        Clock.systemDefaultZone(),
        folder -> Files.getFileStore(folder).getUsableSpace(),
        file -> Files.newOutputStream(file, StandardOpenOption.CREATE_NEW),
        System.getProperty("os.name", "").startsWith("Windows")
            ? WINDOWS_MAX_PATH_CHARS
            : Integer.MAX_VALUE);
  }

  public StudyStore(
      Path root,
      boolean includeName,
      Clock clock,
      FreeSpace freeSpace,
      TempOpener tempOpener,
      int maxPathChars) {
    this.root = root.toAbsolutePath().normalize();
    this.incoming = this.root.resolve(INCOMING_FOLDER);
    this.quarantine = this.root.resolve(QUARANTINE_FOLDER);
    this.includeName = includeName;
    this.clock = clock;
    this.freeSpace = freeSpace;
    this.tempOpener = tempOpener;
    this.maxPathChars = maxPathChars;
  }

  public Path root() {
    return root;
  }

  void setIncludeName(boolean includeName) {
    this.includeName = includeName;
  }

  void attachCatalog(StudyCatalog catalog) {
    this.catalog = catalog;
  }

  /** Creates the folders, proves they are writable, and removes files left by a crash. */
  public synchronized void prepare() throws IOException {
    Files.createDirectories(incoming);
    Files.delete(Files.createTempFile(incoming, "write-test", TEMP_EXTENSION));
    // Only once: import and receive share this store, and may be writing their files right now.
    if (!leftoversRemoved) {
      leftoversRemoved = true;
      try (DirectoryStream<Path> leftovers =
          Files.newDirectoryStream(incoming, "*" + TEMP_EXTENSION)) {
        for (Path file : leftovers) {
          Files.deleteIfExists(file);
        }
      }
    }
    folders.clear();
    imagesOfFolder.clear();
    identityOfFolder.clear();
    for (Path folder : studyFolders()) {
      StoreNaming.studyKeyOfFolder(folder.getFileName().toString())
          .ifPresent(key -> folders.put(key, folder));
    }
  }

  /** Also brings the incoming folder back if the storage folder was removed and has returned. */
  public boolean hasFreeSpace() throws IOException {
    Files.createDirectories(incoming);
    return freeSpace.of(incoming) >= MIN_FREE_BYTES;
  }

  public Path newTempFile() {
    return incoming.resolve(UUID.randomUUID() + TEMP_EXTENSION);
  }

  public OutputStream openTempFile(Path file) throws IOException {
    return tempOpener.open(file);
  }

  /**
   * Moves a verified temporary file into the folder of its study, unless it clashes with what is
   * there. A resent image with the same content is dropped; one with the same identifier but other
   * content, or for another patient than the study's, is kept aside and reported, never discarded.
   */
  public synchronized Commit commit(
      Path temp, Attributes dataset, String studyUid, String seriesUid, String sopUid)
      throws IOException {
    Path folder = folderOf(dataset, studyUid);
    Map<String, Path> images = imagesOf(folder);
    Path existing = images.get(sopUid);
    boolean held = existing != null && Files.exists(existing);
    if (!sameIdentity(folder, images, dataset)) {
      quarantine(temp);
      return new Commit(Outcome.CONFLICT, null);
    }
    if (held) {
      if (contentDigest(existing).equals(contentDigest(temp))) {
        Files.deleteIfExists(temp);
        return new Commit(Outcome.ALREADY_HELD, null);
      }
      quarantine(temp);
      return new Commit(Outcome.CONFLICT, null);
    }
    long room = (long) maxPathChars - folder.toString().length() - 1;
    int minChars = sopUid.length() + StoreNaming.FILE_EXTENSION.length();
    int maxChars = (int) Math.clamp(room, minChars, MAX_FILE_NAME_CHARS);
    String name = StoreNaming.fileName(dataset, includeName, sopUid, maxChars);
    Path target = contained(folder.resolve(name), folder);
    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
    images.put(sopUid, target);
    StudyCatalog studies = catalog;
    if (studies != null) {
      studies.recordStored(folder, dataset, clock.millis());
    }
    return new Commit(Outcome.STORED, new StoredInstance(studyUid, seriesUid, sopUid, target));
  }

  /** Writes a new object into the file it is given. */
  @FunctionalInterface
  public interface DerivedWriter {
    void write(Path file) throws IOException;
  }

  /**
   * Saves an object made by this viewer, such as a stitch, into its study. When this returns, the
   * file is on the disk under its real name, has been read back, and is in the catalog. A failure
   * throws and leaves nothing behind.
   *
   * @return the stored object
   */
  public StoredInstance saveDerived(DerivedWriter writer) throws IOException {
    prepare();
    if (!hasFreeSpace()) {
      throw new IOException("The disk is nearly full");
    }
    Path temp = newTempFile();
    try {
      writer.write(temp);
      try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
        channel.force(true); // a power cut after "saved" must not lose it
      }
      long size = Files.size(temp);
      Attributes header = readHeader(temp);
      String studyUid = header == null ? null : header.getString(Tag.StudyInstanceUID);
      String seriesUid = header == null ? null : header.getString(Tag.SeriesInstanceUID);
      String sopUid = header == null ? null : header.getString(Tag.SOPInstanceUID);
      if (studyUid == null || seriesUid == null || sopUid == null) {
        throw new IOException("The file written is not a complete DICOM object");
      }
      Commit commit = commit(temp, header, studyUid, seriesUid, sopUid);
      if (commit.outcome() != Outcome.STORED) {
        throw new IOException("It clashes with what the study holds and was not added");
      }
      Path stored = commit.instance().file();
      Attributes back = readHeader(stored);
      if (Files.size(stored) != size
          || back == null
          || !sopUid.equals(back.getString(Tag.SOPInstanceUID))) {
        throw new IOException("The saved file could not be read back");
      }
      return commit.instance();
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  /** Whether the image belongs to the patient the study's folder already holds. */
  private boolean sameIdentity(Path folder, Map<String, Path> images, Attributes dataset) {
    String known = identityOfFolder.get(folder);
    if (known == null) {
      for (Path image : images.values()) {
        Attributes header = readHeader(image);
        if (header != null) {
          known = identity(header);
          break;
        }
      }
      if (known == null) {
        known = identity(dataset);
      }
      identityOfFolder.put(folder, known);
    }
    return known.equals(identity(dataset));
  }

  private static String identity(Attributes d) {
    return String.join(
        "|",
        Objects.toString(d.getString(Tag.PatientID), "").trim(),
        Objects.toString(d.getString(Tag.IssuerOfPatientID), "").trim(),
        Objects.toString(d.getString(Tag.PatientName), "")
            .split("=", -1)[0]
            .replace('^', ' ')
            .replaceAll("\\s+", " ")
            .trim()
            .toUpperCase(Locale.ROOT));
  }

  /** Keeps a clashing file, named by its content so that a resend does not pile up copies. */
  private void quarantine(Path temp) throws IOException {
    Files.createDirectories(quarantine);
    Files.move(
        temp,
        quarantine.resolve(contentDigest(temp) + StoreNaming.FILE_EXTENSION),
        StandardCopyOption.REPLACE_EXISTING);
  }

  /** The digest of the data set, leaving out the file meta header each sender writes its own of. */
  static String contentDigest(Path file) throws IOException {
    long start;
    try (DicomInputStream in = new DicomInputStream(file.toFile())) {
      in.readFileMetaInformation();
      start = in.getPosition();
    }
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
      in.skipNBytes(start);
      byte[] buffer = new byte[DIGEST_BUFFER];
      for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
        digest.update(buffer, 0, n);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  /** The DICOM header of a file without its pixels, or null when it is not DICOM. */
  public static Attributes readHeader(Path file) {
    try (DicomInputStream in = new DicomInputStream(file.toFile())) {
      return in.readDatasetUntilPixelData();
    } catch (IOException | RuntimeException | OutOfMemoryError e) {
      return null;
    }
  }

  private Path folderOf(Attributes dataset, String studyUid) throws IOException {
    String key = StoreNaming.studyKey(studyUid);
    Path folder = folders.get(key);
    if (folder == null || !Files.isDirectory(folder)) {
      String name = StoreNaming.folderName(dataset, includeName, studyUid);
      folder = contained(root.resolve(name), root);
      Files.createDirectories(folder);
      folders.put(key, folder);
    }
    return folder;
  }

  private Map<String, Path> imagesOf(Path folder) throws IOException {
    Map<String, Path> images = imagesOfFolder.get(folder);
    if (images == null) {
      images = new HashMap<>();
      try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
        for (Path file : files) {
          Optional<String> uid = StoreNaming.sopUidOfFile(file.getFileName().toString());
          if (uid.isPresent()) {
            images.put(uid.get(), file);
          }
        }
      }
      imagesOfFolder.put(folder, images);
    }
    return images;
  }

  /** The last line of defence against path traversal: names are sanitized before this. */
  private static Path contained(Path path, Path parent) throws IOException {
    Path normal = path.normalize();
    if (!parent.equals(normal.getParent())) {
      throw new IOException("Name escapes its folder");
    }
    return normal;
  }

  /** The study folders with their sizes, most recent first. */
  public List<StoredStudy> listStudies() throws IOException {
    List<StoredStudy> studies = new ArrayList<>();
    for (Path folder : studyFolders()) {
      Optional<String> key = StoreNaming.studyKeyOfFolder(folder.getFileName().toString());
      if (key.isPresent()) {
        long[] sizeAndCount = new long[2];
        Files.walkFileTree(
            folder,
            new SimpleFileVisitor<>() {
              @Override
              public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                sizeAndCount[0] += attrs.size();
                sizeAndCount[1]++;
                return FileVisitResult.CONTINUE;
              }
            });
        studies.add(new StoredStudy(folder, key.get(), (int) sizeAndCount[1], sizeAndCount[0]));
      }
    }
    studies.sort(Comparator.comparing((StoredStudy s) -> lastModified(s.folder())).reversed());
    return studies;
  }

  /** Deletes a study folder. Only a folder directly in the storage folder and made here. */
  public synchronized void deleteStudy(Path folder) throws IOException {
    Path normal = folder.toAbsolutePath().normalize();
    Optional<String> key =
        normal.getFileName() == null
            ? Optional.empty()
            : StoreNaming.studyKeyOfFolder(normal.getFileName().toString());
    if (!root.equals(normal.getParent()) || key.isEmpty() || Files.isSymbolicLink(normal)) {
      throw new IOException("Not a study folder of the storage folder");
    }
    folders.remove(key.get());
    imagesOfFolder.remove(normal);
    identityOfFolder.remove(normal);
    StudyCatalog studies = catalog;
    if (studies != null) {
      studies.forget(normal.getFileName().toString());
    }
    Files.walkFileTree(
        normal,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
            if (e != null) {
              throw e;
            }
            Files.delete(dir);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private List<Path> studyFolders() throws IOException {
    List<Path> result = new ArrayList<>();
    if (Files.isDirectory(root)) {
      try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
        for (Path entry : entries) {
          if (Files.isDirectory(entry) && !entry.getFileName().toString().startsWith(".")) {
            result.add(entry);
          }
        }
      }
    }
    return result;
  }

  private static Long lastModified(Path path) {
    try {
      return Files.getLastModifiedTime(path).toMillis();
    } catch (IOException e) {
      return 0L;
    }
  }
}
