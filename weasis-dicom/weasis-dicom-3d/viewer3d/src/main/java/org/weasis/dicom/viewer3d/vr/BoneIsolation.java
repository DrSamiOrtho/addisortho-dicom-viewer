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
 *       by the field of view stays. That air then reaches {@link #AIR_REACH_MM} further through
 *       what is neither air nor dense, across the blurred edge of a standard kernel;
 *   <li>each 6-connected piece of the dense voxels is external when at least {@link
 *       #MIN_AIR_CONTACTS} of its voxels, and {@link #AIR_CONTACT_SHARE} of them, touch outside air
 *       face to face; the other pieces are internal;
 *   <li>an internal piece of {@link #SEED_MM3} or more is bone; one under {@link #NOISE_MM3} is
 *       noise and goes; one in between stays only when it lies within {@link #JOIN_MM} of a piece
 *       that stays, the true distance in mm with the voxel size of each axis. That spreads from
 *       piece to piece for at most {@link #MAX_LINKS} links, so a fracture fragment, a chip, a
 *       sesamoid, the small carpal bones and the phalanges of a finger stay through their joint
 *       spaces, while the specks in a pad, the sheet of a table board and the marker on a pad go,
 *       and a string of specks cannot carry the bone far along a pad seam. Without any piece of
 *       {@link #SEED_MM3} the largest internal piece serves as the seed;
 *   <li>the bone region: each kept piece with its own holes filled slice by slice (the marrow canal
 *       and a nail inside it stay), so two bones never close a hole between them over pad or soft
 *       tissue; the dropped pieces are out before the fill;
 *   <li>metal touching a bone of that region, before it is widened, without air contact (a plate, a
 *       nail, a prosthesis) is kept whole; a marker or other metal beside the bone does not touch
 *       it and goes; of metal touching air (a fixator pin, a wire out of the skin) only the part
 *       inside the widened region stays;
 *   <li>the region and the kept metal widened by {@link #MARGIN_VOXELS}, so the smooth surface is
 *       not clipped.
 * </ol>
 *
 * <p>Known losses, put back by Undo or Whole volume: a bone fragment displaced by more than {@link
 * #JOIN_MM} from any kept bone, a paediatric ossification centre further than that from its shaft,
 * and any bone smaller than {@link #SEED_MM3} in a volume that holds no bigger bone near it. The
 * other way round, a marker or a speck of {@link #NOISE_MM3} or more within {@link #JOIN_MM} of the
 * bone stays, and so does a chain of such specks that starts there, up to {@link #MAX_LINKS} long.
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

  /**
   * How far, in mm, the outside air reaches into what is neither air nor dense: the blurred layer
   * that a smooth (standard) reconstruction kernel leaves between a cast or a table and the air,
   * about one voxel of -100 HU where a bone kernel goes straight from dense to air. Without it a
   * cast on a standard-kernel series never touches the air and stays. Bone lies deeper than this
   * under the skin.
   */
  public static final double AIR_REACH_MM = 1.0;

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
   * An internal piece between the two sizes stays when the gap between it and a kept piece is
   * shorter than this, in mm: the Euclidean distance between the edges of their nearest voxels,
   * with each axis's voxel size, so a 3 mm joint space on 1.5 mm slices is 3 mm, not 4.5.
   */
  public static final double JOIN_MM = 4;

  /**
   * Most links in a chain of small pieces joined one to the next from a bone: enough for the three
   * phalanges of a finger beyond its metacarpal, or the two rows of the carpus beyond the radius.
   */
  public static final int MAX_LINKS = 4;

  /**
   * Largest box, in grid voxels, searched around one small piece (eight bytes each while it is
   * measured, 32 MB at most); a piece under 1 cm3 needing more is a sheet or a seam, not a
   * fragment, and is dropped.
   */
  private static final long MAX_REACH_VOXELS = 1L << 22;

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
  private static final byte WIDENED = 3;

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
    int[] reachSteps = reachSteps(gridVoxelMm);
    int airSteps =
        Math.max(1, (int) Math.round(AIR_REACH_MM / Math.min(gridVoxelMm.x, gridVoxelMm.y)));
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
    try {
      GridSlices grid = new GridSlices(space, slices);
      for (int z = 0; z < sz; z++) {
        if (cancelled.getAsBoolean()) {
          return Optional.empty();
        }
        float[] values = grid.slice(z);
        classify(values, boneHu, dense.sliceWords(z), metal.sliceWords(z), cell);
        floodFromBorder(cell, sx, sy, queue);
        widenAir(cell, values, boneHu, sx, sy, airSteps);
        mark(cell, REACHED, air.sliceWords(z));
      }

      // Every dense piece: external, bone by size, noise, or a candidate between the two sizes.
      int bonePieces = 0;
      int externalPieces = 0;
      int droppedPieces = 0;
      Box bones = null;
      List<Piece> kept = new ArrayList<>();
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
              kept.add(p);
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
        kept.add(largest);
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
          join(
              region,
              cand,
              scratch,
              candidates,
              kept,
              gridVoxelMm,
              reachSteps,
              bones,
              queue,
              cancelled);
      if (join == null) {
        return Optional.empty();
      }
      droppedPieces += candidates.size();
      bones = join.box();

      // Each bone's own holes, so that two bones cannot enclose pad or soft tissue between them.
      long keptVoxels = 0;
      for (Piece p : kept) {
        if (cancelled.getAsBoolean()) {
          return Optional.empty();
        }
        fillPiece(region, scratch, p, cell, queue);
        keptVoxels += p.voxels();
      }
      // The fills only add voxels: fewer than the kept pieces means a bone was lost, and the view
      // must stay whole rather than show what is left of it.
      long filled = region.count();
      if (filled < keptVoxels) {
        throw new IllegalStateException(
            "Bone isolation lost bone: " + filled + " voxels for " + keptVoxels + " kept");
      }

      // Metal is kept whole only when it touches a bone itself, before the margin: a marker beside
      // the bone no longer gets in through the widened edge.
      int implants = 0;
      Box box = bones;
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
          box = union(box, p.box());
        }
      }
      if (cancelled.getAsBoolean()) {
        return Optional.empty();
      }
      dilate(region, scratch, MARGIN_VOXELS, box.grow(1, region));
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
    }
  }

  /** Memory of a search on the grid: four bit volumes and the scratch of one slice. */
  public static long workingBytes(VolumeSpace space) {
    Vector3i g = space.grid();
    long sliceVoxels = (long) g.x * g.y;
    long sliceWords = (sliceVoxels + 63) >>> 6;
    return 4 * 8L * sliceWords * g.z + sliceVoxels + 3 * 8L * sliceWords;
  }

  /**
   * Grid voxels a voxel may be off on each axis alone and still leave a gap under {@link #JOIN_MM}:
   * the half size of the box holding every voxel that near. One for a gap of none.
   */
  static int[] reachSteps(Vector3d voxelMm) {
    return new int[] {
      1 + (int) Math.floor(JOIN_MM / voxelMm.x - 1e-9),
      1 + (int) Math.floor(JOIN_MM / voxelMm.y - 1e-9),
      1 + (int) Math.floor(JOIN_MM / voxelMm.z - 1e-9)
    };
  }

  /** What the spread over the candidates gave: the pieces joined and the box of all kept pieces. */
  private record Join(int joined, Box box) {}

  /**
   * Moves into {@code region}, link by link, every candidate within {@link #JOIN_MM} of it, for at
   * most {@link #MAX_LINKS} links: the pieces found near the region in one round join it only at
   * the round's end, so each round adds one link to a chain. The joined pieces leave {@code
   * pending} for {@code kept}; {@code null} when cancelled.
   */
  private static Join join(
      VoxelBits region,
      VoxelBits cand,
      VoxelBits scratch,
      List<Piece> pending,
      List<Piece> kept,
      Vector3d voxelMm,
      int[] steps,
      Box box,
      IntQueue queue,
      BooleanSupplier cancelled) {
    int joined = 0;
    // Only a candidate whose reach meets a piece kept in the last round can have come near.
    List<Box> fresh = List.of(box);
    for (int link = 0; link < MAX_LINKS && !fresh.isEmpty() && !pending.isEmpty(); link++) {
      List<Piece> near = new ArrayList<>();
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
        if (nearRegion(p, cand, region, scratch, voxelMm, reach, queue)) {
          it.remove();
          near.add(p);
        }
      }
      List<Box> next = new ArrayList<>();
      for (Piece p : near) {
        flood(cand, region, null, null, p.x(), p.y(), p.z(), queue);
        kept.add(p);
        joined++;
        box = union(box, p.box());
        next.add(p.box());
      }
      fresh = next;
    }
    return new Join(joined, box);
  }

  /**
   * Whether a voxel of {@code region} is less than {@link #JOIN_MM} from the candidate piece grown
   * from its seed voxel over {@code cand}, edge to edge: the piece is marked in {@code mark} and
   * grown by one voxel each way (a 3 by 3 by 3 block), and the exact Euclidean distance to that, in
   * mm with each axis's voxel size, is computed over {@code reach} in three one-dimensional passes.
   * From the grown piece the distance to a voxel is the gap between the voxels' edges, axis by
   * axis. {@code mark} must be clear within {@code reach} and is clear again on return.
   */
  private static boolean nearRegion(
      Piece p,
      VoxelBits cand,
      VoxelBits region,
      VoxelBits mark,
      Vector3d voxelMm,
      Box reach,
      IntQueue queue) {
    int bx = reach.x1() - reach.x0() + 1;
    int by = reach.y1() - reach.y0() + 1;
    int bz = reach.z1() - reach.z0() + 1;
    long n = (long) bx * by * bz;
    if (n > MAX_REACH_VOXELS) {
      return false; // not a compact fragment: a sheet or a seam across the pad
    }
    markPiece(cand, mark, p, queue);
    double[] d = new double[(int) n];
    for (int z = 0, i = 0; z < bz; z++) {
      for (int y = 0; y < by; y++) {
        for (int x = 0; x < bx; x++, i++) {
          d[i] = mark.get(reach.x0() + x, reach.y0() + y, reach.z0() + z) ? 0 : FAR;
        }
      }
    }
    clearBox(mark, reach);
    // The block grown around each voxel of the piece: a minimum over three along each axis.
    for (int z = 0; z < bz; z++) {
      for (int y = 0; y < by; y++) {
        nearestOfThree(d, (z * by + y) * bx, 1, bx);
      }
    }
    for (int z = 0; z < bz; z++) {
      for (int x = 0; x < bx; x++) {
        nearestOfThree(d, z * by * bx + x, bx, by);
      }
    }
    for (int y = 0; y < by; y++) {
      for (int x = 0; x < bx; x++) {
        nearestOfThree(d, y * bx + x, bx * by, bz);
      }
    }
    int longest = Math.max(bx, Math.max(by, bz));
    double[] line = new double[longest];
    double[] out = new double[longest];
    int[] v = new int[longest];
    double[] cut = new double[longest + 1];
    for (int z = 0; z < bz; z++) {
      for (int y = 0; y < by; y++) {
        distanceAlong(d, (z * by + y) * bx, 1, bx, voxelMm.x, line, out, v, cut);
      }
    }
    for (int z = 0; z < bz; z++) {
      for (int x = 0; x < bx; x++) {
        distanceAlong(d, z * by * bx + x, bx, by, voxelMm.y, line, out, v, cut);
      }
    }
    for (int y = 0; y < by; y++) {
      for (int x = 0; x < bx; x++) {
        distanceAlong(d, y * bx + x, bx * by, bz, voxelMm.z, line, out, v, cut);
      }
    }
    double r2 = JOIN_MM * JOIN_MM - 1e-9;
    for (int z = 0, i = 0; z < bz; z++) {
      for (int y = 0; y < by; y++) {
        for (int x = 0; x < bx; x++, i++) {
          if (d[i] < r2 && region.get(reach.x0() + x, reach.y0() + y, reach.z0() + z)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  /** Each value of the line becomes the least of itself and its two neighbours. */
  private static void nearestOfThree(double[] d, int start, int stride, int len) {
    double before = FAR;
    for (int q = 0; q < len; q++) {
      int i = start + q * stride;
      double here = d[i];
      double after = q + 1 < len ? d[i + stride] : FAR;
      d[i] = Math.min(here, Math.min(before, after));
      before = here;
    }
  }

  /** Squared distance standing for "no piece voxel on this line yet". */
  private static final double FAR = 1e30;

  /**
   * One pass of the exact squared Euclidean distance transform (Felzenszwalb and Huttenlocher) on
   * the {@code len} values of {@code d} from {@code start}, {@code stride} apart, voxels {@code
   * step} mm apart: each value becomes the least of the others' value plus their squared distance.
   */
  static void distanceAlong(
      double[] d,
      int start,
      int stride,
      int len,
      double step,
      double[] f,
      double[] out,
      int[] v,
      double[] cut) {
    for (int q = 0; q < len; q++) {
      f[q] = d[start + q * stride];
    }
    int k = 0;
    v[0] = 0;
    cut[0] = Double.NEGATIVE_INFINITY;
    cut[1] = Double.POSITIVE_INFINITY;
    for (int q = 1; q < len; q++) {
      double s = intersection(f, v[k], q, step);
      // cut[0] is minus infinity, so k never goes below 0.
      while (s <= cut[k]) {
        k--;
        s = intersection(f, v[k], q, step);
      }
      k++;
      v[k] = q;
      cut[k] = s;
      cut[k + 1] = Double.POSITIVE_INFINITY;
    }
    k = 0;
    for (int q = 0; q < len; q++) {
      double pq = q * step;
      while (cut[k + 1] < pq) {
        k++;
      }
      double dq = (q - v[k]) * step;
      out[q] = Math.min(FAR, dq * dq + f[v[k]]);
    }
    for (int q = 0; q < len; q++) {
      d[start + q * stride] = out[q];
    }
  }

  /** Where the parabolas of lines {@code a} and {@code b} cross, in mm along the line. */
  private static double intersection(double[] f, int a, int b, double step) {
    double pa = a * step;
    double pb = b * step;
    return ((f[b] + pb * pb) - (f[a] + pa * pa)) / (2 * (pb - pa));
  }

  /** Marks in {@code mark} the 6-connected piece of {@code from} holding the piece's seed. */
  private static void markPiece(VoxelBits from, VoxelBits mark, Piece p, IntQueue queue) {
    int sx = from.sizeX();
    int sy = from.sizeY();
    int sz = from.sizeZ();
    mark.set(p.x(), p.y(), p.z());
    queue.add(p.x() + sx * (p.y() + sy * p.z()));
    while (!queue.isEmpty()) {
      int i = queue.poll();
      int x = i % sx;
      int y = (i / sx) % sy;
      int z = i / sx / sy;
      for (int[] nb : NEIGHBOURS) {
        int nx = x + nb[0];
        int ny = y + nb[1];
        int nz = z + nb[2];
        if (nx >= 0
            && ny >= 0
            && nz >= 0
            && nx < sx
            && ny < sy
            && nz < sz
            && from.get(nx, ny, nz)
            && mark.testAndSet(nx, ny, nz)) {
          queue.add(nx + sx * (ny + sy * nz));
        }
      }
    }
  }

  /**
   * Fills the holes of one kept piece, slice by slice within its box: the piece moves out of {@code
   * region} into {@code scratch}, is filled there, and goes back with its holes. A piece that lay
   * in the hole of a bone filled before it (a speck in the marrow canal) is joined to that bone by
   * now and moves with it, so the box is the one of what moved, not of the piece alone.
   */
  private static void fillPiece(
      VoxelBits region, VoxelBits scratch, Piece p, byte[] cell, IntQueue queue) {
    Piece moved = flood(region, scratch, null, null, p.x(), p.y(), p.z(), queue);
    Box box = moved.box().grow(1, region);
    fillHoles(scratch, box, cell, queue);
    for (int z = box.z0(); z <= box.z1(); z++) {
      long[] s = scratch.sliceWords(z);
      or(region.sliceWords(z), s);
      Arrays.fill(s, 0L);
    }
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

  /**
   * Lets the outside air of a slice reach {@code steps} cells further, 4-connected, through cells
   * that are neither air nor dense, so a dense piece behind the blurred edge of a smooth kernel
   * touches it.
   */
  static void widenAir(byte[] cell, float[] values, double boneHu, int w, int h, int steps) {
    for (int s = 0; s < steps; s++) {
      boolean grew = false;
      for (int i = 0; i < cell.length; i++) {
        if (cell[i] == REACHED || values[i] >= boneHu) {
          continue;
        }
        int x = i % w;
        int y = i / w;
        if ((x > 0 && cell[i - 1] == REACHED)
            || (x < w - 1 && cell[i + 1] == REACHED)
            || (y > 0 && cell[i - w] == REACHED)
            || (y < h - 1 && cell[i + w] == REACHED)) {
          cell[i] = WIDENED;
          grew = true;
        }
      }
      if (!grew) {
        return;
      }
      for (int i = 0; i < cell.length; i++) {
        if (cell[i] == WIDENED) {
          cell[i] = REACHED;
        }
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
