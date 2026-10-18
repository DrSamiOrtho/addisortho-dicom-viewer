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

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds studies from what a person types: every word must match something of the study.
 *
 * <ul>
 *   <li>A name word matches the start of any word of the patient name, in any order and case,
 *       without accents ("abebe kebede" finds {@code KEBEDE^ABEBE}); Ethiopic (Amharic) words match
 *       anywhere in the name, as typed.
 *   <li>A patient ID or accession number matches from its start.
 *   <li>A date (yyyymmdd, dd/mm/yyyy, dd-mm-yyyy, dd.mm.yyyy or yyyy-mm-dd) matches the study date
 *       or the birth date; a year alone matches the study year. Ethiopian calendar dates are not
 *       converted.
 *   <li>A word of three letters or more also matches inside the study description.
 * </ul>
 */
public final class PatientMatcher {

  private static final Pattern DMY = Pattern.compile("(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4})");
  private static final Pattern YMD = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");

  private PatientMatcher() {}

  /** Study rows matching the query, newest study first; all of them for an empty query. */
  public static List<StudyEntry> search(List<StudyEntry> studies, String query) {
    List<String> words = words(query);
    List<StudyEntry> found = new ArrayList<>();
    for (StudyEntry s : studies) {
      if (words.stream().allMatch(w -> matches(s, w))) {
        found.add(s);
      }
    }
    found.sort(
        Comparator.comparing((StudyEntry s) -> s.studyDate() == null ? "" : s.studyDate())
            .reversed()
            .thenComparing(s -> normalize(s.patientName())));
    return found;
  }

  static boolean matches(StudyEntry s, String word) {
    String date = toDicomDate(word);
    if (date != null) {
      return date.equals(s.studyDate()) || date.equals(s.birthDate());
    }
    if (word.matches("\\d{4}") && s.studyDate() != null && s.studyDate().startsWith(word)) {
      return true;
    }
    for (String part : words(s.patientName())) {
      if (part.startsWith(word)) {
        return true;
      }
    }
    if (isEthiopic(word) && normalize(s.patientName()).contains(word)) {
      return true;
    }
    if (startsWith(s.patientId(), word) || startsWith(s.accession(), word)) {
      return true;
    }
    return word.length() >= 3 && normalize(s.studyDescription()).contains(word);
  }

  private static boolean startsWith(String value, String word) {
    return value != null && normalize(value).startsWith(word);
  }

  /** The words of a text: lower case, no accents, DICOM separators and punctuation as spaces. */
  static List<String> words(String text) {
    String n = normalize(text);
    List<String> out = new ArrayList<>();
    for (String w : n.split(" ")) {
      if (!w.isEmpty()) {
        out.add(w);
      }
    }
    return out;
  }

  static String normalize(String text) {
    if (text == null) {
      return "";
    }
    String s = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    s = s.toLowerCase(Locale.ROOT).replace('^', ' ').replace(',', ' ').replace('=', ' ');
    return s.replaceAll("\\s+", " ").trim(); // NON-NLS
  }

  /** A typed date as yyyymmdd, or {@code null} when the word is not a date. */
  static String toDicomDate(String word) {
    if (word.matches("\\d{8}") && validDate(word)) {
      return word;
    }
    Matcher m = DMY.matcher(word);
    if (m.matches()) {
      String d =
          String.format(
              "%s%02d%02d", m.group(3), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
      return validDate(d) ? d : null;
    }
    m = YMD.matcher(word);
    if (m.matches()) {
      String d =
          String.format(
              "%s%02d%02d", m.group(1), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
      return validDate(d) ? d : null;
    }
    return null;
  }

  private static boolean validDate(String yyyymmdd) {
    int month = Integer.parseInt(yyyymmdd.substring(4, 6));
    int day = Integer.parseInt(yyyymmdd.substring(6, 8));
    return month >= 1 && month <= 12 && day >= 1 && day <= 31;
  }

  private static boolean isEthiopic(String word) {
    return word.codePoints()
        .anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.ETHIOPIC);
  }

  /** {@code LAST^FIRST^MIDDLE} as {@code LAST FIRST MIDDLE}. */
  public static String displayName(String dicomName) {
    if (dicomName == null) {
      return "";
    }
    return dicomName.replace('^', ' ').replaceAll("\\s+", " ").trim(); // NON-NLS
  }

  /** yyyymmdd as dd/mm/yyyy for the table; anything else as it is. */
  public static String displayDate(String dicomDate) {
    if (dicomDate == null || !dicomDate.matches("\\d{8}")) {
      return dicomDate == null ? "" : dicomDate;
    }
    return dicomDate.substring(6, 8)
        + "/"
        + dicomDate.substring(4, 6)
        + "/"
        + dicomDate.substring(0, 4);
  }
}
