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
import com.addisortho.viewer.bones.grow.BoxMask;
import com.addisortho.viewer.bones.grow.HuSource;
import java.util.List;
import javax.swing.SwingWorker;

/**
 * Cuts bones off the event dispatch thread: Erase piece (at the narrowest link) and Separate (a
 * fragment from the bone it presses on, along the fracture line).
 */
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
  private final Bone neighbour;
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
    this(kind, bone, null, null, voxel, null, null);
  }

  /**
   * Separates a fragment from the bone it presses on, along the darkest surface between the two
   * clicks on the CT (a fracture line or a joint space). Both clicks may be on one bone (the
   * fragment is cut off it) or on two (their border is drawn again).
   *
   * @param fragment the bone clicked on the fragment
   * @param fragmentVoxel the click on the fragment
   * @param neighbour the bone clicked second, the one the fragment comes away from
   * @param neighbourVoxel the click on it
   * @param mm voxel size along x, y and z
   * @param hu the CT, read on the worker thread only
   */
  SplitWorker(
      Bone fragment,
      int[] fragmentVoxel,
      Bone neighbour,
      int[] neighbourVoxel,
      double[] mm,
      HuSource hu) {
    this(Kind.SPLIT, fragment, neighbour, neighbourVoxel, fragmentVoxel, mm, hu);
  }

  private SplitWorker(
      Kind kind, Bone bone, Bone neighbour, int[] keep, int[] voxel, double[] mm, HuSource hu) {
    this.kind = kind;
    this.bone = bone;
    this.neighbour = neighbour;
    this.keep = keep;
    this.voxel = voxel;
    this.mm = mm;
    this.hu = hu;
  }

  Kind kind() {
    return kind;
  }

  /** The bone clicked: for Erase piece the one cut, for Separate the fragment's. */
  Bone bone() {
    return bone;
  }

  /** For Separate, the bone the fragment comes away from (may be {@link #bone()} itself). */
  Bone neighbour() {
    return neighbour;
  }

  int[] voxel() {
    return voxel;
  }

  @Override
  protected Split doInBackground() throws SplitException {
    if (keep != null) {
      // The neighbour's side is kept, the fragment's side is cut.
      BoxMask whole =
          neighbour.id() == bone.id()
              ? bone.mask()
              : BoxMask.union(List.of(neighbour.mask(), bone.mask()));
      return AutoSplit.splitBetween(whole, keep, voxel, mm[0], mm[1], mm[2], hu);
    }
    return BoneSplit.split(bone.mask(), bone.anchor(), voxel, BoneSplit.MAX_DEPTH);
  }
}
