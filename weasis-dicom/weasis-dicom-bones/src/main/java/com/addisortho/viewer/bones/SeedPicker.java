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
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;
import java.util.Optional;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.dcm4che3.data.Tag;
import org.joml.Vector3d;
import org.weasis.core.api.media.data.MediaSeries;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.viewer2d.mpr.MprController;
import org.weasis.dicom.viewer2d.mpr.MprView;
import org.weasis.dicom.viewer2d.mpr.Volume;
import org.weasis.dicom.viewer3d.vr.View3d;

/**
 * Takes points of the CT in patient space for the Bones tools: the point clicked while armed, on
 * the bone surface of the 3D view or in an MPR view (axial, coronal, sagittal).
 */
final class SeedPicker {

  /** Farthest a click may move between press and release, in pixels; more is a drag. */
  private static final int CLICK_TOLERANCE = 4;

  /**
   * A seed position.
   *
   * @param lps patient position, mm
   * @param frameOfReferenceUID frame of reference of the MPR series it was taken on
   * @param plane the view it was taken in, for the user
   */
  record Seed(Vector3d lps, String frameOfReferenceUID, String plane) {}

  private AWTEventListener listener;
  private Component pressedView;
  private Point pressedAt;

  boolean isArmed() {
    return listener != null;
  }

  /**
   * Hands every left click on a 3D view or an MPR view to {@code onPick} as a patient position, on
   * the event dispatch thread, until disarmed. A press and release at the same place is a click; a
   * drag still rotates the 3D view or moves through the MPR as usual. The views receive the click
   * too, so the 3D view's left button should hold a tool that does nothing on a click, such as
   * rotation. A click in the 3D view that meets nothing is reported to {@code onMiss}.
   */
  void arm(Consumer<Seed> onPick, Consumer<String> onMiss) {
    disarm();
    listener =
        event -> {
          if (!(event instanceof MouseEvent e)
              || !SwingUtilities.isLeftMouseButton(e)
              || (e.getModifiersEx() & InputEvent.ALT_DOWN_MASK) != 0) {
            return;
          }
          Component c = e.getComponent();
          Component view = view3dOf(c);
          if (view == null) {
            view = mprViewOf(c);
          }
          if (view == null) {
            return;
          }
          Point p = SwingUtilities.convertPoint(c, e.getPoint(), view);
          if (e.getID() == MouseEvent.MOUSE_PRESSED) {
            pressedView = view;
            pressedAt = p;
            return;
          }
          if (e.getID() != MouseEvent.MOUSE_RELEASED) {
            return;
          }
          boolean click =
              view == pressedView && pressedAt != null && pressedAt.distance(p) <= CLICK_TOLERANCE;
          pressedView = null;
          pressedAt = null;
          if (!click) {
            return;
          }
          if (view instanceof View3d view3d) {
            pick3d(view3d, p, onPick, onMiss);
          } else if (view instanceof MprView mpr) {
            fromClick(mpr, p)
                .ifPresentOrElse(
                    onPick, () -> onMiss.accept(Messages.getString("BoneGrowTool.missed")));
          }
        };
    Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK);
  }

  /** The surface the 3D view shows under the point, through the view's own picking. */
  private void pick3d(View3d view, Point p, Consumer<Seed> onPick, Consumer<String> onMiss) {
    String unavailable = view.pickUnavailableReason();
    if (unavailable != null) {
      onMiss.accept(unavailable);
      return;
    }
    AWTEventListener armed = listener;
    view.pickAsync(
        p,
        hit -> {
          if (listener != armed) {
            return; // disarmed or armed again meanwhile
          }
          Optional<Vector3d> lps = hit.flatMap(view::lpsOf);
          Volume<?, ?> volume =
              view.getVolTexture() == null ? null : view.getVolTexture().getVolume();
          if (lps.isEmpty() || volume == null) {
            onMiss.accept(Messages.getString("BoneGrowTool.missed"));
            return;
          }
          onPick.accept(new Seed(lps.get(), frameOfReferenceOf(volume), "3D")); // NON-NLS
        });
  }

  private static View3d view3dOf(Component component) {
    if (component instanceof View3d view) {
      return view;
    }
    return component == null
        ? null
        : (View3d) SwingUtilities.getAncestorOfClass(View3d.class, component);
  }

  void disarm() {
    if (listener != null) {
      Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
      listener = null;
    }
    pressedView = null;
    pressedAt = null;
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
