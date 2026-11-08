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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.weasis.dicom.explorer.library.CatalogStudy;

/**
 * One study found on the computer, gathered from its instances wherever they are: the same study in
 * two folders (or a folder and a zip) is one row, each image counted once (by SOP Instance UID).
 *
 * @param containers the files and archives to open, in a stable order
 * @param places the folders (or archives) holding the study, for the user
 * @param temporary found by a one-off folder search, not saved in the index
 */
public record StudyEntry(
    String studyUid,
    String patientName,
    String patientId,
    String birthDate,
    String sex,
    String studyDate,
    String studyDescription,
    String accession,
    String modalities,
    int seriesCount,
    int imageCount,
    List<String> containers,
    List<String> places,
    boolean temporary) {

  /** Groups instances into studies; unreadable study UIDs are dropped. */
  public static List<StudyEntry> of(Collection<IndexedFile> files, boolean temporary) {
    Map<String, List<IndexedFile>> byStudy = new LinkedHashMap<>();
    for (IndexedFile f : files) {
      if (f.studyUid() != null && !f.studyUid().isBlank()) {
        byStudy.computeIfAbsent(f.studyUid(), _ -> new ArrayList<>()).add(f);
      }
    }
    List<StudyEntry> studies = new ArrayList<>();
    for (List<IndexedFile> list : byStudy.values()) {
      studies.add(build(list, temporary));
    }
    return studies;
  }

  private static StudyEntry build(List<IndexedFile> files, boolean temporary) {
    IndexedFile first = files.getFirst();
    Set<String> sops = new LinkedHashSet<>();
    Set<String> series = new LinkedHashSet<>();
    Set<String> modalities = new TreeSet<>();
    Set<String> containers = new TreeSet<>();
    Set<String> places = new TreeSet<>();
    for (IndexedFile f : files) {
      sops.add(f.sopUid() == null || f.sopUid().isBlank() ? f.location() : f.sopUid());
      if (f.seriesUid() != null) {
        series.add(f.seriesUid());
      }
      if (f.modality() != null && !f.modality().isBlank()) {
        modalities.add(f.modality());
      }
      containers.add(f.container());
      places.add(f.inArchive() ? f.container() : parentOf(f.location()));
    }
    return new StudyEntry(
        first.studyUid(),
        first.patientName(),
        first.patientId(),
        first.birthDate(),
        first.sex(),
        first.studyDate(),
        first.studyDescription(),
        first.accession(),
        String.join("/", modalities),
        series.size(),
        sops.size(),
        List.copyOf(containers),
        List.copyOf(places),
        temporary);
  }

  private static String parentOf(String path) {
    int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    return i <= 0 ? path : path.substring(0, i);
  }

  /** One study of the managed store, opened from its folder. */
  public static StudyEntry of(CatalogStudy s, String folder) {
    return new StudyEntry(
        s.studyUid(),
        s.patientName(),
        s.patientId(),
        s.birthDate(),
        s.sex(),
        s.studyDate(),
        s.studyDescription(),
        s.accession(),
        String.join("/", s.modalities()),
        s.series().size(),
        s.imageCount(),
        List.of(folder),
        List.of(folder),
        false);
  }

  /**
   * One row per study. A study in both places opens from both, managed copy first: the library may
   * hold a saved stitch the folder lacks, and the folder images that were never imported.
   */
  public static List<StudyEntry> merge(List<StudyEntry> managed, List<StudyEntry> found) {
    Map<String, StudyEntry> byStudy = new LinkedHashMap<>();
    managed.forEach(s -> byStudy.put(s.studyUid(), s));
    for (StudyEntry s : found) {
      byStudy.merge(s.studyUid(), s, StudyEntry::alsoIn);
    }
    return List.copyOf(byStudy.values());
  }

  /** This study, also found at the places of {@code other}. Images are not double counted. */
  private StudyEntry alsoIn(StudyEntry other) {
    Set<String> allContainers = new LinkedHashSet<>(containers);
    allContainers.addAll(other.containers);
    Set<String> allPlaces = new LinkedHashSet<>(places);
    allPlaces.addAll(other.places);
    return new StudyEntry(
        studyUid,
        patientName,
        patientId,
        birthDate,
        sex,
        studyDate,
        studyDescription,
        accession,
        modalities,
        Math.max(seriesCount, other.seriesCount),
        Math.max(imageCount, other.imageCount),
        List.copyOf(allContainers),
        List.copyOf(allPlaces),
        temporary && other.temporary);
  }

  /** The patient name as people write it: the DICOM {@code ^} separators become spaces. */
  public String displayName() {
    return PatientMatcher.displayName(patientName);
  }
}
