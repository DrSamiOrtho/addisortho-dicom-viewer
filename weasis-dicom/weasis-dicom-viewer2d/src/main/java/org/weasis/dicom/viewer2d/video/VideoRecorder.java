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

import java.awt.Component;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JOptionPane;
import javax.swing.ProgressMonitor;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.dicom.viewer2d.Messages;

/**
 * Records a video: each frame is drawn on the event dispatch thread by a {@link FrameSource} (the
 * view is moved to the frame, then painted), and encoded on a worker thread, with a progress dialog
 * that can cancel. A cancelled or failed export deletes its partial file.
 */
public final class VideoRecorder {
  private static final Logger LOGGER = LoggerFactory.getLogger(VideoRecorder.class);

  /** Draws one frame of the video. Called on the event dispatch thread, in frame order. */
  @FunctionalInterface
  public interface FrameSource {
    /**
     * @return the picture of frame {@code index}, or {@code null} when it cannot be drawn
     */
    BufferedImage frame(int index) throws Exception;
  }

  private VideoRecorder() {}

  /**
   * Starts recording and returns at once.
   *
   * @param frames number of frames, see {@link FrameSequence#MAX_FRAMES}
   * @param restore puts the view back as it was; run on the event dispatch thread at the end,
   *     whatever happened
   */
  public static void record(
      Component parent,
      Path file,
      VideoFormat format,
      int fps,
      int frames,
      FrameSource source,
      Runnable restore) {
    ProgressMonitor monitor =
        new ProgressMonitor(
            parent,
            String.format(Messages.getString("video.recording"), file.getFileName()),
            null,
            0,
            frames);
    monitor.setMillisToDecideToPopup(0);
    monitor.setMillisToPopup(0);

    SwingWorker<Integer, Void> worker =
        new SwingWorker<>() {
          @Override
          protected Integer doInBackground() throws Exception {
            VideoWriter writer = null;
            boolean complete = false;
            try {
              for (int i = 0; i < frames; i++) {
                if (isCancelled() || monitor.isCanceled()) {
                  return null;
                }
                BufferedImage image = drawOnEdt(source, i);
                if (image == null) {
                  throw new IllegalStateException("Frame " + i + " could not be drawn");
                }
                if (writer == null) {
                  writer = new VideoWriter(file, format, fps, image.getWidth(), image.getHeight());
                }
                writer.addFrame(image);
                int done = i + 1;
                SwingUtilities.invokeLater(() -> monitor.setProgress(done));
              }
              complete = true;
              return frames;
            } finally {
              if (writer != null) {
                writer.close();
              }
              if (!complete) {
                Files.deleteIfExists(file);
              }
            }
          }

          @Override
          protected void done() {
            monitor.close();
            try {
              restore.run();
            } catch (RuntimeException e) {
              LOGGER.warn("Cannot restore the view after the video", e);
            }
            try {
              Integer written = get();
              if (written != null) {
                JOptionPane.showMessageDialog(
                    parent,
                    String.format(
                        Messages.getString("video.saved"),
                        file.getFileName(),
                        written,
                        FrameSequence.durationSeconds(written, fps)),
                    Messages.getString("video.title"),
                    JOptionPane.INFORMATION_MESSAGE);
              }
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } catch (Exception e) {
              LOGGER.error("Video export to {}", file, e);
              JOptionPane.showMessageDialog(
                  parent,
                  String.format(Messages.getString("video.failed"), rootMessage(e)),
                  Messages.getString("video.title"),
                  JOptionPane.ERROR_MESSAGE);
            }
          }
        };
    worker.execute();
  }

  private static BufferedImage drawOnEdt(FrameSource source, int index)
      throws InterruptedException, InvocationTargetException {
    AtomicReference<BufferedImage> image = new AtomicReference<>();
    AtomicReference<Exception> failure = new AtomicReference<>();
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            image.set(source.frame(index));
          } catch (Exception e) {
            failure.set(e);
          }
        });
    if (failure.get() != null) {
      throw new InvocationTargetException(failure.get());
    }
    return image.get();
  }

  private static String rootMessage(Throwable e) {
    Throwable t = e;
    while (t.getCause() != null && t.getCause() != t) {
      t = t.getCause();
    }
    return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
  }
}
