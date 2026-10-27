/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.stitching;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StitchWriterTest {

  private static final double W = 200;
  private static final double H = 300;

  @TempDir Path dir;

  private static Attributes header(int i, double spacing, boolean mono1) {
    Attributes a = new Attributes();
    a.setString(Tag.PatientName, VR.PN, "Test^Phantom");
    a.setString(Tag.PatientID, VR.LO, "P1");
    a.setString(Tag.StudyInstanceUID, VR.UI, "2.25.100");
    a.setString(Tag.SeriesInstanceUID, VR.UI, "2.25.10" + i);
    a.setString(Tag.SOPInstanceUID, VR.UI, "2.25.20" + i);
    a.setString(Tag.SOPClassUID, VR.UI, UID.ComputedRadiographyImageStorage);
    a.setString(Tag.Modality, VR.CS, "CR");
    a.setString(Tag.PhotometricInterpretation, VR.CS, mono1 ? "MONOCHROME1" : "MONOCHROME2");
    a.setString(Tag.PixelSpacing, VR.DS, Double.toString(spacing), Double.toString(spacing));
    a.setString(Tag.BodyPartExamined, VR.CS, "LEG");
    a.setString(Tag.BurnedInAnnotation, VR.CS, "NO");
    return a;
  }

  private record Built(Stitch stitch, List<Phantom.Cut> cuts, Phantom phantom) {}

  private static Built build() throws IOException {
    Phantom leg = Phantom.leg(300, 900, 21);
    List<Phantom.Cut> cuts =
        List.of(
            new Phantom.Cut(new Rigid(0, 50, 0), W, H, 0.3, 1, 0),
            new Phantom.Cut(new Rigid(Math.toRadians(0.7), 54, 250), W, H, 0.3, 1.4, 0.2),
            new Phantom.Cut(new Rigid(Math.toRadians(-0.5), 47, 500), W, H, 0.35, 0.8, -0.1));
    List<Piece> pieces = new ArrayList<>();
    for (int i = 0; i < cuts.size(); i++) {
      Phantom.Cut c = cuts.get(i);
      boolean mono1 = i == 2;
      float[] v = leg.piece(c, 0.01, 30 + i);
      if (mono1) {
        for (int k = 0; k < v.length; k++) {
          v[k] = -v[k];
        }
      }
      int w = (int) Math.round(c.widthMm() / c.spacingMm());
      int h = (int) Math.round(c.heightMm() / c.spacingMm());
      Piece p = new Piece(header(i, c.spacingMm(), mono1), w, h, v::clone);
      p.prepare();
      pieces.add(p);
    }
    List<Seam> seams = new ArrayList<>();
    for (int i = 0; i + 1 < pieces.size(); i++) {
      seams.add(
          SeamMatcher.match(
              pieces.get(i).match(), pieces.get(i + 1).match(), SeamMatcher.Band.WHOLE));
    }
    Stitch s =
        new Stitch(
            pieces,
            seams,
            new Stitch.Choices("AP", "standing", Stitch.Leg.LEFT, SeamMatcher.Band.WHOLE));
    return new Built(s, cuts, leg);
  }

  @Test
  void writesADraftSecondaryCaptureThatMatchesThePhantom() throws IOException {
    Built b = build();
    Stitch s = b.stitch();
    assertFalse(s.anyFailed(), () -> s.seams.toString());
    assertTrue(s.hasScale());
    Path file = dir.resolve("stitch.dcm");
    Attributes written = StitchWriter.write(s, file, (done, total) -> true);

    Attributes read;
    byte[] pixels;
    try (DicomInputStream in = new DicomInputStream(file.toFile())) {
      read = in.readDataset();
      pixels = read.getBytes(Tag.PixelData);
    }
    assertEquals(UID.SecondaryCaptureImageStorage, read.getString(Tag.SOPClassUID));
    assertEquals("CR", read.getString(Tag.Modality));
    assertEquals("2.25.100", read.getString(Tag.StudyInstanceUID));
    assertEquals("Test^Phantom", read.getString(Tag.PatientName));
    assertNotEquals("2.25.100", read.getString(Tag.SeriesInstanceUID));
    assertArrayEquals(new String[] {"DERIVED", "SECONDARY"}, read.getStrings(Tag.ImageType));
    assertEquals(
        "Long leg stitched (draft, automatic, not reviewed)",
        read.getString(Tag.SeriesDescription));
    assertEquals(903, read.getInt(Tag.SeriesNumber, 0));
    assertEquals("MONOCHROME2", read.getString(Tag.PhotometricInterpretation));
    assertEquals(0.3, read.getDouble(Tag.PixelSpacing, 0), 1e-9);
    assertNull(read.getString(Tag.ImagerPixelSpacing));
    assertNull(
        read.getString(Tag.PixelSpacingCalibrationType), "PixelSpacing as stated is not GEOMETRY");
    assertEquals("LEG", read.getString(Tag.BodyPartExamined));
    assertEquals("NO", read.getString(Tag.BurnedInAnnotation));
    assertEquals(65535, read.getInt(Tag.PixelPaddingValue, 0));
    assertEquals(
        "113090", read.getNestedDataset(Tag.DerivationCodeSequence).getString(Tag.CodeValue));
    assertEquals(3, read.getSequence(Tag.SourceImageSequence).size());
    assertEquals(
        "NO",
        read.getNestedDataset(Tag.SourceImageSequence).getString(Tag.SpatialLocationsPreserved));
    assertTrue(read.getString(Tag.DerivationDescription).contains("Left leg"));
    String json =
        read.getString(StitchWriter.PRIVATE_CREATOR, StitchWriter.PRIVATE_DATA, VR.LT, null);
    assertTrue(json.contains("\"seams\":[{\"row\":"), json);
    assertTrue(json.contains("\"leg\":\"Left\""), json);

    int rows = read.getInt(Tag.Rows, 0);
    int cols = read.getInt(Tag.Columns, 0);
    assertEquals(written.getInt(Tag.Rows, 0), rows);
    assertEquals((long) rows * cols * 2, pixels.length);
    assertTrue(rows * 0.3 > 780, "three pieces of 300 mm with overlaps span more than 780 mm");

    // geometry and exposure: one linear map from phantom to output fits everywhere in the leg
    var px = ByteBuffer.wrap(pixels).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
    Rigid frameToCanvas = b.cuts().get(0).pose();
    Compositor c0 = new Compositor(s);
    assertEquals(c0.rows, rows, "the label strip is part of the rows");
    List<double[]> pairs = new ArrayList<>();
    for (int r = 200; r < rows - 200; r += 7) {
      for (int c = cols / 4; c < cols * 3 / 4; c += 5) {
        int v = px.get(r * cols + c) & 0xffff;
        if (v == 65535) {
          continue;
        }
        double[] q =
            frameToCanvas.apply(c0.originX + (c + 0.5) * 0.3, c0.originY + (r + 0.5) * 0.3);
        float truth = b.phantom().canvas.sample(q[0], q[1]);
        if (!Float.isNaN(truth)) {
          pairs.add(new double[] {truth, v});
        }
      }
    }
    assertTrue(pairs.size() > 1000);
    assertTrue(correlation(pairs) > 0.97, "correlation " + correlation(pairs));
  }

  @Test
  void spacingFromDifferentSourcesGivesNoScale() throws IOException {
    Built b = build();
    Attributes h = b.stitch().pieces.get(1).header;
    h.remove(Tag.PixelSpacing);
    h.setString(Tag.ImagerPixelSpacing, VR.DS, "0.3", "0.3");
    Piece changed =
        new Piece(h, b.stitch().pieces.get(1).width, b.stitch().pieces.get(1).height, () -> null);
    List<Piece> pieces = new ArrayList<>(b.stitch().pieces);
    pieces.set(1, changed);
    Stitch s = new Stitch(pieces, b.stitch().seams, b.stitch().choices);
    assertFalse(s.hasScale());
    assertTrue(s.noScaleReason().orElseThrow().contains("different sources"));
    assertTrue(s.labelLines().stream().anyMatch(l -> l.startsWith("No scale")));
  }

  @Test
  void theLabelWrapsOnANarrowFilmAndKeepsEveryWord() {
    var lines =
        List.of(
            "Stitched draft, semi-automatic, not reviewed.",
            "Right leg. Lengths and angles across seams unverified.");
    var o = Compositor.Overlay.place(lines, 0.3, 300);
    assertTrue(o.w() <= 300, "fits the 90 mm wide image");
    assertTrue(o.strip() >= o.h(), "the label has its own strip above the image");
    java.awt.FontMetrics fm =
        new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_BYTE_GRAY)
            .createGraphics()
            .getFontMetrics(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 12));
    List<String> wrapped = Compositor.Overlay.wrap(lines.get(1), fm, 120);
    assertTrue(wrapped.size() > 1);
    assertEquals(lines.get(1), String.join(" ", wrapped));
  }

  @Test
  void cancellingStopsTheWrite() throws IOException {
    Stitch s = build().stitch();
    Path file = dir.resolve("cancelled.dcm");
    org.junit.jupiter.api.Assertions.assertThrows(
        java.util.concurrent.CancellationException.class,
        () -> StitchWriter.write(s, file, (done, total) -> false));
    assertTrue(Files.exists(file));
  }

  private static double correlation(List<double[]> p) {
    double sx = 0;
    double sy = 0;
    double sxx = 0;
    double syy = 0;
    double sxy = 0;
    for (double[] q : p) {
      sx += q[0];
      sy += q[1];
      sxx += q[0] * q[0];
      syy += q[1] * q[1];
      sxy += q[0] * q[1];
    }
    int n = p.size();
    return (sxy - sx * sy / n) / Math.sqrt((sxx - sx * sx / n) * (syy - sy * sy / n));
  }
}
