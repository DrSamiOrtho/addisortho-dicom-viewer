/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.stitching;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.junit.jupiter.api.Test;

/**
 * The two-band blend (case LL-001, 7 Nov 2026): two exposures whose processing differs so that no
 * single gain and offset joins them (soft tissue brighter, bone darker in the lower piece) must
 * meet without a step at the cut; the tone difference is spread over the tone feather instead.
 */
class CompositorTest {

  private static final double W = 200;
  private static final double H = 300;

  private static Attributes header(int i) {
    Attributes a = new Attributes();
    a.setString(Tag.SOPInstanceUID, VR.UI, "2.25.30" + i);
    a.setString(Tag.Modality, VR.CS, "DX");
    a.setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2");
    a.setString(Tag.PixelSpacing, VR.DS, "1", "1");
    return a;
  }

  @Test
  void aToneDifferenceNoLinearMapCanRemoveLeavesNoStepAtTheCut() throws IOException {
    Phantom leg = Phantom.leg(300, 900, 5);
    var upper = new Phantom.Cut(new Rigid(0, 50, 0), W, H, 1, 1, 0);
    var lower = new Phantom.Cut(new Rigid(0, 50, 200), W, H, 1, 1, 0);
    float[] u = leg.piece(upper, 0, 1);
    float[] l = leg.piece(lower, 0, 2);
    for (int k = 0; k < l.length; k++) {
      l[k] += l[k] < 0.5f ? 0.15f : -0.15f; // the lower exposure's own curve
    }
    Piece a = new Piece(header(0), (int) W, (int) H, u::clone);
    Piece b = new Piece(header(1), (int) W, (int) H, l::clone);
    a.prepare();
    b.prepare();
    Seam seam =
        new Seam(
            Phantom.truth(upper, lower),
            Seam.Method.AUTOMATIC,
            Seam.Status.CHECK,
            0.1,
            0.01,
            0.01,
            1,
            2,
            0.1,
            List.of(),
            List.of("test"));
    Stitch stitch =
        new Stitch(
            List.of(a, b),
            List.of(seam),
            new Stitch.Choices("AP", "standing", Stitch.Leg.LEFT, SeamMatcher.Band.WHOLE));
    Compositor c = new Compositor(stitch);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    c.write(out, (done, total) -> true);
    byte[] px = out.toByteArray();
    int row = c.seamRow(0);
    // the bone runs down the middle of the piece: columns 95 to 105 mm, at 1 mm per output pixel
    int x0 = (int) ((100 - 5 - c.originX) / c.mm);
    int x1 = (int) ((100 + 5 - c.originX) / c.mm);
    double far = Math.abs(mean(px, c.cols, row + 40, x0, x1) - mean(px, c.cols, row - 40, x0, x1));
    double near = Math.abs(mean(px, c.cols, row + 3, x0, x1) - mean(px, c.cols, row - 3, x0, x1));
    assertTrue(far > 500, "the tone difference survives the linear map: " + far);
    assertTrue(near < 0.3 * far, "step at the cut " + near + " against " + far + " far from it");
    assertTrue(c.toneHalfMm(0) > Stitch.FEATHER_MM, "tone feather " + c.toneHalfMm(0));
  }

  private static double mean(byte[] px, int cols, int row, int x0, int x1) {
    double s = 0;
    for (int x = x0; x < x1; x++) {
      int i = 2 * (row * cols + x);
      s += (px[i] & 0xff) | ((px[i + 1] & 0xff) << 8);
    }
    return s / (x1 - x0);
  }
}
