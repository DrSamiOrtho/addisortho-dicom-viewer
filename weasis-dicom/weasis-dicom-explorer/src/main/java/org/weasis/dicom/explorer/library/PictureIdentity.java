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

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/**
 * Who a picture that is not DICOM shows and when it was taken, as a person stated it. An empty
 * value means unknown and stays unknown: nothing is ever filled in from the file's own dates.
 *
 * @param patientName as typed, in any script, or empty when unknown
 * @param patientId the record number, kept as text so that leading zeros survive, or empty
 * @param dateTaken null when unknown
 */
public record PictureIdentity(String patientName, String patientId, LocalDate dateTaken) {

  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT); // NON-NLS

  /** A value the user must correct; the message is a key of the module's messages. */
  public static final class Invalid extends Exception {
    public Invalid(String messageKey) {
      super(messageKey);
    }
  }

  /**
   * @param date year-month-day, or empty
   * @param today a later date is refused: a picture cannot have been taken tomorrow
   */
  public static PictureIdentity parse(String name, String id, String date, LocalDate today)
      throws Invalid {
    String patientName = clean(name);
    String patientId = clean(id);
    String text = date == null ? "" : date.strip();
    LocalDate taken = null;
    if (!text.isEmpty()) {
      try {
        taken = LocalDate.parse(text, DATE);
      } catch (DateTimeParseException e) {
        throw new Invalid("library.picture.date.invalid");
      }
      if (taken.isAfter(today)) {
        throw new Invalid("library.picture.date.future");
      }
    }
    return new PictureIdentity(patientName, patientId, taken);
  }

  private static String clean(String value) throws Invalid {
    String text = value == null ? "" : value.strip();
    if (text.chars().anyMatch(Character::isISOControl)) {
      throw new Invalid("library.picture.text.invalid");
    }
    return text;
  }

  /** Neither a name nor a record number: the picture would belong to nobody in particular. */
  public boolean isAnonymous() {
    return patientName.isEmpty() && patientId.isEmpty();
  }
}
