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
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.filechooser.FileNameExtensionFilter;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.dicom.viewer2d.Messages;

/**
 * Asks how to export a video: the container, the frame rate and either the slices to scroll through
 * or the rotation to turn, then the file. The suggested file names never hold patient details.
 */
public final class VideoExportDialog {

  /** Last folder a video was saved to, kept between sessions. */
  public static final String P_LAST_DIR = "video.export.last.dir";

  private static final String P_FORMAT = "video.export.format";
  private static final String P_FPS = "video.export.fps";

  /** The axis a 3D rotation video turns about, as seen on the screen. */
  public enum RotationAxis {
    /** Turntable: about the screen's vertical axis. */
    VERTICAL("video.axis.vertical"),
    /** Tumble: about the screen's horizontal axis. */
    HORIZONTAL("video.axis.horizontal"),
    /** Roll: about the line of sight. */
    VIEW("video.axis.view");

    private final String key;

    RotationAxis(String key) {
      this.key = key;
    }

    @Override
    public String toString() {
      return Messages.getString(key);
    }
  }

  /** A slice video: slice numbers are 1-based, as the viewer shows them. */
  public record SliceSettings(
      Path file, VideoFormat format, int fps, boolean anonymize, int first, int last, int step) {}

  /** A rotation video of the 3D view. */
  public record RotationSettings(
      Path file, VideoFormat format, int fps, RotationAxis axis, double degrees, int frames) {}

  private VideoExportDialog() {}

  /**
   * @param sliceCount slices of the series or of the MPR stack
   * @param current slice shown now, 1-based
   */
  public static Optional<SliceSettings> askSlices(Component parent, int sliceCount, int current) {
    int max = Math.max(1, sliceCount);
    JComboBox<VideoFormat> format = formatBox();
    JSpinner fps = fpsSpinner(10);
    JSpinner first = new JSpinner(new SpinnerNumberModel(1, 1, max, 1));
    JSpinner last = new JSpinner(new SpinnerNumberModel(max, 1, max, 1));
    JSpinner step = new JSpinner(new SpinnerNumberModel(1, 1, Math.max(1, max - 1), 1));
    JCheckBox anonymize = new JCheckBox(Messages.getString("video.anonymize"), true);
    anonymize.setToolTipText(Messages.getString("video.anonymize.tip"));

    JPanel panel = new JPanel(new GridBagLayout());
    int row = 0;
    row = addRow(panel, row, Messages.getString("video.format"), format);
    row = addRow(panel, row, Messages.getString("video.fps"), fps);
    row = addRow(panel, row, Messages.getString("video.first"), first);
    row = addRow(panel, row, Messages.getString("video.last"), last);
    row = addRow(panel, row, Messages.getString("video.step"), step);
    row = addRow(panel, row, null, anonymize);
    addRow(
        panel,
        row,
        null,
        new JLabel(String.format(Messages.getString("video.slices.hint"), max, current)));

    if (!confirm(parent, panel, Messages.getString("video.slices.title"))) {
      return Optional.empty();
    }
    VideoFormat f = (VideoFormat) format.getSelectedItem();
    int rate = (Integer) fps.getValue();
    remember(f, rate);
    return chooseFile(parent, f, "slices") // NON-NLS
        .map(
            file ->
                new SliceSettings(
                    file,
                    f,
                    rate,
                    anonymize.isSelected(),
                    (Integer) first.getValue(),
                    (Integer) last.getValue(),
                    (Integer) step.getValue()));
  }

  public static Optional<RotationSettings> askRotation(Component parent) {
    JComboBox<VideoFormat> format = formatBox();
    JSpinner fps = fpsSpinner(24);
    JComboBox<RotationAxis> axis = new JComboBox<>(RotationAxis.values());
    JSpinner degrees = new JSpinner(new SpinnerNumberModel(360, -3600, 3600, 15));
    JSpinner frames = new JSpinner(new SpinnerNumberModel(72, 2, FrameSequence.MAX_FRAMES, 1));
    JLabel duration = new JLabel();
    Runnable showDuration =
        () ->
            duration.setText(
                String.format(
                    Messages.getString("video.duration"),
                    FrameSequence.durationSeconds(
                        (Integer) frames.getValue(), (Integer) fps.getValue())));
    fps.addChangeListener(_ -> showDuration.run());
    frames.addChangeListener(_ -> showDuration.run());
    showDuration.run();

    JPanel panel = new JPanel(new GridBagLayout());
    int row = 0;
    row = addRow(panel, row, Messages.getString("video.format"), format);
    row = addRow(panel, row, Messages.getString("video.axis"), axis);
    row = addRow(panel, row, Messages.getString("video.degrees"), degrees);
    row = addRow(panel, row, Messages.getString("video.frames"), frames);
    row = addRow(panel, row, Messages.getString("video.fps"), fps);
    row = addRow(panel, row, null, duration);
    addRow(panel, row, null, new JLabel(Messages.getString("video.rotation.hint")));

    if (!confirm(parent, panel, Messages.getString("video.rotation.title"))) {
      return Optional.empty();
    }
    VideoFormat f = (VideoFormat) format.getSelectedItem();
    int rate = (Integer) fps.getValue();
    remember(f, rate);
    return chooseFile(parent, f, "3d-rotation") // NON-NLS
        .map(
            file ->
                new RotationSettings(
                    file,
                    f,
                    rate,
                    (RotationAxis) axis.getSelectedItem(),
                    ((Number) degrees.getValue()).doubleValue(),
                    (Integer) frames.getValue()));
  }

  private static JComboBox<VideoFormat> formatBox() {
    JComboBox<VideoFormat> box = new JComboBox<>(VideoFormat.values());
    String saved = GuiUtils.getUICore().getLocalPersistence().getProperty(P_FORMAT, "MP4");
    for (VideoFormat f : VideoFormat.values()) {
      if (f.name().equals(saved)) {
        box.setSelectedItem(f);
      }
    }
    return box;
  }

  private static JSpinner fpsSpinner(int fallback) {
    int saved = GuiUtils.getUICore().getLocalPersistence().getIntProperty(P_FPS, fallback);
    int value = Math.clamp(saved, 1, VideoWriter.MAX_FPS);
    return new JSpinner(new SpinnerNumberModel(value, 1, VideoWriter.MAX_FPS, 1));
  }

  private static void remember(VideoFormat format, int fps) {
    GuiUtils.getUICore().getLocalPersistence().setProperty(P_FORMAT, format.name());
    GuiUtils.getUICore().getLocalPersistence().putIntProperty(P_FPS, fps);
  }

  private static int addRow(JPanel panel, int row, String label, JComponent field) {
    GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(3, 3, 3, 3);
    c.gridy = row;
    c.anchor = GridBagConstraints.LINE_START;
    if (label != null) {
      c.gridx = 0;
      panel.add(new JLabel(label), c);
      c.gridx = 1;
    } else {
      c.gridx = 0;
      c.gridwidth = 2;
    }
    panel.add(field, c);
    return row + 1;
  }

  private static boolean confirm(Component parent, JPanel panel, String title) {
    return JOptionPane.showConfirmDialog(
            parent, panel, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        == JOptionPane.OK_OPTION;
  }

  private static Optional<Path> chooseFile(Component parent, VideoFormat format, String name) {
    String dir = GuiUtils.getUICore().getLocalPersistence().getProperty(P_LAST_DIR, "");
    JFileChooser chooser = dir.isEmpty() ? new JFileChooser() : new JFileChooser(dir);
    chooser.setFileFilter(new FileNameExtensionFilter(format.title(), format.extension()));
    chooser.setSelectedFile(
        new File(chooser.getCurrentDirectory(), name + "." + format.extension()));
    if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION
        || chooser.getSelectedFile() == null) {
      return Optional.empty();
    }
    Path file = format.withExtension(chooser.getSelectedFile().toPath());
    if (Files.exists(file)
        && JOptionPane.showConfirmDialog(
                parent,
                String.format(Messages.getString("video.overwrite"), file.getFileName()),
                Messages.getString("video.title"),
                JOptionPane.YES_NO_OPTION)
            != JOptionPane.YES_OPTION) {
      return Optional.empty();
    }
    Path parentDir = file.toAbsolutePath().getParent();
    if (parentDir != null) {
      GuiUtils.getUICore().getLocalPersistence().setProperty(P_LAST_DIR, parentDir.toString());
    }
    return Optional.of(file);
  }
}
