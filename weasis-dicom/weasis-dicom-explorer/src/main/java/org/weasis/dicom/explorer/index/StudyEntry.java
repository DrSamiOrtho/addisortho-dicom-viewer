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

  /** The patient name as people write it: the DICOM {@code ^} separators become spaces. */
  public String displayName() {
    return PatientMatcher.displayName(patientName);
  }
}
