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
import java.util.function.Consumer;
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
  private static final String VIDEO_SYNTAXES = "1.2.840.10008.1.2.4.10"; // MPEG-2, MPEG-4, HEVC
  private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");

  /** Puts a folder's new entries on the disk. */
  @FunctionalInterface
  interface FolderFlush {
    void flush(Path folder) throws IOException;
  }

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
  private final Map<Path, Identity> identityOfFolder = new HashMap<>();
  private LongSet knownSops;
  // Replaced only by tests, to make a flush fail.
  FolderFlush folderFlush = StudyStore::flushFolder;

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
    knownSops = null; // folders may have been restored; read them again when next needed
    for (Path folder : studyFolders()) {
      StoreNaming.studyKeyOfFolder(folder.getFileName().toString())
          .ifPresent(key -> folders.put(key, folder));
    }
  }

  /** Also brings the incoming folder back if the storage folder was removed and has returned. */
  public boolean hasFreeSpace() throws IOException {
    return hasFreeSpace(0);
  }

  /** Whether {@code bytes} more would still leave the margin free. */
  public boolean hasFreeSpace(long bytes) throws IOException {
    Files.createDirectories(incoming);
    return freeSpace.of(incoming) - bytes >= MIN_FREE_BYTES;
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
    Path current = folders.get(StoreNaming.studyKey(studyUid));
    boolean inStudy = current != null && imagesOf(current).containsKey(sopUid);
    if (!inStudy && heldInAnotherStudy(current, sopUid)) {
      // Refused before the folder is made, so a clash leaves no empty study behind.
      quarantine(temp);
      return new Commit(Outcome.CONFLICT, null);
    }
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
        folderFlush.flush(folder); // an earlier attempt may have failed at this very step
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
    flushFile(temp);
    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
    try {
      folderFlush.flush(folder); // on the disk before the sender or the user is told it is stored
    } catch (IOException e) {
      // The file already has its name: forget what is cached, so it is read back for later checks.
      imagesOfFolder.remove(folder);
      knownSops = null;
      throw e;
    }
    Identity accepted = identityOfFolder.get(folder);
    if (accepted != null) {
      identityOfFolder.put(folder, accepted.withNamesOf(identity(dataset)));
    }
    images.put(sopUid, target);
    if (knownSops != null) {
      knownSops.add(sopHash(sopUid));
    }
    StudyCatalog studies = catalog;
    if (studies != null) {
      studies.recordStored(folder, dataset, clock.millis());
    }
    return new Commit(Outcome.STORED, new StoredInstance(studyUid, seriesUid, sopUid, target));
  }

  /**
   * @param catalogSaved false when the file is safe but the search list could not be written: it
   *     still shows up after the next start, which rebuilds the list from the files
   */
  public record SavedDerived(StoredInstance instance, boolean catalogSaved) {}

  private boolean persistCatalog() {
    StudyCatalog studies = catalog;
    try {
      if (studies != null) {
        studies.save();
      }
      return true;
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }

  static void flushFile(Path file) throws IOException {
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
      channel.force(true);
    }
  }

  /**
   * Whether an image reused from another study holds this identifier. The set of identifiers can
   * only say "maybe", so a hit is confirmed against the file names.
   */
  private boolean heldInAnotherStudy(Path current, String sopUid) throws IOException {
    if (knownSops == null) {
      LongSet known = new LongSet();
      for (Path folder : studyFolders()) {
        forEachStoredSop(folder, sop -> known.add(sopHash(sop)));
      }
      knownSops = known;
    }
    if (!knownSops.contains(sopHash(sopUid))) {
      return false;
    }
    for (Path folder : studyFolders()) {
      if (!folder.equals(current)) {
        boolean[] found = {false};
        forEachStoredSop(folder, sop -> found[0] |= sopUid.equals(sop));
        if (found[0]) {
          return true;
        }
      }
    }
    return false;
  }

  private static void forEachStoredSop(Path folder, Consumer<String> action) throws IOException {
    try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
      for (Path file : files) {
        StoreNaming.sopUidOfFile(file.getFileName().toString()).ifPresent(action);
      }
    }
  }

  private static long sopHash(String sopUid) {
    long hash = 0xcbf29ce484222325L; // FNV-1a
    for (int i = 0; i < sopUid.length(); i++) {
      hash = (hash ^ sopUid.charAt(i)) * 0x100000001b3L;
    }
    return hash;
  }

  /** A compact set of hashes: a hospital's store can hold millions of images. */
  private static final class LongSet {
    private long[] slots = new long[1 << 12];
    private int size;

    boolean contains(long value) {
      long v = value == 0 ? 1 : value;
      for (int i = index(v, slots.length); slots[i] != 0; i = (i + 1) & (slots.length - 1)) {
        if (slots[i] == v) {
          return true;
        }
      }
      return false;
    }

    void add(long value) {
      long v = value == 0 ? 1 : value;
      if ((size + 1) * 2 > slots.length) {
        long[] old = slots;
        slots = new long[old.length * 2];
        size = 0;
        for (long item : old) {
          if (item != 0) {
            insert(item);
          }
        }
      }
      insert(v);
    }

    private void insert(long v) {
      int i = index(v, slots.length);
      while (slots[i] != 0) {
        if (slots[i] == v) {
          return;
        }
        i = (i + 1) & (slots.length - 1);
      }
      slots[i] = v;
      size++;
    }

    private static int index(long v, int length) {
      return (int) (v ^ (v >>> 32)) & (length - 1);
    }
  }

  /**
   * Whether a DICOM file holds all the pixel data it declares. A header can arrive whole with the
   * image cut short, and only the full length tells.
   */
  public static boolean isComplete(Path file) {
    try (DicomInputStream in = new DicomInputStream(file.toFile())) {
      Attributes header = in.readDatasetUntilPixelData();
      if (in.tag() != Tag.PixelData) {
        return true; // an object with no image, such as a report
      }
      if (in.length() == -1) {
        return encapsulatedIsWhole(in, fragmentsNeeded(header, in.getTransferSyntax()));
      }
      // Read through, not measured against the file: a deflated file is smaller than its pixels.
      return in.length() >= expectedPixelBytes(header) && readThrough(in, in.length());
    } catch (IOException | RuntimeException | OutOfMemoryError e) {
      return false;
    }
  }

  /**
   * Each frame takes at least one fragment and a fragment never holds two frames (DICOM PS3.5 A.4),
   * except in video, where one stream holds them all.
   */
  private static int fragmentsNeeded(Attributes header, String transferSyntax) {
    boolean video = transferSyntax.startsWith(VIDEO_SYNTAXES);
    return video ? 1 : Math.max(1, header.getInt(Tag.NumberOfFrames, 1));
  }

  /** The first item is the offset table, which may be empty; the image is in the items after it. */
  private static boolean encapsulatedIsWhole(DicomInputStream in, int fragmentsNeeded)
      throws IOException {
    boolean offsetTable = true;
    int fragments = 0;
    while (true) {
      in.readHeader();
      if (in.tag() == Tag.SequenceDelimitationItem) {
        return in.length() == 0 && fragments >= fragmentsNeeded;
      }
      if (in.tag() != Tag.Item || in.length() < 0 || !readThrough(in, in.length())) {
        return false;
      }
      if (!offsetTable && in.length() > 0) {
        fragments++;
      }
      offsetTable = false;
    }
  }

  private static boolean readThrough(InputStream in, long bytes) throws IOException {
    byte[] buffer = new byte[DIGEST_BUFFER];
    long left = bytes;
    while (left > 0) {
      int n = in.read(buffer, 0, (int) Math.min(left, buffer.length));
      if (n < 0) {
        return false;
      }
      left -= n;
    }
    return true;
  }

  /** What an uncompressed image needs, or 0 when the header does not say enough to work it out. */
  private static long expectedPixelBytes(Attributes h) {
    long rows = h.getInt(Tag.Rows, 0);
    long columns = h.getInt(Tag.Columns, 0);
    long bits = h.getInt(Tag.BitsAllocated, 0);
    if (rows == 0 || columns == 0 || bits == 0) {
      return 0;
    }
    long samples = h.getInt(Tag.SamplesPerPixel, 1);
    long frames = Math.max(1, h.getInt(Tag.NumberOfFrames, 1));
    long pixelBits = rows * columns * frames * samples * bits;
    String colour = h.getString(Tag.PhotometricInterpretation, "");
    if (colour.endsWith("_422")) {
      pixelBits = pixelBits * 2 / 3;
    }
    return (pixelBits + 7) / 8;
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
   * @return the stored object, and whether the catalog was saved as well
   */
  public SavedDerived saveDerived(DerivedWriter writer) throws IOException {
    prepare();
    if (!hasFreeSpace()) {
      throw new IOException("The disk is nearly full");
    }
    Path temp = newTempFile();
    try {
      writer.write(temp);
      if (!isComplete(temp)) {
        throw new IOException("The file written is not a complete DICOM object");
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
      return new SavedDerived(commit.instance(), persistCatalog());
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  /** Makes the new file's name survive a power cut too, where the system allows it. */
  static void flushFolder(Path folder) throws IOException {
    if (WINDOWS) {
      return; // a folder cannot be opened this way there; NTFS journals the rename itself
    }
    try (FileChannel channel = FileChannel.open(folder, StandardOpenOption.READ)) {
      channel.force(true);
    } catch (IOException e) {
      if (!flushNotSupported(e)) {
        throw e;
      }
    }
  }

  /** Some network and synced folders cannot flush a folder: that is not a failed write. */
  private static boolean flushNotSupported(IOException e) {
    String text = Objects.toString(e.getMessage(), "");
    return text.contains("Invalid argument") || text.contains("not supported");
  }

  /** Whether the image belongs to the patient the study's folder already holds. */
  private boolean sameIdentity(Path folder, Map<String, Path> images, Attributes dataset) {
    Identity known = identityOfFolder.get(folder);
    if (known == null) {
      // Every image, because each may be the one that brought an ideographic or phonetic name.
      for (Path image : images.values()) {
        Attributes header = readHeader(image);
        if (header != null) {
          known = known == null ? identity(header) : known.withNamesOf(identity(header));
        }
      }
      if (known == null) {
        known = identity(dataset);
      }
      identityOfFolder.put(folder, known);
    }
    return known.samePatientAs(identity(dataset));
  }

  /** Who an image is for, field by field: joined into one text, two patients could match. */
  private record Identity(
      String patientId,
      String issuer,
      String universalId,
      String issuerType,
      List<List<String>> nameGroups) {

    /** This identity, also holding any name form only the other one has. */
    Identity withNamesOf(Identity other) {
      List<List<String>> merged = new ArrayList<>(nameGroups);
      for (int i = 0; i < other.nameGroups.size(); i++) {
        if (i >= merged.size()) {
          merged.add(other.nameGroups.get(i));
        } else if (merged.get(i).isEmpty()) {
          merged.set(i, other.nameGroups.get(i));
        }
      }
      return new Identity(patientId, issuer, universalId, issuerType, merged);
    }

    /**
     * The alphabetic name must agree. The ideographic and phonetic ones must agree where both sides
     * have one: many machines send only the alphabetic form, which is no contradiction.
     */
    boolean samePatientAs(Identity o) {
      if (!patientId.equals(o.patientId)
          || !issuer.equals(o.issuer)
          || !universalId.equals(o.universalId)
          || !issuerType.equals(o.issuerType)
          || !nameGroups.getFirst().equals(o.nameGroups.getFirst())) {
        return false;
      }
      for (int i = 1; i < Math.min(nameGroups.size(), o.nameGroups.size()); i++) {
        List<String> a = nameGroups.get(i);
        List<String> b = o.nameGroups.get(i);
        if (!a.isEmpty() && !b.isEmpty() && !a.equals(b)) {
          return false;
        }
      }
      return true;
    }
  }

  private static Identity identity(Attributes d) {
    Attributes qualifiers = d.getNestedDataset(Tag.IssuerOfPatientIDQualifiersSequence);
    return new Identity(
        text(d, Tag.PatientID),
        text(d, Tag.IssuerOfPatientID),
        qualifiers == null ? "" : text(qualifiers, Tag.UniversalEntityID),
        qualifiers == null ? "" : text(qualifiers, Tag.UniversalEntityIDType),
        nameGroups(d.getString(Tag.PatientName)));
  }

  private static String text(Attributes d, int tag) {
    return Objects.toString(d.getString(tag), "").trim();
  }

  /** Each representation of a name: alphabetic, then ideographic and phonetic where present. */
  private static List<List<String>> nameGroups(String dicomName) {
    List<List<String>> groups = new ArrayList<>();
    for (String group : Objects.toString(dicomName, "").split("=", -1)) {
      groups.add(nameParts(group));
    }
    return groups;
  }

  private static List<String> nameParts(String group) {
    List<String> parts = new ArrayList<>();
    for (String part : group.split("\\^", -1)) {
      parts.add(part.replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT));
    }
    while (!parts.isEmpty() && parts.getLast().isEmpty()) {
      parts.removeLast();
    }
    return parts;
  }

  /** Keeps a clashing file, named by its content so that a resend does not pile up copies. */
  private void quarantine(Path temp) throws IOException {
    Files.createDirectories(quarantine);
    flushFile(temp);
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
      folderFlush.flush(root);
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
