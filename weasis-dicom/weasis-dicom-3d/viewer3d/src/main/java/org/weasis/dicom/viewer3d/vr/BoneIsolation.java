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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.joml.Vector3d;
import org.joml.Vector3i;
import org.weasis.dicom.viewer3d.vr.VoxelFlood.Box;
import org.weasis.dicom.viewer3d.vr.VoxelFlood.IntQueue;

/**
 * Finds the bone of a CT for the 3D view to show it alone, without the plaster cast, the splint,
 * the table, the positioning pad or the clothing, which no threshold can tell from bone: plaster is
 * as dense as bone. The test is contact with outside air. Bone is wrapped in skin and soft tissue,
 * so it hardly ever touches air directly, while a cast, a table or a lead always does. What does
 * not touch air either is noise: specks above the threshold inside a pad, a table board or a cast
 * whose foam or padding is denser than air. Those are told from bone by their size, in real mm
 * since the voxels are anisotropic, and by their distance to a bone that is big enough.
 *
 * <p>On the grid of the kept mask:
 *
 * <ol>
 *   <li>dense voxels: from the bone threshold up to {@link #METAL_HU}; metal above it is kept
 *       apart, so a fixator pin never joins the bone to the outside;
 *   <li>outside air: the voxels below {@link #AIR_HU} joined, within their own axial slice, to the
 *       slice's border; in two dimensions the lungs, the bowel and an air gap under a cast never
 *       count as outside, and the volume's own edge is not air, so a bone cut by the first slice or
 *       by the field of view stays;
 *   <li>each 6-connected piece of the dense voxels is external when at least {@link
 *       #MIN_AIR_CONTACTS} of its voxels, and {@link #AIR_CONTACT_SHARE} of them, touch outside air
 *       face to face; the other pieces are internal;
 *   <li>an internal piece of {@link #SEED_MM3} or more is bone; one under {@link #NOISE_MM3} is
 *       noise and goes; one in between stays only when it lies within {@link #JOIN_MM} of a piece
 *       that stays, which spreads from piece to piece until nothing changes, so a fracture
 *       fragment, a chip, a sesamoid, the small carpal bones and the phalanges of a finger stay
 *       through their joint spaces, while the specks in a pad, the sheet of a table board and the
 *       marker on a pad go. Without any piece of {@link #SEED_MM3} the largest internal piece
 *       serves as the seed;
 *   <li>the bone region: the kept pieces with their holes filled slice by slice (the marrow canal
 *       and a nail inside it stay), widened by {@link #MARGIN_VOXELS} so the smooth surface is not
 *       clipped; the dropped pieces are out before the fill, so they never close a hole or widen
 *       into a sheet;
 *   <li>metal touching that region without air contact (a plate, a nail, a prosthesis) is kept
 *       whole; of metal touching air (a fixator pin, a wire out of the skin) only the part inside
 *       the region stays; a lead marker beside the bone has no kept piece around it and goes.
 * </ol>
 *
 * <p>Known losses, put back by Undo or Whole volume: a bone fragment displaced by more than {@link
 * #JOIN_MM} from any kept bone, a paediatric ossification centre further than that from its shaft,
 * and any bone smaller than {@link #SEED_MM3} in a volume that holds no bigger bone near it. The
 * other way round, a marker or a speck of {@link #NOISE_MM3} or more within {@link #JOIN_MM} of the
 * bone stays, and so does a chain of such specks that starts there.
 *
 * <p>The result is a guess for display, applied as one undoable cut; it changes no voxel. Memory:
 * four bit volumes of the grid plus the scratch of one slice, all taken from the budget up front,
 * and one record per piece between the two sizes.
 */
public final class BoneIsolation {

  /** Dense voxels above this are metal, not bone. */
  public static final double METAL_HU = 2500;

  /** Voxels below this are air. */
  public static final double AIR_HU = -400;

  /** Fewest voxels touching outside air that make a dense piece external. */
  public static final int MIN_AIR_CONTACTS = 50;

  /** Share of a dense piece's voxels that must touch outside air to make it external. */
  public static final double AIR_CONTACT_SHARE = 0.005;

  /** Widening of the bone region, in grid voxels. */
  public static final int MARGIN_VOXELS = 2;

  /** An internal piece of this volume or more, in mm3, is bone on its own. */
  public static final double SEED_MM3 = 1000;

  /** An internal piece under this volume, in mm3, is noise whatever lies near it. */
  public static final double NOISE_MM3 = 5;

  /**
   * An internal piece between the two sizes stays when it lies within this distance, in mm, of a
   * kept piece. Measured in 6-connected steps of the grid, so along a diagonal the reach is
   * shorter.
   */
  public static final double JOIN_MM = 4;

  /**
   * @param region the voxels to keep, on the grid of the kept mask
   * @param voxels how many
   * @param bonePieces dense pieces kept as bone by their size
   * @param joinedPieces smaller dense pieces kept for lying near a kept piece
   * @param droppedPieces internal dense pieces dropped as noise or as too far from the bone
   * @param externalPieces dense pieces cut away for touching outside air
   * @param implants metal pieces kept whole
   */
  public record Result(
      VoxelBits region,
      long voxels,
      int bonePieces,
      int joinedPieces,
      int droppedPieces,
      int externalPieces,
      int implants,
      long millis,
      long peakBytes) {}

  /** One connected piece, what the flood learnt about it, and the voxel it was grown from. */
  private record Piece(
      long voxels, long airVoxels, boolean touchesRegion, Box box, int x, int y, int z) {
    boolean isExternal() {
      return airVoxels >= Math.max(MIN_AIR_CONTACTS, AIR_CONTACT_SHARE * voxels);
    }
  }

  private static final byte BLOCKED = 0;
  private static final byte OPEN = 1;
  private static final byte REACHED = 2;

  private static final int[][] NEIGHBOURS = {
    {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
  };

  private BoneIsolation() {}

  /**
   * @param space the grid of the kept mask over the texture
   * @param sampler CT values of the texture voxels, in HU
   * @param gridVoxelMm size of one grid voxel in mm, on each axis
   * @param boneHu threshold from which a voxel is dense (bone or something as dense)
   * @param cancelled stops the search early, returning empty
   * @param budget working memory; refused with a {@link VoxelFlood.BudgetException} when short
   * @return the region to keep, or empty when no bone is found or the search was cancelled
   */
  public static Optional<Result> find(
      VolumeSpace space,
      VoxelSampler sampler,
      Vector3d gridVoxelMm,
      double boneHu,
      BooleanSupplier cancelled,
      WorkingMemory budget) {
    long samplerBytes = Math.max(0, sampler.workingBytes() - sampler.residentBytes());
    return find(
        space,
        BoneExtent.slicesOf(sampler, space.texture()),
        samplerBytes,
        gridVoxelMm,
        boneHu,
        cancelled,
        budget);
  }

  /** As above, from whole texture slices; {@code samplerBytes} is what making them may take. */
  public static Optional<Result> find(
      VolumeSpace space,
      BoneExtent.SliceSource slices,
      long samplerBytes,
      Vector3d gridVoxelMm,
      double boneHu,
      BooleanSupplier cancelled,
      WorkingMemory budget) {
    long start = System.nanoTime();
    if (!(gridVoxelMm.x > 0 && gridVoxelMm.y > 0 && gridVoxelMm.z > 0)) {
      throw new IllegalArgumentException("Voxel size " + gridVoxelMm);
    }
    double voxelMm3 = gridVoxelMm.x * gridVoxelMm.y * gridVoxelMm.z;
    int[] joinSteps = joinSteps(gridVoxelMm);
    Vector3i g = space.grid();
    int sx = g.x;
    int sy = g.y;
    int sz = g.z;
    int sliceVoxels = sx * sy;
    budget.reserve(workingBytes(space) + samplerBytes);
    VoxelBits dense = new VoxelBits(sx, sy, sz);
    VoxelBits metal = new VoxelBits(sx, sy, sz);
    VoxelBits air = new VoxelBits(sx, sy, sz);
    VoxelBits region = new VoxelBits(sx, sy, sz);
    byte[] cell = new byte[sliceVoxels];
    IntQueue queue = new IntQueue(1 << 12, budget);
    IntQueue queue2 = new IntQueue(1 << 12, budget);
    try {
      GridSlices grid = new GridSlices(space, slices);
      for (int z = 0; z < sz; z++) {
        if (cancelled.getAsBoolean()) {
          return Optional.empty();
        }
        classify(grid.slice(z), boneHu, dense.sliceWords(z), metal.sliceWords(z), cell);
        floodFromBorder(cell, sx, sy, queue);
        mark(cell, REACHED, air.sliceWords(z));
      }

      // Every dense piece: external, bone by size, noise, or a candidate between the two sizes.
      int bonePieces = 0;
      int externalPieces = 0;
      int droppedPieces = 0;
      Box bones = null;
      List<Piece> candidates = new ArrayList<>();
      for (int z = 0; z < sz; z++) {
        long[] d = dense.sliceWords(z);
        for (int w = 0; w < d.length; w++) {
          while (d[w] != 0) {
            if (cancelled.getAsBoolean()) {
              return Optional.empty();
            }
            int i = (w << 6) + Long.numberOfTrailingZeros(d[w]);
            int x = i % sx;
            int y = i / sx;
            Piece p = flood(dense, region, air, null, x, y, z, queue);
            double mm3 = p.voxels() * voxelMm3;
            if (p.isExternal() || mm3 < NOISE_MM3) {
              // Its voxels alone are reached through the region: no other piece touches them.
              flood(region, null, air, null, x, y, z, queue);
              if (p.isExternal()) {
                externalPieces++;
              } else {
                droppedPieces++;
              }
            } else if (mm3 >= SEED_MM3) {
              bones = bones == null ? p.box() : union(bones, p.box());
              bonePieces++;
            } else {
              candidates.add(p);
            }
          }
        }
      }
      if (bonePieces == 0) {
        Piece largest = null;
        for (Piece p : candidates) {
          if (largest == null || p.voxels() > largest.voxels()) {
            largest = p;
          }
        }
        if (largest == null) {
          return Optional.empty();
        }
        candidates.remove(largest);
        bones = largest.box();
        bonePieces = 1;
      }

      // The metal pieces and their air contact, while the air is known; their bits move into the
      // dense volume, which the floods above have emptied.
      List<Piece> metals = new ArrayList<>();
      for (int z = 0; z < sz; z++) {
        long[] m = metal.sliceWords(z);
        for (int w = 0; w < m.length; w++) {
          while (m[w] != 0) {
            int i = (w << 6) + Long.numberOfTrailingZeros(m[w]);
            metals.add(flood(metal, dense, air, null, i % sx, i / sx, z, queue));
          }
        }
      }
      VoxelBits metalBits = dense;
      // The candidates leave the region for the emptied metal volume.
      VoxelBits cand = metal;
      for (Piece p : candidates) {
        flood(region, cand, null, null, p.x(), p.y(), p.z(), queue);
      }
      // The air has served: its volume is the scratch from here on.
      VoxelBits scratch = air;
      scratch.fill(false);

      Join join =
          join(region, cand, scratch, candidates, joinSteps, bones, queue, queue2, cancelled);
      if (join == null) {
        return Optional.empty();
      }
      droppedPieces += candidates.size();
      bones = join.box();

      Box box = bones.grow(1, region);
      fillHoles(region, box, cell, queue);
      if (cancelled.getAsBoolean()) {
        return Optional.empty();
      }
      dilate(region, scratch, MARGIN_VOXELS, box);

      int implants = 0;
      for (Piece p : metals) {
        Piece q = flood(metalBits, scratch, null, region, p.x(), p.y(), p.z(), queue);
        boolean keep = q.touchesRegion() && !p.isExternal();
        for (int k = p.box().z0(); k <= p.box().z1(); k++) {
          long[] s = scratch.sliceWords(k);
          if (keep) {
            or(region.sliceWords(k), s);
          }
          Arrays.fill(s, 0L);
        }
        if (keep) {
          implants++;
        }
      }
      return Optional.of(
          new Result(
              region,
              region.count(),
              bonePieces,
              join.joined(),
              droppedPieces,
              externalPieces,
              implants,
              (System.nanoTime() - start) / 1_000_000,
              budget.peak()));
    } finally {
      queue.free();
      queue2.free();
    }
  }

  /** Memory of a search on the grid: four bit volumes and the scratch of one slice. */
  public static long workingBytes(VolumeSpace space) {
    Vector3i g = space.grid();
    long sliceVoxels = (long) g.x * g.y;
    long sliceWords = (sliceVoxels + 63) >>> 6;
    return 4 * 8L * sliceWords * g.z + sliceVoxels + 3 * 8L * sliceWords;
  }

  /** Steps of {@link #JOIN_MM} on each axis of the grid, at least one. */
  static int[] joinSteps(Vector3d voxelMm) {
    return new int[] {
      Math.max(1, (int) Math.round(JOIN_MM / voxelMm.x)),
      Math.max(1, (int) Math.round(JOIN_MM / voxelMm.y)),
      Math.max(1, (int) Math.round(JOIN_MM / voxelMm.z))
    };
  }

  /** What the spread over the candidates gave: the pieces joined and the box of all kept pieces. */
  private record Join(int joined, Box box) {}

  /**
   * Moves into {@code region} every candidate within the join steps of it, again and again until
   * none is, taking the joined pieces out of {@code pending}; {@code null} when cancelled.
   */
  private static Join join(
      VoxelBits region,
      VoxelBits cand,
      VoxelBits scratch,
      List<Piece> pending,
      int[] steps,
      Box box,
      IntQueue a,
      IntQueue b,
      BooleanSupplier cancelled) {
    int joined = 0;
    // Only a candidate whose reach meets a piece kept in the last round can have come near.
    List<Box> fresh = List.of(box);
    while (!fresh.isEmpty() && !pending.isEmpty()) {
      List<Box> next = new ArrayList<>();
      Iterator<Piece> it = pending.iterator();
      while (it.hasNext()) {
        Piece p = it.next();
        Box reach = grow(p.box(), steps, cand);
        if (!meetsAny(reach, fresh)) {
          continue;
        }
        if (cancelled.getAsBoolean()) {
          return null;
        }
        if (nearRegion(p, cand, region, scratch, steps, reach, a, b)) {
          flood(cand, region, null, null, p.x(), p.y(), p.z(), a);
          it.remove();
          joined++;
          box = union(box, p.box());
          next.add(p.box());
        }
      }
      fresh = next;
    }
    return new Join(joined, box);
  }

  /**
   * Whether any voxel of {@code region} lies within the join steps of the candidate piece grown
   * from the seed voxel over {@code cand}: a breadth-first search outwards from the piece, one step
   * per level, along each axis only up to its steps. {@code visited} must be clear within {@code
   * reach} and is clear again on return; the queues are empty on return.
   */
  private static boolean nearRegion(
      Piece p,
      VoxelBits cand,
      VoxelBits region,
      VoxelBits visited,
      int[] steps,
      Box reach,
      IntQueue level,
      IntQueue nextLevel) {
    int sx = cand.sizeX();
    int sy = cand.sizeY();
    int sz = cand.sizeZ();
    int seed = p.x() + sx * (p.y() + sy * p.z());
    visited.set(p.x(), p.y(), p.z());
    level.add(seed);
    // Level 0: the piece itself, left in place.
    while (!level.isEmpty()) {
      int i = level.poll();
      nextLevel.add(i);
      int x = i % sx;
      int y = (i / sx) % sy;
      int z = i / sx / sy;
      for (int[] n : NEIGHBOURS) {
        int nx = x + n[0];
        int ny = y + n[1];
        int nz = z + n[2];
        if (nx < 0 || ny < 0 || nz < 0 || nx >= sx || ny >= sy || nz >= sz) {
          continue;
        }
        if (cand.get(nx, ny, nz) && visited.testAndSet(nx, ny, nz)) {
          level.add(nx + sx * (ny + sy * nz));
        }
      }
    }
    boolean found = false;
    int maxStep = Math.max(steps[0], Math.max(steps[1], steps[2]));
    for (int k = 1; k <= maxStep && !found; k++) {
      IntQueue t = level;
      level = nextLevel;
      nextLevel = t;
      while (!level.isEmpty() && !found) {
        int i = level.poll();
        int x = i % sx;
        int y = (i / sx) % sy;
        int z = i / sx / sy;
        for (int[] n : NEIGHBOURS) {
          int axis = n[0] != 0 ? 0 : n[1] != 0 ? 1 : 2;
          if (steps[axis] < k) {
            continue;
          }
          int nx = x + n[0];
          int ny = y + n[1];
          int nz = z + n[2];
          if (nx < 0 || ny < 0 || nz < 0 || nx >= sx || ny >= sy || nz >= sz) {
            continue;
          }
          if (region.get(nx, ny, nz)) {
            found = true;
            break;
          }
          if (visited.testAndSet(nx, ny, nz)) {
            nextLevel.add(nx + sx * (ny + sy * nz));
          }
        }
      }
    }
    while (!level.isEmpty()) {
      level.poll();
    }
    while (!nextLevel.isEmpty()) {
      nextLevel.poll();
    }
    clearBox(visited, reach);
    return found;
  }

  private static Box grow(Box b, int[] steps, VoxelBits v) {
    return new Box(
        Math.max(0, b.x0() - steps[0]),
        Math.min(v.sizeX() - 1, b.x1() + steps[0]),
        Math.max(0, b.y0() - steps[1]),
        Math.min(v.sizeY() - 1, b.y1() + steps[1]),
        Math.max(0, b.z0() - steps[2]),
        Math.min(v.sizeZ() - 1, b.z1() + steps[2]));
  }

  private static boolean meetsAny(Box a, List<Box> boxes) {
    for (Box b : boxes) {
      if (a.x0() <= b.x1()
          && b.x0() <= a.x1()
          && a.y0() <= b.y1()
          && b.y0() <= a.y1()
          && a.z0() <= b.z1()
          && b.z0() <= a.z1()) {
        return true;
      }
    }
    return false;
  }

  /** Clears the words holding the rows of the box; the words may reach beyond the box's columns. */
  private static void clearBox(VoxelBits v, Box box) {
    int sx = v.sizeX();
    for (int z = box.z0(); z <= box.z1(); z++) {
      long[] words = v.sliceWords(z);
      for (int y = box.y0(); y <= box.y1(); y++) {
        int row = y * sx;
        Arrays.fill(words, (row + box.x0()) >>> 6, ((row + box.x1()) >>> 6) + 1, 0L);
      }
    }
  }

  /** Dense and metal bits of a slice; the cells open to the air flood are the air voxels. */
  private static void classify(float[] v, double boneHu, long[] dense, long[] metal, byte[] cell) {
    for (int i = 0; i < cell.length; i++) {
      float value = v[i];
      if (value >= boneHu) {
        if (value <= METAL_HU) {
          dense[i >>> 6] |= 1L << i;
        } else {
          metal[i >>> 6] |= 1L << i;
        }
        cell[i] = BLOCKED;
      } else {
        cell[i] = value < AIR_HU ? OPEN : BLOCKED;
      }
    }
  }

  private static void mark(byte[] cell, byte state, long[] words) {
    for (int i = 0; i < cell.length; i++) {
      if (cell[i] == state) {
        words[i >>> 6] |= 1L << i;
      }
    }
  }

  /**
   * Marks every {@link #OPEN} cell joined 4-connected to the border of the {@code w} by {@code h}
   * grid as {@link #REACHED}, span by span.
   */
  static void floodFromBorder(byte[] cell, int w, int h, IntQueue queue) {
    for (int x = 0; x < w; x++) {
      seed(cell, x, queue);
      seed(cell, (h - 1) * w + x, queue);
    }
    for (int y = 1; y < h - 1; y++) {
      seed(cell, y * w, queue);
      seed(cell, y * w + w - 1, queue);
    }
    while (!queue.isEmpty()) {
      int i = queue.poll();
      if (cell[i] != OPEN) {
        continue;
      }
      int y = i / w;
      int row = y * w;
      int x0 = i - row;
      int x1 = x0;
      while (x0 > 0 && cell[row + x0 - 1] == OPEN) {
        x0--;
      }
      while (x1 < w - 1 && cell[row + x1 + 1] == OPEN) {
        x1++;
      }
      Arrays.fill(cell, row + x0, row + x1 + 1, REACHED);
      if (y > 0) {
        seedRow(cell, row - w, x0, x1, queue);
      }
      if (y < h - 1) {
        seedRow(cell, row + w, x0, x1, queue);
      }
    }
  }

  private static void seed(byte[] cell, int i, IntQueue queue) {
    if (cell[i] == OPEN) {
      queue.add(i);
    }
  }

  /** Queues the first cell of each open span of the row within {@code [x0, x1]}. */
  private static void seedRow(byte[] cell, int row, int x0, int x1, IntQueue queue) {
    boolean inSpan = false;
    for (int x = x0; x <= x1; x++) {
      boolean open = cell[row + x] == OPEN;
      if (open && !inSpan) {
        queue.add(row + x);
      }
      inSpan = open;
    }
  }

  /**
   * Grows the 6-connected piece of {@code from} holding the start voxel, clearing its voxels from
   * {@code from} and setting them in {@code into} (when given), and tells how many of them touch
   * {@code air} (when given), whether any lies in or beside {@code region} (when given), and their
   * bounds.
   */
  private static Piece flood(
      VoxelBits from,
      VoxelBits into,
      VoxelBits air,
      VoxelBits region,
      int x0,
      int y0,
      int z0,
      IntQueue queue) {
    int sx = from.sizeX();
    int sy = from.sizeY();
    int sz = from.sizeZ();
    from.clear(x0, y0, z0);
    if (into != null) {
      into.set(x0, y0, z0);
    }
    queue.add(x0 + sx * (y0 + sy * z0));
    long voxels = 0;
    long airVoxels = 0;
    boolean touches = false;
    int[] min = {x0, y0, z0};
    int[] max = {x0, y0, z0};
    while (!queue.isEmpty()) {
      int i = queue.poll();
      int x = i % sx;
      int y = (i / sx) % sy;
      int z = i / sx / sy;
      voxels++;
      min[0] = Math.min(min[0], x);
      min[1] = Math.min(min[1], y);
      min[2] = Math.min(min[2], z);
      max[0] = Math.max(max[0], x);
      max[1] = Math.max(max[1], y);
      max[2] = Math.max(max[2], z);
      if (region != null && !touches && region.get(x, y, z)) {
        touches = true;
      }
      boolean nearAir = false;
      for (int[] n : NEIGHBOURS) {
        int nx = x + n[0];
        int ny = y + n[1];
        int nz = z + n[2];
        if (nx < 0 || ny < 0 || nz < 0 || nx >= sx || ny >= sy || nz >= sz) {
          continue;
        }
        if (air != null && air.get(nx, ny, nz)) {
          nearAir = true;
        } else if (from.testAndClear(nx, ny, nz)) {
          if (into != null) {
            into.set(nx, ny, nz);
          }
          queue.add(nx + sx * (ny + sy * nz));
        }
        if (region != null && !touches && region.get(nx, ny, nz)) {
          touches = true;
        }
      }
      if (nearAir) {
        airVoxels++;
      }
    }
    return new Piece(
        voxels,
        airVoxels,
        touches,
        new Box(min[0], max[0], min[1], max[1], min[2], max[2]),
        x0,
        y0,
        z0);
  }

  private static Box union(Box a, Box b) {
    return new Box(
        Math.min(a.x0(), b.x0()),
        Math.max(a.x1(), b.x1()),
        Math.min(a.y0(), b.y0()),
        Math.max(a.y1(), b.y1()),
        Math.min(a.z0(), b.z0()),
        Math.max(a.z1(), b.z1()));
  }

  /** Sets, slice by slice, every voxel of the box not joined to the box's edge outside the mask. */
  static void fillHoles(VoxelBits mask, Box box, byte[] cell, IntQueue queue) {
    int bw = box.x1() - box.x0() + 1;
    int bh = box.y1() - box.y0() + 1;
    for (int z = box.z0(); z <= box.z1(); z++) {
      if (mask.countSlice(z) == 0) {
        continue;
      }
      for (int y = 0, i = 0; y < bh; y++) {
        for (int x = 0; x < bw; x++, i++) {
          cell[i] = mask.get(box.x0() + x, box.y0() + y, z) ? BLOCKED : OPEN;
        }
      }
      floodFromBorder(cell, bw, bh, queue);
      for (int y = 0, i = 0; y < bh; y++) {
        for (int x = 0; x < bw; x++, i++) {
          if (cell[i] == OPEN) {
            mask.set(box.x0() + x, box.y0() + y, z);
          }
        }
      }
    }
  }

  /**
   * Widens the set voxels of {@code mask} by {@code radius} steps of 6-connected dilation, within
   * the box grown by the radius; {@code scratch} must be clear and is clear again on return.
   */
  static void dilate(VoxelBits mask, VoxelBits scratch, int radius, Box box) {
    int sx = mask.sizeX();
    int sliceBits = mask.sliceBits();
    int words = mask.sliceWords(0).length;
    long[] notFirstColumn = new long[words];
    long[] notLastColumn = new long[words];
    for (int i = 0; i < sliceBits; i++) {
      int x = i % sx;
      if (x != 0) {
        notFirstColumn[i >>> 6] |= 1L << i;
      }
      if (x != sx - 1) {
        notLastColumn[i >>> 6] |= 1L << i;
      }
    }
    long[] shifted = new long[words];
    Box range = box.grow(radius, mask);
    VoxelBits src = mask;
    VoxelBits dst = scratch;
    for (int step = 0; step < radius; step++) {
      for (int z = range.z0(); z <= range.z1(); z++) {
        long[] out = dst.sliceWords(z);
        long[] in = src.sliceWords(z);
        System.arraycopy(in, 0, out, 0, words);
        if (z > 0) {
          or(out, src.sliceWords(z - 1));
        }
        if (z < mask.sizeZ() - 1) {
          or(out, src.sliceWords(z + 1));
        }
        shiftInto(shifted, in, 1);
        and(shifted, notFirstColumn);
        or(out, shifted);
        shiftInto(shifted, in, -1);
        and(shifted, notLastColumn);
        or(out, shifted);
        shiftInto(shifted, in, sx);
        or(out, shifted);
        shiftInto(shifted, in, -sx);
        or(out, shifted);
        int tail = sliceBits & 63;
        if (tail != 0) {
          out[words - 1] &= (1L << tail) - 1;
        }
      }
      VoxelBits t = src;
      src = dst;
      dst = t;
    }
    for (int z = range.z0(); z <= range.z1(); z++) {
      if (src != mask) {
        System.arraycopy(src.sliceWords(z), 0, mask.sliceWords(z), 0, words);
      }
      Arrays.fill(scratch.sliceWords(z), 0L);
    }
  }

  /** {@code dst} = the bits of {@code src} moved by {@code shift} places towards higher indices. */
  static void shiftInto(long[] dst, long[] src, int shift) {
    int n = src.length;
    if (shift >= 0) {
      int q = shift >>> 6;
      int r = shift & 63;
      for (int w = n - 1; w >= 0; w--) {
        int s = w - q;
        long v = 0;
        if (s >= 0) {
          v = src[s] << r;
          if (r != 0 && s > 0) {
            v |= src[s - 1] >>> (64 - r);
          }
        }
        dst[w] = v;
      }
    } else {
      int q = (-shift) >>> 6;
      int r = (-shift) & 63;
      for (int w = 0; w < n; w++) {
        int s = w + q;
        long v = 0;
        if (s < n) {
          v = src[s] >>> r;
          if (r != 0 && s + 1 < n) {
            v |= src[s + 1] << (64 - r);
          }
        }
        dst[w] = v;
      }
    }
  }

  private static void or(long[] dst, long[] src) {
    for (int w = 0; w < dst.length; w++) {
      dst[w] |= src[w];
    }
  }

  private static void and(long[] dst, long[] mask) {
    for (int w = 0; w < dst.length; w++) {
      dst[w] &= mask[w];
    }
  }

  /** The texture's values sampled at the grid voxels, slice by slice; a reused buffer. */
  private static final class GridSlices {
    private final VolumeSpace space;
    private final BoneExtent.SliceSource slices;
    private final int[] textureX;
    private final int[] textureY;
    private final float[] out;

    GridSlices(VolumeSpace space, BoneExtent.SliceSource slices) {
      this.space = space;
      this.slices = slices;
      Vector3i g = space.grid();
      if (g.equals(space.texture())) {
        textureX = null;
        textureY = null;
        out = null;
      } else {
        textureX = new int[g.x];
        textureY = new int[g.y];
        for (int x = 0; x < g.x; x++) {
          textureX[x] = textureVoxelOfGrid(x, 0, 0).x;
        }
        for (int y = 0; y < g.y; y++) {
          textureY[y] = textureVoxelOfGrid(0, y, 0).y;
        }
        out = new float[g.x * g.y];
      }
    }

    private Vector3i textureVoxelOfGrid(int x, int y, int z) {
      return space.textureVoxelOf(space.texCoordOfGrid(x, y, z, new Vector3d()), new Vector3i());
    }

    float[] slice(int z) {
      float[] t = slices.slice(textureVoxelOfGrid(0, 0, z).z);
      if (out == null) {
        return t;
      }
      int tx = space.texture().x;
      for (int y = 0, i = 0; y < textureY.length; y++) {
        int row = textureY[y] * tx;
        for (int x = 0; x < textureX.length; x++, i++) {
          out[i] = t[row + textureX[x]];
        }
      }
      return out;
    }
  }
}
