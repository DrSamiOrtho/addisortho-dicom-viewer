/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones;

import com.addisortho.viewer.bones.grow.BoneSplit;
import com.addisortho.viewer.bones.grow.BoneSplit.Split;
import com.addisortho.viewer.bones.grow.BoneSplit.SplitException;
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

  /**
   * @param voxel the clicked voxel of the bone
   */
  SplitWorker(Kind kind, Bone bone, int[] voxel) {
    this.kind = kind;
    this.bone = bone;
    this.voxel = voxel;
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
    return BoneSplit.split(bone.mask(), bone.anchor(), voxel, BoneSplit.MAX_DEPTH);
  }
}
