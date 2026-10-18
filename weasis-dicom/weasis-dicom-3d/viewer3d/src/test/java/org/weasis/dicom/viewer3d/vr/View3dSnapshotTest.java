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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import org.weasis.dicom.viewer3d.vr.View3dSnapshot.Frame;

/** The GL read-back itself needs a GPU; only the pixel conversion is tested. */
class View3dSnapshotTest {

  @Test
  void glRowsAreFlippedAndAlphaDropped() {
    // 2x2 RGBA as glReadPixels returns it: bottom row first.
    ByteBuffer rgba =
        ByteBuffer.wrap(
            new byte[] {
              1, 2, 3, 99, 4, 5, 6, 99, // bottom row
              7, 8, 9, 99, 10, 11, 12, 99 // top row
            });

    byte[] rgb = View3dSnapshot.toFlippedRgb(rgba, 2, 2);

    assertArrayEquals(new byte[] {7, 8, 9, 10, 11, 12, 1, 2, 3, 4, 5, 6}, rgb);
  }

  @Test
  void frameConvertsToAnRgbImage() {
    byte[] rgb = {(byte) 255, 0, 0, 0, (byte) 128, 0, 0, 0, (byte) 255};
    BufferedImage image = new Frame(3, 1, rgb).toImage();

    assertEquals(3, image.getWidth());
    assertEquals(1, image.getHeight());
    assertEquals(0xFF0000, image.getRGB(0, 0) & 0xFFFFFF);
    assertEquals(0x008000, image.getRGB(1, 0) & 0xFFFFFF);
    assertEquals(0x0000FF, image.getRGB(2, 0) & 0xFFFFFF);
  }
}
