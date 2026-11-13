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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.dcm4che3.util.UIDUtils;

/**
 * The bones selected on one CT in this session. Each change replaces the whole list, whose bones
 * are never changed, so the undo history only keeps the lists before and after each step. The bones
 * all go into one segmentation series, written again after every change with the bones then
 * selected; a bone holds no voxel of another. Used on the event dispatch thread.
 */
final class GrowSession {

  /** Colours given to the bones in turn: bone, then distinct hues for the neighbours. */
  /** A bone found near a voxel, and its voxel nearest to it. */
  record Found(Bone bone, int[] voxel) {}

  private final String ctSeriesUID;
  private final String segSeriesUID = UIDUtils.createUID();
  private List<Bone> bones = List.of();
  private final Set<Integer> selected = new LinkedHashSet<>();
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

  /**
   * Counts the changes of what is written, so a write can tell whether it is still current: hiding
   * or showing a bone does not count.
   */
  int version() {
    return version;
  }

  /** Replaces the list of bones. */
  void set(List<Bone> now) {
    List<Bone> before = bones;
    bones = List.copyOf(now);
    if (!sameSegments(before, bones)) {
      version++;
    }
    selected.retainAll(bones.stream().map(Bone::id).toList());
  }

  private static boolean sameSegments(List<Bone> a, List<Bone> b) {
    if (a.size() != b.size()) {
      return false;
    }
    for (int i = 0; i < a.size(); i++) {
      if (!a.get(i).sameSegment(b.get(i))) {
        return false;
      }
    }
    return true;
  }

  /** The bones selected by a click, in the order clicked; not part of the undo history. */
  Set<Integer> selected() {
    return selected;
  }

  /** The selected bones, in the order of the list. */
  List<Bone> selectedBones() {
    return bones.stream().filter(b -> selected.contains(b.id())).toList();
  }

  /** A number for a new bone; never given twice, so undo and redo cannot mix two bones up. */
  int newId() {
    return nextId++;
  }

  /** A colour for a new bone with this mask: one that no bone touching it has. */
  Color colorOf(int id, BoxMask mask) {
    return BoneColors.pick(id, mask, bones);
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

  /** How far from a bone, in mm, a click on its surface still counts as on it. */
  static final double NEAR_CLICK_MM = 1.5;

  private double mmX = 1;
  private double mmY = 1;
  private double mmZ = 1;

  /** The voxel size of the session's CT, along the columns, the rows and across the slices. */
  void voxelSize(double x, double y, double z) {
    if (x > 0 && y > 0 && z > 0) {
      mmX = x;
      mmY = y;
      mmZ = z;
    }
  }

  double[] voxelSize() {
    return new double[] {mmX, mmY, mmZ};
  }

  /**
   * The bone a click on a surface means: the one holding the voxel, otherwise the nearest within
   * {@link #NEAR_CLICK_MM}, or within one pixel where the pixels are wider than that. Across slices
   * further apart than that no bone is taken from the next slice.
   */
  Optional<Found> boneNearClick(int[] voxel) {
    return boneNear(voxel, Math.max(NEAR_CLICK_MM, Math.max(mmX, mmY)), mmX, mmY, mmZ);
  }

  /**
   * The bone holding the voxel; otherwise the bone with a voxel nearest to it within {@code radius}
   * voxels, as a click on the surface may land just outside the bone.
   */
  Optional<Found> boneNear(int[] voxel, int radius) {
    return boneNear(voxel, radius, 1, 1, 1);
  }

  /**
   * The bone holding the voxel; otherwise the bone with a voxel nearest to it within {@code
   * radiusMm}, the distance in mm with the voxel size of each axis. A count of voxels reached 9 to
   * 15 mm across 3 to 5 mm slices and took the bone of the next slice for the one clicked (review
   * of 11 Nov 2026, B8).
   */
  Optional<Found> boneNear(int[] voxel, double radiusMm, double mmX, double mmY, double mmZ) {
    for (Bone b : bones) {
      if (b.mask().contains(voxel[0], voxel[1], voxel[2])) {
        return Optional.of(new Found(b, voxel));
      }
    }
    Found best = null;
    double bestDistance = Double.MAX_VALUE;
    double limit = radiusMm * radiusMm + 1e-9;
    int rx = (int) Math.floor(radiusMm / mmX + 1e-9);
    int ry = (int) Math.floor(radiusMm / mmY + 1e-9);
    int rz = (int) Math.floor(radiusMm / mmZ + 1e-9);
    for (Bone b : bones) {
      VoxelBox box = b.mask().box();
      for (int z = Math.max(box.z0(), voxel[2] - rz); z <= Math.min(box.z1(), voxel[2] + rz); z++) {
        for (int y = Math.max(box.y0(), voxel[1] - ry);
            y <= Math.min(box.y1(), voxel[1] + ry);
            y++) {
          for (int x = Math.max(box.x0(), voxel[0] - rx);
              x <= Math.min(box.x1(), voxel[0] + rx);
              x++) {
            double dx = (x - voxel[0]) * mmX;
            double dy = (y - voxel[1]) * mmY;
            double dz = (z - voxel[2]) * mmZ;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestDistance && d <= limit && b.mask().contains(x, y, z)) {
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
