/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer.pr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.weasis.core.ui.model.GraphicModel;
import org.weasis.core.ui.model.ReferencedImage;
import org.weasis.core.ui.model.ReferencedSeries;
import org.weasis.core.ui.model.graphic.Graphic;
import org.weasis.core.ui.model.graphic.imp.AnnotationGraphic;
import org.weasis.core.ui.model.graphic.imp.line.LineGraphic;
import org.weasis.core.ui.model.imp.XmlGraphicModel;
import org.weasis.core.ui.model.layer.LayerType;
import org.weasis.core.ui.model.layer.imp.DefaultLayer;

/** Marks saved as a presentation state of one image, and read back as drawings. */
class SavedMarksWriteTest {
  private static final String STUDY = "2.25.100";
  private static final String SERIES = "2.25.101";
  private static final String IMAGE = "2.25.102";
  private static final String BIOPSY = "የባዮፕሲ ቦታ"; // "biopsy site"

  @TempDir Path tmp;

  private static Attributes image() {
    Attributes a = new Attributes();
    a.setString(Tag.SpecificCharacterSet, VR.CS, "ISO_IR 192");
    a.setString(Tag.SOPClassUID, VR.UI, UID.DigitalXRayImageStorageForPresentation);
    a.setString(Tag.SOPInstanceUID, VR.UI, IMAGE);
    a.setString(Tag.StudyInstanceUID, VR.UI, STUDY);
    a.setString(Tag.SeriesInstanceUID, VR.UI, SERIES);
    a.setString(Tag.PatientName, VR.PN, "ፈተና^ሰላም");
    a.setString(Tag.PatientID, VR.LO, "000123");
    a.setString(Tag.Modality, VR.CS, "DX");
    a.setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2");
    a.setInt(Tag.Rows, VR.US, 1024);
    a.setInt(Tag.Columns, VR.US, 768);
    a.setInt(Tag.SamplesPerPixel, VR.US, 1);
    return a;
  }

  private static GraphicModel marks(boolean withGraphics) throws Exception {
    XmlGraphicModel model = new XmlGraphicModel();
    model.setReferencedSeries(
        List.of(new ReferencedSeries(SERIES, List.of(new ReferencedImage(IMAGE)))));
    if (withGraphics) {
      Graphic line =
          new LineGraphic()
              .buildGraphic(List.of(new Point2D.Double(10, 20), new Point2D.Double(200, 300)));
      line.setLayer(new DefaultLayer(LayerType.MEASURE));
      model.addGraphic(line);
      AnnotationGraphic note = new AnnotationGraphic();
      note.setPts(List.of(new Point2D.Double(400, 400), new Point2D.Double(450, 420)));
      note.setLabels(new String[] {BIOPSY});
      note.setLayer(new DefaultLayer(LayerType.ANNOTATION));
      model.addGraphic(note);
    }
    return model;
  }

  private Attributes writeAndRead(GraphicModel model) throws IOException {
    Path file = tmp.resolve("pr.dcm");
    DicomPrSerializer.writeLabelled(
        model,
        image(),
        file.toFile(),
        "2.25.200",
        "2.25.201",
        pr -> pr.setString(Tag.ContentLabel, VR.CS, "AO_MARKS"));
    try (DicomInputStream in = new DicomInputStream(file.toFile())) {
      return in.readDataset();
    }
  }

  private static String referencedImage(Attributes pr) {
    return pr.getNestedDataset(Tag.ReferencedSeriesSequence)
        .getNestedDataset(Tag.ReferencedImageSequence)
        .getString(Tag.ReferencedSOPInstanceUID);
  }

  @Test
  void marksKeepTheirShapesLabelAndPatientAndLeaveTheImageAlone() throws Exception {
    Attributes pr = writeAndRead(marks(true));

    assertEquals("AO_MARKS", pr.getString(Tag.ContentLabel));
    assertEquals(UID.GrayscaleSoftcopyPresentationStateStorage, pr.getString(Tag.SOPClassUID));
    assertEquals(STUDY, pr.getString(Tag.StudyInstanceUID));
    assertEquals("000123", pr.getString(Tag.PatientID));
    assertEquals("ፈተና^ሰላም", pr.getString(Tag.PatientName));
    assertEquals(IMAGE, referencedImage(pr));
    assertTrue(pr.getString(Tag.SOPInstanceUID).startsWith("2.25.201"));

    GraphicModel back = PrGraphicUtil.getPresentationModel(pr);
    assertNotNull(back);
    assertEquals(2, back.getModels().size());
    AnnotationGraphic note =
        (AnnotationGraphic)
            back.getModels().stream()
                .filter(AnnotationGraphic.class::isInstance)
                .findFirst()
                .orElseThrow();
    assertArrayEquals(new String[] {BIOPSY}, note.getLabels());
  }

  @Test
  void anEmptySaveStillNamesItsImageSoItReplacesEarlierMarks() throws Exception {
    Attributes pr = writeAndRead(marks(false));

    assertEquals(IMAGE, referencedImage(pr));
    GraphicModel back = PrGraphicUtil.getPresentationModel(pr);
    assertTrue(back == null || back.getModels().isEmpty());
  }
}
