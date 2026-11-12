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
import java.awt.Color;
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

  /**
   * How deep into each of two bones their border may move when the two clicks alone cannot decide
   * it (a click that finds no cortex, or a result that would leave a bone under a third of itself):
   * each bone then keeps for certain what lies further than this from the other. See {@link
   * AutoSplit#splitBorder}.
   */
  static final double BORDER_BAND_MM = 12;

  private final Kind kind;
  private final Bone bone;
  private final Bone neighbour;
  private final int[] voxel;
  private final int[] keep;
  private final double[] mm;
  private final HuSource hu;
  private final List<Bone> others;
  private volatile List<Color> takenColours;

  /**
   * Cuts the clicked part off at its narrowest link to the bone's anchor.
   *
   * @param voxel the clicked voxel of the bone
   */
  SplitWorker(Kind kind, Bone bone, int[] voxel) {
    this(kind, bone, null, null, voxel, null, null, List.of());
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
   * @param others the bones as they are now, to find the colours beside a fragment cut off
   */
  SplitWorker(
      Bone fragment,
      int[] fragmentVoxel,
      Bone neighbour,
      int[] neighbourVoxel,
      double[] mm,
      HuSource hu,
      List<Bone> others) {
    this(Kind.SPLIT, fragment, neighbour, neighbourVoxel, fragmentVoxel, mm, hu, others);
  }

  private SplitWorker(
      Kind kind,
      Bone bone,
      Bone neighbour,
      int[] keep,
      int[] voxel,
      double[] mm,
      HuSource hu,
      List<Bone> others) {
    this.others = List.copyOf(others);
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

  /**
   * For a fragment cut off one bone: the colours of the bones beside it, found on the worker
   * thread; null otherwise.
   */
  List<Color> takenColours() {
    return takenColours;
  }

  @Override
  protected Split doInBackground() throws SplitException {
    if (keep != null) {
      // The neighbour's side is kept, the fragment's side is cut.
      if (neighbour.id() == bone.id()) {
        Split s = AutoSplit.splitBetween(bone.mask(), keep, voxel, mm[0], mm[1], mm[2], hu);
        takenColours = BoneColors.taken(s.cut(), others);
        return s;
      }
      // Two bones: only the band along their contact is decided again.
      return AutoSplit.splitBorder(
          neighbour.mask(), bone.mask(), keep, voxel, mm[0], mm[1], mm[2], hu, BORDER_BAND_MM);
    }
    return BoneSplit.split(bone.mask(), bone.anchor(), voxel, BoneSplit.MAX_DEPTH);
  }
}
