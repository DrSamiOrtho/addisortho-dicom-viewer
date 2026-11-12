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
import com.addisortho.viewer.bones.grow.VoxelBox;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * The colours of the bones. Two bones that touch never get the same colour while the palette has
 * one left: with eight colours handed out in turn, the cuboid, a cuneiform and the fifth metatarsal
 * of a foot were all yellow and read as one bone, and a border between two bones could not be seen
 * (Sami, 12 Nov 2026).
 */
final class BoneColors {

  /** Twelve colours that stay apart on a dark ground and beside each other. */
  static final Color[] PALETTE = {
    new Color(241, 214, 145),
    new Color(111, 184, 210),
    new Color(216, 101, 79),
    new Color(128, 174, 128),
    new Color(183, 156, 220),
    new Color(220, 200, 60),
    new Color(240, 140, 190),
    new Color(90, 130, 220),
    new Color(245, 150, 60),
    new Color(70, 200, 180),
    new Color(170, 110, 90),
    new Color(150, 210, 80)
  };

  /** Two bones this many voxels apart or nearer are neighbours. */
  static final int REACH = 3;

  private BoneColors() {}

  /**
   * A colour for each mask, in order: the next of the palette that no neighbour before it in the
   * list has, so the list still runs through the palette where nothing touches.
   */
  static List<Color> assign(List<BoxMask> masks) {
    List<Color> out = new ArrayList<>();
    for (int i = 0; i < masks.size(); i++) {
      List<Color> taken = new ArrayList<>();
      for (int j = 0; j < i; j++) {
        if (near(masks.get(i), masks.get(j), REACH)) {
          taken.add(out.get(j));
        }
      }
      out.add(firstFree(i, taken));
    }
    return out;
  }

  /** A colour for a new bone beside the bones that are there. */
  static Color pick(int id, BoxMask mask, List<Bone> others) {
    List<Color> taken = new ArrayList<>();
    for (Bone b : others) {
      if (near(mask, b.mask(), REACH)) {
        taken.add(b.color());
      }
    }
    return firstFree(id - 1, taken);
  }

  // the palette from {@code start} on, round once; when every colour is taken, the one at start
  private static Color firstFree(int start, List<Color> taken) {
    for (int k = 0; k < PALETTE.length; k++) {
      Color c = PALETTE[Math.floorMod(start + k, PALETTE.length)];
      if (!taken.contains(c)) {
        return c;
      }
    }
    return PALETTE[Math.floorMod(start, PALETTE.length)];
  }

  /** Whether some voxel of {@code a} lies within {@code reach} voxels of one of {@code b}. */
  static boolean near(BoxMask a, BoxMask b, int reach) {
    if (a.count() > b.count()) {
      return near(b, a, reach);
    }
    VoxelBox ab = a.box();
    VoxelBox bb = b.box();
    int x0 = Math.max(ab.x0(), bb.x0() - reach);
    int x1 = Math.min(ab.x1(), bb.x1() + reach);
    int y0 = Math.max(ab.y0(), bb.y0() - reach);
    int y1 = Math.min(ab.y1(), bb.y1() + reach);
    int z0 = Math.max(ab.z0(), bb.z0() - reach);
    int z1 = Math.min(ab.z1(), bb.z1() + reach);
    for (int z = z0; z <= z1; z++) {
      if (!a.hasVoxelsInSlice(z)) {
        continue;
      }
      for (int y = y0; y <= y1; y++) {
        for (int x = x0; x <= x1; x++) {
          if (!a.contains(x, y, z)) {
            continue;
          }
          for (int r = 0; r <= reach; r++) {
            if (b.contains(x + r, y, z)
                || b.contains(x - r, y, z)
                || b.contains(x, y + r, z)
                || b.contains(x, y - r, z)
                || b.contains(x, y, z + r)
                || b.contains(x, y, z - r)) {
              return true;
            }
          }
        }
      }
    }
    return false;
  }
}
