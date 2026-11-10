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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Documents this viewer keeps beside a study that are not images, such as the editable versions of
 * a stitch: kept in the store's hidden {@code .projects} folder, one folder per kind and per study,
 * named by the Study Instance UID and never by the patient. The walks of the store over its studies
 * (study list and counts, catalog, patient index, deletion) pass over hidden folders, and export
 * and send take only the images chosen, so these files are not counted, listed, exported or sent as
 * images. An import of a folder that holds another library's {@code .projects} would read them and
 * reject them as not DICOM. This class does not read the documents; it only keeps them.
 *
 * <p>What it guarantees, for each document:
 *
 * <ul>
 *   <li><b>All or nothing.</b> A document is written under a hidden temporary name, forced to the
 *       disk and read back, and only then given its name, so a document that did not read back is
 *       never kept. A stop at any point, clean or not, leaves it absent or whole; temporary files
 *       are never listed, and the next write of the kind removes those left behind.
 *   <li><b>Written once.</b> The name is given by a hard link, which the file system refuses when
 *       the name exists. Every write and deletion of a kind also holds the kind's two locks: this
 *       process's, and a file lock on the kind's {@code .lock}, which is never deleted, so all
 *       processes of this viewer on the library lock the same file. Where the file system has no
 *       hard links, the name is given by a move that only those locks serialize. Nothing rewrites a
 *       document, and nothing deletes one except the deletion of its whole study from the library
 *       ({@link #deleteStudy}, Sami's decision of 10 Nov 2026).
 *   <li><b>Durable on return, where the system allows.</b> The file is forced before and after it
 *       is named, and every folder from the study's to the store root is flushed on every write. On
 *       macOS and Linux that makes the name durable on a local disk. Windows offers no folder flush
 *       to Java: there the named file is forced and NTFS's journal is relied on, which has not been
 *       tested with a power cut. Network and synced folders, and file systems that refuse a folder
 *       flush, give no durability guarantee (the same as for the library's images).
 * </ul>
 *
 * Messages of its exceptions are meant for the user and hold no path; their causes may, and must
 * not be logged.
 */
public final class ProjectDocuments {

  /** The hidden folder of the store root that holds every kind. */
  static final String FOLDER = ".projects"; // NON-NLS

  private static final String SUFFIX = ".json"; // NON-NLS
  private static final String PART = ".part"; // NON-NLS
  private static final String LOCK = ".lock"; // NON-NLS

  /** Far above any project document: a larger file is not one this viewer wrote. */
  static final int MAX_BYTES = 16 * 1024 * 1024;

  private static final Pattern KIND = Pattern.compile("[a-z]{1,32}");
  // Digits in dot-separated parts, as DICOM UIDs are: no empty part, no trailing dot.
  private static final Pattern STUDY_UID = Pattern.compile("[0-9]+(\\.[0-9]+)*");
  // Lower case only: on a file system that ignores case two spellings would be one file.
  private static final Pattern DOCUMENT_ID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  /** One lock per study folder for the whole process, whichever object writes. */
  private static final Map<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

  /** Gives a written temporary file its name, refusing when the name exists. */
  @FunctionalInterface
  interface Namer {
    void name(Path target, Path temp) throws IOException;
  }

  /** Called between the steps of a write; tests make it throw to stop a write part way. */
  @FunctionalInterface
  interface Step {
    void reached(String step) throws IOException;
  }

  private final Path folder;
  // Replaced only by tests.
  Namer namer = ProjectDocuments::link;
  Step step = _ -> {};
  StudyStore.FolderFlush folderFlush = StudyStore::flushFolder;

  /**
   * @param storeRoot the root of the managed store
   * @param kind which documents, such as {@code stitch}: lower-case letters only
   */
  public ProjectDocuments(Path storeRoot, String kind) {
    if (!KIND.matcher(kind).matches()) {
      throw new IllegalArgumentException("not a kind of document: " + kind);
    }
    this.folder = storeRoot.toAbsolutePath().normalize().resolve(FOLDER).resolve(kind);
  }

  /**
   * Keeps a new document.
   *
   * @throws FileAlreadyExistsException when a document of that identifier is already kept; what is
   *     kept is untouched
   * @throws IOException when it could not be kept; then it is not kept
   */
  public void put(String studyUid, String documentId, byte[] document) throws IOException {
    Path study = studyFolder(studyUid);
    String id = checkedId(documentId);
    if (document.length == 0 || document.length > MAX_BYTES) {
      throw new IOException("The document is empty or too large to keep.");
    }
    Path target = study.resolve(id + SUFFIX);
    try (KindLock _ = lockKind()) {
      Files.createDirectories(study);
      removeLeftovers(study);
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        throw new FileAlreadyExistsException(id);
      }
      Path temp = study.resolve("." + id + "-" + UUID.randomUUID() + PART);
      try {
        write(temp, document);
        step.reached("forced");
        // checked before it has its name: a document that does not read back is never kept
        if (!Arrays.equals(Files.readAllBytes(temp), document)) {
          throw new IOException("the document did not read back as it was written");
        }
        step.reached("written");
        namer.name(target, temp);
        step.reached("named");
      } finally {
        Files.deleteIfExists(temp);
      }
      forceFile(target);
      persistPath(study);
    } catch (FileAlreadyExistsException e) {
      throw new FileAlreadyExistsException(id); // without the path
    } catch (IOException e) {
      throw unwritable(e);
    }
  }

  /** Both locks of this kind: the process's, and a file lock other viewer processes respect. */
  private record KindLock(ReentrantLock inProcess, FileChannel channel, FileLock lock)
      implements AutoCloseable {
    @Override
    public void close() throws IOException {
      try (FileChannel _ = channel) {
        lock.release();
      } finally {
        inProcess.unlock();
      }
    }
  }

  /**
   * Takes the kind's locks. They live on {@code .lock} in the kind's folder, which is never
   * deleted, so every writer and deleter of every process locks the same file (Codex review, 10 Nov
   * 2026: a lock file deleted while another process held or awaited it split the exclusion in two).
   */
  private KindLock lockKind() throws IOException {
    Files.createDirectories(folder);
    ReentrantLock inProcess = LOCKS.computeIfAbsent(folder.toRealPath(), _ -> new ReentrantLock());
    inProcess.lock();
    FileChannel channel = null;
    try {
      channel =
          FileChannel.open(
              folder.resolve(LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      return new KindLock(inProcess, channel, channel.lock());
    } catch (IOException | RuntimeException e) {
      if (channel != null) {
        channel.close();
      }
      inProcess.unlock();
      throw e;
    }
  }

  /** Forces the named file and its metadata, which on Windows is the one flush offered. */
  private static void forceFile(Path file) throws IOException {
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
      channel.force(true);
    }
  }

  /**
   * Flushes every folder from the study's up to the store root, on every write, so a folder made by
   * an earlier write whose flush failed is flushed now (Codex review, 10 Nov 2026).
   */
  private void persistPath(Path study) throws IOException {
    folderFlush.flush(study);
    folderFlush.flush(folder);
    folderFlush.flush(folder.getParent());
    folderFlush.flush(folder.getParent().getParent());
  }

  /**
   * The document exactly as it was put.
   *
   * @throws NoSuchFileException when none of that identifier is kept
   */
  public byte[] get(String studyUid, String documentId) throws IOException {
    Path file = studyFolder(studyUid).resolve(checkedId(documentId) + SUFFIX);
    BasicFileAttributes attrs;
    try {
      attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    } catch (NoSuchFileException e) {
      throw new NoSuchFileException(documentId);
    } catch (IOException e) {
      throw new IOException("The kept document could not be read.", e);
    }
    if (!attrs.isRegularFile()) {
      throw new NoSuchFileException(documentId);
    }
    if (attrs.size() > MAX_BYTES) {
      throw new IOException("The kept document is too large to be one this viewer wrote.");
    }
    try {
      return Files.readAllBytes(file);
    } catch (NoSuchFileException e) {
      throw new NoSuchFileException(documentId);
    } catch (IOException e) {
      throw new IOException("The kept document could not be read.", e);
    }
  }

  /** The identifiers of every document kept for the study, in no order; empty when none. */
  public List<String> list(String studyUid) throws IOException {
    Path study = studyFolder(studyUid);
    List<String> ids = new ArrayList<>();
    if (!Files.isDirectory(study, LinkOption.NOFOLLOW_LINKS)) {
      return ids;
    }
    try (DirectoryStream<Path> files = Files.newDirectoryStream(study, "*" + SUFFIX)) {
      for (Path f : files) {
        String name = f.getFileName().toString();
        String id = name.substring(0, name.length() - SUFFIX.length());
        if (DOCUMENT_ID.matcher(id).matches()
            && Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
          ids.add(id);
        }
      }
    } catch (IOException e) {
      throw new IOException("The kept documents of this study could not be listed.", e);
    }
    return ids;
  }

  /**
   * Deletes every document of every kind kept for the study whose folder key ({@link
   * StoreNaming#studyKey}) is {@code studyKey}, with their folders. Only for when the study itself
   * is deleted from the library: its projects go with it (Sami, 10 Nov 2026). Projects are found by
   * the key of the study's folder, so no image needs to be readable to find them. Each kind's are
   * deleted under the kind's locks, so no write is cut in half.
   *
   * @throws IOException when a document could not be deleted; nothing of the study's images has
   *     been touched then, so the deletion can be tried again
   */
  static void deleteStudy(Path storeRoot, String studyKey) throws IOException {
    Path all = storeRoot.toAbsolutePath().normalize().resolve(FOLDER);
    if (!Files.isDirectory(all, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    List<String> kinds = new ArrayList<>();
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(all)) {
      for (Path entry : entries) {
        String name = entry.getFileName().toString();
        if (KIND.matcher(name).matches() && Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
          kinds.add(name);
        }
      }
    }
    for (String kind : kinds) {
      new ProjectDocuments(storeRoot, kind).deleteAll(studyKey);
    }
  }

  private void deleteAll(String studyKey) throws IOException {
    try (KindLock _ = lockKind()) {
      List<Path> studies = new ArrayList<>();
      try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
        for (Path entry : entries) {
          String name = entry.getFileName().toString();
          if (usableStudyUid(name)
              && Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
              && StoreNaming.studyKey(name).equals(studyKey)) {
            studies.add(entry);
          }
        }
      }
      for (Path study : studies) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(study)) {
          for (Path f : files) {
            Files.delete(f);
          }
        }
        Files.delete(study);
      }
      if (!studies.isEmpty()) {
        folderFlush.flush(folder);
      }
    }
  }

  private static boolean usableStudyUid(String uid) {
    return uid != null && uid.length() <= 64 && STUDY_UID.matcher(uid).matches();
  }

  private Path studyFolder(String studyUid) throws IOException {
    if (!usableStudyUid(studyUid)) {
      throw new IOException("The study has no usable Study Instance UID.");
    }
    return folder.resolve(studyUid);
  }

  private static String checkedId(String documentId) throws IOException {
    if (documentId == null || !DOCUMENT_ID.matcher(documentId).matches()) {
      throw new IOException("Not a usable document identifier.");
    }
    return documentId;
  }

  private static void write(Path temp, byte[] document) throws IOException {
    try (FileChannel out =
        FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      ByteBuffer bytes = ByteBuffer.wrap(document);
      while (bytes.hasRemaining()) {
        out.write(bytes);
      }
      out.force(true);
    }
  }

  /**
   * A hard link, which fails when the name exists. Only where the file system has no hard links
   * (some USB and network drives) is the name given by a move, which is serialized by the kind's
   * locks between viewer processes but is not by itself a refusal to replace; any other failure of
   * the link is a failure of the write.
   */
  private static void link(Path target, Path temp) throws IOException {
    try {
      Files.createLink(target, temp);
    } catch (FileAlreadyExistsException e) {
      throw e;
    } catch (UnsupportedOperationException e) {
      move(target, temp);
    } catch (FileSystemException e) {
      if (!linksUnsupported(e)) {
        throw e;
      }
      move(target, temp);
    }
  }

  private static void move(Path target, Path temp) throws IOException {
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      throw new FileAlreadyExistsException(target.getFileName().toString());
    }
    Files.move(temp, target);
  }

  /** The system's answers when a file system has no hard links (macOS, Linux, Windows). */
  static boolean linksUnsupported(FileSystemException e) {
    String reason = String.valueOf(e.getReason()) + " " + e.getMessage();
    return reason.contains("not supported")
        || reason.contains("Operation not permitted")
        || reason.contains("Function not implemented")
        || reason.contains("Incorrect function")
        || reason.contains("Invalid function");
  }

  /** Temporary files of writes that stopped; under the kind's locks no write is under way. */
  private static void removeLeftovers(Path study) throws IOException {
    try (DirectoryStream<Path> parts = Files.newDirectoryStream(study, ".*" + PART)) {
      for (Path part : parts) {
        Files.deleteIfExists(part);
      }
    }
  }

  private static IOException unwritable(IOException e) {
    return new IOException(
        "It could not be written to the study library on this computer"
            + " (the disk may be full, or the folder read-only or removed).",
        e);
  }
}
