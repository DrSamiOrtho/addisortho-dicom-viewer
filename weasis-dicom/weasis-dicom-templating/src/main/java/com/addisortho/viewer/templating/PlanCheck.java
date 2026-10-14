/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.templating;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.regex.Pattern;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.io.DicomInputStream.IncludeBulkData;

/**
 * Whether a plan the page sends belongs to the X-ray it was handed: a DICOM Secondary Capture with a
 * picture, of the same patient and study, with its own new instance and series.
 */
final class PlanCheck {

  static final Pattern UID_TEXT = Pattern.compile("[0-9]+(\\.[0-9]+)*");

  /** The outcome: accepted with its UIDs, or refused with an HTTP status and the reason in words. */
  record Result(int status, String error, String sop, String series) {
    boolean accepted() {
      return status == 200;
    }

    static Result refuse(int status, String error) {
      return new Result(status, error, null, null);
    }
  }

  private PlanCheck() {}

  static Result check(byte[] body, Handover h) {
    Attributes fmi;
    Attributes ds;
    boolean picture;
    try (DicomInputStream in = new DicomInputStream(new ByteArrayInputStream(body))) {
      in.setIncludeBulkData(IncludeBulkData.NO);
      fmi = in.readFileMetaInformation();
      ds = in.readDatasetUntilPixelData();
      picture = in.tag() == Tag.PixelData;
    } catch (IOException | RuntimeException e) {
      return Result.refuse(415, "not a DICOM file");
    }
    if (fmi == null) {
      return Result.refuse(415, "not a DICOM file");
    }
    if (!UID.SecondaryCaptureImageStorage.equals(ds.getString(Tag.SOPClassUID))) {
      return Result.refuse(415, "not a templating plan (Secondary Capture)");
    }
    if (!same(ds, Tag.StudyInstanceUID, h.study())) {
      return Result.refuse(422, "the plan is not for this X-ray (StudyInstanceUID differs)");
    }
    if (!same(ds, Tag.PatientID, h.patientId())) {
      return Result.refuse(422, "the plan is not for this X-ray (PatientID differs)");
    }
    if (!same(ds, Tag.IssuerOfPatientID, h.issuer())) {
      return Result.refuse(422, "the plan is not for this X-ray (IssuerOfPatientID differs)");
    }
    String sop = Handover.text(ds, Tag.SOPInstanceUID);
    String series = Handover.text(ds, Tag.SeriesInstanceUID);
    if (!isUid(sop) || !isUid(series) || !sop.equals(fmi.getString(Tag.MediaStorageSOPInstanceUID))) {
      return Result.refuse(422, "the plan has no valid instance or series UID");
    }
    if (sop.equals(h.sop()) || series.equals(h.series())) {
      return Result.refuse(422, "the plan has the X-ray's own instance or series UID");
    }
    if (!picture) {
      return Result.refuse(422, "the plan has no picture");
    }
    return new Result(200, null, sop, series);
  }

  static boolean isUid(String s) {
    return s != null && s.length() <= 64 && UID_TEXT.matcher(s).matches();
  }

  private static boolean same(Attributes ds, int tag, String expected) {
    return Handover.text(ds, tag).equals(expected);
  }
}
