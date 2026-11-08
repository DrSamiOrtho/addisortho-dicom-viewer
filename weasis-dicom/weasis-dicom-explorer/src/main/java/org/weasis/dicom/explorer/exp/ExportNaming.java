/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer.exp;

import java.io.File;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.dcm4che3.data.Attributes;
import org.weasis.dicom.codec.utils.DicomFileLabel;

/**
 * Readable names for the files of one export, after {@link DicomFileLabel}, kept unique so that an
 * export never replaces another image with the same patient, day and view.
 */
public final class ExportNaming {
  private final boolean withPatient;
  private final boolean inPatientFolder;
  private final Set<String> used = new HashSet<>();

  /**
   * @param withPatient false leaves the name, age and MRN out of the file names, for sharing
   *     outside the hospital. The DICOM header keeps them, so this is not anonymization
   * @param inPatientFolder the export folder is already named after the patient
   */
  public ExportNaming(boolean withPatient, boolean inPatientFolder) {
    this.withPatient = withPatient;
    this.inPatientFolder = inPatientFolder;
  }

  public boolean inPatientFolder() {
    return inPatientFolder;
  }

  /**
   * The name of one file without its extension.
   *
   * @param header the image's DICOM header, or null when it cannot be read
   * @param fallback the name to use when the header gives nothing, carrying no patient details
   */
  public String stem(Attributes header, String fallback) {
    String name = DicomFileLabel.imageLabel(header, withPatient);
    return name.isEmpty() ? fallback : name;
  }

  /**
   * A file in {@code folder} that neither this export nor an earlier one has used.
   *
   * @param extension with its dot
   */
  public File uniqueFile(File folder, String stem, String extension) {
    for (int n = 1; ; n++) {
      File file = new File(folder, stem + (n == 1 ? "" : " (" + n + ")") + extension);
      if (used.add(file.getPath().toLowerCase(Locale.ROOT)) && !file.exists()) {
        return file;
      }
    }
  }
}
