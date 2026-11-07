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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;

/**
 * Builds the folder and file names of received images:
 *
 * <pre>
 * NAME_AGE_MRN_yyyy-MM-dd_studyKey / NAME_AGE_MRN_yyyy-MM-dd_sopInstanceUid.dcm
 * </pre>
 *
 * Every patient field comes from the network and is untrusted, so each one is sanitized and capped.
 * The date is the day the image arrived on this computer. A missing field is left out, and a folder
 * that lacks the name or the MRN carries the Study Instance UID instead.
 */
final class ReceiveNaming {

  static final String FILE_EXTENSION = ".dcm";
  static final char SEPARATOR = '_';

  private static final int MAX_NAME_LENGTH = 48;
  private static final int MAX_MRN_LENGTH = 32;
  private static final int STUDY_KEY_BYTES = 6;
  private static final int MAX_FOLDER_CHARS = 110;
  private static final int MAX_NAME_BYTES = 240;

  private static final String RESERVED_CHARS = "<>:\"/\\|?*";
  private static final Pattern RESERVED_DEVICE =
      Pattern.compile(
          "(CON|PRN|AUX|NUL|COM[0-9¹²³]|LPT[0-9¹²³])([ .].*)?", Pattern.CASE_INSENSITIVE);
  private static final Pattern DICOM_AGE = Pattern.compile("0*(\\d{1,3})([DWMY])");
  private static final DateTimeFormatter DICOM_DATE = DateTimeFormatter.ofPattern("uuuuMMdd");
  private static final DateTimeFormatter ARRIVAL_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

  private ReceiveNaming() {}

  /** The name of a study folder. Always ends with the study key, so that a study has one folder. */
  static String folderName(
      Attributes dataset, LocalDate arrival, boolean includeName, String studyUid) {
    String name = includeName ? patientName(dataset) : "";
    String mrn = patientId(dataset);
    List<String> tail = new ArrayList<>();
    tail.add(age(dataset, arrival));
    tail.add(ARRIVAL_DATE.format(arrival));
    if ((includeName && name.isEmpty()) || mrn.isEmpty()) {
      tail.add(studyUid);
    }
    tail.add(studyKey(studyUid));
    return fit(name, mrn, tail, MAX_FOLDER_CHARS);
  }

  /** The name of an image file, which keeps the whole SOP Instance UID so it can never collide. */
  static String fileName(
      Attributes dataset, LocalDate arrival, boolean includeName, String sopUid, int maxChars) {
    String name = includeName ? patientName(dataset) : "";
    List<String> tail = List.of(age(dataset, arrival), ARRIVAL_DATE.format(arrival), sopUid);
    return fit(name, patientId(dataset), tail, maxChars - FILE_EXTENSION.length()) + FILE_EXTENSION;
  }

  /** A short and stable key of the study, which finds the folder again on a later arrival day. */
  static String studyKey(String studyUid) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(studyUid.getBytes(StandardCharsets.US_ASCII));
      return HexFormat.of().formatHex(digest, 0, STUDY_KEY_BYTES);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The study key of a folder name, or empty if the folder was not made by the receiver. */
  static Optional<String> studyKeyOfFolder(String folderName) {
    int index = folderName.lastIndexOf(SEPARATOR);
    String key = folderName.substring(index + 1);
    return key.matches("[0-9a-f]{" + STUDY_KEY_BYTES * 2 + "}")
        ? Optional.of(key)
        : Optional.empty();
  }

  /** The SOP Instance UID of a file name made by {@link #fileName}. */
  static Optional<String> sopUidOfFile(String fileName) {
    if (!fileName.endsWith(FILE_EXTENSION)) {
      return Optional.empty();
    }
    String base = fileName.substring(0, fileName.length() - FILE_EXTENSION.length());
    return Optional.of(base.substring(base.lastIndexOf(SEPARATOR) + 1));
  }

  /** The alphabetic group of the person name in the order the machine sent it, with spaces. */
  static String patientName(Attributes dataset) {
    String raw = dataset.getString(Tag.PatientName);
    if (raw == null) {
      return "";
    }
    for (String group : raw.split("=")) {
      String name = sanitize(group.replace('^', ' '), MAX_NAME_LENGTH);
      if (!name.isEmpty()) {
        return name;
      }
    }
    return "";
  }

  static String patientId(Attributes dataset) {
    String raw = dataset.getString(Tag.PatientID);
    return raw == null ? "" : sanitize(raw, MAX_MRN_LENGTH);
  }

  /** Age at the study, from the birth date. Falls back to the Patient's Age element when absent. */
  static String age(Attributes dataset, LocalDate arrival) {
    Optional<LocalDate> birth = parseDate(dataset.getString(Tag.PatientBirthDate));
    LocalDate reference = parseDate(dataset.getString(Tag.StudyDate)).orElse(arrival);
    if (birth.isPresent() && !birth.get().isAfter(reference)) {
      return formatAge(birth.get(), reference);
    }
    String declared = dataset.getString(Tag.PatientAge);
    if (declared != null) {
      var matcher = DICOM_AGE.matcher(declared.trim());
      if (matcher.matches()) {
        return matcher.group(1) + matcher.group(2);
      }
    }
    return "";
  }

  private static String formatAge(LocalDate birth, LocalDate reference) {
    Period period = Period.between(birth, reference);
    if (period.getYears() >= 2) {
      return period.getYears() + "Y";
    }
    long months = period.toTotalMonths();
    return months >= 1 ? months + "M" : ChronoUnit.DAYS.between(birth, reference) + "D";
  }

  private static Optional<LocalDate> parseDate(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(LocalDate.parse(value.trim().replace(".", ""), DICOM_DATE));
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }

  /** Makes one untrusted field safe as a part of a file name on Windows, macOS and Linux. */
  static String sanitize(String raw, int maxLength) {
    String text = Normalizer.normalize(raw, Normalizer.Form.NFC);
    StringBuilder out = new StringBuilder();
    text.codePoints().forEach(cp -> out.appendCodePoint(isUnsafe(cp) ? ' ' : cp));
    String clean = out.toString().replaceAll("\\s+", " ").replaceAll("\\.{2,}", ".");
    clean = trimEnds(truncate(trimEnds(clean), maxLength));
    return RESERVED_DEVICE.matcher(clean).matches() ? "_" + clean : clean;
  }

  private static boolean isUnsafe(int cp) {
    if (RESERVED_CHARS.indexOf(cp) >= 0) {
      return true;
    }
    return switch (Character.getType(cp)) {
      case Character.CONTROL,
          Character.FORMAT,
          Character.SURROGATE,
          Character.PRIVATE_USE,
          Character.UNASSIGNED,
          Character.LINE_SEPARATOR,
          Character.PARAGRAPH_SEPARATOR ->
          true;
      default -> false;
    };
  }

  private static String trimEnds(String text) {
    return text.replaceAll("^[. ]+", "").replaceAll("[. ]+$", "");
  }

  private static String truncate(String text, int maxCodePoints) {
    if (text.codePointCount(0, text.length()) <= maxCodePoints) {
      return text;
    }
    return text.substring(0, text.offsetByCodePoints(0, maxCodePoints));
  }

  /**
   * Joins the parts within the length limits of file systems: shortens the name, then the MRN, and
   * in the last resort keeps only the last part, which is the UID that makes the name unique.
   */
  private static String fit(String name, String mrn, List<String> tail, int maxChars) {
    String shortName = name;
    String shortMrn = mrn;
    String joined = join(shortName, shortMrn, tail);
    while (!fits(joined, maxChars) && !shortName.isEmpty()) {
      shortName = dropLastCodePoint(shortName);
      joined = join(shortName, shortMrn, tail);
    }
    while (!fits(joined, maxChars) && !shortMrn.isEmpty()) {
      shortMrn = dropLastCodePoint(shortMrn);
      joined = join(shortName, shortMrn, tail);
    }
    return fits(joined, maxChars) ? joined : tail.getLast();
  }

  private static boolean fits(String text, int maxChars) {
    return text.length() <= maxChars
        && text.getBytes(StandardCharsets.UTF_8).length <= MAX_NAME_BYTES;
  }

  private static String dropLastCodePoint(String text) {
    return trimEnds(text.substring(0, text.offsetByCodePoints(text.length(), -1)));
  }

  private static String join(String name, String mrn, List<String> tail) {
    List<String> parts = new ArrayList<>();
    parts.add(name);
    parts.add(tail.getFirst());
    parts.add(mrn);
    parts.addAll(tail.subList(1, tail.size()));
    return String.join(
        String.valueOf(SEPARATOR), parts.stream().filter(p -> !p.isEmpty()).toList());
  }

  static String safeForLog(String value) {
    return value == null ? "" : value.replaceAll("[^\\x20-\\x7E]", "?");
  }
}
