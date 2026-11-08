/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.grow;

/**
 * Splits a selected bone in two at its narrowest link, between the part holding the bone's own
 * starting point and the part clicked on: the bone is eroded one voxel at a time until the two
 * points fall in different pieces, then every piece grows back inside the bone at the same pace, so
 * the cut runs where the link was thinnest. A part already loose from the rest comes away at once.
 *
 * <p>Used for both Erase piece (the clicked part is dropped) and Split (it becomes a bone of its
 * own). Run off the event dispatch thread; interrupting the thread cancels it.
 */
public final class BoneSplit {

  /**
   * Deepest erosion tried, in voxels. A joint or a leak through a vessel is a few voxels thick;
   * deeper erosion would wipe out the thin parts of the bone itself.
   */
  public static final int MAX_DEPTH = 12;

  /**
   * The two parts.
   *
   * @param kept the part holding the bone's starting point
   * @param cut the part clicked on
   * @param depth erosion, in voxels, that set the parts apart; 0 for a loose part
   */
  public record Split(BoxMask kept, BoxMask cut, int depth) {}

  /** The bone cannot be split there. */
  public static final class SplitException extends Exception {

    /** Why the bone cannot be split there. */
    public enum Reason {
      /** The clicked voxel is not part of the bone. */
      NOT_IN_BONE,
      /**
       * No erosion up to {@link #MAX_DEPTH} sets the clicked part apart from the starting point.
       */
      NOT_SEPARABLE
    }

    private final Reason reason;

    SplitException(Reason reason) {
      super(reason.name());
      this.reason = reason;
    }

    public Reason reason() {
      return reason;
    }
  }

  private BoneSplit() {}

  /**
   * @param keep the bone's starting point, grid coordinates; the voxel of the bone farthest from
   *     {@code cut} stands in for it when it is no longer part of the bone
   * @param cut the clicked voxel, grid coordinates
   * @throws SplitException when the click is outside the bone or no link is thin enough
   */
  public static Split split(BoxMask bone, int[] keep, int[] cut, int maxDepth)
      throws SplitException {
    Thread caller = Thread.currentThread();
    VoxelBox box = bone.box();
    // One clear voxel around the bone, so the erosion eats the bone's faces on the block's edges.
    BitVolume solid = bone.bits().padded(1);
    int ox = box.x0() - 1;
    int oy = box.y0() - 1;
    int oz = box.z0() - 1;
    int[] c = {cut[0] - ox, cut[1] - oy, cut[2] - oz};
    if (!RegionGrow.inside(solid, c[0], c[1], c[2]) || !solid.get(c[0], c[1], c[2])) {
      throw new SplitException(SplitException.Reason.NOT_IN_BONE);
    }
    int[] k = {keep[0] - ox, keep[1] - oy, keep[2] - oz};
    if (!RegionGrow.inside(solid, k[0], k[1], k[2]) || !solid.get(k[0], k[1], k[2])) {
      k = farthest(solid, c);
    }
    VoxelBox block = new VoxelBox(ox, box.x1() + 1, oy, box.y1() + 1, oz, box.z1() + 1);
    BitVolume core = solid;
    for (int depth = 0; depth <= maxDepth; depth++) {
      RegionGrow.checkCancelled(caller);
      if (depth > 0) {
        core = core.morph(1, false, false, null, caller);
      }
      int[] ks = core.get(k[0], k[1], k[2]) ? k : RegionGrow.nearestInside(solid, core, k, caller);
      int[] cs = core.get(c[0], c[1], c[2]) ? c : RegionGrow.nearestInside(solid, core, c, caller);
      if (ks == null || cs == null) {
        break; // the erosion has worn one of the two parts away
      }
      BitVolume keptCore = solid.emptyCopy();
      RegionGrow.flood(core, ks, keptCore, false, caller);
      if (keptCore.get(cs[0], cs[1], cs[2])) {
        continue;
      }
      BitVolume cutCore = solid.emptyCopy();
      RegionGrow.flood(core, cs, cutCore, false, caller);
      BitVolume cutPart = depth == 0 ? cutCore : RegionGrow.regrow(solid, core, cutCore, caller);
      BitVolume keptPart = solid.copy();
      keptPart.andNot(cutPart);
      BoxMask kept = BoxMask.trimmed(block, keptPart);
      BoxMask gone = BoxMask.trimmed(block, cutPart);
      if (kept == null || gone == null) {
        break;
      }
      return new Split(kept, gone, depth);
    }
    throw new SplitException(SplitException.Reason.NOT_SEPARABLE);
  }

  /** The set voxel of {@code v} farthest from {@code p}. */
  private static int[] farthest(BitVolume v, int[] p) {
    int[] best = p;
    long bestDistance = -1;
    for (int z = 0; z < v.sizeZ(); z++) {
      if (v.countSlice(z) == 0) {
        continue;
      }
      for (int y = 0; y < v.sizeY(); y++) {
        for (int x = 0; x < v.sizeX(); x++) {
          if (v.get(x, y, z)) {
            long dx = x - p[0];
            long dy = y - p[1];
            long dz = z - p[2];
            long d = dx * dx + dy * dy + dz * dz;
            if (d > bestDistance) {
              bestDistance = d;
              best = new int[] {x, y, z};
            }
          }
        }
      }
    }
    return best;
  }
}
