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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.dicom.codec.utils.DicomFileLabel;
import org.weasis.dicom.explorer.index.IndexStore;

/**
 * The searchable list of studies in the managed store. The files in the store are the truth and
 * this is derived from them: the catalog is written after a file is durable, replaced in one atomic
 * step, and {@link #reconcile} repairs it after any interruption. Losing it loses nothing.
 */
public final class StudyCatalog {
  private static final Logger LOGGER = LoggerFactory.getLogger(StudyCatalog.class);
  private static final String HEADER = "AO-STUDY-CATALOG\t1"; // NON-NLS
  private static final int FIELDS = 16;
  private static final String LIST_SEPARATOR = "\\";

  private final Path file;
  private final Object saveLock = new Object();
  // Guarded by this.
  private final Map<String, CatalogStudy> byUid = new LinkedHashMap<>();
  private volatile Runnable changed = () -> {};

  public StudyCatalog(Path file) {
    this.file = file;
  }

  /** Called after every change, on the thread that made it. */
  void onChanged(Runnable listener) {
    changed = listener;
  }

  public synchronized List<CatalogStudy> studies() {
    return List.copyOf(byUid.values());
  }

  /** Reads the saved catalog. A missing or damaged one is empty, and reconciling rebuilds it. */
  public synchronized void load() {
    byUid.clear();
    if (!Files.isRegularFile(file)) {
      return;
    }
    try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      if (!HEADER.equals(r.readLine())) {
        return;
      }
      String line;
      while ((line = r.readLine()) != null) {
        String[] p = line.split("\t", -1);
        if (p.length == FIELDS) {
          CatalogStudy row = parse(p);
          byUid.put(row.studyUid(), row);
        }
      }
    } catch (IOException | RuntimeException e) {
      LOGGER.warn("The study catalog cannot be read and will be rebuilt: {}", e.getClass());
      byUid.clear();
    }
  }

  private static CatalogStudy parse(String[] p) {
    return new CatalogStudy(
        text(p[0]),
        text(p[1]),
        text(p[2]),
        text(p[3]),
        text(p[4]),
        text(p[5]),
        text(p[6]),
        text(p[7]),
        text(p[8]),
        text(p[9]),
        text(p[10]),
        list(p[11]),
        list(p[12]),
        Integer.parseInt(p[13]),
        Long.parseLong(p[14]),
        Long.parseLong(p[15]));
  }

  private static String text(String field) {
    return "\\0".equals(field) ? null : IndexStore.unescape(field);
  }

  private static Set<String> list(String field) {
    String value = IndexStore.unescape(field);
    return value.isEmpty()
        ? Set.of()
        : new LinkedHashSet<>(Arrays.asList(value.split(java.util.regex.Pattern.quote("|"))));
  }

  /** Replaces the saved catalog in one step, so a crash leaves the old one or the new one. */
  public void save() throws IOException {
    List<CatalogStudy> rows = studies();
    synchronized (saveLock) {
      Files.createDirectories(file.getParent());
      Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
      try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
        w.write(HEADER);
        w.newLine();
        for (CatalogStudy s : rows) {
          w.write(format(s));
          w.newLine();
        }
      }
      IndexStore.userOnly(tmp);
      try {
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }

  private static String format(CatalogStudy s) {
    return String.join(
        "\t",
        IndexStore.escape(s.studyUid()),
        IndexStore.escape(s.folder()),
        IndexStore.escape(s.patientName()),
        IndexStore.escape(s.patientId()),
        IndexStore.escape(s.issuer()),
        IndexStore.escape(s.birthDate()),
        IndexStore.escape(s.sex()),
        IndexStore.escape(s.studyDate()),
        IndexStore.escape(s.imageDate()),
        IndexStore.escape(s.studyDescription()),
        IndexStore.escape(s.accession()),
        IndexStore.escape(String.join("|", s.modalities())),
        IndexStore.escape(String.join("|", s.series())),
        Integer.toString(s.imageCount()),
        Long.toString(s.firstStored()),
        Long.toString(s.lastStored()));
  }

  /** Adds one image that is already durable in {@code folder}. */
  void recordStored(Path folder, Attributes dataset, long now) {
    synchronized (this) {
      String uid = dataset.getString(Tag.StudyInstanceUID);
      byUid.put(uid, merge(byUid.get(uid), folder.getFileName().toString(), dataset, now));
    }
    changed.run();
  }

  /** Drops the row of a study folder that is being deleted. */
  void forget(String folder) {
    synchronized (this) {
      byUid.values().removeIf(row -> folder.equals(row.folder()));
    }
    changed.run();
  }

  private static CatalogStudy merge(CatalogStudy row, String folder, Attributes d, long now) {
    Set<String> modalities = new TreeSet<>(row == null ? Set.of() : row.modalities());
    Set<String> series = new LinkedHashSet<>(row == null ? Set.of() : row.series());
    String modality = d.getString(Tag.Modality);
    if (modality != null && !modality.isBlank()) {
      modalities.add(modality);
    }
    series.add(d.getString(Tag.SeriesInstanceUID));
    if (row != null) {
      String date = row.imageDate() != null ? row.imageDate() : imageDate(d);
      return new CatalogStudy(
          row.studyUid(),
          row.folder(),
          row.patientName(),
          row.patientId(),
          row.issuer(),
          row.birthDate(),
          row.sex(),
          row.studyDate(),
          date,
          row.studyDescription(),
          row.accession(),
          modalities,
          series,
          row.imageCount() + 1,
          row.firstStored(),
          Math.max(row.lastStored(), now));
    }
    return new CatalogStudy(
        d.getString(Tag.StudyInstanceUID),
        folder,
        d.getString(Tag.PatientName),
        d.getString(Tag.PatientID),
        d.getString(Tag.IssuerOfPatientID),
        d.getString(Tag.PatientBirthDate),
        d.getString(Tag.PatientSex),
        d.getString(Tag.StudyDate),
        imageDate(d),
        d.getString(Tag.StudyDescription),
        d.getString(Tag.AccessionNumber),
        modalities,
        series,
        1,
        now,
        now);
  }

  private static String imageDate(Attributes d) {
    return DicomFileLabel.imageDate(d).map(DicomFileLabel.ImageDate::label).orElse(null);
  }

  /** The outcome of comparing the catalog with the files. */
  public record Reconciled(int rebuilt, int removed) {}

  /**
   * Makes the catalog match the store after a restart: a study folder the catalog lacks or counts
   * differently is read again, and a row whose folder is gone is dropped. Only headers are read,
   * and only of the folders that differ.
   */
  public Reconciled reconcile(Path root) throws IOException {
    Map<String, CatalogStudy> byFolder = new LinkedHashMap<>();
    for (CatalogStudy row : studies()) {
      byFolder.put(row.folder(), row);
    }
    Set<String> present = new HashSet<>();
    int rebuilt = 0;
    if (Files.isDirectory(root)) {
      try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
        for (Path folder : entries) {
          String name = folder.getFileName().toString();
          if (name.startsWith(".")
              || !Files.isDirectory(folder)
              || StoreNaming.studyKeyOfFolder(name).isEmpty()) {
            continue;
          }
          present.add(name);
          List<Path> images = images(folder);
          CatalogStudy row = byFolder.get(name);
          if (row == null || row.imageCount() != images.size()) {
            rebuild(folder, images, row);
            rebuilt++;
          }
        }
      }
    }
    int removed = 0;
    synchronized (this) {
      for (CatalogStudy row : byFolder.values()) {
        if (!present.contains(row.folder())) {
          byUid.remove(row.studyUid());
          removed++;
        }
      }
    }
    if (rebuilt + removed > 0) {
      save();
      changed.run();
    }
    return new Reconciled(rebuilt, removed);
  }

  private static List<Path> images(Path folder) throws IOException {
    List<Path> images = new ArrayList<>();
    try (DirectoryStream<Path> files =
        Files.newDirectoryStream(folder, "*" + StoreNaming.FILE_EXTENSION)) {
      for (Path f : files) {
        if (Files.isRegularFile(f)) {
          images.add(f);
        }
      }
    }
    return images;
  }

  private void rebuild(Path folder, List<Path> images, CatalogStudy before) throws IOException {
    String name = folder.getFileName().toString();
    long received =
        before != null ? before.firstStored() : Files.getLastModifiedTime(folder).toMillis();
    CatalogStudy row = null;
    for (Path image : images) {
      Attributes header = StudyStore.readHeader(image);
      if (header != null && header.getString(Tag.StudyInstanceUID) != null) {
        row = merge(row, name, header, received);
      }
    }
    synchronized (this) {
      if (before != null) {
        byUid.remove(before.studyUid());
      }
      if (row != null) {
        byUid.put(row.studyUid(), row);
      }
    }
  }
}
