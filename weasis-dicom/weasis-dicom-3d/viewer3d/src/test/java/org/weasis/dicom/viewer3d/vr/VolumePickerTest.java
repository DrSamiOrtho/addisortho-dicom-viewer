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

  @Test
  void aCoarserMaskGridLeavesThePickUnchanged() {
    // The same ray through the same texture, with the mask grid halved on each axis: the march
    // and the opacity correction follow the texture, so the hit is identical, not half a voxel
    // coarser. A translucent sphere makes the accumulation depend on the step.
    byte[] lut = new byte[2000 * 4];
    for (int i = 0; i < 2000; i++) {
      lut[i * 4 + 3] = (byte) (i >= 500 ? 77 : 0); // alpha 0.3 above 500
    }
    TransferFunction translucent = new TransferFunction(lut, 2000, 1000, 1.0);
    VolumeSpace halved = SPACE.withGrid(new Vector3i(N / 2, N / 2, N / 2));
    Vector3d origin = new Vector3d(0.1, -0.2, -3);
    Vector3d dir = new Vector3d(0, 0, 1);
    Hit full =
        new VolumePicker(SPACE, SPHERE, translucent, t -> false)
            .pickRay(origin, dir, FULL_MIN, FULL_MAX)
            .orElseThrow();
    Hit coarse =
        new VolumePicker(halved, SPHERE, translucent, t -> false)
            .pickRay(origin, dir, FULL_MIN, FULL_MAX)
            .orElseThrow();
    assertEquals(full.depth(), coarse.depth(), 0.0);
    assertEquals(full.texCoord(), coarse.texCoord());
    // A translucent surface is entered before the ray is half opaque: the hit lies past it.
    double surface = 3 - Math.sqrt(RADIUS * RADIUS - 0.1 * 0.1 - 0.2 * 0.2);
    assertTrue(full.depth() > surface + VOXEL, "ray accumulates through the translucent shell");
    // The mask grid still decides what is cut away: a coarse voxel cut hides a texture voxel.
    assertEquals(new Vector3i(N / 2 - 1, N / 2 - 1, N / 2 - 1).x, coarse.grid().x, N / 4);
  }

  /** Anatomy opaque everywhere, so the first sample that is shown is the hit. */
  private static final VoxelSampler SOLID =
      (x, y, z) -> x < 0 || y < 0 || z < 0 || x >= N || y >= N || z >= N ? Double.NaN : 1000;

  private static final VoxelSampler AIR =
      (x, y, z) -> x < 0 || y < 0 || z < 0 || x >= N || y >= N || z >= N ? Double.NaN : 0;

  /** A segment filling the front half of the texture (z < 0.5), another one the back half. */
  private static final SegmentField FRONT = t -> t.z < 0.5 ? 1.0 : 0.0;

  private static final SegmentField BACK = t -> t.z >= 0.5 ? 1.0 : 0.0;

  private static double depthOf(VolumePicker picker) {
    return picker
        .pickRay(new Vector3d(0, 0, -3), new Vector3d(0, 0, 1), FULL_MIN, FULL_MAX)
        .orElseThrow()
        .depth();
  }

  @Test
  void segmentationModesHideOrShowWhatTheRendererDoes() {
    // The ray enters the volume at world z = -1 (depth 2) and reaches its middle at depth 3.
    assertEquals(2.0, depthOf(new VolumePicker(SPACE, SOLID, OPAQUE_ABOVE_500, t -> false)), VOXEL);
    // Mask include with the segment in front: the front is shown, picked at its face.
    assertEquals(
        2.0,
        depthOf(
            new VolumePicker(
                SPACE,
                SOLID,
                OPAQUE_ABOVE_500,
                t -> false,
                VolumePicker.SegMode.MASK_INCLUDE,
                FRONT)),
        VOXEL);
    // Mask include with the segment behind: the front half is hidden, the pick lands on the back.
    assertEquals(
        3.0,
        depthOf(
            new VolumePicker(
                SPACE,
                SOLID,
                OPAQUE_ABOVE_500,
                t -> false,
                VolumePicker.SegMode.MASK_INCLUDE,
                BACK)),
        VOXEL);
    // Mask exclude with the segment in front: the front half is cut away, the pick lands behind it.
    assertEquals(
        3.0,
        depthOf(
            new VolumePicker(
                SPACE,
                SOLID,
                OPAQUE_ABOVE_500,
                t -> false,
                VolumePicker.SegMode.MASK_EXCLUDE,
                FRONT)),
        VOXEL);
    // Segmentation only: the anatomy does not count, the visible segment's surface does.
    assertEquals(
        3.0,
        depthOf(
            new VolumePicker(
                SPACE, SOLID, OPAQUE_ABOVE_500, t -> false, VolumePicker.SegMode.SEG_ONLY, BACK)),
        VOXEL);
    // Overlay with transparent anatomy: the shader draws the overlay only where the anatomy ray
    // produced something (pxColor.a > 0), so with air alone nothing is shown and nothing picked.
    assertTrue(
        new VolumePicker(
                SPACE, AIR, OPAQUE_ABOVE_500, t -> false, VolumePicker.SegMode.OVERLAY, BACK)
            .pickRay(new Vector3d(0, 0, -3), new Vector3d(0, 0, 1), FULL_MIN, FULL_MAX)
            .isEmpty());
    // Overlay with opaque anatomy in front of an opaque segment behind it: the shader composites
    // the segment layer over the anatomy, so the eye lands on the segment.
    assertEquals(
        3.0,
        depthOf(
            new VolumePicker(
                SPACE, SOLID, OPAQUE_ABOVE_500, t -> false, VolumePicker.SegMode.OVERLAY, BACK)),
        VOXEL);
    // A faint, thin segment behind the anatomy does not cover it: its accumulated opacity over
    // 0.1 of the texture stays well below one half, so the anatomy is picked.
    SegmentField faintThin = t -> t.z >= 0.5 && t.z < 0.55 ? 0.05 : 0.0;
    assertEquals(
        2.0,
        depthOf(
            new VolumePicker(
                SPACE,
                SOLID,
                OPAQUE_ABOVE_500,
                t -> false,
                VolumePicker.SegMode.OVERLAY,
                faintThin)),
        VOXEL);
    // A translucent but thick segment does cover: alpha 0.3 over half the texture accumulates to
    // 1 - 0.7^5 = 0.83, crossing one half about 0.19 texture units (0.39 world) past its front.
    SegmentField translucentThick = t -> t.z >= 0.5 ? 0.3 : 0.0;
    double expected = 3.0 + 2 * 0.1 * Math.log(0.5) / Math.log(0.7);
    assertEquals(
        expected,
        depthOf(
            new VolumePicker(
                SPACE,
                SOLID,
                OPAQUE_ABOVE_500,
                t -> false,
                VolumePicker.SegMode.OVERLAY,
                translucentThick)),
        VOXEL);
    // The overlay is drawn only where the anatomy ray produced something: air and a segment
    // behind it with transparent anatomy shows the segment (checked above), but in mask modes
    // nothing changes.
    // Nothing visible at all: no hit.
    assertTrue(
        new VolumePicker(
                SPACE, AIR, OPAQUE_ABOVE_500, t -> false, VolumePicker.SegMode.MASK_INCLUDE, FRONT)
            .pickRay(new Vector3d(0, 0, -3), new Vector3d(0, 0, 1), FULL_MIN, FULL_MAX)
            .isEmpty());
  }

  @Test
  void aCancelledPickGivesUpBetweenSamples() {
    int[] samples = {0};
    VoxelSampler counting =
        (x, y, z) -> {
          samples[0]++;
          return SOLID.valueAt(x, y, z);
        };
    boolean[] cancel = {false};
    VolumePicker picker =
        new VolumePicker(SPACE, counting, OPAQUE_ABOVE_500, t -> false)
            .withCancel(() -> cancel[0] || samples[0] > 0);
    // The first sample flips the flag: the march stops at the next check without a hit.
    assertTrue(
        picker
            .pickRay(new Vector3d(0.9, 0.9, -3), new Vector3d(0, 0, 1), FULL_MIN, FULL_MAX)
            .isEmpty());
    assertTrue(samples[0] <= 8, "stopped after the first sample's lookups: " + samples[0]);
    cancel[0] = false;
    samples[0] = 0;
    assertTrue(
        new VolumePicker(SPACE, SOLID, OPAQUE_ABOVE_500, t -> false)
            .withCancel(() -> false)
            .pickRay(new Vector3d(0, 0, -3), new Vector3d(0, 0, 1), FULL_MIN, FULL_MAX)
            .isPresent());
  }

  @Test
  void aThinFullyOpaqueSegmentCoversTheAnatomyAsItDoesOnScreen() {
    // One texture slice of alpha 1 behind opaque anatomy: the shader's accumulation reaches full
    // opacity in that slice, so the overlay covers the anatomy and the pick lands on the segment.
    SegmentField oneSlice = t -> t.z >= 0.5 && t.z < 0.5 + 1.0 / N ? 1.0 : 0.0;
    double depth =
        depthOf(
            new VolumePicker(
                SPACE,
                SOLID,
                OPAQUE_ABOVE_500,
                t -> false,
                VolumePicker.SegMode.OVERLAY,
                oneSlice));
    assertEquals(3.0, depth, VOXEL);
    // Alpha just below one over the same thin slice does not cover: the anatomy is picked.
    SegmentField nearly = t -> t.z >= 0.5 && t.z < 0.5 + 1.0 / N ? 0.9 : 0.0;
    assertEquals(
        2.0,
        depthOf(
            new VolumePicker(
                SPACE, SOLID, OPAQUE_ABOVE_500, t -> false, VolumePicker.SegMode.OVERLAY, nearly)),
        VOXEL);
  }
}
