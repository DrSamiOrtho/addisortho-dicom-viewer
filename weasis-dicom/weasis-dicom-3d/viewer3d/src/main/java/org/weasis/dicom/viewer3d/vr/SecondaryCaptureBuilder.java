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
package org.weasis.dicom.viewer3d.vr;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.ElementDictionary;
import org.dcm4che3.data.Sequence;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomOutputStream;
import org.dcm4che3.util.UIDUtils;

/**
 * Builds the DICOM Secondary Capture (RGB, uncompressed) of a 3D picture. It keeps the patient and
 * study of the source CT, so the picture joins that study, and gets its own series and instance
 * UIDs and a reference to the CT series it was rendered from.
 */
public final class SecondaryCaptureBuilder {

  public static final String SERIES_DESCRIPTION = "3D picture (draft)"; // NON-NLS
  public static final String DERIVATION_DESCRIPTION =
      "Addis Ortho DICOM Viewer 3D rendering; not for diagnosis"; // NON-NLS

  /** Series number given to every 3D picture, far above those of the acquisitions. */
  public static final int SERIES_NUMBER = 9000;

  /** Copied from the source; written empty when absent, as the IOD requires them (type 2). */
  private static final int[] REQUIRED_TAGS = {
    Tag.PatientName,
    Tag.PatientID,
    Tag.PatientBirthDate,
    Tag.PatientSex,
    Tag.StudyDate,
    Tag.StudyTime,
    Tag.AccessionNumber,
    Tag.ReferringPhysicianName,
    Tag.StudyID
  };

  /** Copied from the source only when present. */
  private static final int[] OPTIONAL_TAGS = {Tag.IssuerOfPatientID, Tag.StudyDescription};

  /** An image of the source series, referenced by the picture. */
  public record SourceInstance(String sopClassUID, String sopInstanceUID) {}

  private SecondaryCaptureBuilder() {}

  /**
   * Builds the dataset.
   *
   * @param source attributes of an image of the source CT series (patient, study and series)
   * @param sourceInstances images of the source series, listed in the reference to it
   * @param rgb interleaved RGB pixels, top row first, {@code width * height * 3} bytes
   * @param now creation date and time
   */
  public static Attributes build(
      Attributes source,
      List<SourceInstance> sourceInstances,
      int width,
      int height,
      byte[] rgb,
      Date now) {
    if (width <= 0 || height <= 0 || rgb == null || rgb.length != width * height * 3) {
      throw new IllegalArgumentException("RGB pixels do not match " + width + "x" + height);
    }
    Attributes attrs = new Attributes();
    // UTF-8, so that any patient name of the source can be written
    attrs.setString(Tag.SpecificCharacterSet, VR.CS, "ISO_IR 192"); // NON-NLS
    copyStrings(source, attrs);

    String studyUID = source.getString(Tag.StudyInstanceUID);
    attrs.setString(
        Tag.StudyInstanceUID, VR.UI, studyUID == null ? UIDUtils.createUID() : studyUID);
    attrs.setString(Tag.SOPClassUID, VR.UI, UID.SecondaryCaptureImageStorage);
    attrs.setString(Tag.SOPInstanceUID, VR.UI, UIDUtils.createUID());
    attrs.setString(Tag.SeriesInstanceUID, VR.UI, UIDUtils.createUID());
    attrs.setDate(Tag.InstanceCreationDateAndTime, now);

    // General Series, General Equipment, SC Equipment
    attrs.setString(Tag.Modality, VR.CS, "OT"); // NON-NLS
    attrs.setInt(Tag.SeriesNumber, VR.IS, SERIES_NUMBER);
    attrs.setString(Tag.SeriesDescription, VR.LO, SERIES_DESCRIPTION);
    attrs.setDate(Tag.SeriesDateAndTime, now);
    attrs.setNull(Tag.Manufacturer, VR.LO);
    attrs.setString(Tag.ConversionType, VR.CS, "WSD"); // NON-NLS

    // General Image and General Reference
    attrs.setInt(Tag.InstanceNumber, VR.IS, 1);
    attrs.setNull(Tag.PatientOrientation, VR.CS);
    attrs.setDate(Tag.ContentDateAndTime, now);
    attrs.setString(Tag.ImageType, VR.CS, "DERIVED", "SECONDARY"); // NON-NLS
    attrs.setString(Tag.DerivationDescription, VR.ST, DERIVATION_DESCRIPTION);
    attrs.setString(Tag.BurnedInAnnotation, VR.CS, "NO"); // NON-NLS
    attrs.setString(Tag.LossyImageCompression, VR.CS, "00"); // NON-NLS
    addSeriesReference(attrs, source.getString(Tag.SeriesInstanceUID), sourceInstances);

    // Image Pixel
    attrs.setInt(Tag.SamplesPerPixel, VR.US, 3);
    attrs.setString(Tag.PhotometricInterpretation, VR.CS, "RGB"); // NON-NLS
    attrs.setInt(Tag.PlanarConfiguration, VR.US, 0);
    attrs.setInt(Tag.Rows, VR.US, height);
    attrs.setInt(Tag.Columns, VR.US, width);
    attrs.setInt(Tag.BitsAllocated, VR.US, 8);
    attrs.setInt(Tag.BitsStored, VR.US, 8);
    attrs.setInt(Tag.HighBit, VR.US, 7);
    attrs.setInt(Tag.PixelRepresentation, VR.US, 0);
    attrs.setBytes(Tag.PixelData, VR.OB, rgb);
    return attrs;
  }

  /** Writes the dataset as a DICOM file (Explicit VR Little Endian). */
  public static void write(Attributes dataset, Path file) throws IOException {
    try (DicomOutputStream dos =
        new DicomOutputStream(
            new BufferedOutputStream(Files.newOutputStream(file)), UID.ExplicitVRLittleEndian)) {
      dos.writeDataset(dataset.createFileMetaInformation(UID.ExplicitVRLittleEndian), dataset);
    }
  }

  // Decoded with the source's character set, re-encoded in UTF-8 when written.
  private static void copyStrings(Attributes source, Attributes target) {
    for (int tag : REQUIRED_TAGS) {
      VR vr = ElementDictionary.vrOf(tag, null);
      String[] values = source.getStrings(tag);
      if (values == null || values.length == 0) {
        target.setNull(tag, vr);
      } else {
        target.setString(tag, vr, values);
      }
    }
    for (int tag : OPTIONAL_TAGS) {
      String[] values = source.getStrings(tag);
      if (values != null && values.length > 0) {
        target.setString(tag, ElementDictionary.vrOf(tag, null), values);
      }
    }
  }

  // Common Instance Reference: the CT series and its images.
  private static void addSeriesReference(
      Attributes attrs, String seriesUID, List<SourceInstance> instances) {
    if (seriesUID == null) {
      return;
    }
    Sequence series = attrs.newSequence(Tag.ReferencedSeriesSequence, 1);
    Attributes item = new Attributes(2);
    item.setString(Tag.SeriesInstanceUID, VR.UI, seriesUID);
    Sequence images = item.newSequence(Tag.ReferencedInstanceSequence, instances.size());
    for (SourceInstance instance : instances) {
      Attributes ref = new Attributes(2);
      ref.setString(Tag.ReferencedSOPClassUID, VR.UI, instance.sopClassUID());
      ref.setString(Tag.ReferencedSOPInstanceUID, VR.UI, instance.sopInstanceUID());
      images.add(ref);
    }
    series.add(item);
  }
}
