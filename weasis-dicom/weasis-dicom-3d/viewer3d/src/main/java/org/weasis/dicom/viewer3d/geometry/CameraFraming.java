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

import org.joml.Quaterniond;
import org.joml.Vector2d;
import org.joml.Vector3d;

/**
 * The maths of a camera that orbits a pivot: the zoom that frames a box, the pan that keeps the
 * image still when the pivot moves, and rotations about the screen axes.
 *
 * <p>The camera looks down its -z axis from the origin; the pivot sits at {@code (pan, -d)} in view
 * space where {@code d = }{@link #CAMERA_DISTANCE_AT_ZOOM_ONE}{@code / zoom}, see {@link Camera}.
 */
public final class CameraFraming {

  /** Distance from the camera to the pivot at zoom 1 (the camera's {@code fov * zNear}). */
  public static final double CAMERA_DISTANCE_AT_ZOOM_ONE = Camera.INITIAL_FOV * Camera.NEAR_PLANE;

  /** Room left around a framed box, as a factor on its half extents. */
  public static final double FIT_MARGIN = 1.08;

  private CameraFraming() {}

  /**
   * The zoom at which the box {@code [min, max]} (world units, centred on the pivot) is entirely in
   * view whatever its orientation on screen is after {@code rotation}.
   *
   * @param fovDegrees vertical field of view
   * @param aspect viewport width over height
   */
  public static double fitZoom(
      Vector3d min, Vector3d max, Quaterniond rotation, double fovDegrees, double aspect) {
    Vector3d centre = new Vector3d(min).add(max).mul(0.5);
    double tanHalf = Math.tan(Math.toRadians(fovDegrees) / 2.0);
    double distance = 0.0;
    Vector3d corner = new Vector3d();
    for (int i = 0; i < 8; i++) {
      corner.set(
          ((i & 1) == 0 ? min.x : max.x) - centre.x,
          ((i & 2) == 0 ? min.y : max.y) - centre.y,
          ((i & 4) == 0 ? min.z : max.z) - centre.z);
      rotation.transform(corner);
      double x = Math.abs(corner.x) * FIT_MARGIN;
      double y = Math.abs(corner.y) * FIT_MARGIN;
      distance = Math.max(distance, x / (tanHalf * aspect) + corner.z);
      distance = Math.max(distance, y / tanHalf + corner.z);
    }
    if (distance <= 0.0) {
      return Camera.SCALE_MAX;
    }
    return Math.clamp(CAMERA_DISTANCE_AT_ZOOM_ONE / distance, Camera.SCALE_MIN, Camera.SCALE_MAX);
  }

  /** The pan and the view-space depth offset that leave the image untouched after a pivot move. */
  public record PivotRebase(Vector2d pan, double depthOffset) {}

  /**
   * The pan and depth offset that keep every point of the volume exactly where it is, in all three
   * view coordinates, when the pivot moves by {@code pivotDelta} in world units. The view transform
   * is T(pan * halfHeight, -distance + depthOffset) R T(-pivot): the rotated delta is folded into
   * the pan on x and y and into the depth offset on z, and the pan is rescaled to the half height
   * at the new depth.
   *
   * @param cameraDistance distance from the camera to the pivot before the move, the zoom's
   * @param depthOffset extra view-space depth before the move
   */
  public static PivotRebase rebasePivot(
      Quaterniond rotation,
      Vector3d pivotDelta,
      Vector2d pan,
      double cameraDistance,
      double depthOffset,
      double fovDegrees) {
    Vector3d d = rotation.transform(new Vector3d(pivotDelta));
    double z0 = -cameraDistance + depthOffset;
    double z1 = z0 + d.z;
    double hh0 = viewHalfHeight(Math.abs(z0), fovDegrees);
    double hh1 = viewHalfHeight(Math.abs(z1), fovDegrees);
    if (hh1 <= 0) {
      return new PivotRebase(new Vector2d(pan), depthOffset + d.z);
    }
    return new PivotRebase(
        new Vector2d((pan.x * hh0 + d.x) / hh1, (pan.y * hh0 + d.y) / hh1), depthOffset + d.z);
  }

  /** Half height of the view at the pivot, in world units. */
  public static double viewHalfHeight(double cameraDistance, double fovDegrees) {
    return cameraDistance * Math.tan(Math.toRadians(fovDegrees) / 2.0);
  }

  /**
   * {@code rotation} turned by {@code radians} about an axis of the screen: x to the right, y up, z
   * towards the viewer.
   */
  public static Quaterniond rotateAboutViewAxis(
      Quaterniond rotation, Vector3d viewAxis, double radians) {
    Quaterniond turn = new Quaterniond().rotateAxis(radians, viewAxis.x, viewAxis.y, viewAxis.z);
    return turn.mul(rotation, new Quaterniond()).normalize();
  }
}
