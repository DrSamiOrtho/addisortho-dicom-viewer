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

import java.util.ArrayDeque;
import java.util.Deque;
import org.joml.Vector3d;
import org.joml.Vector3i;

/**
 * Which voxels of the volume are still shown after the scalpel and keep/remove cuts: one bit per
 * voxel of a grid laid over the volume texture, with the cuts kept as a history of {@link MaskDiff}
 * for undo and redo. The renderer skips every voxel whose bit is clear, through the whole depth and
 * whatever the camera does.
 *
 * <p>The grid is the texture itself up to {@link #MAX_FULL_RESOLUTION_VOXELS} voxels; a larger
 * volume gets a grid halved on each axis (one mask voxel for eight texture voxels), which keeps the
 * GPU copy, one byte per grid voxel, within budget.
 *
 * <p>Not thread-safe: cuts are computed on a worker thread from a consistent snapshot and applied
 * on the event dispatch thread, which also reads the mask for rendering.
 */
public final class KeptMask {

  /** Largest grid kept at the texture's own resolution. */
  public static final long MAX_FULL_RESOLUTION_VOXELS = 256L * 1024 * 1024;

  /** Longest undo history kept; the oldest cut is forgotten beyond it. */
  public static final int MAX_HISTORY = 32;

  private final VolumeSpace space;
  private final VoxelBits kept;
  private final Deque<MaskDiff> undo = new ArrayDeque<>();
  private final Deque<MaskDiff> redo = new ArrayDeque<>();
  private long removedVoxels;
  private int dirtyMin = Integer.MAX_VALUE;
  private int dirtyMax = -1;
  private int generation;

  public KeptMask(VolumeSpace space) {
    this.space = space;
    this.kept = new VoxelBits(space.grid().x, space.grid().y, space.grid().z);
    this.kept.fill(true);
  }

  /** The grid for a texture of the given size: the texture itself, or halved when too large. */
  public static Vector3i gridFor(Vector3i textureSize) {
    Vector3i grid = new Vector3i(textureSize);
    while ((long) grid.x * grid.y * grid.z > MAX_FULL_RESOLUTION_VOXELS) {
      grid.set(Math.max(1, grid.x / 2), Math.max(1, grid.y / 2), Math.max(1, grid.z / 2));
    }
    return grid;
  }

  public VolumeSpace space() {
    return space;
  }

  /** The bits themselves, read by the cuts; never modify them directly. */
  public VoxelBits bits() {
    return kept;
  }

  public boolean isKept(int x, int y, int z) {
    return kept.get(x, y, z);
  }

  /** Whether the volume is shown at a texture coordinate. */
  public boolean isKeptAt(Vector3d texCoord) {
    Vector3i g = space.gridOfTexCoord(texCoord, new Vector3i());
    return kept.get(g.x, g.y, g.z);
  }

  /**
   * Whether the voxel {@code (vx, vy, vz)} of the CPU volume is shown; the API for the bone export
   * and segmentation, which work on the volume's own grid.
   */
  public boolean isKeptVolumeVoxel(int vx, int vy, int vz) {
    Vector3i g = space.gridOfVolume(vx, vy, vz, new Vector3i());
    return kept.get(g.x, g.y, g.z);
  }

  public long removedVoxels() {
    return removedVoxels;
  }

  public boolean isFull() {
    return removedVoxels == 0;
  }

  public boolean canUndo() {
    return !undo.isEmpty();
  }

  public boolean canRedo() {
    return !redo.isEmpty();
  }

  /** Changes each time the voxels change, so a renderer knows when to upload. */
  public int generation() {
    return generation;
  }

  /** Applies a cut and makes it the last undoable step; an empty diff changes nothing. */
  public void apply(MaskDiff diff) {
    if (diff.isEmpty()) {
      return;
    }
    flip(diff);
    undo.push(diff);
    if (undo.size() > MAX_HISTORY) {
      undo.removeLast();
    }
    redo.clear();
  }

  public boolean undo() {
    MaskDiff diff = undo.poll();
    if (diff == null) {
      return false;
    }
    flip(diff);
    redo.push(diff);
    return true;
  }

  public boolean redo() {
    MaskDiff diff = redo.poll();
    if (diff == null) {
      return false;
    }
    flip(diff);
    undo.push(diff);
    return true;
  }

  /** Shows the whole volume again, as an undoable step. */
  public void restore() {
    if (isFull()) {
      return;
    }
    VoxelBits removed = new VoxelBits(kept.sizeX(), kept.sizeY(), kept.sizeZ());
    for (int z = 0; z < kept.sizeZ(); z++) {
      long[] src = kept.sliceWords(z);
      long[] dst = removed.sliceWords(z);
      for (int w = 0; w < src.length; w++) {
        dst[w] = ~src[w];
      }
      // The bits past the slice's voxels must stay clear.
      removed.flipRange(z, kept.sliceBits(), src.length << 6);
    }
    apply(MaskDiff.ofSetVoxels(removed));
  }

  /** Forgets the undo and redo history; the voxels do not change. */
  public void clearHistory() {
    undo.clear();
    redo.clear();
  }

  /** Memory of the bits and of the history. */
  public long bytes() {
    long n = kept.bytes();
    for (MaskDiff d : undo) {
      n += d.bytes();
    }
    for (MaskDiff d : redo) {
      n += d.bytes();
    }
    return n;
  }

  /** First slice changed since {@link #clearDirty()}, or {@link Integer#MAX_VALUE}. */
  public int dirtyMin() {
    return dirtyMin;
  }

  /** Last slice changed since {@link #clearDirty()}, or -1. */
  public int dirtyMax() {
    return dirtyMax;
  }

  public boolean isDirty() {
    return dirtyMax >= 0;
  }

  public void clearDirty() {
    dirtyMin = Integer.MAX_VALUE;
    dirtyMax = -1;
  }

  private void flip(MaskDiff diff) {
    diff.applyTo(kept);
    removedVoxels = space.gridVoxels() - kept.count();
    dirtyMin = Math.min(dirtyMin, diff.zMin());
    dirtyMax = Math.max(dirtyMax, diff.zMax());
    generation++;
  }
}
