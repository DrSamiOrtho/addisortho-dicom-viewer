/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.explorer.model.DataExplorerModel;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.api.media.data.MediaElement;
import org.weasis.core.api.media.data.MediaSeries;
import org.weasis.core.api.media.data.TagW;
import org.weasis.core.ui.editor.SeriesViewerFactory;
import org.weasis.core.ui.editor.ViewerOpenOptions;
import org.weasis.core.ui.editor.ViewerPlacement;
import org.weasis.core.ui.editor.ViewerPluginBuilder;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.viewer2d.EventManager;
import org.weasis.dicom.viewer3d.View3DFactory;
import org.weasis.dicom.viewer3d.vr.View3d;
import org.weasis.dicom.viewer3d.vr.View3dSnapshot;

/**
 * A check of the bones in 3D that needs no hand on the mouse, for test builds: started with {@code
 * -Daddisortho.bones.selfcheck=<folder>}, it opens the CT shown in the 2D viewer in 3D, finds the
 * bones and saves a picture of the 3D view in each look to the folder. Without the property it does
 * nothing.
 */
final class SelfCheck {

  private static final Logger LOGGER = LoggerFactory.getLogger(SelfCheck.class);

  /** Ticks to wait for a picture to settle after a change, at one a second. */
  private static final int SETTLE = 5;

  private static Path folder;
  private static Timer timer;
  private static View3d view;
  private static int step;
  private static int waited;
  private static int lastSize = -1;
  private static int stable;

  private SelfCheck() {}

  static synchronized void start() {
    String dir = System.getProperty("addisortho.bones.selfcheck"); // NON-NLS
    if (dir == null || timer != null) {
      return;
    }
    folder = Path.of(dir);
    View3d.addBoneIsolatedListener(
        v -> {
          if (view == null) {
            view = v;
            note("bone isolated"); // NON-NLS
          }
        });
    timer = new Timer(1000, _ -> tick());
    timer.start();
    note("started"); // NON-NLS
  }

  private static void tick() {
    try {
      switch (step) {
        case 0 -> open3d();
        case 1 -> {
          if (view != null && settled()) {
            picture("1-bone-only"); // NON-NLS
            BoneGrowTool.selfFind(view);
            next();
          }
        }
        case 2 -> {
          if (BoneGrowTool.selfIdle(view) && settled()) {
            note(BoneGrowTool.selfStatus());
            picture("2-colours-on-ct"); // NON-NLS
            BoneGrowTool.selfLook(true, false);
            next();
          }
        }
        case 3 -> {
          if (settled()) {
            picture("3-solid"); // NON-NLS
            BoneGrowTool.selfLook(false, true);
            next();
          }
        }
        case 4 -> {
          if (settled()) {
            picture("4-natural"); // NON-NLS
            BoneGrowTool.selfLook(true, false);
            note("done"); // NON-NLS
            timer.stop();
          }
        }
        default -> timer.stop();
      }
    } catch (RuntimeException e) {
      LOGGER.error("Bones self-check", e);
      note("failed: " + e); // NON-NLS
      timer.stop();
    }
  }

  private static void next() {
    step++;
    waited = 0;
  }

  private static boolean settled() {
    return ++waited > SETTLE;
  }

  /** Opens the CT of the 2D viewer in 3D once all its images have arrived. */
  @SuppressWarnings("unchecked")
  private static void open3d() {
    MediaSeries<DicomImageElement> series = EventManager.getInstance().getSelectedOriginalSeries();
    if (series == null) {
      return;
    }
    int size = series.size(null);
    stable = size == lastSize ? stable + 1 : 0;
    lastSize = size;
    if (stable < SETTLE) {
      return;
    }
    SeriesViewerFactory factory = GuiUtils.getUICore().getViewerFactory(View3DFactory.class);
    if (factory == null || !factory.canReadSeries(series)) {
      note("the 3D viewer cannot read this series"); // NON-NLS
      timer.stop();
      return;
    }
    note("opening " + size + " images in 3D"); // NON-NLS
    ViewerOpenOptions options =
        ViewerOpenOptions.builder().placement(ViewerPlacement.newTab()).build();
    List<MediaSeries<MediaElement>> list =
        List.of((MediaSeries<MediaElement>) (MediaSeries<?>) series);
    DataExplorerModel model = (DataExplorerModel) series.getTagValue(TagW.ExplorerModel);
    new ViewerPluginBuilder(factory, list, model, options).open();
    next();
  }

  private static void picture(String name) {
    View3dSnapshot.capture(view)
        .ifPresentOrElse(
            frame -> {
              try {
                Files.createDirectories(folder);
                ImageIO.write(frame.toImage(), "png", folder.resolve(name + ".png").toFile());
                note("saved " + name); // NON-NLS
              } catch (IOException e) {
                note("could not save " + name + ": " + e); // NON-NLS
              }
            },
            () -> note("no picture for " + name)); // NON-NLS
  }

  private static void note(String text) {
    try {
      Files.createDirectories(folder);
      Files.writeString(
          folder.resolve("notes.txt"), // NON-NLS
          text.replaceAll("<[^>]*>", "") + System.lineSeparator(),
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException e) {
      LOGGER.warn("Bones self-check note", e);
    }
  }
}
