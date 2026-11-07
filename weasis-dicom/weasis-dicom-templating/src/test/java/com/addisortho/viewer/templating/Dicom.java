/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.templating;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomOutputStream;

/** Small synthetic DICOM objects for the tests: a DX X-ray and plans for it. No patient data. */
final class Dicom {

  static final String STUDY = "1.2.826.0.1.3680043.8.498.1";
  static final String SERIES = "1.2.826.0.1.3680043.8.498.1.1";
  static final String SOP = "1.2.826.0.1.3680043.8.498.1.1.1";

  private Dicom() {}

  static Attributes xray() {
    Attributes a = image(UID.DigitalXRayImageStorageForPresentation, SERIES, SOP);
    a.setString(Tag.Modality, VR.CS, "DX");
    a.setString(Tag.SeriesDescription, VR.LO, "Pelvis AP \"standing\"");
    return a;
  }

  static Attributes plan(String series, String sop) {
    Attributes a = image(UID.SecondaryCaptureImageStorage, series, sop);
    a.setString(Tag.Modality, VR.CS, "OT");
    a.setString(Tag.SeriesDescription, VR.LO, "Templating plan right hip (draft)");
    return a;
  }

  private static Attributes image(String sopClass, String series, String sop) {
    Attributes a = new Attributes();
    a.setString(Tag.SOPClassUID, VR.UI, sopClass);
    a.setString(Tag.SOPInstanceUID, VR.UI, sop);
    a.setString(Tag.StudyInstanceUID, VR.UI, STUDY);
    a.setString(Tag.SeriesInstanceUID, VR.UI, series);
    a.setString(Tag.PatientName, VR.PN, "TEMPLATING^PHANTOM");
    a.setString(Tag.PatientID, VR.LO, "AOC-SYN-TPL-01");
    a.setString(Tag.IssuerOfPatientID, VR.LO, "AOC");
    a.setString(Tag.InstanceNumber, VR.IS, "1");
    a.setInt(Tag.Rows, VR.US, 2);
    a.setInt(Tag.Columns, VR.US, 2);
    a.setInt(Tag.BitsAllocated, VR.US, 16);
    a.setInt(Tag.BitsStored, VR.US, 12);
    a.setInt(Tag.HighBit, VR.US, 11);
    a.setInt(Tag.PixelRepresentation, VR.US, 0);
    a.setInt(Tag.SamplesPerPixel, VR.US, 1);
    a.setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2");
    a.setBytes(Tag.PixelData, VR.OW, new byte[8]);
    return a;
  }

  static byte[] part10(Attributes ds) {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (DicomOutputStream out = new DicomOutputStream(bos, UID.ExplicitVRLittleEndian)) {
      out.writeDataset(ds.createFileMetaInformation(UID.ExplicitVRLittleEndian), ds);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return bos.toByteArray();
  }

  static byte[] withoutMeta(Attributes ds) {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (DicomOutputStream out = new DicomOutputStream(bos, UID.ImplicitVRLittleEndian)) {
      out.writeDataset(null, ds);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return bos.toByteArray();
  }
}
