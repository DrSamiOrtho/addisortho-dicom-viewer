/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.pipeline;

import java.awt.BorderLayout;
import java.awt.Component;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.Insertable;
import org.weasis.core.api.gui.util.AppProperties;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.api.media.data.MediaSeries;
import org.weasis.core.api.media.data.TagW;
import org.weasis.core.ui.docking.PluginTool;
import org.weasis.core.ui.editor.image.ViewCanvas;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.explorer.DicomModel;
import org.weasis.dicom.explorer.HangingProtocols.OpeningViewer;
import org.weasis.dicom.explorer.LoadLocalDicom;
import org.weasis.dicom.viewer3d.EventManager;

/**
 * Runs the owner's 3D bone pipeline (planning/make_3d.sh, TotalSegmentator on this computer) on the
 * CT of the 3D view and opens what it made in the same study. Where the pipeline is not installed
 * the panel only says so.
 */
public class Make3dTool extends PluginTool {

  private static final Logger LOGGER = LoggerFactory.getLogger(Make3dTool.class);
  public static final String BUTTON_NAME = Messages.getString("Make3dTool.title");
  static final String SCRIPT_KEY = "addisortho.make3d.script"; // NON-NLS

  private final JLabel scriptLabel = new JLabel();
  private final JComboBox<String> region =
      new JComboBox<>(Make3dCommand.REGIONS.toArray(new String[0]));
  private final JCheckBox keepName = new JCheckBox(Messages.getString("Make3dTool.keepName"), true);
  private final JButton run = new JButton(Messages.getString("Make3dTool.run"));
  private final JTextArea log = new JTextArea(12, 30);
  private SwingWorker<Integer, String> worker;

  public Make3dTool() {
    super(BUTTON_NAME, Insertable.Type.TOOL, 45);
    dockable.setTitleIcon(null);
    setDockableWidth(320);
    JPanel p = new JPanel();
    p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
    p.add(wrap(Messages.getString("Make3dTool.intro")));
    JPanel s = new JPanel(new BorderLayout(4, 0));
    s.add(scriptLabel, BorderLayout.CENTER);
    JButton choose = new JButton(Messages.getString("Make3dTool.choose"));
    choose.addActionListener(e -> chooseScript(choose));
    s.add(choose, BorderLayout.EAST);
    p.add(s);
    JPanel r = new JPanel(new BorderLayout(4, 0));
    r.add(new JLabel(Messages.getString("Make3dTool.region")), BorderLayout.WEST);
    r.add(region, BorderLayout.CENTER);
    p.add(r);
    p.add(wrap(Messages.getString("Make3dTool.knee")));
    p.add(keepName);
    run.addActionListener(e -> runOrCancel());
    p.add(run);
    log.setEditable(false);
    log.setLineWrap(true);
    p.add(new JScrollPane(log));
    setLayout(new BorderLayout());
    add(p, BorderLayout.NORTH);
    showScript();
  }

  private static JLabel wrap(String text) {
    return new JLabel("<html>" + text.replace("&", "&amp;").replace("<", "&lt;") + "</html>");
  }

  private static Path script() {
    String v = GuiUtils.getUICore().getLocalPersistence().getProperty(SCRIPT_KEY, "");
    Path p = v.isBlank() ? null : Path.of(v);
    return p != null && Files.isRegularFile(p) ? p : null;
  }

  private void showScript() {
    Path p = script();
    scriptLabel.setText(
        p == null
            ? Messages.getString("Make3dTool.noScript")
            : Messages.getString("Make3dTool.script") + ": " + p.getFileName());
    scriptLabel.setToolTipText(p == null ? null : p.toString());
    run.setEnabled(p != null || worker != null);
  }

  private void chooseScript(Component parent) {
    JFileChooser fc = new JFileChooser();
    fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
    if (fc.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION
        && fc.getSelectedFile().getName().equals("make_3d.sh")) {
      GuiUtils.getUICore()
          .getLocalPersistence()
          .setProperty(SCRIPT_KEY, fc.getSelectedFile().getAbsolutePath());
    }
    showScript();
  }

  private void runOrCancel() {
    if (worker != null) {
      worker.cancel(true);
      return;
    }
    ViewCanvas<DicomImageElement> view = EventManager.getInstance().getSelectedViewPane();
    MediaSeries<DicomImageElement> series = view == null ? null : view.getSeries();
    Path sh = script();
    if (series == null || sh == null) {
      log.setText(Messages.getString("Make3dTool.noCt"));
      return;
    }
    String reg = (String) region.getSelectedItem();
    String code = Make3dCommand.codeFor(reg, LocalDateTime.now());
    Path base = AppProperties.buildAccessibleTempDirectory("make3d", code);
    Make3dCommand cmd =
        new Make3dCommand(
            sh, base.resolve("ct"), base.resolve("out"), reg, code, keepName.isSelected());
    Object model = series.getTagValue(TagW.ExplorerModel);
    log.setText("");
    run.setText(Messages.getString("Make3dTool.cancel"));
    worker =
        new SwingWorker<>() {
          private Process process;

          @Override
          protected Integer doInBackground() throws Exception {
            Files.createDirectories(cmd.ctFolder());
            List<DicomImageElement> images = new java.util.ArrayList<>();
            series.getMedias(null, null).forEach(images::add);
            publish(Messages.getString("Make3dTool.exporting").formatted(images.size()));
            int i = 0;
            for (DicomImageElement img : images) {
              if (isCancelled()) {
                return -1;
              }
              img.saveToFile(cmd.ctFolder().resolve("%05d.dcm".formatted(i++)).toFile());
            }
            process = new ProcessBuilder(cmd.arguments()).redirectErrorStream(true).start();
            try (BufferedReader in =
                new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
              String line;
              while ((line = in.readLine()) != null) {
                publish(line);
              }
            } finally {
              if (isCancelled() && process.isAlive()) {
                process.destroy();
              }
            }
            return process.waitFor();
          }

          @Override
          protected void process(List<String> lines) {
            for (String l : lines) {
              log.append(l + "\n");
            }
          }

          @Override
          protected void done() {
            if (process != null && process.isAlive()) {
              process.destroy();
            }
            try {
              if (isCancelled()) {
                log.append(Messages.getString("Make3dTool.cancelled") + "\n");
              } else {
                int exit = get();
                if (exit == 0) {
                  log.append(
                      Messages.getString("Make3dTool.done").formatted(open(cmd.outFolder(), model))
                          + "\n");
                } else {
                  log.append(Messages.getString("Make3dTool.failed").formatted(exit) + "\n");
                }
              }
            } catch (Exception e) {
              LOGGER.error("Make 3D bones failed", e);
              log.append(String.valueOf(e.getMessage()) + "\n");
            } finally {
              worker = null;
              run.setText(Messages.getString("Make3dTool.run"));
              showScript();
            }
          }
        };
    worker.execute();
  }

  /** Loads what the pipeline wrote into the explorer, in the CT's study; returns the file count. */
  private static int open(Path folder, Object model) throws IOException {
    if (!Files.isDirectory(folder)) {
      return 0;
    }
    File[] files;
    try (Stream<Path> s = Files.list(folder)) {
      files = s.filter(f -> f.toString().endsWith(".dcm")).map(Path::toFile).toArray(File[]::new);
    }
    if (files.length > 0 && model instanceof DicomModel m) {
      DicomModel.LOADING_EXECUTOR.execute(new LoadLocalDicom(files, false, m, OpeningViewer.NONE));
    }
    return files.length;
  }

  @Override
  protected void changeToolWindowAnchor(bibliothek.gui.dock.common.CLocation clocation) {
    // nothing to change
  }
}
