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
package org.weasis.dicom.viewer3d.vr;

import java.util.Optional;
import javax.swing.JOptionPane;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.weasis.dicom.viewer2d.video.FrameSequence;
import org.weasis.dicom.viewer2d.video.VideoExportDialog;
import org.weasis.dicom.viewer2d.video.VideoExportDialog.RotationAxis;
import org.weasis.dicom.viewer2d.video.VideoExportDialog.RotationSettings;
import org.weasis.dicom.viewer2d.video.VideoRecorder;
import org.weasis.dicom.viewer3d.Messages;
import org.weasis.dicom.viewer3d.geometry.Camera;
import org.weasis.dicom.viewer3d.geometry.CameraFraming;

/**
 * Rotation video of the 3D view, RadiAnt's advanced movie: the volume turns from the current view
 * about a screen axis, frame by frame at full quality, and is saved as MP4 or MOV. The frames are
 * the OpenGL picture alone (see {@link View3dSnapshot}), so they hold no patient name.
 */
public final class View3dVideoExport {

  private View3dVideoExport() {}

  /** The screen axis of a rotation: x to the right, y up, z towards the viewer. */
  static Vector3d screenAxis(RotationAxis axis) {
    return switch (axis) {
      case VERTICAL -> new Vector3d(0, 1, 0);
      case HORIZONTAL -> new Vector3d(1, 0, 0);
      case VIEW -> new Vector3d(0, 0, 1);
    };
  }

  public static void export(View3d view) {
    if (!view.hasValidContent() || View3dSnapshot.capture(view).isEmpty()) {
      JOptionPane.showMessageDialog(
          view,
          Messages.getString("3d.picture.error"),
          Messages.getString("3d.viewer"),
          JOptionPane.WARNING_MESSAGE);
      return;
    }
    Optional<RotationSettings> asked = VideoExportDialog.askRotation(view);
    if (asked.isEmpty()) {
      return;
    }
    RotationSettings s = asked.get();
    if (view.isMovieRunning()) {
      view.toggleMovie();
    }
    Camera camera = view.getCamera();
    Vector3d position = new Vector3d(camera.getPosition());
    Quaterniond start = new Quaterniond(camera.getRotation());
    double zoom = camera.getZoomFactor();
    double[] angles = FrameSequence.rotationAngles(s.degrees(), s.frames());
    Vector3d axis = screenAxis(s.axis());
    VideoRecorder.record(
        view,
        s.file(),
        s.format(),
        s.fps(),
        angles.length,
        i -> {
          Quaterniond turned =
              CameraFraming.rotateAboutViewAxis(start, axis, Math.toRadians(angles[i]));
          camera.setAdjusting(false);
          camera.set(position, turned, zoom, false);
          return View3dSnapshot.capture(view).map(View3dSnapshot.Frame::toImage).orElse(null);
        },
        () -> {
          camera.setAdjusting(false);
          camera.set(position, start, zoom);
        });
  }
}
