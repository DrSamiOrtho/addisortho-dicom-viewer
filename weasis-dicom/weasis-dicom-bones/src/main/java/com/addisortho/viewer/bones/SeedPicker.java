/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.dcm4che3.data.Tag;
import org.joml.Vector3d;
import org.joml.Vector3i;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.api.media.data.MediaSeries;
import org.weasis.core.ui.editor.image.ViewerPlugin;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.viewer2d.EventManager;
import org.weasis.dicom.viewer2d.mpr.MprContainer;
import org.weasis.dicom.viewer2d.mpr.MprController;
import org.weasis.dicom.viewer2d.mpr.MprView;
import org.weasis.dicom.viewer2d.mpr.Volume;

/**
 * Takes the seed of a region grow from the MPR views (axial, coronal, sagittal), in patient space:
 * either the point clicked in one of them once armed, or their crosshair.
 */
final class SeedPicker {

  /**
   * A seed position.
   *
   * @param lps patient position, mm
   * @param frameOfReferenceUID frame of reference of the MPR series it was taken on
   * @param plane the view it was taken in, for the user
   */
  record Seed(Vector3d lps, String frameOfReferenceUID, String plane) {}

  private AWTEventListener listener;

  boolean isArmed() {
    return listener != null;
  }

  /**
   * Waits for the next left click in an MPR view and hands its patient position to {@code onPick},
   * on the event dispatch thread. The click still reaches the view, which may move its crosshair
   * there.
   */
  void arm(Consumer<Seed> onPick) {
    disarm();
    listener =
        event -> {
          if (event instanceof MouseEvent e
              && e.getID() == MouseEvent.MOUSE_PRESSED
              && SwingUtilities.isLeftMouseButton(e)
              && mprViewOf(e.getComponent()) instanceof MprView view) {
            Point p = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), view);
            Optional<Seed> seed = fromClick(view, p);
            if (seed.isPresent()) {
              disarm();
              onPick.accept(seed.get());
            }
          }
        };
    Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK);
  }

  void disarm() {
    if (listener != null) {
      Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
      listener = null;
    }
  }

  private static MprView mprViewOf(Component component) {
    if (component instanceof MprView view) {
      return view;
    }
    return component == null
        ? null
        : (MprView) SwingUtilities.getAncestorOfClass(MprView.class, component);
  }

  /** Patient position of a point of an MPR view, through the transform the view draws with. */
  static Optional<Seed> fromClick(MprView view, Point point) {
    MprController controller = view.getMprController();
    Volume<?, ?> volume = controller == null ? null : controller.getVolume();
    if (volume == null || volume.isBasic()) {
      return Optional.empty();
    }
    Point2D image = view.getImageCoordinatesFromMouse(point.x, point.y);
    Vector3d scaled =
        image == null ? null : view.getScaledVolumeCoordinatesFromImage(image.getX(), image.getY());
    if (scaled == null) {
      return Optional.empty();
    }
    Vector3d voxel = scaled.div(volume.getVoxelRatio());
    return seedAt(volume, voxel, view.getPlane().name());
  }

  /**
   * The crosshair of an MPR showing a series of the given frame of reference: the selected MPR
   * first, else the most recent one.
   */
  static Optional<Seed> fromCrosshair(String frameOfReferenceUID) {
    List<MprContainer> containers = new ArrayList<>();
    if (EventManager.getInstance().getSelectedView2dContainer() instanceof MprContainer selected) {
      containers.add(selected);
    }
    List<ViewerPlugin<?>> plugins = GuiUtils.getUICore().getViewerPlugins();
    synchronized (plugins) {
      for (int i = plugins.size() - 1; i >= 0; i--) {
        if (plugins.get(i) instanceof MprContainer c && !containers.contains(c)) {
          containers.add(c);
        }
      }
    }
    for (MprContainer container : containers) {
      MprController controller = container.getMprController();
      Volume<?, ?> volume = controller == null ? null : controller.getVolume();
      Vector3d centre = controller == null ? null : controller.getCrossHairPosition();
      if (volume == null
          || centre == null
          || volume.isBasic()
          || !Objects.equals(frameOfReferenceUID, frameOfReferenceOf(volume))) {
        continue;
      }
      // Inverse of MprController.toSliceSpace: the crosshair lives in the slice-size cube.
      int slice = volume.getSliceSize();
      Vector3i size = volume.getSize();
      Vector3d ratio = volume.getVoxelRatio();
      Vector3d voxel =
          new Vector3d(
              (centre.x - (slice - size.x * ratio.x) / 2.0) / ratio.x,
              (centre.y - (slice - size.y * ratio.y) / 2.0) / ratio.y,
              (centre.z - (slice - size.z * ratio.z) / 2.0) / ratio.z);
      Optional<Seed> seed = seedAt(volume, voxel, "crosshair"); // NON-NLS
      if (seed.isPresent()) {
        return seed;
      }
    }
    return Optional.empty();
  }

  private static Optional<Seed> seedAt(Volume<?, ?> volume, Vector3d voxel, String plane) {
    try {
      Vector3d lps = volume.voxelToLps(voxel.x, voxel.y, voxel.z, new Vector3d());
      return Optional.of(new Seed(lps, frameOfReferenceOf(volume), plane));
    } catch (UnsupportedOperationException e) {
      return Optional.empty();
    }
  }

  static String frameOfReferenceOf(Volume<?, ?> volume) {
    if (volume.getStack() == null) {
      return null;
    }
    MediaSeries<DicomImageElement> series = volume.getStack().getSeries();
    String uid = TagD.getTagValue(series, Tag.FrameOfReferenceUID, String.class);
    if (uid == null && volume.getStack().getMiddleImage() != null) {
      uid =
          TagD.getTagValue(
              volume.getStack().getMiddleImage(), Tag.FrameOfReferenceUID, String.class);
    }
    return uid;
  }
}
