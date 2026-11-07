/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones;

import com.addisortho.viewer.bones.surface.Box;
import com.addisortho.viewer.bones.surface.LabelSampler;
import java.awt.Color;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.stream.IntStream;
import org.joml.Matrix3d;
import org.joml.Vector3d;
import org.weasis.dicom.codec.seg.SegmentationVolume;
import org.weasis.opencv.seg.RegionAttributes;

/**
 * One segment of a SEG and the other segments around it, copied out of the SEG's canonical volume
 * over the segment's bounding box plus a margin. Only that block is kept, not the whole grid.
 */
final class SegmentLabels implements LabelSampler {

  private final int x0;
  private final int y0;
  private final int z0;
  private final int sizeX;
  private final int sizeY;
  private final int sizeZ;
  private final byte[] block;
  private final Box bone;
  // LPS to SEG voxel: voxel = inverse * (lps - origin).
  private final double ox;
  private final double oy;
  private final double oz;
  private final double m00;
  private final double m01;
  private final double m02;
  private final double m10;
  private final double m11;
  private final double m12;
  private final double m20;
  private final double m21;
  private final double m22;

  private SegmentLabels(SegmentationVolume volume, int[] range, byte[] block, Box bone) {
    this.x0 = range[0];
    this.y0 = range[2];
    this.z0 = range[4];
    this.sizeX = range[1] - range[0] + 1;
    this.sizeY = range[3] - range[2] + 1;
    this.sizeZ = range[5] - range[4] + 1;
    this.block = block;
    this.bone = bone;
    Vector3d origin = volume.voxelToLps(0, 0, 0);
    Matrix3d inverse =
        new Matrix3d(
                volume.voxelToLps(1, 0, 0).sub(origin),
                volume.voxelToLps(0, 1, 0).sub(origin),
                volume.voxelToLps(0, 0, 1).sub(origin))
            .invert();
    this.ox = origin.x;
    this.oy = origin.y;
    this.oz = origin.z;
    this.m00 = inverse.m00();
    this.m01 = inverse.m01();
    this.m02 = inverse.m02();
    this.m10 = inverse.m10();
    this.m11 = inverse.m11();
    this.m12 = inverse.m12();
    this.m20 = inverse.m20();
    this.m21 = inverse.m21();
    this.m22 = inverse.m22();
  }

  /**
   * Reads {@code segment} and its neighbours. Runs over every slice of the volume: call it off the
   * event dispatch thread.
   *
   * @param declared every segment number of the volume
   * @param marginMm extent kept around the segment, at least as far as the label may grow
   * @return nothing when the segment has no voxel
   * @throws IllegalStateException when the volume was unloaded during the read
   * @throws CancellationException when the thread is interrupted
   */
  static Optional<SegmentLabels> extract(
      SegmentationVolume volume, Set<Integer> declared, int segment, double marginMm) {
    boolean[] boneIds = storageIds(volume, declared, s -> s == segment);
    boolean[] otherIds = storageIds(volume, declared, s -> s != segment);
    SliceReader reader = new SliceReader(volume);
    Thread caller = Thread.currentThread();
    int[] found = boneRange(volume, reader, boneIds, caller);
    if (found == null) {
      return Optional.empty();
    }
    int[] range = withMargin(volume, found, marginMm);
    byte[] block = copyBlock(volume, reader, range, boneIds, otherIds, caller);
    if (volume.isDisposed()) {
      throw new IllegalStateException("The segmentation volume was unloaded during the export");
    }
    return Optional.of(new SegmentLabels(volume, range, block, boneBox(volume, found)));
  }

  /** Box holding every voxel of the segment, half a voxel beyond the outer voxel centres. */
  Box boneBox() {
    return bone;
  }

  @Override
  public int labelAt(double x, double y, double z) {
    double dx = x - ox;
    double dy = y - oy;
    double dz = z - oz;
    // JOML matrices are column-major: mRC is column R, row C.
    int i = (int) Math.round(m00 * dx + m10 * dy + m20 * dz) - x0;
    int j = (int) Math.round(m01 * dx + m11 * dy + m21 * dz) - y0;
    int k = (int) Math.round(m02 * dx + m12 * dy + m22 * dz) - z0;
    if (i < 0 || j < 0 || k < 0 || i >= sizeX || j >= sizeY || k >= sizeZ) {
      return NONE;
    }
    return block[(k * sizeY + j) * sizeX + i];
  }

  private interface SegmentFilter {
    boolean accept(int segment);
  }

  /**
   * Storage IDs of the volume that hold at least one accepted segment. The volume keeps its ID to
   * segments table private, but its colour table composites exactly the visible segments of each
   * ID: with only the accepted segments shown, at full opacity, their IDs get a non-zero alpha.
   */
  private static boolean[] storageIds(
      SegmentationVolume volume, Set<Integer> declared, SegmentFilter filter) {
    Map<Integer, RegionAttributes> shown = new HashMap<>();
    for (int s : declared) {
      RegionAttributes a = new RegionAttributes(s, String.valueOf(s), Color.WHITE);
      a.setVisible(filter.accept(s));
      a.setInteriorOpacity(1f);
      shown.put(s, a);
    }
    byte[] lut = volume.buildSegmentColorLUT(shown);
    boolean[] ids = new boolean[lut.length / 4];
    for (int id = 1; id < ids.length; id++) {
      ids[id] = lut[4 * id + 3] != 0;
    }
    return ids;
  }

  /** Reads the storage IDs of one slice; one instance per thread. */
  private static final class SliceReader {
    private final int sliceSize;
    private final boolean wide;

    SliceReader(SegmentationVolume volume) {
      this.sliceSize = volume.getSizeX() * volume.getSizeY();
      this.wide = volume.isShortMode();
    }

    ByteBuffer newBuffer() {
      return ByteBuffer.allocate(wide ? 2 * sliceSize : sliceSize).order(ByteOrder.nativeOrder());
    }

    /** Fills {@code buffer} with slice {@code z}; false when the volume has no data. */
    boolean read(SegmentationVolume volume, int z, ByteBuffer buffer) {
      buffer.clear();
      return volume.exportSliceInto(z, buffer);
    }

    int id(ByteBuffer buffer, int index) {
      return wide ? buffer.getShort(2 * index) & 0xFFFF : buffer.get(index) & 0xFF;
    }
  }

  /** Voxel range {x0, x1, y0, y1, z0, z1} of the bone, or {@code null} when it has none. */
  private static int[] boneRange(
      SegmentationVolume volume, SliceReader reader, boolean[] boneIds, Thread caller) {
    int sx = volume.getSizeX();
    int sy = volume.getSizeY();
    return IntStream.range(0, volume.getSizeZ())
        .parallel()
        .mapToObj(
            z -> {
              checkCancelled(caller);
              ByteBuffer buffer = reader.newBuffer();
              if (!reader.read(volume, z, buffer)) {
                return null;
              }
              int[] r = null;
              for (int y = 0; y < sy; y++) {
                for (int x = 0; x < sx; x++) {
                  int id = reader.id(buffer, y * sx + x);
                  if (id < boneIds.length && boneIds[id]) {
                    r = r == null ? new int[] {x, x, y, y, z, z} : grow(r, x, y);
                  }
                }
              }
              return r;
            })
        .filter(r -> r != null)
        .reduce(SegmentLabels::union)
        .orElse(null);
  }

  private static int[] grow(int[] r, int x, int y) {
    r[0] = Math.min(r[0], x);
    r[1] = Math.max(r[1], x);
    r[2] = Math.min(r[2], y);
    r[3] = Math.max(r[3], y);
    return r;
  }

  private static int[] union(int[] a, int[] b) {
    return new int[] {
      Math.min(a[0], b[0]),
      Math.max(a[1], b[1]),
      Math.min(a[2], b[2]),
      Math.max(a[3], b[3]),
      Math.min(a[4], b[4]),
      Math.max(a[5], b[5])
    };
  }

  private static int[] withMargin(SegmentationVolume volume, int[] r, double marginMm) {
    Vector3d origin = volume.voxelToLps(0, 0, 0);
    int mx = (int) Math.ceil(marginMm / volume.voxelToLps(1, 0, 0).distance(origin));
    int my = (int) Math.ceil(marginMm / volume.voxelToLps(0, 1, 0).distance(origin));
    int mz = (int) Math.ceil(marginMm / volume.voxelToLps(0, 0, 1).distance(origin));
    return new int[] {
      Math.max(0, r[0] - mx),
      Math.min(volume.getSizeX() - 1, r[1] + mx),
      Math.max(0, r[2] - my),
      Math.min(volume.getSizeY() - 1, r[3] + my),
      Math.max(0, r[4] - mz),
      Math.min(volume.getSizeZ() - 1, r[5] + mz)
    };
  }

  /** Bone, other segment or nothing for each voxel of {@code range}; the bone wins overlaps. */
  private static byte[] copyBlock(
      SegmentationVolume volume,
      SliceReader reader,
      int[] range,
      boolean[] boneIds,
      boolean[] otherIds,
      Thread caller) {
    int bx = range[1] - range[0] + 1;
    int by = range[3] - range[2] + 1;
    int bz = range[5] - range[4] + 1;
    byte[] block = new byte[bx * by * bz];
    int sx = volume.getSizeX();
    IntStream.range(0, bz)
        .parallel()
        .forEach(
            k -> {
              checkCancelled(caller);
              ByteBuffer buffer = reader.newBuffer();
              if (!reader.read(volume, range[4] + k, buffer)) {
                return;
              }
              for (int j = 0; j < by; j++) {
                int row = (range[2] + j) * sx + range[0];
                int out = (k * by + j) * bx;
                for (int i = 0; i < bx; i++) {
                  int id = reader.id(buffer, row + i);
                  if (id < boneIds.length && boneIds[id]) {
                    block[out + i] = BONE;
                  } else if (id < otherIds.length && otherIds[id]) {
                    block[out + i] = OTHER;
                  }
                }
              }
            });
    return block;
  }

  private static Box boneBox(SegmentationVolume volume, int[] r) {
    double[] corners = new double[24];
    int n = 0;
    for (int c = 0; c < 8; c++) {
      // voxelToLps takes whole voxels: extend half a voxel by averaging with the next one out.
      int x = (c & 1) == 0 ? r[0] : r[1];
      int y = (c & 2) == 0 ? r[2] : r[3];
      int z = (c & 4) == 0 ? r[4] : r[5];
      int sx = (c & 1) == 0 ? -1 : 1;
      int sy = (c & 2) == 0 ? -1 : 1;
      int sz = (c & 4) == 0 ? -1 : 1;
      Vector3d p = volume.voxelToLps(x, y, z);
      Vector3d q = volume.voxelToLps(x + sx, y + sy, z + sz);
      p.add(q).mul(0.5);
      corners[n++] = p.x;
      corners[n++] = p.y;
      corners[n++] = p.z;
    }
    return Box.around(corners);
  }

  private static void checkCancelled(Thread caller) {
    if (caller.isInterrupted()) {
      throw new CancellationException();
    }
  }
}
