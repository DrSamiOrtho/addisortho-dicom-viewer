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

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import org.jcodec.codecs.h264.H264Encoder;
import org.jcodec.common.Codec;
import org.jcodec.common.MuxerTrack;
import org.jcodec.common.VideoCodecMeta;
import org.jcodec.common.VideoEncoder.EncodedFrame;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Packet;
import org.jcodec.common.model.Packet.FrameType;
import org.jcodec.common.model.Picture;
import org.jcodec.common.model.Size;
import org.jcodec.containers.mp4.Brand;
import org.jcodec.containers.mp4.muxer.MP4Muxer;
import org.jcodec.scale.ColorUtil;
import org.jcodec.scale.Transform;

/**
 * Writes frames to an H.264 video in an MP4 or MOV file with JCodec, a pure Java encoder: nothing
 * native, so it behaves the same in every installer (macOS arm64 and x64, Windows, Linux).
 *
 * <p>Every frame is drawn into the size given at creation, rounded down to even numbers as 4:2:0
 * chroma needs; a frame of another size is scaled to fit, centred on black. Not thread-safe.
 */
public final class VideoWriter implements Closeable {

  /** Highest frame rate offered; players struggle beyond it. */
  public static final int MAX_FPS = 60;

  /** A key frame every this many frames, so players can seek. */
  private static final int KEY_INTERVAL = 25;

  private final SeekableByteChannel channel;
  private final MP4Muxer muxer;
  private final MuxerTrack track;
  private final H264Encoder encoder;
  private final Transform toYuv;
  private final int width;
  private final int height;
  private final int fps;
  private final BufferedImage canvas;
  private final Picture rgb;
  private final Picture yuv;
  private ByteBuffer buffer;
  private int frames;
  private boolean closed;

  /**
   * @param fps frames per second, 1 to {@link #MAX_FPS}
   * @param width frame width in pixels, at least 2
   * @param height frame height in pixels, at least 2
   */
  public VideoWriter(Path file, VideoFormat format, int fps, int width, int height)
      throws IOException {
    if (fps < 1 || fps > MAX_FPS || width < 2 || height < 2) {
      throw new IllegalArgumentException("Invalid video size or frame rate");
    }
    this.width = even(width);
    this.height = even(height);
    this.fps = fps;
    this.channel = NIOUtils.writableChannel(file.toFile());
    try {
      this.muxer =
          MP4Muxer.createMP4Muxer(channel, format == VideoFormat.MOV ? Brand.MOV : Brand.MP4);
      this.track =
          muxer.addVideoTrack(
              Codec.H264,
              VideoCodecMeta.createSimpleVideoCodecMeta(
                  new Size(this.width, this.height), ColorSpace.YUV420J));
    } catch (IOException | RuntimeException e) {
      NIOUtils.closeQuietly(channel);
      throw e;
    }
    this.encoder = H264Encoder.createH264Encoder();
    encoder.setKeyInterval(KEY_INTERVAL);
    this.toYuv = ColorUtil.getTransform(ColorSpace.RGB, ColorSpace.YUV420J);
    this.canvas = new BufferedImage(this.width, this.height, BufferedImage.TYPE_INT_RGB);
    this.rgb = Picture.create(this.width, this.height, ColorSpace.RGB);
    this.yuv = Picture.create(this.width, this.height, ColorSpace.YUV420J);
  }

  /** The largest even number not above {@code n}. */
  static int even(int n) {
    return n & ~1;
  }

  public int width() {
    return width;
  }

  public int height() {
    return height;
  }

  public int frameCount() {
    return frames;
  }

  /** Encodes one frame, shown for {@code 1 / fps} seconds. */
  public void addFrame(BufferedImage image) throws IOException {
    if (closed) {
      throw new IllegalStateException("Video already closed");
    }
    drawFitted(image);
    toPicture(canvas, rgb);
    toYuv.transform(rgb, yuv);
    if (buffer == null) {
      buffer = ByteBuffer.allocate(encoder.estimateBufferSize(yuv));
    }
    buffer.clear();
    EncodedFrame encoded = encoder.encodeFrame(yuv, buffer);
    track.addFrame(
        Packet.createPacket(
            encoded.getData(),
            frames,
            fps,
            1,
            frames,
            encoded.isKeyFrame() ? FrameType.KEY : FrameType.INTER,
            null));
    frames++;
  }

  private void drawFitted(BufferedImage image) {
    Graphics2D g = canvas.createGraphics();
    try {
      if (image.getWidth() == width && image.getHeight() == height) {
        g.drawImage(image, 0, 0, null);
        return;
      }
      g.setColor(Color.BLACK);
      g.fillRect(0, 0, width, height);
      double scale =
          Math.min(width / (double) image.getWidth(), height / (double) image.getHeight());
      int w = (int) Math.round(image.getWidth() * scale);
      int h = (int) Math.round(image.getHeight() * scale);
      g.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      g.drawImage(image, (width - w) / 2, (height - h) / 2, w, h, null);
    } finally {
      g.dispose();
    }
  }

  /** JCodec keeps 8-bit samples as signed bytes centred on zero. */
  private static void toPicture(BufferedImage image, Picture dst) {
    byte[] data = dst.getPlaneData(0);
    int w = image.getWidth();
    int h = image.getHeight();
    int[] row = new int[w];
    int i = 0;
    for (int y = 0; y < h; y++) {
      image.getRGB(0, y, w, 1, row, 0, w);
      for (int x = 0; x < w; x++) {
        int p = row[x];
        data[i++] = (byte) (((p >> 16) & 0xFF) - 128);
        data[i++] = (byte) (((p >> 8) & 0xFF) - 128);
        data[i++] = (byte) ((p & 0xFF) - 128);
      }
    }
  }

  /** Writes the index of the file and closes it. */
  @Override
  public void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    try {
      muxer.finish();
    } finally {
      NIOUtils.closeQuietly(channel);
    }
  }
}
