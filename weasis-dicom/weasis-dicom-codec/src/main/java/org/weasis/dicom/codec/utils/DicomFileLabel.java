/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.codec.utils;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;

/**
 * The one policy for what an image's file or folder is called: "Full Name MRN 00123 45Y 2026-01-15
 * CR AP". Every part comes from the image's own header. A part the header does not have is left
 * out, never guessed, and the day the file arrived or was exported is never used as the image's
 * date. Only the file name is cleaned and shortened: the header keeps the complete values.
 */
public final class DicomFileLabel {
  private static final int LONGEST_NAME = 80;
  private static final int LONGEST_MRN = 64;
  private static final int LONGEST_VIEW = 40;
  private static final int LONGEST_MODALITY = 16;

  /** Leaves room for the folders above on Windows and for a " (12)" and an extension. */
  static final int LONGEST_LABEL_CHARS = 140;

  /** File systems count bytes, and one Amharic letter is three of them. */
  static final int LONGEST_LABEL_BYTES = 200;

  private static final String RESERVED_CHARS = "<>:\"/\\|?*";
  private static final Pattern RESERVED_DEVICE =
      Pattern.compile(
          "(CON|PRN|AUX|NUL|COM[0-9¹²³]|LPT[0-9¹²³])([ .].*)?", Pattern.CASE_INSENSITIVE);
  private static final Pattern DICOM_AGE = Pattern.compile("0*(\\d+)([DWMY])");

  /**
   * The date an image can be labelled with.
   *
   * @param day the date
   * @param acquired true when the header gives the day of the image itself; false when it only
   *     gives the Study Date, which is not the same thing and is labelled as such
   */
  public record ImageDate(LocalDate day, boolean acquired) {
    public String label() {
      return acquired ? day.toString() : "study " + day; // NON-NLS
    }
  }

  private DicomFileLabel() {}

  /**
   * The date of the image: the acquisition date and time, the acquisition date, the first frame's
   * acquisition time for an enhanced object, and only then the Study Date, labelled as such.
   */
  public static Optional<ImageDate> imageDate(Attributes header) {
    if (header == null) {
      return Optional.empty();
    }
    List<String> candidates = new ArrayList<>();
    candidates.add(header.getString(Tag.AcquisitionDateTime));
    candidates.add(header.getString(Tag.AcquisitionDate));
    candidates.add(frameAcquisition(header, Tag.PerFrameFunctionalGroupsSequence));
    candidates.add(frameAcquisition(header, Tag.SharedFunctionalGroupsSequence));
    for (String candidate : candidates) {
      LocalDate day = date(candidate);
      if (day != null) {
        return Optional.of(new ImageDate(day, true));
      }
    }
    LocalDate study = date(header.getString(Tag.StudyDate));
    return study == null ? Optional.empty() : Optional.of(new ImageDate(study, false));
  }

  private static String frameAcquisition(Attributes header, int groups) {
    Attributes item = header.getNestedDataset(groups);
    Attributes content = item == null ? null : item.getNestedDataset(Tag.FrameContentSequence);
    return content == null ? null : content.getString(Tag.FrameAcquisitionDateTime);
  }

  /** The first eight digits of a DA or DT value as a date, or null when they are not one. */
  private static LocalDate date(String dicomDate) {
    if (dicomDate == null) {
      return null;
    }
    String digits = dicomDate.trim().replace(".", ""); // old files write 2026.01.15
    if (digits.length() < 8) {
      return null;
    }
    try {
      return LocalDate.parse(digits.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  /**
   * A DICOM person name ("Family^Given^Middle^Prefix^Suffix") as "Given Middle Family". A name
   * without "^" is kept in its own order: it was typed as one text, usually first name first.
   */
  public static String fullName(String dicomName) {
    if (dicomName == null) {
      return "";
    }
    // Only the alphabetic group: ideographic and phonetic groups follow "=".
    String alphabetic = dicomName.split("=", -1)[0];
    String[] parts = alphabetic.split("\\^", -1);
    String text =
        parts.length < 2
            ? alphabetic
            : String.join(
                " ",
                parts.length > 3 ? parts[3] : "",
                parts[1],
                parts.length > 2 ? parts[2] : "",
                parts[0],
                parts.length > 4 ? parts[4] : "");
    return text.replaceAll("\\s+", " ").trim();
  }

  /**
   * The name of one image's file without its extension, or an empty text when the header gives
   * nothing to name it by.
   *
   * @param withPatient false leaves the name, age and MRN out of the file name. The header inside
   *     the file still has them, so this does not make the file anonymous
   */
  public static String imageLabel(Attributes header, boolean withPatient) {
    if (header == null) {
      return "";
    }
    Optional<ImageDate> date = imageDate(header);
    String name = "";
    String mrn = "";
    String age = "";
    if (withPatient) {
      name = sanitize(fullName(header.getString(Tag.PatientName)), LONGEST_NAME);
      mrn = sanitize(header.getString(Tag.PatientID), LONGEST_MRN);
      age = age(header, date.map(ImageDate::day).orElse(null));
    }
    return assemble(
        name,
        mrn,
        age,
        date.map(ImageDate::label).orElse(""),
        sanitize(header.getString(Tag.Modality), LONGEST_MODALITY),
        sanitize(view(header), LONGEST_VIEW));
  }

  /**
   * The name of a folder, zip or disc image holding one patient's images, or an empty text when
   * neither the name nor the MRN is known.
   *
   * @param dates the dates of the images inside; several different ones become a range, labelled as
   *     study dates if any of them is one
   */
  public static String folderLabel(String fullName, String mrn, Collection<ImageDate> dates) {
    String name = sanitize(fullName, LONGEST_NAME);
    String id = sanitize(mrn, LONGEST_MRN);
    if (name.isEmpty() && id.isEmpty()) {
      return "";
    }
    return assemble(name, id, "", rangeLabel(dates), "", "");
  }

  /**
   * The line that says whose images are open: "John Doe | MRN 00123 | 2026-01-15 | CR". Unlike a
   * file name it needs no cleaning, and a date known only from the study says so.
   */
  public static String contextLine(Attributes header) {
    if (header == null) {
      return "";
    }
    List<String> parts = new ArrayList<>();
    String name = fullName(header.getString(Tag.PatientName));
    String mrn = header.getString(Tag.PatientID);
    String modality = header.getString(Tag.Modality);
    if (!name.isEmpty()) {
      parts.add(name);
    }
    if (mrn != null && !mrn.isBlank()) {
      parts.add("MRN " + mrn.trim()); // NON-NLS
    }
    imageDate(header).map(ImageDate::label).ifPresent(parts::add);
    if (modality != null && !modality.isBlank()) {
      parts.add(modality.trim());
    }
    return String.join("  |  ", parts);
  }

  private static String rangeLabel(Collection<ImageDate> dates) {
    if (dates.isEmpty()) {
      return "";
    }
    LocalDate first =
        dates.stream().map(ImageDate::day).min(Comparator.naturalOrder()).orElseThrow();
    LocalDate last =
        dates.stream().map(ImageDate::day).max(Comparator.naturalOrder()).orElseThrow();
    String range = first.equals(last) ? first.toString() : first + " to " + last; // NON-NLS
    return dates.stream().allMatch(ImageDate::acquired) ? range : "study " + range; // NON-NLS
  }

  private static String view(Attributes header) {
    for (int tag : new int[] {Tag.ViewPosition, Tag.SeriesDescription, Tag.ProtocolName}) {
      String value = header.getString(tag);
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
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
      return "";
    }
    Period age = Period.between(born, imageDay);
    if (age.getYears() > 0) {
      return age.getYears() + "Y";
    }
    return age.getMonths() > 0 ? age.getMonths() + "M" : age.getDays() + "D";
  }

  /**
   * Joins the parts within what file systems allow. The view goes first, then the name, and the MRN
   * only in the last resort: of two long names, the MRN is what tells the patients apart.
   */
  private static String assemble(
      String name, String mrn, String age, String date, String modality, String view) {
    String shortName = name;
    String shortMrn = mrn;
    String shortView = view;
    String joined = join(shortName, shortMrn, age, date, modality, shortView);
    while (tooLong(joined) && !shortView.isEmpty()) {
      shortView = dropLast(shortView);
      joined = join(shortName, shortMrn, age, date, modality, shortView);
    }
    while (tooLong(joined) && !shortName.isEmpty()) {
      shortName = dropLast(shortName);
      joined = join(shortName, shortMrn, age, date, modality, shortView);
    }
    while (tooLong(joined) && !shortMrn.isEmpty()) {
      shortMrn = dropLast(shortMrn);
      joined = join(shortName, shortMrn, age, date, modality, shortView);
    }
    return RESERVED_DEVICE.matcher(joined).matches() ? "_" + joined : joined;
  }

  private static String join(
      String name, String mrn, String age, String date, String modality, String view) {
    List<String> parts = new ArrayList<>();
    for (String part :
        new String[] {name, mrn.isEmpty() ? "" : "MRN " + mrn, age, date, modality, view}) {
      if (!part.isEmpty()) {
        parts.add(part);
      }
    }
    return String.join(" ", parts);
  }

  private static boolean tooLong(String text) {
    return text.length() > LONGEST_LABEL_CHARS
        || text.getBytes(StandardCharsets.UTF_8).length > LONGEST_LABEL_BYTES;
  }

  private static String dropLast(String text) {
    return trimEnds(text.substring(0, text.offsetByCodePoints(text.length(), -1)));
  }

  /**
   * Makes one field from a header safe as part of a file name on Windows, macOS and Linux. Headers
   * come from files and from the network, so they are untrusted. Letters of any language are kept.
   *
   * @return the cleaned text, empty for null
   */
  public static String sanitize(String raw, int maxCodePoints) {
    if (raw == null) {
      return "";
    }
    String text = Normalizer.normalize(raw, Normalizer.Form.NFC);
    StringBuilder out = new StringBuilder();
    text.codePoints().forEach(cp -> out.appendCodePoint(isUnsafe(cp) ? ' ' : cp));
    String clean = out.toString().replaceAll("\\s+", " ").replaceAll("\\.{2,}", ".");
    clean = trimEnds(truncate(trimEnds(clean), maxCodePoints));
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
}
