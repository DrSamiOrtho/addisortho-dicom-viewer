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
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.weasis.core.util.FileUtil;
import org.weasis.core.util.StringUtil;

/**
 * Readable names for the files of one export: "Full Name 45Y AP 2026-01-15". Every part comes from
 * the image's own header. A part the header does not have is left out, never guessed. The header
 * inside the file always keeps the complete values, however the file name is shortened.
 */
public final class ExportNaming {
  private static final int LONGEST_NAME = 80;
  private static final int LONGEST_VIEW = 40;
  private static final Pattern DICOM_AGE = Pattern.compile("0*(\\d+)([DWMY])");

  private final boolean withPatient;
  private final boolean inPatientFolder;
  private final Set<String> used = new HashSet<>();

  /**
   * @param withPatient false leaves the name and age out, for sharing outside the hospital
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
    if (header == null) {
      return fallback;
    }
    LocalDate acquired = acquisitionDate(header);
    LocalDate study = date(header.getString(Tag.StudyDate));
    StringJoiner parts = new StringJoiner(" ");
    if (withPatient) {
      add(parts, shortened(LocalExport.fullName(header.getString(Tag.PatientName)), LONGEST_NAME));
      add(parts, age(header, acquired != null ? acquired : study));
    }
    add(parts, shortened(view(header), LONGEST_VIEW));
    if (acquired != null) {
      add(parts, acquired.toString());
    } else if (study != null) {
      // Not the same thing as the day the picture was taken, so it says so.
      add(parts, "study " + study);
    }
    String name = FileUtil.getValidFileNameWithoutHTML(parts.toString());
    name = name.replaceAll("\\s+", " ").trim().replaceAll("[. ]+$", "");
    return name.isEmpty() ? fallback : name;
  }

  /**
   * A file in {@code folder} that neither this export nor an earlier one has used, so an export
   * never replaces another image that happens to have the same view on the same day.
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

  private static void add(StringJoiner parts, String part) {
    if (StringUtil.hasText(part)) {
      parts.add(part.trim());
    }
  }

  private static String shortened(String text, int longest) {
    return text != null && text.length() > longest ? text.substring(0, longest) : text;
  }

  private static String view(Attributes header) {
    for (int tag : new int[] {Tag.ViewPosition, Tag.SeriesDescription, Tag.ProtocolName}) {
      String value = header.getString(tag);
      if (StringUtil.hasText(value)) {
        return value;
      }
    }
    return null;
  }

  private static LocalDate acquisitionDate(Attributes header) {
    LocalDate day = date(header.getString(Tag.AcquisitionDate));
    if (day != null) {
      return day;
    }
    String dateTime = header.getString(Tag.AcquisitionDateTime);
    return dateTime == null || dateTime.length() < 8 ? null : date(dateTime.substring(0, 8));
  }

  private static LocalDate date(String dicomDate) {
    if (dicomDate == null) {
      return null;
    }
    try {
      return LocalDate.parse(dicomDate.trim(), DateTimeFormatter.BASIC_ISO_DATE);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  /** The age stored in the header, or worked out for the day of the image, never for today. */
  private static String age(Attributes header, LocalDate imageDay) {
    String stored = header.getString(Tag.PatientAge);
    if (stored != null) {
      Matcher matcher = DICOM_AGE.matcher(stored.trim());
      if (matcher.matches()) {
        return matcher.group(1) + matcher.group(2);
      }
    }
    LocalDate born = date(header.getString(Tag.PatientBirthDate));
    if (born == null || imageDay == null || born.isAfter(imageDay)) {
      return null;
    }
    Period age = Period.between(born, imageDay);
    if (age.getYears() > 0) {
      return age.getYears() + "Y";
    }
    return age.getMonths() > 0 ? age.getMonths() + "M" : age.getDays() + "D";
  }
}
