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

/**
 * One DICOM instance found on the computer: where it is and the header fields the patient search
 * needs. A file inside a zip archive has {@code location} {@code archive.zip!/entry} and the size
 * and date of the archive, so an unchanged archive is not read again.
 *
 * @param location the file path, or {@code zipPath!/entryName}
 * @param size size of the file (or of the archive), to skip it when unchanged
 * @param modified last modification time in ms (of the file or the archive)
 */
public record IndexedFile(
    String location,
    long size,
    long modified,
    String studyUid,
    String seriesUid,
    String sopUid,
    String modality,
    String patientName,
    String patientId,
    String birthDate,
    String sex,
    String studyDate,
    String studyDescription,
    String accession) {

  /** Separator between an archive and an entry in {@link #location}. */
  public static final String ZIP_SEPARATOR = "!/";

  public boolean inArchive() {
    return location.contains(ZIP_SEPARATOR);
  }

  /** The file to open: the file itself, or the archive that holds it. */
  public String container() {
    int i = location.indexOf(ZIP_SEPARATOR);
    return i < 0 ? location : location.substring(0, i);
  }
}
