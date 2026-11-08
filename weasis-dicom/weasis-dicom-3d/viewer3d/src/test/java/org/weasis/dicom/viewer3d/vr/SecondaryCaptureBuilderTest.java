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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Sequence;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.util.UIDUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.weasis.dicom.viewer3d.vr.SecondaryCaptureBuilder.SourceInstance;

/** Synthetic CT attributes only: no real patient data. */
class SecondaryCaptureBuilderTest {

  private static final String STUDY_UID = "1.2.826.0.1.3680043.2.1125.1.1";
  private static final String CT_SERIES_UID = "1.2.826.0.1.3680043.2.1125.1.2";
  private static final String CT_SOP_UID = "1.2.826.0.1.3680043.2.1125.1.3";

  private static Attributes syntheticCt() {
    Attributes ct = new Attributes();
    ct.setString(Tag.SpecificCharacterSet, VR.CS, "ISO_IR 100");
    ct.setString(Tag.PatientName, VR.PN, "Test^Synthétique");
    ct.setString(Tag.PatientID, VR.LO, "SYN-0001");
    ct.setString(Tag.IssuerOfPatientID, VR.LO, "TEST-ISSUER");
    ct.setString(Tag.PatientBirthDate, VR.DA, "19700101");
    ct.setString(Tag.PatientSex, VR.CS, "O");
    ct.setString(Tag.StudyInstanceUID, VR.UI, STUDY_UID);
    ct.setString(Tag.StudyDate, VR.DA, "20260101");
    ct.setString(Tag.StudyTime, VR.TM, "101500");
    ct.setString(Tag.AccessionNumber, VR.SH, "ACC1");
    ct.setString(Tag.ReferringPhysicianName, VR.PN, "Ref^Doctor");
    ct.setString(Tag.StudyID, VR.SH, "S1");
    ct.setString(Tag.StudyDescription, VR.LO, "Synthetic knee");
    ct.setString(Tag.SeriesInstanceUID, VR.UI, CT_SERIES_UID);
    ct.setString(Tag.SOPInstanceUID, VR.UI, CT_SOP_UID);
    ct.setString(Tag.SOPClassUID, VR.UI, UID.CTImageStorage);
    ct.setString(Tag.Modality, VR.CS, "CT");
    ct.setString(Tag.SeriesDescription, VR.LO, "Bone 3 mm");
    ct.setString(Tag.InstitutionName, VR.LO, "Not copied");
    return ct;
  }

  private static Attributes build(Attributes ct) {
    byte[] rgb = new byte[2 * 3 * 3];
    rgb[0] = (byte) 255;
    return SecondaryCaptureBuilder.build(
        ct, List.of(new SourceInstance(UID.CTImageStorage, CT_SOP_UID)), 3, 2, rgb, new Date());
  }

  @Test
  void patientAndStudyAreCopiedFromTheCt() {
    Attributes ct = syntheticCt();
    Attributes sc = build(ct);
    int[] copied = {
      Tag.PatientName,
      Tag.PatientID,
      Tag.IssuerOfPatientID,
      Tag.PatientBirthDate,
      Tag.PatientSex,
      Tag.StudyInstanceUID,
      Tag.StudyDate,
      Tag.StudyTime,
      Tag.AccessionNumber,
      Tag.ReferringPhysicianName,
      Tag.StudyID,
      Tag.StudyDescription
    };
    for (int tag : copied) {
      assertEquals(ct.getString(tag), sc.getString(tag), Integer.toHexString(tag));
    }
    assertAll(
        () -> assertEquals("Test^Synthétique", sc.getString(Tag.PatientName)),
        () -> assertEquals("ISO_IR 192", sc.getString(Tag.SpecificCharacterSet)),
        () -> assertFalse(sc.contains(Tag.InstitutionName)));
  }

  @Test
  void seriesAndInstanceGetNewUids() {
    Attributes ct = syntheticCt();
    Attributes first = build(ct);
    Attributes second = build(ct);
    String seriesUID = first.getString(Tag.SeriesInstanceUID);
    String sopUID = first.getString(Tag.SOPInstanceUID);
    assertAll(
        () -> assertNotNull(seriesUID),
        () -> assertNotNull(sopUID),
        () -> assertNotEquals(CT_SERIES_UID, seriesUID),
        () -> assertNotEquals(CT_SOP_UID, sopUID),
        () -> assertNotEquals(seriesUID, sopUID),
        () -> assertNotEquals(seriesUID, second.getString(Tag.SeriesInstanceUID)),
        () -> assertNotEquals(sopUID, second.getString(Tag.SOPInstanceUID)),
        () -> assertTrue(UIDUtils.isValid(seriesUID)),
        () -> assertTrue(UIDUtils.isValid(sopUID)));
  }

  @Test
  void secondaryCaptureDescription() {
    Attributes sc = build(syntheticCt());
    assertAll(
        () -> assertEquals(UID.SecondaryCaptureImageStorage, sc.getString(Tag.SOPClassUID)),
        () -> assertEquals("1.2.840.10008.5.1.4.1.1.7", sc.getString(Tag.SOPClassUID)),
        () -> assertEquals("OT", sc.getString(Tag.Modality)),
        () -> assertEquals("WSD", sc.getString(Tag.ConversionType)),
        () ->
            assertArrayEquals(new String[] {"DERIVED", "SECONDARY"}, sc.getStrings(Tag.ImageType)),
        () -> assertEquals("3D picture (draft)", sc.getString(Tag.SeriesDescription)),
        () ->
            assertEquals(
                "Addis Ortho DICOM Viewer 3D rendering; not for diagnosis",
                sc.getString(Tag.DerivationDescription)),
        () -> assertNotNull(sc.getDate(Tag.ContentDateAndTime)));
  }

  @Test
  void referencesTheCtSeries() {
    Attributes sc = build(syntheticCt());
    Sequence series = sc.getSequence(Tag.ReferencedSeriesSequence);
    assertNotNull(series);
    assertEquals(1, series.size());
    Attributes item = series.getFirst();
    assertEquals(CT_SERIES_UID, item.getString(Tag.SeriesInstanceUID));
    Attributes image = item.getSequence(Tag.ReferencedInstanceSequence).getFirst();
    assertEquals(UID.CTImageStorage, image.getString(Tag.ReferencedSOPClassUID));
    assertEquals(CT_SOP_UID, image.getString(Tag.ReferencedSOPInstanceUID));
  }

  @Test
  void rgbPixelModule() {
    Attributes sc = build(syntheticCt());
    assertAll(
        () -> assertEquals(3, sc.getInt(Tag.SamplesPerPixel, 0)),
        () -> assertEquals("RGB", sc.getString(Tag.PhotometricInterpretation)),
        () -> assertEquals(0, sc.getInt(Tag.PlanarConfiguration, -1)),
        () -> assertEquals(2, sc.getInt(Tag.Rows, 0)),
        () -> assertEquals(3, sc.getInt(Tag.Columns, 0)),
        () -> assertEquals(8, sc.getInt(Tag.BitsAllocated, 0)),
        () -> assertEquals(8, sc.getInt(Tag.BitsStored, 0)),
        () -> assertEquals(7, sc.getInt(Tag.HighBit, 0)),
        () -> assertEquals(0, sc.getInt(Tag.PixelRepresentation, -1)),
        () -> assertEquals(18, sc.getBytes(Tag.PixelData).length));
  }

  @Test
  void missingRequiredTagsAreWrittenEmptyAndMissingOptionalOnesAreLeftOut() {
    Attributes ct = new Attributes();
    ct.setString(Tag.SeriesInstanceUID, VR.UI, CT_SERIES_UID);
    Attributes sc = build(ct);
    assertAll(
        () -> assertTrue(sc.contains(Tag.PatientName)),
        () -> assertTrue(sc.contains(Tag.PatientID)),
        () -> assertTrue(sc.contains(Tag.StudyDate)),
        () -> assertTrue(sc.contains(Tag.AccessionNumber)),
        () -> assertNull(sc.getString(Tag.PatientName)),
        () -> assertFalse(sc.contains(Tag.IssuerOfPatientID)),
        () -> assertFalse(sc.contains(Tag.StudyDescription)),
        // Without a study the picture cannot join one; it still needs a valid UID.
        () -> assertTrue(UIDUtils.isValid(sc.getString(Tag.StudyInstanceUID))));
  }

  @Test
  void pixelsMustMatchTheSize() {
    Attributes ct = syntheticCt();
    List<SourceInstance> none = List.of();
    Date now = new Date();
    assertThrows(
        IllegalArgumentException.class,
        () -> SecondaryCaptureBuilder.build(ct, none, 3, 2, new byte[17], now));
    assertThrows(
        IllegalArgumentException.class,
        () -> SecondaryCaptureBuilder.build(ct, none, 0, 2, new byte[0], now));
  }

  @Test
  void writtenFileReadsBack(@TempDir Path dir) throws Exception {
    Attributes sc = build(syntheticCt());
    Path file = dir.resolve("picture.dcm");
    SecondaryCaptureBuilder.write(sc, file);

    try (DicomInputStream in = new DicomInputStream(file.toFile())) {
      Attributes fmi = in.readFileMetaInformation();
      Attributes read = in.readDataset();
      assertAll(
          () -> assertEquals(UID.ExplicitVRLittleEndian, fmi.getString(Tag.TransferSyntaxUID)),
          () ->
              assertEquals(
                  UID.SecondaryCaptureImageStorage, fmi.getString(Tag.MediaStorageSOPClassUID)),
          () -> assertEquals(sc.getString(Tag.SOPInstanceUID), read.getString(Tag.SOPInstanceUID)),
          () -> assertEquals("Test^Synthétique", read.getString(Tag.PatientName)),
          () -> assertEquals(STUDY_UID, read.getString(Tag.StudyInstanceUID)),
          () -> assertArrayEquals(sc.getBytes(Tag.PixelData), read.getBytes(Tag.PixelData)));
    }
  }
}
