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
package org.weasis.dicom.viewer2d.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.jcodec.api.FrameGrab;
import org.jcodec.common.io.FileChannelWrapper;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Picture;
import org.jcodec.scale.ColorUtil;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class VideoWriterTest {

  @TempDir Path dir;

  private static BufferedImage solid(int w, int h, Color c) {
    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setColor(c);
    g.fillRect(0, 0, w, h);
    g.dispose();
    return img;
  }

  @ParameterizedTest
  @EnumSource(VideoFormat.class)
  void writesAVideoThatDecodesBackFrameByFrame(VideoFormat format) throws Exception {
    Path file = format.withExtension(dir.resolve("clip"));
    // Odd sizes are rounded down to even; a frame of another size is scaled in.
    try (VideoWriter writer = new VideoWriter(file, format, 10, 65, 49)) {
      assertEquals(64, writer.width());
      assertEquals(48, writer.height());
      for (int i = 0; i < 12; i++) {
        writer.addFrame(solid(i < 6 ? 64 : 128, i < 6 ? 48 : 96, Color.RED));
      }
      assertEquals(12, writer.frameCount());
    }
    assertTrue(file.getFileName().toString().endsWith("." + format.extension()));

    byte[] head = Arrays.copyOf(Files.readAllBytes(file), 12);
    assertEquals("ftyp", new String(head, 4, 4, StandardCharsets.US_ASCII));
    String brand = new String(head, 8, 4, StandardCharsets.US_ASCII);
    if (format == VideoFormat.MOV) {
      assertEquals("qt  ", brand);
    } else {
      assertNotEquals("qt  ", brand);
    }

    try (FileChannelWrapper in = NIOUtils.readableChannel(file.toFile())) {
      FrameGrab grab = FrameGrab.createFrameGrab(in);
      assertEquals(12, grab.getVideoTrack().getMeta().getTotalFrames());
      assertEquals(1.2, grab.getVideoTrack().getMeta().getTotalDuration(), 1e-6);
      Picture frame = grab.getNativeFrame();
      assertNotNull(frame);
      Picture rgb = Picture.create(frame.getWidth(), frame.getHeight(), ColorSpace.RGB);
      ColorUtil.getTransform(frame.getColor(), ColorSpace.RGB).transform(frame, rgb);
      byte[] px = rgb.getPlaneData(0);
      int centre = ((rgb.getHeight() / 2) * rgb.getWidth() + rgb.getWidth() / 2) * 3;
      int r = px[centre] + 128;
      int g = px[centre + 1] + 128;
      int b = px[centre + 2] + 128;
      assertTrue(r > 200 && g < 60 && b < 60, "decoded colour " + r + "," + g + "," + b);
    }
  }

  @ParameterizedTest
  @EnumSource(VideoFormat.class)
  void extensionIsAddedOnce(VideoFormat format) {
    Path plain = format.withExtension(Path.of("a", "video"));
    assertEquals("video." + format.extension(), plain.getFileName().toString());
    assertEquals(plain, format.withExtension(plain));
  }

  @org.junit.jupiter.api.Test
  void rejectsInvalidSettingsAndLateFrames() throws IOException {
    Path file = dir.resolve("bad.mp4");
    assertThrows(
        IllegalArgumentException.class, () -> new VideoWriter(file, VideoFormat.MP4, 0, 64, 64));
    assertThrows(
        IllegalArgumentException.class, () -> new VideoWriter(file, VideoFormat.MP4, 30, 1, 64));
    VideoWriter writer = new VideoWriter(file, VideoFormat.MP4, 30, 32, 32);
    writer.addFrame(solid(32, 32, Color.WHITE));
    writer.close();
    writer.close();
    assertThrows(IllegalStateException.class, () -> writer.addFrame(solid(32, 32, Color.WHITE)));
  }
}
