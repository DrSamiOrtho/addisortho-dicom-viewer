/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones;

import com.addisortho.viewer.bones.grow.AutoSplit;
import com.addisortho.viewer.bones.grow.BoneSplit;
import com.addisortho.viewer.bones.grow.BoneSplit.Split;
import com.addisortho.viewer.bones.grow.BoneSplit.SplitException;
import com.addisortho.viewer.bones.grow.HuSource;
import javax.swing.SwingWorker;

/** Splits a bone at its narrowest link off the event dispatch thread, for Erase piece and Split. */
final class SplitWorker extends SwingWorker<Split, Void> {

  /** What the clicked part becomes. */
  enum Kind {
    /** dropped */
    ERASE,
    /** a bone of its own */
    SPLIT
  }

  private final Kind kind;
  private final Bone bone;
  private final int[] voxel;
  private final int[] keep;
  private final double[] mm;
  private final HuSource hu;

  /**
   * Cuts the clicked part off at its narrowest link to the bone's anchor.
   *
   * @param voxel the clicked voxel of the bone
   */
  SplitWorker(Kind kind, Bone bone, int[] voxel) {
    this(kind, bone, null, voxel, null, null);
  }

  /**
   * Splits the bone between two marks, along the darkest surface between them on the CT (a
   * fracture line or a joint space).
   *
   * @param keep the mark on the part that stays the bone
   * @param cut the mark on the part split off
   * @param mm voxel size along x, y and z
   * @param hu the CT, read on the worker thread only
   */
  SplitWorker(Bone bone, int[] keep, int[] cut, double[] mm, HuSource hu) {
    this(Kind.SPLIT, bone, keep, cut, mm, hu);
  }

  private SplitWorker(
      Kind kind, Bone bone, int[] keep, int[] voxel, double[] mm, HuSource hu) {
    this.kind = kind;
    this.bone = bone;
    this.keep = keep;
    this.voxel = voxel;
    this.mm = mm;
    this.hu = hu;
  }

  Kind kind() {
    return kind;
  }

  Bone bone() {
    return bone;
  }

  int[] voxel() {
    return voxel;
  }

  @Override
  protected Split doInBackground() throws SplitException {
    if (keep != null) {
      return AutoSplit.splitBetween(bone.mask(), keep, voxel, mm[0], mm[1], mm[2], hu);
    }
    return BoneSplit.split(bone.mask(), bone.anchor(), voxel, BoneSplit.MAX_DEPTH);
  }
}
