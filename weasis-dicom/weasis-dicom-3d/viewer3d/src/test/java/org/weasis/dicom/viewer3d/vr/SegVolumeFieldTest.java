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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/** The CPU segment field against the shader's label and smoothed-occupancy rules. */
class SegVolumeFieldTest {

  private static final int N = 16;

  /** Segment 1: a disc of radius 5 on slices 4 to 7 and of radius 2 on slices 8 to 11. */
  private static final SegVolumeField.LabelSource LABELS =
      new SegVolumeField.LabelSource() {
        @Override
        public int sizeX() {
          return N;
        }

        @Override
        public int sizeY() {
          return N;
        }

        @Override
        public int sizeZ() {
          return N;
        }

        @Override
        public int[] slice(int z) {
          int[] ids = new int[N * N];
          double r = z >= 4 && z <= 7 ? 5 : z >= 8 && z <= 11 ? 2 : -1;
          for (int y = 0; y < N; y++) {
            for (int x = 0; x < N; x++) {
              double d = Math.hypot(x - 7.5, y - 7.5);
              ids[y * N + x] = d <= r ? 1 : 0;
            }
          }
          return ids;
        }
      };

  private static byte[] lut(int alpha1) {
    return new byte[] {0, 0, 0, 0, (byte) 255, 0, 0, (byte) alpha1};
  }

  /** Texture coordinate of a point given in segmentation voxel units (z in volume order). */
  private static Vector3d at(double x, double y, double volumeZ) {
    double texZ = N - 1 - volumeZ;
    return new Vector3d((x + 0.5) / N, (y + 0.5) / N, (texZ + 0.5) / N);
  }

  @Test
  void labelFieldIsTheNearestLabelWithItsLutAlpha() {
    SegVolumeField field = new SegVolumeField(LABELS, lut(128), false);
    assertEquals(128 / 255.0, field.opacity(at(7.5, 7.5, 5)), 1e-6);
    assertTrue(field.insideVisible(at(11, 7, 5)), "inside the wide disc");
    assertFalse(field.insideVisible(at(11, 7, 9)), "outside the narrow disc");
    assertFalse(field.insideVisible(at(7.5, 7.5, 2)));
    assertEquals(0, new SegVolumeField(LABELS, lut(0), false).opacity(at(7.5, 7.5, 5)), 0.0);
  }

  @Test
  void smoothFieldFollowsTheTrilinearOccupancyNotTheLabel() {
    SegVolumeField smooth = new SegVolumeField(LABELS, lut(255), true);
    SegVolumeField labels = new SegVolumeField(LABELS, lut(255), false);
    // Between the last wide slice (volume 7) and the first narrow one (8), at radius 4: the label
    // sampled nearest says inside on the wide side and outside on the narrow side, while the
    // smoothed occupancy runs from +1.5 voxels inside to -2 outside across the gap, so the
    // displayed surface crosses one half part way between the slices.
    Vector3d wideSide = at(11.5, 7, 7.3);
    Vector3d narrowSide = at(11.5, 7, 7.7);
    assertTrue(labels.insideVisible(wideSide));
    assertFalse(labels.insideVisible(narrowSide));
    double occWide = smooth.occupancy(wideSide);
    double occNarrow = smooth.occupancy(narrowSide);
    assertTrue(occWide > occNarrow);
    // The occupancy is exactly the interpolation of the encoded slices the renderer uploads.
    boolean[] insideWide = new boolean[N * N];
    boolean[] insideNarrow = new boolean[N * N];
    for (int i = 0; i < N * N; i++) {
      insideWide[i] = LABELS.slice(7)[i] == 1;
      insideNarrow[i] = LABELS.slice(8)[i] == 1;
    }
    byte[] encWide = SegOccupancy.encodeSlice(insideWide, N, N);
    byte[] encNarrow = SegOccupancy.encodeSlice(insideNarrow, N, N);
    int i = 7 * N + 11; // x 11 and 12 at y 7 (x 11.5 is between them), weights 0.5 each
    double w = (encWide[i] & 0xFF) * 0.5 + (encWide[i + 1] & 0xFF) * 0.5;
    double n = (encNarrow[i] & 0xFF) * 0.5 + (encNarrow[i + 1] & 0xFF) * 0.5;
    // Volume z 7.3 is texture z 7.7, i.e. 7.7 - 0.5 = 7.2 in voxel units: 0.3 of texture slice 7
    // (volume slice 8, narrow) and 0.7 of texture slice 8 (volume slice 7, wide).
    assertEquals((0.7 * w + 0.3 * n) / 255.0, occWide, 1e-9);
    assertEquals(occWide >= 0.5, smooth.insideVisible(wideSide));
    assertEquals(occNarrow >= 0.5, smooth.insideVisible(narrowSide));
    // Just outside the wide disc's label but within the smoothed surface: the field takes the
    // alpha of the nearest visible label around the point, as segSurfaceColor does.
    Vector3d rim = at(12.6, 7.5, 5.0);
    assertFalse(labels.insideVisible(rim));
    if (smooth.insideVisible(rim)) {
      assertEquals(1.0, smooth.opacity(rim), 1e-9);
    }
    // Beyond the volume the occupancy is the texture's zero border.
    assertEquals(0.0, smooth.occupancy(new Vector3d(1.2, 0.5, 0.5)), 0.0);
  }
}
