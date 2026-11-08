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

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog.ModalityType;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.dicom.viewer2d.Messages;

/**
 * Records a video: each frame is drawn on the event dispatch thread by a {@link FrameSource} (the
 * view is moved to the frame, then painted), and encoded on a worker thread. A modal progress
 * dialog blocks every other window while it runs, so nothing can change the view, select another
 * one or scroll it between frames; its Cancel button stops the export. A cancelled or failed export
 * deletes its partial file, also when closing the file fails.
 */
public final class VideoRecorder {
  private static final Logger LOGGER = LoggerFactory.getLogger(VideoRecorder.class);

  /** Draws one frame of the video. Called on the event dispatch thread, in frame order. */
  @FunctionalInterface
  public interface FrameSource {
    /**
     * @return the picture of frame {@code index}, or {@code null} when it cannot be drawn
     * @throws Exception to stop the export, with the message shown to the user
     */
    BufferedImage frame(int index) throws Exception;
  }

  private VideoRecorder() {}

  /**
   * Records the video and returns when it is done, cancelled or failed. Call it on the event
   * dispatch thread; the modal dialog keeps the application painting meanwhile.
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
    Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
    JDialog dialog =
        new JDialog(owner, Messages.getString("video.title"), ModalityType.APPLICATION_MODAL);
    JProgressBar bar = new JProgressBar(0, frames);
    bar.setStringPainted(true);
    JButton cancel = new JButton(Messages.getString("video.cancel"));
    AtomicBoolean cancelled = new AtomicBoolean();
    cancel.addActionListener(_ -> cancelled.set(true));
    dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
    dialog.addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(WindowEvent e) {
            cancelled.set(true);
          }
        });
    JPanel panel = new JPanel(new BorderLayout(8, 8));
    panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
    panel.add(
        new JLabel(String.format(Messages.getString("video.recording"), file.getFileName())),
        BorderLayout.NORTH);
    panel.add(bar, BorderLayout.CENTER);
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 0, 0));
    buttons.add(cancel);
    panel.add(buttons, BorderLayout.SOUTH);
    dialog.setContentPane(panel);
    dialog.pack();
    dialog.setLocationRelativeTo(parent);

    SwingWorker<Integer, Void> worker =
        new SwingWorker<>() {
          @Override
          protected Integer doInBackground() throws Exception {
            return encode(file, format, fps, frames, source, cancelled, bar);
          }

          @Override
          protected void done() {
            dialog.dispose();
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
    dialog.setVisible(true); // blocks, with its own event loop, until done() disposes it
  }

  /**
   * Draws and encodes every frame; {@code null} when cancelled. The file is deleted unless every
   * frame was written and the file closed.
   */
  static Integer encode(
      Path file,
      VideoFormat format,
      int fps,
      int frames,
      FrameSource source,
      AtomicBoolean cancelled,
      JProgressBar bar)
      throws Exception {
    VideoWriter writer = null;
    boolean complete = false;
    try {
      for (int i = 0; i < frames; i++) {
        if (cancelled.get()) {
          return null;
        }
        BufferedImage image = drawOnEdt(source, i);
        if (image == null) {
          throw new IllegalStateException("Frame " + (i + 1) + " could not be drawn");
        }
        if (writer == null) {
          writer = new VideoWriter(file, format, fps, image.getWidth(), image.getHeight());
        }
        writer.addFrame(image);
        if (bar != null) {
          int done = i + 1;
          SwingUtilities.invokeLater(() -> bar.setValue(done));
        }
      }
      VideoWriter finished = writer;
      writer = null;
      if (finished != null) {
        finished.close();
      }
      complete = true;
      return frames;
    } finally {
      if (writer != null) {
        try {
          writer.close();
        } catch (IOException e) {
          LOGGER.debug("Closing the unfinished video", e);
        }
      }
      if (!complete) {
        Files.deleteIfExists(file);
      }
    }
  }

  private static BufferedImage drawOnEdt(FrameSource source, int index)
      throws InterruptedException, InvocationTargetException {
    if (SwingUtilities.isEventDispatchThread()) {
      try {
        return source.frame(index);
      } catch (Exception e) {
        throw new InvocationTargetException(e);
      }
    }
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
