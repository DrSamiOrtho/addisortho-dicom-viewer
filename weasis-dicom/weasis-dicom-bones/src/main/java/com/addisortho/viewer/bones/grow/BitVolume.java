/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.grow;

import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.stream.IntStream;

/**
 * A binary volume of one bit per voxel. Each row of {@code sizeX} voxels starts on a new 64-bit
 * word, so a step along x is a word shift and a step along y or z a word offset; the bits past
 * {@code sizeX} in the last word of a row are always clear.
 */
public final class BitVolume {

  private final int sizeX;
  private final int sizeY;
  private final int sizeZ;
  private final int wordsPerRow;
  private final long lastWordMask;
  private final long[] words;

  /**
   * @throws IllegalArgumentException when a size is not positive or the volume needs more than
   *     {@link Integer#MAX_VALUE} words
   */
  public BitVolume(int sizeX, int sizeY, int sizeZ) {
    if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
      throw new IllegalArgumentException("Empty volume " + sizeX + "x" + sizeY + "x" + sizeZ);
    }
    this.sizeX = sizeX;
    this.sizeY = sizeY;
    this.sizeZ = sizeZ;
    this.wordsPerRow = (sizeX + 63) >>> 6;
    int tail = sizeX & 63;
    this.lastWordMask = tail == 0 ? -1L : (1L << tail) - 1;
    long count = (long) wordsPerRow * sizeY * sizeZ;
    if (count > Integer.MAX_VALUE - 8) {
      throw new IllegalArgumentException("Volume too large: " + count + " words");
    }
    this.words = new long[(int) count];
  }

  /** An empty volume of the same size. */
  public BitVolume emptyCopy() {
    return new BitVolume(sizeX, sizeY, sizeZ);
  }

  public BitVolume copy() {
    BitVolume c = emptyCopy();
    System.arraycopy(words, 0, c.words, 0, words.length);
    return c;
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

  /** Memory taken by the bits. */
  public long bytes() {
    return 8L * words.length;
  }

  /** Bytes a volume of this size takes, without allocating it. */
  public static long bytesFor(int sizeX, int sizeY, int sizeZ) {
    return 8L * ((sizeX + 63) >>> 6) * sizeY * sizeZ;
  }

  public boolean get(int x, int y, int z) {
    return (words[word(x, y, z)] & (1L << x)) != 0;
  }

  public void set(int x, int y, int z) {
    words[word(x, y, z)] |= 1L << x;
  }

  public void clear(int x, int y, int z) {
    words[word(x, y, z)] &= ~(1L << x);
  }

  /** Sets the voxel and tells whether it was clear before. */
  boolean testAndSet(int x, int y, int z) {
    int w = word(x, y, z);
    long bit = 1L << x;
    long old = words[w];
    words[w] = old | bit;
    return (old & bit) == 0;
  }

  /** Index of the word holding voxel x; Java shifts use the low 6 bits of x for the bit. */
  private int word(int x, int y, int z) {
    return (z * sizeY + y) * wordsPerRow + (x >>> 6);
  }

  public long count() {
    long n = 0;
    for (long w : words) {
      n += Long.bitCount(w);
    }
    return n;
  }

  /** Set voxels of slice {@code z}. */
  public long countSlice(int z) {
    long n = 0;
    int from = z * sizeY * wordsPerRow;
    for (int i = from; i < from + sizeY * wordsPerRow; i++) {
      n += Long.bitCount(words[i]);
    }
    return n;
  }

  public boolean isEmpty() {
    for (long w : words) {
      if (w != 0) {
        return false;
      }
    }
    return true;
  }

  /**
   * The smallest block holding every set voxel, in this volume's coordinates, or {@code null} when
   * none is set.
   */
  public VoxelBox bounds() {
    int x0 = Integer.MAX_VALUE;
    int x1 = -1;
    int y0 = Integer.MAX_VALUE;
    int y1 = -1;
    int z0 = Integer.MAX_VALUE;
    int z1 = -1;
    for (int z = 0; z < sizeZ; z++) {
      for (int y = 0; y < sizeY; y++) {
        int row = (z * sizeY + y) * wordsPerRow;
        for (int w = 0; w < wordsPerRow; w++) {
          long bits = words[row + w];
          if (bits != 0) {
            x0 = Math.min(x0, (w << 6) + Long.numberOfTrailingZeros(bits));
            x1 = Math.max(x1, (w << 6) + 63 - Long.numberOfLeadingZeros(bits));
            y0 = Math.min(y0, y);
            y1 = Math.max(y1, y);
            z0 = Math.min(z0, z);
            z1 = Math.max(z1, z);
          }
        }
      }
    }
    return x1 < 0 ? null : new VoxelBox(x0, x1, y0, y1, z0, z1);
  }

  /** A copy of the block {@code box} of this volume. */
  public BitVolume crop(VoxelBox box) {
    BitVolume c = new BitVolume(box.sizeX(), box.sizeY(), box.sizeZ());
    for (int z = 0; z < c.sizeZ; z++) {
      for (int y = 0; y < c.sizeY; y++) {
        for (int x = 0; x < c.sizeX; x++) {
          if (get(box.x0() + x, box.y0() + y, box.z0() + z)) {
            c.set(x, y, z);
          }
        }
      }
    }
    return c;
  }

  /** Keeps only the voxels also set in {@code other}, of the same size. */
  public void and(BitVolume other) {
    checkSameSize(other);
    for (int i = 0; i < words.length; i++) {
      words[i] &= other.words[i];
    }
  }

  /** Adds the voxels set in {@code other}, of the same size. */
  public void or(BitVolume other) {
    checkSameSize(other);
    for (int i = 0; i < words.length; i++) {
      words[i] |= other.words[i];
    }
  }

  /** Clears the voxels set in {@code other}, of the same size. */
  public void andNot(BitVolume other) {
    checkSameSize(other);
    for (int i = 0; i < words.length; i++) {
      words[i] &= ~other.words[i];
    }
  }

  /**
   * A copy of this volume with {@code margin} clear voxels added on every side, so that an erosion
   * with clear outside works on its own edges as on any other.
   */
  public BitVolume padded(int margin) {
    BitVolume p = new BitVolume(sizeX + 2 * margin, sizeY + 2 * margin, sizeZ + 2 * margin);
    for (int z = 0; z < sizeZ; z++) {
      for (int y = 0; y < sizeY; y++) {
        for (int x = 0; x < sizeX; x++) {
          if (get(x, y, z)) {
            p.set(x + margin, y + margin, z + margin);
          }
        }
      }
    }
    return p;
  }

  public boolean sameContent(BitVolume other) {
    checkSameSize(other);
    return Arrays.equals(words, other.words);
  }

  private void checkSameSize(BitVolume other) {
    if (other.sizeX != sizeX || other.sizeY != sizeY || other.sizeZ != sizeZ) {
      throw new IllegalArgumentException("Volumes of different sizes");
    }
  }

  /**
   * One step of 6-connected dilation (a voxel is set when it or a face neighbour is) or erosion (a
   * voxel stays set when it and all its face neighbours are), written into {@code target}.
   *
   * @param outside the value of the voxels beyond the volume
   * @throws CancellationException when {@code caller} is interrupted
   */
  void step(BitVolume target, boolean dilate, boolean outside, Thread caller) {
    checkSameSize(target);
    long border = outside ? -1L : 0L;
    int sliceWords = sizeY * wordsPerRow;
    IntStream.range(0, sizeZ)
        .parallel()
        .forEach(
            z -> {
              RegionGrow.checkCancelled(caller);
              for (int y = 0; y < sizeY; y++) {
                int row = (z * sizeY + y) * wordsPerRow;
                for (int w = 0; w < wordsPerRow; w++) {
                  int i = row + w;
                  long self = words[i];
                  long fromLeft = (self << 1) | (w > 0 ? words[i - 1] >>> 63 : border & 1L);
                  long fromRight = (self >>> 1) | (w < wordsPerRow - 1 ? words[i + 1] << 63 : 0L);
                  if (w == wordsPerRow - 1 && outside) {
                    fromRight |= 1L << ((sizeX - 1) & 63);
                  }
                  long up = y > 0 ? words[i - wordsPerRow] : border;
                  long down = y < sizeY - 1 ? words[i + wordsPerRow] : border;
                  long front = z > 0 ? words[i - sliceWords] : border;
                  long back = z < sizeZ - 1 ? words[i + sliceWords] : border;
                  long v =
                      dilate
                          ? self | fromLeft | fromRight | up | down | front | back
                          : self & fromLeft & fromRight & up & down & front & back;
                  target.words[i] = w == wordsPerRow - 1 ? v & lastWordMask : v;
                }
              }
            });
  }

  /**
   * Repeats {@link #step} {@code steps} times, keeping only the voxels of {@code constraint} after
   * each step when it is not {@code null}.
   *
   * @return a new volume
   */
  BitVolume morph(int steps, boolean dilate, boolean outside, BitVolume constraint, Thread caller) {
    if (steps <= 0) {
      return copy();
    }
    // This volume is only read by the first step; two buffers take turns after it.
    BitVolume current = emptyCopy();
    stepInto(this, current, dilate, outside, constraint, caller);
    BitVolume next = steps > 1 ? emptyCopy() : null;
    for (int s = 1; s < steps; s++) {
      stepInto(current, next, dilate, outside, constraint, caller);
      BitVolume t = current;
      current = next;
      next = t;
    }
    return current;
  }

  private static void stepInto(
      BitVolume from,
      BitVolume to,
      boolean dilate,
      boolean outside,
      BitVolume constraint,
      Thread caller) {
    from.step(to, dilate, outside, caller);
    if (constraint != null) {
      to.and(constraint);
    }
  }
}
