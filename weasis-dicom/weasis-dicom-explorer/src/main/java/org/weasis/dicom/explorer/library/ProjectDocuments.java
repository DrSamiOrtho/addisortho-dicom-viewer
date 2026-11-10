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
import java.nio.file.DirectoryNotEmptyException;
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
 * named by the Study Instance UID and never by the patient. Every walk of the store over studies,
 * the catalog, import, the patient index and deletion, passes over hidden folders, so these files
 * are never counted, listed, opened, exported or sent as images. This class does not read the
 * documents; it only keeps them.
 *
 * <p>What it guarantees, for each document:
 *
 * <ul>
 *   <li><b>All or nothing.</b> A document is written under a hidden temporary name and forced to
 *       the disk, and only then given its name. A stop at any point, clean or not, leaves it either
 *       absent or whole; temporary files are never listed, and the next write to the study removes
 *       those left behind.
 *   <li><b>Written once.</b> The name is given by a hard link, which the file system refuses when
 *       the name exists, so a document is never replaced, by this or another process on the same
 *       library. The check and the link are also made under a lock held by this process and by a
 *       file lock in the study's folder, which other processes of this viewer respect. Where the
 *       file system has no hard links the name is given by a move made under the same two locks,
 *       which holds against every caller that uses this class. Nothing rewrites a document, and
 *       nothing deletes one except the deletion of its whole study from the library ({@link
 *       #deleteStudy}, Sami's decision of 10 Nov 2026).
 *   <li><b>Durable on return.</b> The file and then its folder are forced to the disk before {@link
 *       #put} returns (Windows journals the name itself), and the document is read back and
 *       compared.
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
  private static final Pattern STUDY_UID = Pattern.compile("[0-9][0-9.]{0,63}");
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
    Path real;
    try {
      if (!Files.isDirectory(study)) {
        Files.createDirectories(study);
        folderFlush.flush(study.getParent()); // the study's folder survives a power cut too
      }
      real = study.toRealPath(); // one lock however the library's path was spelled
    } catch (IOException e) {
      throw unwritable(e);
    }
    ReentrantLock inProcess = LOCKS.computeIfAbsent(real, _ -> new ReentrantLock());
    inProcess.lock();
    try (FileChannel lockFile =
            FileChannel.open(
                study.resolve(LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock _ = lockFile.lock()) {
      removeLeftovers(study);
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        throw new FileAlreadyExistsException(id);
      }
      Path temp = study.resolve("." + id + "-" + UUID.randomUUID() + PART);
      try {
        write(temp, document);
        step.reached("written");
        namer.name(target, temp);
        step.reached("named");
      } finally {
        Files.deleteIfExists(temp);
      }
      folderFlush.flush(study);
    } catch (FileAlreadyExistsException e) {
      throw new FileAlreadyExistsException(id); // without the path
    } catch (IOException e) {
      throw unwritable(e);
    } finally {
      inProcess.unlock();
    }
    byte[] back;
    try {
      back = Files.readAllBytes(target);
    } catch (IOException e) {
      throw new IOException("The document was kept but could not be read back.", e);
    }
    if (!Arrays.equals(back, document)) {
      throw new IOException("The document kept does not read back as it was written.");
    }
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
   * Deletes every document of every kind kept for the study, with their folders. Only for when the
   * study itself is deleted from the library: its projects go with it (Sami, 10 Nov 2026). Each
   * study's documents are deleted under the same two locks a write takes, so no write is cut in
   * half; a write that comes after finds its study gone and fails.
   *
   * @throws IOException when a document could not be deleted; those not yet reached are kept
   */
  static void deleteStudy(Path storeRoot, String studyUid) throws IOException {
    Path all = storeRoot.toAbsolutePath().normalize().resolve(FOLDER);
    if (studyUid == null
        || !STUDY_UID.matcher(studyUid).matches()
        || studyUid.contains("..")
        || !Files.isDirectory(all, LinkOption.NOFOLLOW_LINKS)) {
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
      new ProjectDocuments(storeRoot, kind).deleteAll(studyUid);
    }
  }

  private void deleteAll(String studyUid) throws IOException {
    Path study = studyFolder(studyUid);
    if (!Files.isDirectory(study, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    ReentrantLock inProcess = LOCKS.computeIfAbsent(study.toRealPath(), _ -> new ReentrantLock());
    inProcess.lock();
    try (FileChannel lockFile =
            FileChannel.open(
                study.resolve(LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock _ = lockFile.lock();
        DirectoryStream<Path> files = Files.newDirectoryStream(study)) {
      for (Path f : files) {
        if (!f.getFileName().toString().equals(LOCK)) {
          Files.deleteIfExists(f);
        }
      }
    } finally {
      inProcess.unlock();
    }
    Files.deleteIfExists(study.resolve(LOCK));
    try {
      Files.deleteIfExists(study);
    } catch (DirectoryNotEmptyException e) {
      // a write made after the deletion: left for the user, never deleted unseen
    }
  }

  private Path studyFolder(String studyUid) throws IOException {
    if (studyUid == null || !STUDY_UID.matcher(studyUid).matches() || studyUid.contains("..")) {
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
   * A hard link, which fails when the name exists; a move under the locks where the file system has
   * none (some USB and network drives).
   */
  private static void link(Path target, Path temp) throws IOException {
    try {
      Files.createLink(target, temp);
    } catch (FileAlreadyExistsException e) {
      throw e;
    } catch (UnsupportedOperationException | FileSystemException e) {
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        throw new FileAlreadyExistsException(target.getFileName().toString());
      }
      Files.move(temp, target); // no REPLACE_EXISTING: refused if the name has appeared
    }
  }

  /** Temporary files of writes that stopped; under the locks, no write of ours is under way. */
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
