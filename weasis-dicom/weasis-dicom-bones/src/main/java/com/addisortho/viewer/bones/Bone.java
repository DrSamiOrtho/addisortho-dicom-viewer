/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones;

import com.addisortho.viewer.bones.grow.BoxMask;
import com.addisortho.viewer.bones.seg.SegmentationBuilder.Segment;
import java.awt.Color;

/**
 * A selected bone of a CT: its voxels and how it is shown. Never changed; an edit makes a new one,
 * so an undo step keeps the bones it had.
 *
 * @param id the bone's own number in its session, never given twice; the segment numbers of the
 *     written segmentation follow the list order instead
 * @param label name shown in the list and written as the segment label
 * @param mask the voxels, on the CT grid
 * @param anchor the voxel the bone started from (its seed, or the click that split it off): Erase
 *     piece and Split keep the part holding it
 * @param description how the bone was made, written as the segment description
 */
record Bone(int id, String label, Color color, BoxMask mask, int[] anchor, String description) {

  Bone withMask(BoxMask m) {
    return new Bone(id, label, color, m, anchor, description);
  }

  Bone withLabel(String l) {
    return new Bone(id, l, color, mask, anchor, description);
  }

  Bone withColor(Color c) {
    return new Bone(id, label, c, mask, anchor, description);
  }

  /** The segment written for this bone, numbered {@code number} in the segmentation. */
  Segment segment(int number) {
    return new Segment(number, label, color, mask, description);
  }

  double volumeCm3(double voxelVolumeMm3) {
    return mask.count() * voxelVolumeMm3 / 1000.0;
  }
}
