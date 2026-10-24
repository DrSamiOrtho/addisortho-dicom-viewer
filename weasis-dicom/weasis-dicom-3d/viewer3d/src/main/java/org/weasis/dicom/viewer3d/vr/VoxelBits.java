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

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * One bit per voxel of a grid, stored slice by slice so a slice can be exported or flipped on its
 * own. Within a slice the voxels are in row-major order ({@code y * sizeX + x}).
 */
public final class VoxelBits {

  private final int sizeX;
  private final int sizeY;
  private final int sizeZ;
  private final int sliceBits;
  private final long[][] slices;

  public VoxelBits(int sizeX, int sizeY, int sizeZ) {
    if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
      throw new IllegalArgumentException("Empty grid " + sizeX + "x" + sizeY + "x" + sizeZ);
    }
    this.sizeX = sizeX;
    this.sizeY = sizeY;
    this.sizeZ = sizeZ;
    this.sliceBits = Math.multiplyExact(sizeX, sizeY);
    this.slices = new long[sizeZ][(sliceBits + 63) >>> 6];
  }

  public int sizeX() {
    return sizeX;
  }

  public int sizeY() {
    return sizeY;
  }

  public int sizeZ() {
    return sizeZ;
  }

  /** Voxels of one slice. */
  public int sliceBits() {
    return sliceBits;
  }

  /** Memory taken by the bits. */
  public long bytes() {
    return 8L * slices[0].length * sizeZ;
  }

  public boolean get(int x, int y, int z) {
    int i = y * sizeX + x;
    return (slices[z][i >>> 6] & (1L << i)) != 0;
  }

  public void set(int x, int y, int z) {
    int i = y * sizeX + x;
    slices[z][i >>> 6] |= 1L << i;
  }

  public void clear(int x, int y, int z) {
    int i = y * sizeX + x;
    slices[z][i >>> 6] &= ~(1L << i);
  }

  /** Sets the voxel and tells whether it was clear before. */
  public boolean testAndSet(int x, int y, int z) {
    int i = y * sizeX + x;
    long[] s = slices[z];
    long bit = 1L << i;
    long old = s[i >>> 6];
    s[i >>> 6] = old | bit;
    return (old & bit) == 0;
  }

  /** Clears the voxel and tells whether it was set before. */
  public boolean testAndClear(int x, int y, int z) {
    int i = y * sizeX + x;
    long[] s = slices[z];
    long bit = 1L << i;
    long old = s[i >>> 6];
    s[i >>> 6] = old & ~bit;
    return (old & bit) != 0;
  }

  /** Whether voxel {@code i} of slice {@code z} is set, in row-major slice order. */
  public boolean getInSlice(int z, int i) {
    return (slices[z][i >>> 6] & (1L << i)) != 0;
  }

  public void fill(boolean value) {
    for (long[] s : slices) {
      Arrays.fill(s, value ? -1L : 0L);
      if (value) {
        clearTail(s);
      }
    }
  }

  /** Flips the voxels {@code [from, to)} of slice {@code z}, in row-major slice order. */
  public void flipRange(int z, int from, int to) {
    if (from >= to) {
      return;
    }
    long[] s = slices[z];
    int w0 = from >>> 6;
    int w1 = (to - 1) >>> 6;
    long first = -1L << from;
    long last = -1L >>> (63 - ((to - 1) & 63));
    if (w0 == w1) {
      s[w0] ^= first & last;
      return;
    }
    s[w0] ^= first;
    for (int w = w0 + 1; w < w1; w++) {
      s[w] ^= -1L;
    }
    s[w1] ^= last;
  }

  public long count() {
    long n = 0;
    for (long[] s : slices) {
      for (long w : s) {
        n += Long.bitCount(w);
      }
    }
    return n;
  }

  public long countSlice(int z) {
    long n = 0;
    for (long w : slices[z]) {
      n += Long.bitCount(w);
    }
    return n;
  }

  /**
   * The words of slice {@code z}; the bits past {@link #sliceBits()} in the last word are clear.
   */
  public long[] sliceWords(int z) {
    return slices[z];
  }

  /**
   * Writes slice {@code z} as one byte per voxel, {@code 255} for a set voxel and {@code 0} for a
   * clear one, at the buffer's position.
   */
  public void exportSlice(int z, ByteBuffer dst) {
    long[] s = slices[z];
    int bits = sliceBits;
    for (int i = 0; i < bits; i += 64) {
      long w = s[i >>> 6];
      int n = Math.min(64, bits - i);
      if (w == 0) {
        for (int k = 0; k < n; k++) {
          dst.put((byte) 0);
        }
      } else if (w == -1L && n == 64) {
        for (int k = 0; k < 64; k++) {
          dst.put((byte) 0xFF);
        }
      } else {
        for (int k = 0; k < n; k++) {
          dst.put((w & (1L << k)) != 0 ? (byte) 0xFF : (byte) 0);
        }
      }
    }
  }

  private void clearTail(long[] s) {
    int tail = sliceBits & 63;
    if (tail != 0) {
      s[s.length - 1] &= (1L << tail) - 1;
    }
  }
}
