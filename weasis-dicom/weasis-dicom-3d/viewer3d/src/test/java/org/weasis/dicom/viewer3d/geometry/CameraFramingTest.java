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
package org.weasis.dicom.viewer3d.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.joml.Vector2d;
import org.joml.Vector3d;
import org.joml.Vector4d;
import org.junit.jupiter.api.Test;

/** The pivot, pan and fit maths of the camera, with the view matrix built as Camera builds it. */
class CameraFramingTest {

  private static final double FOV = 45;
  private static final double ASPECT = 1.5;

  /** The camera's view matrix: pan in view space, rotation, then the pivot brought to the axis. */
  private static Matrix4d view(Quaterniond rotation, Vector3d pivot, Vector2d pan, double zoom) {
    double distance = CameraFraming.CAMERA_DISTANCE_AT_ZOOM_ONE / zoom;
    double hh = CameraFraming.viewHalfHeight(distance, FOV);
    return new Matrix4d()
        .setTranslation(new Vector3d(pan.x * hh, pan.y * hh, -distance))
        .rotate(rotation)
        .translate(new Vector3d(pivot).negate());
  }

  private static Vector2d ndc(Matrix4d view, Vector3d world) {
    Matrix4d proj = new Matrix4d().setPerspective(Math.toRadians(FOV), ASPECT, 0.1, 100);
    Vector4d c = proj.mul(view, new Matrix4d()).transform(new Vector4d(world, 1));
    return new Vector2d(c.x / c.w, c.y / c.w);
  }

  @Test
  void fittedBoxStaysInsideTheView() {
    Random random = new Random(7);
    for (int i = 0; i < 50; i++) {
      Vector3d min = new Vector3d(random.nextDouble() - 1, random.nextDouble() - 1, -0.9);
      Vector3d max =
          new Vector3d(min)
              .add(0.1 + random.nextDouble(), 0.1 + random.nextDouble(), 0.3 + random.nextDouble());
      Quaterniond rotation =
          new Quaterniond()
              .rotationXYZ(
                  random.nextDouble() * 6, random.nextDouble() * 6, random.nextDouble() * 6);
      double zoom = CameraFraming.fitZoom(min, max, rotation, FOV, ASPECT);
      Vector3d pivot = new Vector3d(min).add(max).mul(0.5);
      Matrix4d view = view(rotation, pivot, new Vector2d(), zoom);
      double extent = 0;
      for (int corner = 0; corner < 8; corner++) {
        Vector3d p =
            new Vector3d(
                (corner & 1) == 0 ? min.x : max.x,
                (corner & 2) == 0 ? min.y : max.y,
                (corner & 4) == 0 ? min.z : max.z);
        Vector2d n = ndc(view, p);
        assertTrue(Math.abs(n.x) <= 1.0 + 1e-9 && Math.abs(n.y) <= 1.0 + 1e-9, "corner " + n);
        extent = Math.max(extent, Math.max(Math.abs(n.x), Math.abs(n.y)));
      }
      // The fit is tight: a corner reaches the edge up to the margin.
      assertTrue(extent > 1.0 / CameraFraming.FIT_MARGIN - 0.02, "fit is loose: " + extent);
    }
  }

  @Test
  void panCompensationKeepsTheImageStillWhenThePivotMoves() {
    Quaterniond rotation = new Quaterniond().rotationXYZ(-1.2, 0.4, 0.3);
    double zoom = 1.7;
    double distance = CameraFraming.CAMERA_DISTANCE_AT_ZOOM_ONE / zoom;
    Vector3d oldPivot = new Vector3d(0, 0, 0);
    Vector3d newPivot = new Vector3d(0.3, -0.2, 0.25);
    Vector2d pan = new Vector2d(0.1, -0.05);
    Vector2d compensated =
        new Vector2d(pan)
            .add(
                CameraFraming.panForPivotChange(
                    rotation,
                    new Vector3d(newPivot).sub(oldPivot),
                    CameraFraming.viewHalfHeight(distance, FOV)));
    Matrix4d before = view(rotation, oldPivot, pan, zoom);
    Matrix4d after = view(rotation, newPivot, compensated, zoom);
    // Points at the depth of the pivot keep their place exactly; others move only by the
    // perspective of the small depth change, which the compensation leaves alone.
    Vector3d p = new Vector3d(newPivot).add(0.2, 0.1, 0);
    Vector4d vb = before.transform(new Vector4d(p, 1));
    Vector4d va = after.transform(new Vector4d(p, 1));
    assertEquals(vb.x, va.x, 1e-9);
    assertEquals(vb.y, va.y, 1e-9);
  }

  @Test
  void rotationAboutTheScreenAxesTurnsTheViewNotTheVolumeAxes() {
    Quaterniond rotation = new Quaterniond().rotationXYZ(-1.3, 0.2, 0.5);
    Vector3d world = new Vector3d(0.4, 0.1, -0.2);
    Vector3d inView = rotation.transform(new Vector3d(world));
    Quaterniond rolled =
        CameraFraming.rotateAboutViewAxis(rotation, new Vector3d(0, 0, 1), Math.PI / 2);
    Vector3d afterRoll = rolled.transform(new Vector3d(world));
    // A quarter roll about the view axis turns the view x into view y.
    assertEquals(-inView.y, afterRoll.x, 1e-9);
    assertEquals(inView.x, afterRoll.y, 1e-9);
    assertEquals(inView.z, afterRoll.z, 1e-9);
  }

  @Test
  void zoomCentresOnThePivot() {
    Quaterniond rotation = new Quaterniond().rotationXYZ(-1.57, 0, 0.3);
    Vector3d pivot = new Vector3d(0.3, 0.1, -0.4);
    Vector2d pan = new Vector2d(0.25, -0.1);
    Vector2d n1 = ndc(view(rotation, pivot, pan, 1.0), pivot);
    Vector2d n2 = ndc(view(rotation, pivot, pan, 3.0), pivot);
    // The pivot stays at the same place on screen whatever the zoom: zooming converges on it.
    assertEquals(n1.x, n2.x, 1e-9);
    assertEquals(n1.y, n2.y, 1e-9);
  }
}
