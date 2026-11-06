/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.stitching;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.ElementDictionary;
import org.dcm4che3.data.Sequence;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomOutputStream;
import org.dcm4che3.util.UIDUtils;

/**
 * Writes the stitch as a Secondary Capture in the pieces' patient and study (design section 7). The
 * header is written first, then the pixel data streamed strip by strip from the {@link Compositor}.
 */
final class StitchWriter {

  static final String SOFTWARE = "Addis Ortho Viewer long-leg stitch 0.1 (phase 1, draft)";
  static final String PRIVATE_CREATOR = "ADDIS ORTHO STITCH 1";
  static final int PRIVATE_DATA = 0x00711001;
  static final int SERIES_NUMBER = 903;

  private static final int[] PATIENT_STUDY = {
    Tag.SpecificCharacterSet,
    Tag.PatientName,
    Tag.PatientID,
    Tag.IssuerOfPatientID,
    Tag.PatientBirthDate,
    Tag.PatientSex,
    Tag.PatientAge,
    Tag.StudyInstanceUID,
    Tag.StudyDate,
    Tag.StudyTime,
    Tag.StudyID,
    Tag.AccessionNumber,
    Tag.ReferringPhysicianName,
    Tag.StudyDescription,
    Tag.InstitutionName
  };

  // Type 2 attributes of the Patient, General Study and General Series modules: present, empty when
  // unknown (Astra, code review P2)
  private static final int[] TYPE2 = {
    Tag.PatientName,
    Tag.PatientID,
    Tag.PatientBirthDate,
    Tag.PatientSex,
    Tag.StudyDate,
    Tag.StudyTime,
    Tag.ReferringPhysicianName,
    Tag.StudyID,
    Tag.AccessionNumber
  };

  private StitchWriter() {}

  /** The header of the output, without pixel data. */
  static Attributes header(Stitch s, Compositor c) {
    Attributes first = s.pieces.get(0).header;
    Attributes a = new Attributes();
    a.addSelected(first, PATIENT_STUDY);
    for (int tag : TYPE2) {
      if (!a.contains(tag)) {
        a.setNull(tag, ElementDictionary.vrOf(tag, null));
      }
    }
    String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmss"));
    a.setString(Tag.SOPClassUID, VR.UI, UID.SecondaryCaptureImageStorage);
    a.setString(Tag.SOPInstanceUID, VR.UI, UIDUtils.createUID());
    a.setString(Tag.SeriesInstanceUID, VR.UI, UIDUtils.createUID());
    a.setString(Tag.Modality, VR.CS, first.getString(Tag.Modality, "OT"));
    a.setInt(Tag.SeriesNumber, VR.IS, SERIES_NUMBER);
    a.setInt(Tag.InstanceNumber, VR.IS, 1);
    a.setString(Tag.SeriesDescription, VR.LO, s.seriesDescription());
    a.setString(Tag.ImageType, VR.CS, "DERIVED", "SECONDARY");
    a.setString(Tag.ConversionType, VR.CS, "WSD");
    a.setString(Tag.SeriesDate, VR.DA, now);
    a.setString(Tag.SeriesTime, VR.TM, time);
    a.setString(Tag.ContentDate, VR.DA, now);
    a.setString(Tag.ContentTime, VR.TM, time);
    a.setString(Tag.InstanceCreationDate, VR.DA, now);
    a.setString(Tag.InstanceCreationTime, VR.TM, time);
    a.setString(Tag.SecondaryCaptureDeviceManufacturer, VR.LO, "Addis Ortho Trading PLC");
    a.setString(Tag.SecondaryCaptureDeviceSoftwareVersions, VR.LO, SOFTWARE);
    a.setString(Tag.ImageComments, VR.LT, String.join(" ", s.labelLines()));

    a.setInt(Tag.SamplesPerPixel, VR.US, 1);
    a.setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2");
    a.setInt(Tag.Rows, VR.US, c.rows);
    a.setInt(Tag.Columns, VR.US, c.cols);
    a.setInt(Tag.BitsAllocated, VR.US, 16);
    a.setInt(Tag.BitsStored, VR.US, 16);
    a.setInt(Tag.HighBit, VR.US, 15);
    a.setInt(Tag.PixelRepresentation, VR.US, 0);
    a.setInt(Tag.PixelPaddingValue, VR.US, Compositor.PADDING);
    a.setString(Tag.PresentationLUTShape, VR.CS, "IDENTITY");
    a.setString(Tag.WindowCenter, VR.DS, fmt(c.windowCenter));
    a.setString(Tag.WindowWidth, VR.DS, fmt(c.windowWidth));

    if (s.hasScale()) {
      a.setString(Tag.PixelSpacing, VR.DS, fmt(c.mm), fmt(c.mm));
      boolean magnified = s.pieces.get(0).spacing.basis() == Spacing.Basis.MAGNIFIED;
      if (magnified) {
        a.setString(Tag.PixelSpacingCalibrationType, VR.CS, "GEOMETRY");
      }
      a.setString(
          Tag.PixelSpacingCalibrationDescription,
          VR.LO,
          trim(
              s.pieces.get(0).spacing.basis().words
                  + "; not calibrated to the patient plane; stitched draft",
              64));
    }

    copyIfAllAgree(s, a, Tag.PatientOrientation, VR.CS);
    if (!a.contains(Tag.PatientOrientation)) {
      a.setNull(Tag.PatientOrientation, VR.CS); // Type 2C: present, empty when not known
    }
    copyIfAllAgree(s, a, Tag.BodyPartExamined, VR.CS);
    copyIfAllAgree(s, a, Tag.ImageLaterality, VR.CS);
    copyIfAllAgree(s, a, Tag.Laterality, VR.CS);
    copyIfAllAgree(s, a, Tag.ViewPosition, VR.CS);
    String burned = burnedIn(s);
    if (burned != null) {
      a.setString(Tag.BurnedInAnnotation, VR.CS, burned);
    }

    a.setString(Tag.DerivationDescription, VR.ST, trim(s.derivation(), 1024));
    Attributes code = new Attributes(3);
    code.setString(Tag.CodeValue, VR.SH, "113090");
    code.setString(Tag.CodingSchemeDesignator, VR.SH, "DCM");
    code.setString(Tag.CodeMeaning, VR.LO, "Image stitching");
    a.newSequence(Tag.DerivationCodeSequence, 1).add(code);
    Sequence src = a.newSequence(Tag.SourceImageSequence, s.pieces.size());
    for (Piece p : s.pieces) {
      Attributes item = new Attributes(3);
      item.setString(Tag.ReferencedSOPClassUID, VR.UI, p.sopClassUid());
      item.setString(Tag.ReferencedSOPInstanceUID, VR.UI, p.sopInstanceUid());
      item.setString(Tag.SpatialLocationsPreserved, VR.CS, "NO");
      src.add(item);
    }
    a.setString(PRIVATE_CREATOR, PRIVATE_DATA, VR.LT, seamData(s, c));
    return a;
  }

  /** Writes the stitch to {@code file}. */
  static Attributes write(Stitch s, Path file, Compositor.Progress progress) throws IOException {
    Compositor c = new Compositor(s);
    Attributes a = header(s, c);
    Attributes fmi = a.createFileMetaInformation(UID.ExplicitVRLittleEndian);
    long bytes = (long) c.rows * c.cols * 2;
    if (bytes > Integer.MAX_VALUE) {
      throw new IOException("the stitch is too large to write");
    }
    try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16);
        DicomOutputStream dos = new DicomOutputStream(raw, UID.ExplicitVRLittleEndian)) {
      dos.writeDataset(fmi, a);
      dos.writeHeader(Tag.PixelData, VR.OW, (int) bytes);
      c.write(dos, progress);
    }
    return a;
  }

  /**
   * Machine-readable seam and source-region data (design section 7): each piece's pose on the
   * stitch frame and the output rows the seams assign it, where each seam lies, the rows where two
   * pieces are blended, and how sure each seam is. A piece may also fill a gap outside its assigned
   * rows where no other piece has image. JSON in a private LT element.
   */
  static String seamData(Stitch s, Compositor c) {
    StringBuilder sb = new StringBuilder("{\"version\":1,\"software\":\"").append(SOFTWARE);
    sb.append("\",\"status\":\"draft, ").append(s.method().word).append(", not reviewed\"");
    sb.append(",\"leg\":\"").append(s.choices.leg().word).append('"');
    sb.append(",\"projection\":\"").append(s.choices.projection()).append('"');
    sb.append(",\"position\":\"").append(s.choices.position()).append('"');
    sb.append(",\"matchedOn\":\"").append(s.choices.band().words()).append('"');
    sb.append(",\"outputMm\":").append(s.hasScale() ? fmt(c.mm) : "null");
    sb.append(",\"originMm\":[")
        .append(fmt(c.originX))
        .append(',')
        .append(fmt(c.originY))
        .append(']');
    sb.append(",\"pieces\":[");
    List<Rigid> poses = s.poses();
    for (int i = 0; i < s.pieces.size(); i++) {
      Piece p = s.pieces.get(i);
      Rigid pose = poses.get(i);
      int from = i == 0 ? 0 : c.seamRow(i - 1);
      int to = i == s.pieces.size() - 1 ? c.rows : c.seamRow(i);
      sb.append(i == 0 ? "" : ",")
          .append("{\"sopInstanceUid\":\"")
          .append(p.sopInstanceUid())
          .append("\",\"assignedRows\":[")
          .append(from)
          .append(',')
          .append(to)
          .append("],\"poseDeg\":")
          .append(fmt(pose.degrees()))
          .append(",\"poseMm\":[")
          .append(fmt(pose.tx()))
          .append(',')
          .append(fmt(pose.ty()))
          .append("],\"sizeMm\":[")
          .append(fmt(p.widthMm()))
          .append(',')
          .append(fmt(p.heightMm()))
          .append(']')
          .append(",\"spacing\":\"")
          .append(p.spacing.basis().name())
          .append("\"}");
    }
    sb.append("],\"seams\":[");
    for (int i = 0; i < s.seams.size(); i++) {
      Seam m = s.seams.get(i);
      sb.append(i == 0 ? "" : ",")
          .append("{\"row\":")
          .append(c.seamRow(i))
          .append(",\"blendRows\":[")
          .append(c.seamRow(i) - (int) Math.ceil(Stitch.FEATHER_MM / 2 / c.mm))
          .append(',')
          .append(c.seamRow(i) + (int) Math.ceil(Stitch.FEATHER_MM / 2 / c.mm))
          .append(']')
          .append(",\"method\":\"")
          .append(m.method().word)
          .append("\",\"status\":\"")
          .append(m.status().word)
          .append("\",\"rmsMm\":")
          .append(num(m.rmsMm()))
          .append(",\"rotationSigmaDeg\":")
          .append(num(m.rotationSigmaDeg()))
          .append(",\"shiftSigmaMm\":")
          .append(num(m.shiftSigmaMm()))
          .append(",\"scale\":")
          .append(num(m.scale()))
          .append(",\"pairs\":")
          .append(m.pairs().size())
          .append('}');
    }
    return sb.append("]}").toString();
  }

  private static void copyIfAllAgree(Stitch s, Attributes a, int tag, VR vr) {
    String[] v = s.pieces.get(0).header.getStrings(tag);
    if (v == null || v.length == 0) {
      return;
    }
    Predicate<Piece> same = p -> Arrays.equals(v, p.header.getStrings(tag));
    if (s.pieces.stream().allMatch(same)) {
      a.setString(tag, vr, v);
    }
  }

  // BurnedInAnnotation is about identifying text in the pixels (Astra): YES if any piece says so
  private static String burnedIn(Stitch s) {
    List<String> v =
        s.pieces.stream().map(p -> p.header.getString(Tag.BurnedInAnnotation)).toList();
    if (v.stream().anyMatch("YES"::equalsIgnoreCase)) {
      return "YES";
    }
    return v.stream()
            .allMatch(x -> Objects.equals(x == null ? null : x.toUpperCase(Locale.ROOT), "NO"))
        ? "NO"
        : null;
  }

  private static String fmt(double v) {
    String t = String.format(Locale.ROOT, "%.6f", v);
    t = t.contains(".") ? t.replaceAll("0+$", "").replaceAll("\\.$", "") : t;
    return t.length() > 16 ? t.substring(0, 16) : t;
  }

  private static String num(double v) {
    return Double.isFinite(v) ? fmt(v) : "null";
  }

  private static String trim(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max - 3) + "...";
  }
}
