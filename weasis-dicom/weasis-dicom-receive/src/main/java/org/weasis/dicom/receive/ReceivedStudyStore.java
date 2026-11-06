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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.dcm4che3.data.Attributes;

/**
 * The storage folder: a folder per study, and an incoming folder for files still being received.
 * Exceptions of this class can carry paths that hold patient names, so callers must not log them.
 */
public class ReceivedStudyStore {

  /** Free space below which an image is refused, so the computer never fills up completely. */
  static final long MIN_FREE_BYTES = 200L * 1024L * 1024L;

  private static final String INCOMING_FOLDER = ".incoming";
  private static final String TEMP_EXTENSION = ".part";

  /**
   * The classic Windows path limit, above which Explorer cannot open a file. No limit elsewhere.
   */
  private static final int WINDOWS_MAX_PATH_CHARS = 250;

  private static final int MAX_FILE_NAME_CHARS = 200;

  /** Reports the free bytes of a folder. */
  @FunctionalInterface
  interface FreeSpace {
    long of(Path folder) throws IOException;
  }

  /** Opens a temporary file for writing. */
  @FunctionalInterface
  interface TempOpener {
    OutputStream open(Path file) throws IOException;
  }

  private final Path root;
  private final Path incoming;
  private final boolean includeName;
  private final Clock clock;
  private final FreeSpace freeSpace;
  private final TempOpener tempOpener;
  private final int maxPathChars;

  // Guarded by this. Study key to folder, and folder to the images it holds by SOP Instance UID.
  private final Map<String, Path> folders = new HashMap<>();
  private final Map<Path, Map<String, Path>> imagesOfFolder = new HashMap<>();

  public ReceivedStudyStore(Path root, boolean includeName) {
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

  ReceivedStudyStore(
      Path root,
      boolean includeName,
      Clock clock,
      FreeSpace freeSpace,
      TempOpener tempOpener,
      int maxPathChars) {
    this.root = root.toAbsolutePath().normalize();
    this.incoming = this.root.resolve(INCOMING_FOLDER);
    this.includeName = includeName;
    this.clock = clock;
    this.freeSpace = freeSpace;
    this.tempOpener = tempOpener;
    this.maxPathChars = maxPathChars;
  }

  public Path root() {
    return root;
  }

  /** Creates the folders, proves they are writable, and removes files left by a crash. */
  public synchronized void prepare() throws IOException {
    Files.createDirectories(incoming);
    Files.delete(Files.createTempFile(incoming, "write-test", TEMP_EXTENSION));
    try (DirectoryStream<Path> leftovers =
        Files.newDirectoryStream(incoming, "*" + TEMP_EXTENSION)) {
      for (Path file : leftovers) {
        Files.deleteIfExists(file);
      }
    }
    folders.clear();
    imagesOfFolder.clear();
    for (Path folder : studyFolders()) {
      ReceiveNaming.studyKeyOfFolder(folder.getFileName().toString())
          .ifPresent(key -> folders.put(key, folder));
    }
  }

  public boolean hasFreeSpace() throws IOException {
    return freeSpace.of(incoming) >= MIN_FREE_BYTES;
  }

  public Path newTempFile() {
    return incoming.resolve(UUID.randomUUID() + TEMP_EXTENSION);
  }

  public OutputStream openTempFile(Path file) throws IOException {
    return tempOpener.open(file);
  }

  /**
   * Moves a verified temporary file into the folder of its study.
   *
   * @return the stored image, or empty if this image was already stored: the first copy is kept
   */
  public synchronized Optional<ReceivedInstance> commit(
      Path temp, Attributes dataset, String studyUid, String seriesUid, String sopUid)
      throws IOException {
    LocalDate arrival = LocalDate.now(clock);
    Path folder = folderOf(dataset, studyUid, arrival);
    Map<String, Path> images = imagesOf(folder);
    Path existing = images.get(sopUid);
    if (existing != null && Files.exists(existing)) {
      Files.deleteIfExists(temp);
      return Optional.empty();
    }
    long room = (long) maxPathChars - folder.toString().length() - 1;
    int minChars = sopUid.length() + ReceiveNaming.FILE_EXTENSION.length();
    int maxChars = (int) Math.clamp(room, minChars, MAX_FILE_NAME_CHARS);
    String name = ReceiveNaming.fileName(dataset, arrival, includeName, sopUid, maxChars);
    Path target = contained(folder.resolve(name), folder);
    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
    images.put(sopUid, target);
    return Optional.of(new ReceivedInstance(studyUid, seriesUid, sopUid, target));
  }

  private Path folderOf(Attributes dataset, String studyUid, LocalDate arrival) throws IOException {
    String key = ReceiveNaming.studyKey(studyUid);
    Path folder = folders.get(key);
    if (folder == null || !Files.isDirectory(folder)) {
      String name = ReceiveNaming.folderName(dataset, arrival, includeName, studyUid);
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
          Optional<String> uid = ReceiveNaming.sopUidOfFile(file.getFileName().toString());
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
  public List<ReceivedStudy> listStudies() throws IOException {
    List<ReceivedStudy> studies = new ArrayList<>();
    for (Path folder : studyFolders()) {
      Optional<String> key = ReceiveNaming.studyKeyOfFolder(folder.getFileName().toString());
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
        studies.add(new ReceivedStudy(folder, key.get(), (int) sizeAndCount[1], sizeAndCount[0]));
      }
    }
    studies.sort(Comparator.comparing((ReceivedStudy s) -> lastModified(s.folder())).reversed());
    return studies;
  }

  /** Deletes a study folder. Only a folder directly in the storage folder and made here. */
  public synchronized void deleteStudy(Path folder) throws IOException {
    Path normal = folder.toAbsolutePath().normalize();
    Optional<String> key =
        normal.getFileName() == null
            ? Optional.empty()
            : ReceiveNaming.studyKeyOfFolder(normal.getFileName().toString());
    if (!root.equals(normal.getParent()) || key.isEmpty() || Files.isSymbolicLink(normal)) {
      throw new IOException("Not a study folder of the storage folder");
    }
    folders.remove(key.get());
    imagesOfFolder.remove(normal);
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
