/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.stitching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.net.URI;
import java.util.List;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.junit.jupiter.api.Test;

class PiecePictureTest {

  private static int grey(BufferedImage image, int x, int y) {
    return image.getRGB(x, y) & 0xFF;
  }

  // left half dark, right half bright, on a plate wider than it is tall
  private static float[] halves(int w, int h) {
    float[] v = new float[w * h];
    for (int y = 0; y < h; y++) {
      for (int x = w / 2; x < w; x++) {
        v[y * w + x] = 1000;
      }
    }
    return v;
  }

  @Test
  void keepsShapeAndLeftRight() {
    PiecePicture p = PiecePicture.of(halves(2000, 1000), 2000, 1000, 0.14, 0.14);
    assertEquals(PiecePicture.LARGE_PX, p.large().getWidth());
    assertEquals(PiecePicture.LARGE_PX / 2, p.large().getHeight());
    assertEquals(PiecePicture.THUMB_PX, p.thumb().getWidth());
    assertEquals(PiecePicture.THUMB_PX / 2, p.thumb().getHeight());
    assertTrue(grey(p.thumb(), 5, 18) < 50, "the left stays on the left");
    assertTrue(grey(p.thumb(), 66, 18) > 200, "the right stays on the right");
  }

  @Test
  void honoursNonSquarePixels() {
    PiecePicture p = PiecePicture.of(new float[1000 * 1000], 1000, 1000, 0.2, 0.1);
    assertEquals(PiecePicture.LARGE_PX, p.large().getWidth());
    assertEquals(PiecePicture.LARGE_PX / 2, p.large().getHeight());
  }

  @Test
  void boneIsBrightOnAnInvertedPlate() throws Exception {
    Attributes h = new Attributes();
    h.setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME1");
    Piece piece = new Piece(h, 200, 100, () -> halves(200, 100));
    PiecePicture p = PiecePicture.of(piece);
    assertTrue(grey(p.thumb(), 5, 18) > 200, "a low MONOCHROME1 value shows bright");
    assertTrue(grey(p.thumb(), 66, 18) < 50);
  }

  @Test
  void detailsTellImagesApartAndSkipWhatIsMissing() {
    Attributes h = new Attributes();
    h.setString(Tag.SeriesNumber, VR.IS, "3");
    h.setString(Tag.AcquisitionTime, VR.TM, "103215.000");
    h.setString(Tag.ViewPosition, VR.CS, "AP");
    Piece piece = new Piece(h, 100, 100, () -> new float[0]);
    assertEquals(List.of("series 3", "taken 10:32:15", "view AP"), piece.details());
    assertEquals(List.of(), new Piece(new Attributes(), 100, 100, () -> new float[0]).details());
  }

  @Test
  void fileNameShownOnlyWhenItSaysSomething() throws Exception {
    assertEquals("femur left.dcm", StitchService.fileName(new URI("file:/x/femur%20left.dcm")));
    assertEquals("IMG0012", StitchService.fileName(new URI("file:/disc/DICOM/IMG0012")));
    assertEquals("", StitchService.fileName(new URI("file:/x/1.2.840.113619.2.55.dcm")));
    assertEquals("", StitchService.fileName(null));
  }
}
