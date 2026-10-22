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
import com.addisortho.viewer.bones.seg.SegmentationBuilder.Segment;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.dcm4che3.util.UIDUtils;

/**
 * The bones selected on one CT in this session. Each change replaces the whole list, whose bones
 * are never changed, so the undo history only keeps the lists before and after each step. The bones
 * all go into one segmentation series, written again after every change with the bones then
 * selected; a bone holds no voxel of another. Used on the event dispatch thread.
 */
final class GrowSession {

  /** Colours given to the bones in turn: bone, then distinct hues for the neighbours. */
  private static final Color[] COLORS = {
    new Color(241, 214, 145),
    new Color(111, 184, 210),
    new Color(216, 101, 79),
    new Color(128, 174, 128),
    new Color(183, 156, 220),
    new Color(220, 200, 60),
    new Color(240, 140, 190),
    new Color(90, 130, 220)
  };

  /** A bone found near a voxel, and its voxel nearest to it. */
  record Found(Bone bone, int[] voxel) {}

  private final String ctSeriesUID;
  private final String segSeriesUID = UIDUtils.createUID();
  private List<Bone> bones = List.of();
  private int version;
  private int nextId = 1;
  private List<Bone> written = List.of();
  private int writtenVersion;
  private String sopInstanceUID;
  private int instanceNumber;

  GrowSession(String ctSeriesUID) {
    this.ctSeriesUID = ctSeriesUID;
  }

  String ctSeriesUID() {
    return ctSeriesUID;
  }

  String segSeriesUID() {
    return segSeriesUID;
  }

  List<Bone> bones() {
    return bones;
  }

  /** Counts the changes of the list, so a write can tell whether it is still current. */
  int version() {
    return version;
  }

  /** Replaces the list of bones. */
  void set(List<Bone> now) {
    bones = List.copyOf(now);
    version++;
  }

  /** A number for a new bone; never given twice, so undo and redo cannot mix two bones up. */
  int newId() {
    return nextId++;
  }

  Color colorOf(int id) {
    return COLORS[(id - 1) % COLORS.length];
  }

  /** The masks of every bone but {@code id}. */
  List<BoxMask> masksExcept(int id) {
    List<BoxMask> masks = new ArrayList<>();
    for (Bone b : bones) {
      if (b.id() != id) {
        masks.add(b.mask());
      }
    }
    return masks;
  }

  /** The list with {@code bone} added at the end. */
  List<Bone> with(Bone bone) {
    List<Bone> all = new ArrayList<>(bones);
    all.add(bone);
    return all;
  }

  /** The list without the bone {@code id}. */
  List<Bone> without(int id) {
    return bones.stream().filter(b -> b.id() != id).toList();
  }

  /**
   * The list with the bone {@code id} replaced by {@code replacement} where it stood, and {@code
   * added} right after it; a {@code null} replacement takes the bone out.
   */
  List<Bone> replaced(int id, Bone replacement, Bone added) {
    List<Bone> all = new ArrayList<>();
    for (Bone b : bones) {
      if (b.id() != id) {
        all.add(b);
        continue;
      }
      if (replacement != null) {
        all.add(replacement);
      }
      if (added != null) {
        all.add(added);
      }
    }
    return all;
  }

  Optional<Bone> bone(int id) {
    return bones.stream().filter(b -> b.id() == id).findFirst();
  }

  /**
   * The bone holding the voxel; otherwise the bone with a voxel nearest to it within {@code radius}
   * voxels, as a click on the surface may land just outside the bone.
   */
  Optional<Found> boneNear(int[] voxel, int radius) {
    for (Bone b : bones) {
      if (b.mask().contains(voxel[0], voxel[1], voxel[2])) {
        return Optional.of(new Found(b, voxel));
      }
    }
    Found best = null;
    long bestDistance = Long.MAX_VALUE;
    for (Bone b : bones) {
      VoxelBox box = b.mask().box();
      for (int z = Math.max(box.z0(), voxel[2] - radius);
          z <= Math.min(box.z1(), voxel[2] + radius);
          z++) {
        for (int y = Math.max(box.y0(), voxel[1] - radius);
            y <= Math.min(box.y1(), voxel[1] + radius);
            y++) {
          for (int x = Math.max(box.x0(), voxel[0] - radius);
              x <= Math.min(box.x1(), voxel[0] + radius);
              x++) {
            long dx = x - voxel[0];
            long dy = y - voxel[1];
            long dz = z - voxel[2];
            long d = dx * dx + dy * dy + dz * dz;
            if (d < bestDistance && d <= (long) radius * radius && b.mask().contains(x, y, z)) {
              bestDistance = d;
              best = new Found(b, new int[] {x, y, z});
            }
          }
        }
      }
    }
    return Optional.ofNullable(best);
  }

  /** The segments of the list as written: numbered 1, 2, 3... in the list's order. */
  static List<Segment> segments(List<Bone> bones) {
    List<Segment> segments = new ArrayList<>(bones.size());
    for (int i = 0; i < bones.size(); i++) {
      segments.add(bones.get(i).segment(i + 1));
    }
    return segments;
  }

  /** The segmentation instance the explorer holds, or {@code null}. */
  String sopInstanceUID() {
    return sopInstanceUID;
  }

  int nextInstanceNumber() {
    return instanceNumber + 1;
  }

  /** Records the segmentation just written from version {@code fromVersion} of the list. */
  void written(List<Bone> bonesWritten, int fromVersion, String sopUID) {
    this.written = List.copyOf(bonesWritten);
    this.writtenVersion = fromVersion;
    this.sopInstanceUID = sopUID;
    if (sopUID != null) {
      instanceNumber++;
    }
  }

  /** Whether the explorer holds the bones as they are now. */
  boolean isWritten() {
    return writtenVersion == version;
  }

  /**
   * The number of the bone's segment in the segmentation the explorer holds, or 0 when the bone's
   * voxels there are not the ones it has now (not written yet, or changed since).
   */
  int writtenNumber(Bone bone) {
    for (int i = 0; i < written.size(); i++) {
      Bone w = written.get(i);
      if (w.id() == bone.id() && w.mask() == bone.mask()) {
        return i + 1;
      }
    }
    return 0;
  }
}
