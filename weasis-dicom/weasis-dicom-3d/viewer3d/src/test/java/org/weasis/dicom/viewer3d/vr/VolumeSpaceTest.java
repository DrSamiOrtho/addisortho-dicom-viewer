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

import org.joml.Vector3d;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;

class VolumeSpaceTest {

  private static final VolumeSpace SPACE =
      new VolumeSpace(
          new Vector3i(256, 256, 330), new Vector3i(512, 512, 660), new Vector3d(1, 1, 0.9));

  @Test
  void textureAndWorldAreInverse() {
    Vector3d t = new Vector3d(0.2, 0.7, 0.4);
    Vector3d w = SPACE.worldOfTexCoord(t, new Vector3d());
    assertEquals(-0.6, w.x, 1e-12);
    assertEquals(0.4, w.y, 1e-12);
    assertEquals(-0.18, w.z, 1e-12);
    Vector3d back = SPACE.texCoordOfWorld(w, new Vector3d());
    assertEquals(t.x, back.x, 1e-12);
    assertEquals(t.y, back.y, 1e-12);
    assertEquals(t.z, back.z, 1e-12);
  }

  @Test
  void volumeSlicesAreUploadedLastFirst() {
    // The first axial slice of the volume is at the top of the texture (z = 1).
    Vector3d t = SPACE.texCoordOfVolume(0, 0, 0, new Vector3d());
    assertEquals(1.0 - 0.5 / 660, t.z, 1e-12);
    Vector3d v = SPACE.volumeOfTexCoord(t, new Vector3d());
    assertEquals(0.0, v.x, 1e-9);
    assertEquals(0.0, v.z, 1e-9);
    // The centre of the texture is the centre of the volume.
    Vector3d centre = SPACE.volumeOfTexCoord(new Vector3d(0.5, 0.5, 0.5), new Vector3d());
    assertEquals(255.5, centre.x, 1e-9);
    assertEquals(329.5, centre.z, 1e-9);
  }

  @Test
  void aCoarserGridCoversTheWholeTexture() {
    Vector3i first = SPACE.gridOfTexCoord(new Vector3d(0, 0, 0), new Vector3i());
    Vector3i last = SPACE.gridOfTexCoord(new Vector3d(1, 1, 1), new Vector3i());
    assertEquals(new Vector3i(0, 0, 0), first);
    assertEquals(new Vector3i(255, 255, 329), last);
    // Two volume voxels share a grid voxel.
    assertEquals(
        SPACE.gridOfVolume(10, 10, 10, new Vector3i()),
        SPACE.gridOfVolume(11, 11, 11, new Vector3i()));
    Vector3d size = SPACE.gridVoxelWorldSize(new Vector3d());
    assertEquals(2.0 / 256, size.x, 1e-12);
    assertEquals(1.8 / 330, size.z, 1e-12);
  }

  @Test
  void gridForHalvesAVolumeAboveTheBudget() {
    assertEquals(new Vector3i(512, 512, 660), KeptMask.gridFor(new Vector3i(512, 512, 660)));
    assertEquals(new Vector3i(512, 512, 1024), KeptMask.gridFor(new Vector3i(1024, 1024, 2048)));
  }
}
