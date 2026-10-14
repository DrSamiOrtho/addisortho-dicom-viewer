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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;
import org.weasis.dicom.viewer3d.vr.VolumePicker.Hit;

/** Picking on a synthetic sphere whose surface is known analytically. */
class VolumePickerTest {

  private static final int N = 64;
  private static final double RADIUS_VOXELS = 20;
  private static final VolumeSpace SPACE =
      VolumeSpace.ofVolume(new Vector3i(N, N, N), new Vector3d(1, 1, 1));

  /** One voxel in world units (the volume spans [-1, 1]). */
  private static final double VOXEL = 2.0 / N;

  private static final double RADIUS = RADIUS_VOXELS * VOXEL;

  /** The sphere, analytic at any fractional position so the surface is exactly known. */
  private static final VoxelSampler SPHERE =
      new VoxelSampler() {
        @Override
        public double valueAt(int x, int y, int z) {
          return interpolate(x, y, z);
        }

        @Override
        public double interpolate(double x, double y, double z) {
          if (x < 0 || y < 0 || z < 0 || x > N - 1 || y > N - 1 || z > N - 1) {
            return Double.NaN;
          }
          double c = (N - 1) / 2.0;
          double d = Math.sqrt((x - c) * (x - c) + (y - c) * (y - c) + (z - c) * (z - c));
          return d <= RADIUS_VOXELS ? 1000 : 0;
        }
      };

  private static final TransferFunction OPAQUE_ABOVE_500 = TransferFunction.step(500, 0, 2000);

  private static VolumePicker picker() {
    return new VolumePicker(SPACE, SPHERE, OPAQUE_ABOVE_500, t -> false);
  }

  private static final Vector3d FULL_MIN = new Vector3d(0, 0, 0);
  private static final Vector3d FULL_MAX = new Vector3d(1, 1, 1);

  @Test
  void rayThroughTheCentreHitsTheSurfaceWithinHalfAVoxel() {
    Vector3d origin = new Vector3d(0, 0, -3);
    Optional<Hit> hit = picker().pickRay(origin, new Vector3d(0, 0, 1), FULL_MIN, FULL_MAX);
    assertTrue(hit.isPresent());
    assertEquals(3 - RADIUS, hit.get().depth(), VOXEL / 2);
    assertEquals(0.5, hit.get().texCoord().x, VOXEL / 2);
  }

  @Test
  void offCentreRayHitsTheSphereWhereGeometrySaysSo() {
    double ox = 0.3;
    double oy = 0.2;
    Vector3d origin = new Vector3d(ox, oy, -3);
    Optional<Hit> hit = picker().pickRay(origin, new Vector3d(0, 0, 1), FULL_MIN, FULL_MAX);
    assertTrue(hit.isPresent());
    double expected = 3 - Math.sqrt(RADIUS * RADIUS - ox * ox - oy * oy);
    assertEquals(expected, hit.get().depth(), VOXEL / 2);
  }

  @Test
  void rayMissingTheSphereHitsNothing() {
    Vector3d origin = new Vector3d(0.9, 0.9, -3);
    assertTrue(picker().pickRay(origin, new Vector3d(0, 0, 1), FULL_MIN, FULL_MAX).isEmpty());
  }

  @Test
  void cutAwayVoxelsAreSeenThrough() {
    // Everything in the front half (texture z < 0.5) is cut away: the ray lands on the far side.
    VolumePicker picker = new VolumePicker(SPACE, SPHERE, OPAQUE_ABOVE_500, t -> t.z < 0.5);
    Vector3d origin = new Vector3d(0, 0, -3);
    Optional<Hit> hit = picker.pickRay(origin, new Vector3d(0, 0, 1), FULL_MIN, FULL_MAX);
    assertTrue(hit.isPresent());
    assertEquals(3.0, hit.get().depth(), VOXEL);
  }

  @Test
  void screenPointIsTurnedIntoTheShadersRay() {
    Matrix4d view = new Matrix4d().setTranslation(new Vector3d(0, 0, -3));
    Matrix4d proj = new Matrix4d().setPerspective(Math.toRadians(45), 1.0, 0.1, 100);
    Optional<Hit> hit =
        picker()
            .pick(
                view.invert(new Matrix4d()), proj.invert(new Matrix4d()), 0, 0, FULL_MIN, FULL_MAX);
    assertTrue(hit.isPresent());
    assertEquals(3 - RADIUS, hit.get().depth(), VOXEL / 2);
  }

  @Test
  void cropBoxLimitsTheRay() {
    Vector3d origin = new Vector3d(0, 0, -3);
    // The crop keeps only the far half: the near surface is not reached.
    Optional<Hit> hit =
        picker().pickRay(origin, new Vector3d(0, 0, 1), new Vector3d(0, 0, 0.5), FULL_MAX);
    assertTrue(hit.isPresent());
    assertEquals(3.0, hit.get().depth(), VOXEL);
  }
}
