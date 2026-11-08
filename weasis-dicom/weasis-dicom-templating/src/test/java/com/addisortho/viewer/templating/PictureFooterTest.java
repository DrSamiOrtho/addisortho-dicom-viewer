/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.templating;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;
import org.weasis.core.ui.editor.image.ViewTransferHandler;

class PictureFooterTest {

  @Test
  void footerIsAStripBelowThePicture() {
    BufferedImage image = new BufferedImage(600, 300, BufferedImage.TYPE_3BYTE_BGR);
    for (int y = 0; y < 300; y++) {
      for (int x = 0; x < 600; x++) {
        image.setRGB(x, y, 0x808080);
      }
    }
    BufferedImage out = ViewTransferHandler.addFooter(image, "Addis Ortho DICOM Viewer");

    assertEquals(600, out.getWidth());
    assertTrue(out.getHeight() > 300);
    assertEquals(0x808080, out.getRGB(10, 299) & 0xFFFFFF, "the picture itself is unchanged");
    assertEquals(new Color(0x03, 0x45, 0x37).getRGB(), out.getRGB(1, out.getHeight() - 1));
    boolean text = false;
    for (int y = 300; y < out.getHeight() && !text; y++) {
      for (int x = 0; x < 600 && !text; x++) {
        text = (out.getRGB(x, y) >> 16 & 0xFF) > 0xC0;
      }
    }
    assertTrue(text, "light text in the strip");
  }
}
