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
    return view(rotation, pivot, pan, 0.0, zoom);
  }

  private static Matrix4d view(
      Quaterniond rotation, Vector3d pivot, Vector2d pan, double depthOffset, double zoom) {
    double distance = CameraFraming.CAMERA_DISTANCE_AT_ZOOM_ONE / zoom;
    double hh = CameraFraming.viewHalfHeight(Math.abs(-distance + depthOffset), FOV);
    return new Matrix4d()
        .setTranslation(new Vector3d(pan.x * hh, pan.y * hh, -distance + depthOffset))
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
  void pivotRebaseKeepsEveryPointWhereItIsIncludingItsDepth() {
    Quaterniond rotation = new Quaterniond().rotationXYZ(-1.2, 0.4, 0.3);
    double zoom = 1.7;
    double distance = CameraFraming.CAMERA_DISTANCE_AT_ZOOM_ONE / zoom;
    Vector3d oldPivot = new Vector3d(0.1, -0.05, 0.2);
    // A move with a large component along the view axis (camera-space z), which is where only
    // compensating x and y would change the perspective size.
    Vector3d delta = new Vector3d(0.3, -0.2, 0.5);
    Vector3d newPivot = new Vector3d(oldPivot).add(delta);
    Vector2d pan = new Vector2d(0.1, -0.05);
    double depth0 = -0.15;
    CameraFraming.PivotRebase rebase =
        CameraFraming.rebasePivot(rotation, delta, pan, distance, depth0, FOV);
    Matrix4d before = view(rotation, oldPivot, pan, depth0, zoom);
    Matrix4d after = view(rotation, newPivot, rebase.pan(), rebase.depthOffset(), zoom);
    Vector3d viewDelta = rotation.transform(new Vector3d(delta));
    assertTrue(Math.abs(viewDelta.z) > 0.1, "the move has a camera-space depth component");
    Random random = new Random(3);
    for (int i = 0; i < 20; i++) {
      Vector3d p =
          new Vector3d(
              random.nextDouble() * 2 - 1,
              random.nextDouble() * 2 - 1,
              random.nextDouble() * 2 - 1);
      Vector4d vb = before.transform(new Vector4d(p, 1));
      Vector4d va = after.transform(new Vector4d(p, 1));
      assertEquals(vb.x, va.x, 1e-9);
      assertEquals(vb.y, va.y, 1e-9);
      assertEquals(vb.z, va.z, 1e-9, "depth, hence the perspective size, is unchanged");
      Vector2d nb = ndc(before, p);
      Vector2d na = ndc(after, p);
      assertEquals(nb.x, na.x, 1e-9);
      assertEquals(nb.y, na.y, 1e-9);
    }
    // The pivot itself now sits at the new depth and on the view axis offset by the pan.
    Vector4d pivotView = after.transform(new Vector4d(newPivot, 1));
    assertEquals(-distance + rebase.depthOffset(), pivotView.z, 1e-9);
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

  /** The orthographic view matrix of the camera: pan at the fixed half height 2 / zoom. */
  private static Matrix4d orthoView(
      Quaterniond rotation, Vector3d pivot, Vector2d pan, double depthOffset, double zoom) {
    double distance = CameraFraming.CAMERA_DISTANCE_AT_ZOOM_ONE / (zoom / 3.5);
    double hh = 2.0 / zoom;
    return new Matrix4d()
        .setTranslation(new Vector3d(pan.x * hh, pan.y * hh, -distance + depthOffset))
        .rotate(rotation)
        .translate(new Vector3d(pivot).negate());
  }

  @Test
  void orthographicPivotRebaseUsesTheProjectionsOwnHalfHeight() {
    Quaterniond rotation = new Quaterniond().rotationXYZ(-1.1, 0.3, 0.2);
    double zoom = 1.0;
    Vector3d oldPivot = new Vector3d();
    Vector3d delta = new Vector3d(0.5, -0.2, 0.3);
    Vector2d pan = new Vector2d(0.05, 0.1);
    double hh = 2.0 / zoom;
    CameraFraming.PivotRebase ortho =
        CameraFraming.rebasePivotOrthographic(rotation, delta, pan, hh, 0.0);
    Matrix4d before = orthoView(rotation, oldPivot, pan, 0.0, zoom);
    Matrix4d after =
        orthoView(rotation, new Vector3d(delta), ortho.pan(), ortho.depthOffset(), zoom);
    Random random = new Random(5);
    for (int i = 0; i < 20; i++) {
      Vector3d p =
          new Vector3d(
              random.nextDouble() * 2 - 1,
              random.nextDouble() * 2 - 1,
              random.nextDouble() * 2 - 1);
      Vector4d vb = before.transform(new Vector4d(p, 1));
      Vector4d va = after.transform(new Vector4d(p, 1));
      assertEquals(vb.x, va.x, 1e-9);
      assertEquals(vb.y, va.y, 1e-9);
      assertEquals(vb.z, va.z, 1e-9);
    }
    // The perspective rebase at the same zoom would shift the image in this projection: it scales
    // the move by a half height that the orthographic view does not have.
    CameraFraming.PivotRebase persp =
        CameraFraming.rebasePivot(
            rotation, delta, pan, CameraFraming.CAMERA_DISTANCE_AT_ZOOM_ONE, 0.0, FOV);
    Matrix4d wrong =
        orthoView(rotation, new Vector3d(delta), persp.pan(), persp.depthOffset(), zoom);
    Vector4d vb = before.transform(new Vector4d(0.2, 0.1, -0.3, 1));
    Vector4d vw = wrong.transform(new Vector4d(0.2, 0.1, -0.3, 1));
    assertTrue(Math.abs(vb.x - vw.x) > 0.05, "the perspective formula is not right here");
  }
}
