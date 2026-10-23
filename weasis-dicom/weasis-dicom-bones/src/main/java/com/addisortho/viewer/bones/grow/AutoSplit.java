/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.grow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Splits all the bone of a CT into separate bones at once, without a click, so the Bones panel
 * opens with every bone already in its list.
 *
 * <ol>
 *   <li>The bone is read twice: at the bone threshold (200 HU), closed by one voxel and with the
 *       marrow filled, which is the bone each piece will hold; and at the dense threshold (300
 *       HU), marrow filled, where the joint spaces are open because the subchondral plates are
 *       dense and the cartilage is not.
 *   <li>The distance of each dense voxel to the nearest non-bone voxel, in mm (a Euclidean distance
 *       transform, exact on the anisotropic grid).
 *   <li>A watershed on that distance: the voxels are taken deepest first and pieces meet where the
 *       bone is narrow. Two pieces stay apart only when the neck between them is thin both in mm
 *       and against the thickness of each piece, and each piece is large enough there: a joint
 *       touching at a point splits, the femoral neck or a narrow metaphysis does not.
 *   <li>Every voxel of the bone threshold goes to the piece that reaches it first.
 *   <li>Pieces under {@link Options#fragmentMm3()} are fragments: each joins the large piece it
 *       touches most, or the nearest within {@link Options#attachMm()}, so a comminuted fracture
 *       gives one bone with its fragments and not dozens of entries. Specks are dropped.
 * </ol>
 *
 * <p>A large CT is worked on a grid coarser by a whole factor, to bound memory; each voxel then
 * takes the piece of its coarse voxel. Run off the event dispatch thread; interrupting the thread
 * cancels it.
 */
public final class AutoSplit {

  /** Whether a voxel of the CT grid is still shown in the 3D view, not cut away. */
  @FunctionalInterface
  public interface Shown {
    boolean at(int x, int y, int z);

    Shown ALL = (_, _, _) -> true;
  }

  /**
   * @param boneHu threshold of the bone each piece holds
   * @param denseHu threshold at which the pieces are told apart
   * @param mmX voxel size along the columns
   * @param mmY voxel size along the rows
   * @param mmZ distance between the slices
   * @param minBoneMm3 smallest part above a neck that can be a bone of its own
   * @param minDepthMm how much thicker than the neck each part must be to be a bone of its own
   * @param neckRatio thickest neck, as a part of the thickness of the thinner of the two parts
   * @param fragmentMm3 below this, a piece is a fragment of the bone it touches or lies near
   * @param attachMm farthest a loose fragment may lie from its bone
   * @param speckMm3 a loose piece below this, near no bone, is dropped
   * @param maxWorkVoxels largest grid worked on; a larger CT is worked on a coarser grid
   */
  public record Options(
      double boneHu,
      double denseHu,
      double mmX,
      double mmY,
      double mmZ,
      double minBoneMm3,
      double minDepthMm,
      double neckRatio,
      double fragmentMm3,
      double attachMm,
      double speckMm3,
      long maxWorkVoxels) {

    public static Options of(double mmX, double mmY, double mmZ) {
      return new Options(
          GrowOptions.DEFAULT_LOWER_HU, 300, mmX, mmY, mmZ, 200, 1.0, 0.45, 1000, 10, 30,
          16_000_000L);
    }

    public Options withMaxWorkVoxels(long voxels) {
      return new Options(
          boneHu, denseHu, mmX, mmY, mmZ, minBoneMm3, minDepthMm, neckRatio, fragmentMm3,
          attachMm, speckMm3, voxels);
    }
  }

  /**
   * A bone found.
   *
   * @param mask its voxels, on the CT grid
   * @param anchor its deepest voxel, on the CT grid
   * @param fragments fragments joined to it
   */
  public record Piece(BoxMask mask, int[] anchor, int fragments) {}

  /**
   * @param pieces the bones, largest first
   * @param fragments fragments joined to a bone
   * @param specks loose specks dropped
   * @param factor coarsening of the grid worked on, 1 for the CT's own
   */
  public record Result(List<Piece> pieces, int fragments, int specks, int factor, long millis) {}

  private static final float INF = 1e12f;

  private AutoSplit() {}

  /**
   * @param box the block of the CT grid to search, such as the 3D view's crop box
   * @param hu gives a reader of the CT for each thread
   */
  public static Result split(VoxelBox box, Supplier<HuSource> hu, Shown shown, Options o) {
    long start = System.nanoTime();
    Thread caller = Thread.currentThread();
    int sx = box.sizeX();
    int sy = box.sizeY();
    int sz = box.sizeZ();
    BitVolume atBone = new BitVolume(sx, sy, sz);
    BitVolume atDense = new BitVolume(sx, sy, sz);
    // One slice per task: a slice's bits share no word with another slice's.
    IntStream.range(0, sz)
        .parallel()
        .forEach(
            k -> {
              RegionGrow.checkCancelled(caller);
              HuSource ct = hu.get();
              int gz = box.z0() + k;
              for (int y = 0; y < sy; y++) {
                for (int x = 0; x < sx; x++) {
                  int gx = box.x0() + x;
                  int gy = box.y0() + y;
                  if (!shown.at(gx, gy, gz)) {
                    continue;
                  }
                  double v = ct.huAt(gx, gy, gz);
                  if (v >= o.boneHu()) {
                    atBone.set(x, y, k);
                    if (v >= o.denseHu()) {
                      atDense.set(x, y, k);
                    }
                  }
                }
              }
            });
    BitVolume solid =
        RegionGrow.fillHoles(RegionGrow.close(atBone, 1, GrowProgress.NONE, caller), caller);
    BitVolume dense = RegionGrow.fillHoles(atDense, caller);
    dense.and(solid);
    VoxelBox tight = solid.bounds();
    if (tight == null) {
      return new Result(List.of(), 0, 0, 1, millis(start));
    }
    solid = solid.crop(tight);
    dense = dense.crop(tight);
    VoxelBox block =
        new VoxelBox(
            box.x0() + tight.x0(),
            box.x0() + tight.x1(),
            box.y0() + tight.y0(),
            box.y0() + tight.y1(),
            box.z0() + tight.z0(),
            box.z0() + tight.z1());
    return new Work(solid, dense, block, o, caller).run(start);
  }

  private static long millis(long start) {
    return (System.nanoTime() - start) / 1_000_000;
  }

  /** One split, on the block holding the bone. */
  private static final class Work {
    private final BitVolume solid;
    private final BitVolume dense;
    private final VoxelBox block;
    private final Options o;
    private final Thread caller;
    private final int f;
    private final int wx;
    private final int wy;
    private final int wz;
    private final double fineMm3;

    Work(BitVolume solid, BitVolume dense, VoxelBox block, Options o, Thread caller) {
      this.solid = solid;
      this.dense = dense;
      this.block = block;
      this.o = o;
      this.caller = caller;
      int factor = 1;
      while (factor < 4 && coarse(solid.sizeX(), factor) * (long) coarse(solid.sizeY(), factor)
              * coarse(solid.sizeZ(), factor)
          > o.maxWorkVoxels()) {
        factor++;
      }
      this.f = factor;
      this.wx = coarse(solid.sizeX(), f);
      this.wy = coarse(solid.sizeY(), f);
      this.wz = coarse(solid.sizeZ(), f);
      this.fineMm3 = o.mmX() * o.mmY() * o.mmZ();
    }

    private static int coarse(int size, int factor) {
      return (size + factor - 1) / factor;
    }

    private int index(int x, int y, int z) {
      return (z * wy + y) * wx + x;
    }

    Result run(long start) {
      boolean[] solidW = new boolean[wx * wy * wz];
      float[] depth = depthOfDense(solidW);
      int[] label = watershed(depth);
      depth = null; // NOSONAR frees the largest array before the next steps
      int pieces = regrow(label, solidW);
      return pieces(label, pieces, start);
    }

    // ---- Coarse grid and distance ----

    /**
     * Distance in mm of each dense voxel of the working grid to the nearest voxel that is not;
     * 0 outside. Fills {@code solidW} with the working grid's bone.
     */
    private float[] depthOfDense(boolean[] solidW) {
      float[] g = new float[wx * wy * wz];
      IntStream.range(0, wz)
          .parallel()
          .forEach(
              z -> {
                RegionGrow.checkCancelled(caller);
                for (int y = 0; y < wy; y++) {
                  for (int x = 0; x < wx; x++) {
                    int denseCount = 0;
                    int present = 0;
                    boolean any = false;
                    for (int dz = 0; dz < f; dz++) {
                      int fz = z * f + dz;
                      if (fz >= solid.sizeZ()) {
                        continue;
                      }
                      for (int dy = 0; dy < f; dy++) {
                        int fy = y * f + dy;
                        if (fy >= solid.sizeY()) {
                          continue;
                        }
                        for (int dx = 0; dx < f; dx++) {
                          int fx = x * f + dx;
                          if (fx >= solid.sizeX()) {
                            continue;
                          }
                          present++;
                          any |= solid.get(fx, fy, fz);
                          if (dense.get(fx, fy, fz)) {
                            denseCount++;
                          }
                        }
                      }
                    }
                    int i = index(x, y, z);
                    solidW[i] = any;
                    // A coarse voxel is dense when most of it is: a joint space stays open.
                    g[i] = present > 0 && 2 * denseCount >= present ? INF : 0;
                  }
                }
              });
      distanceTransform(g);
      for (int i = 0; i < g.length; i++) {
        g[i] = g[i] > 0 ? (float) Math.sqrt(g[i]) : 0;
      }
      return g;
    }

    /** Squared Euclidean distance in mm², in place: 0 stays 0, {@link #INF} gets its distance. */
    private void distanceTransform(float[] g) {
      double ux = o.mmX() * f;
      double uy = o.mmY() * f;
      double uz = o.mmZ() * f;
      squaredDistance(g, wx, wy, wz, ux, uy, uz, caller);
      // The edge of the block counts as outside the bone: no voxel is farther than that.
      clampToEdges(g, ux, uy, uz);
    }

    private void clampToEdges(float[] g, double ux, double uy, double uz) {
      for (int z = 0; z < wz; z++) {
        double ez = Math.min(z + 1, wz - z) * uz;
        for (int y = 0; y < wy; y++) {
          double ey = Math.min(y + 1, wy - y) * uy;
          for (int x = 0; x < wx; x++) {
            int i = index(x, y, z);
            if (g[i] > 0) {
              double ex = Math.min(x + 1, wx - x) * ux;
              double e = Math.min(ex, Math.min(ey, ez));
              g[i] = (float) Math.min(g[i], e * e);
            }
          }
        }
      }
    }

    // ---- Watershed ----

    /** The piece of each dense voxel of the working grid, 1 and up; 0 elsewhere. */
    private int[] watershed(float[] depth) {
      // Deepest first, by steps of 0.05 mm.
      int levels = 1;
      for (float d : depth) {
        levels = Math.max(levels, (int) (d * 20) + 1);
      }
      int[] count = new int[levels + 1];
      int bone = 0;
      for (float d : depth) {
        if (d > 0) {
          count[levels - (int) (d * 20)]++;
          bone++;
        }
      }
      for (int i = 1; i <= levels; i++) {
        count[i] += count[i - 1];
      }
      int[] order = new int[bone];
      int[] next = new int[levels + 1];
      System.arraycopy(count, 0, next, 1, levels);
      for (int i = 0; i < depth.length; i++) {
        if (depth[i] > 0) {
          order[next[levels - (int) (depth[i] * 20)]++] = i;
        }
      }
      RegionGrow.checkCancelled(caller);

      int[] node = new int[depth.length];
      Arrays.fill(node, -1);
      Nodes nodes = new Nodes();
      int[] roots = new int[6];
      int[] kept = new int[6];
      double coarseMm3 = fineMm3 * f * f * f;
      for (int n = 0; n < order.length; n++) {
        if ((n & 0xFFFF) == 0) {
          RegionGrow.checkCancelled(caller);
        }
        int i = order[n];
        float d = depth[i];
        int r = neighbourRoots(node, nodes, i, roots);
        if (r == 0) {
          node[i] = nodes.add(d, i);
          continue;
        }
        // Highest peak first: a part joins the first part kept that it may not stay apart from.
        sortByPeak(roots, r, nodes);
        int k = 0;
        for (int j = 0; j < r; j++) {
          int a = roots[j];
          int joinTo = -1;
          for (int m = 0; m < k; m++) {
            if (!apart(nodes, kept[m], a, d, coarseMm3)) {
              joinTo = kept[m];
              break;
            }
          }
          if (joinTo < 0) {
            kept[k++] = a;
          } else {
            nodes.union(joinTo, a);
          }
        }
        node[i] = kept[0];
        nodes.volume[kept[0]]++;
      }
      // Each voxel takes the piece of its root.
      int[] pieceOfRoot = new int[nodes.size];
      int pieces = 0;
      for (int i = 0; i < node.length; i++) {
        if (node[i] < 0) {
          node[i] = 0;
          continue;
        }
        int root = nodes.find(node[i]);
        if (pieceOfRoot[root] == 0) {
          pieceOfRoot[root] = ++pieces;
          peakOfPiece.put(pieces, nodes.peakVoxel[root]);
        }
        node[i] = pieceOfRoot[root];
      }
      return node;
    }

    /** Deepest voxel of each piece of the watershed, working grid index. */
    private final Map<Integer, Integer> peakOfPiece = new HashMap<>();

    /** Whether two parts meeting at depth {@code d} stay two bones. */
    private boolean apart(Nodes nodes, int a, int b, float d, double voxelMm3) {
      return standsAlone(nodes, a, d, voxelMm3) && standsAlone(nodes, b, d, voxelMm3);
    }

    private boolean standsAlone(Nodes nodes, int a, float d, double voxelMm3) {
      float peak = nodes.peak[a];
      return nodes.volume[a] * voxelMm3 >= o.minBoneMm3()
          && peak - d >= o.minDepthMm()
          && d <= o.neckRatio() * peak;
    }

    /** The distinct roots of the 6 neighbours already taken, in {@code roots}; their count. */
    private int neighbourRoots(int[] node, Nodes nodes, int i, int[] roots) {
      int x = i % wx;
      int y = (i / wx) % wy;
      int z = i / (wx * wy);
      int r = 0;
      for (int dir = 0; dir < 6; dir++) {
        int j = neighbour(x, y, z, dir);
        if (j < 0 || node[j] < 0) {
          continue;
        }
        int root = nodes.find(node[j]);
        boolean seen = false;
        for (int m = 0; m < r; m++) {
          seen |= roots[m] == root;
        }
        if (!seen) {
          roots[r++] = root;
        }
      }
      return r;
    }

    private int neighbour(int x, int y, int z, int dir) {
      return switch (dir) {
        case 0 -> x > 0 ? index(x - 1, y, z) : -1;
        case 1 -> x < wx - 1 ? index(x + 1, y, z) : -1;
        case 2 -> y > 0 ? index(x, y - 1, z) : -1;
        case 3 -> y < wy - 1 ? index(x, y + 1, z) : -1;
        case 4 -> z > 0 ? index(x, y, z - 1) : -1;
        default -> z < wz - 1 ? index(x, y, z + 1) : -1;
      };
    }

    private static void sortByPeak(int[] roots, int r, Nodes nodes) {
      for (int a = 1; a < r; a++) {
        int v = roots[a];
        int b = a - 1;
        while (b >= 0 && nodes.peak[roots[b]] < nodes.peak[v]) {
          roots[b + 1] = roots[b];
          b--;
        }
        roots[b + 1] = v;
      }
    }

    // ---- Regrow into the bone threshold ----

    /**
     * Gives every bone voxel of the working grid the piece that reaches it first; a part no piece
     * reaches becomes a piece of its own. Returns the number of pieces.
     */
    private int regrow(int[] label, boolean[] solidW) {
      IntQueue queue = new IntQueue(1 << 16);
      for (int i = 0; i < label.length; i++) {
        if (label[i] > 0) {
          queue.add(i);
        }
      }
      int pieces = 0;
      for (int l : label) {
        pieces = Math.max(pieces, l);
      }
      spread(label, solidW, queue);
      for (int i = 0; i < label.length; i++) {
        if (solidW[i] && label[i] == 0) {
          label[i] = ++pieces;
          queue.add(i);
          spread(label, solidW, queue);
        }
      }
      return pieces;
    }

    private void spread(int[] label, boolean[] solidW, IntQueue queue) {
      int visited = 0;
      while (!queue.isEmpty()) {
        if ((++visited & 0xFFFF) == 0) {
          RegionGrow.checkCancelled(caller);
        }
        int i = queue.poll();
        int x = i % wx;
        int y = (i / wx) % wy;
        int z = i / (wx * wy);
        for (int dir = 0; dir < 6; dir++) {
          int j = neighbour(x, y, z, dir);
          if (j >= 0 && solidW[j] && label[j] == 0) {
            label[j] = label[i];
            queue.add(j);
          }
        }
      }
    }

    // ---- Pieces, fragments and masks ----

    private Result pieces(int[] label, int count, long start) {
      // Voxels and extent of each piece on the CT's own grid.
      long[] voxels = new long[count + 1];
      int[][] extent = new int[count + 1][];
      for (int z = 0; z < solid.sizeZ(); z++) {
        RegionGrow.checkCancelled(caller);
        if (solid.countSlice(z) == 0) {
          continue;
        }
        for (int y = 0; y < solid.sizeY(); y++) {
          for (int x = 0; x < solid.sizeX(); x++) {
            if (solid.get(x, y, z)) {
              int l = label[index(x / f, y / f, z / f)];
              voxels[l]++;
              extent[l] = grow(extent[l], x, y, z);
            }
          }
        }
      }
      Map<Long, Integer> contacts = contacts(label);
      int[] home = new int[count + 1];
      int fragments = 0;
      int specks = 0;
      boolean anyLarge = false;
      for (int l = 1; l <= count; l++) {
        anyLarge |= voxels[l] * fineMm3 >= o.fragmentMm3();
      }
      for (int l = 1; l <= count; l++) {
        if (voxels[l] == 0) {
          home[l] = -1;
        } else if (!anyLarge || voxels[l] * fineMm3 >= o.fragmentMm3()) {
          home[l] = l;
        } else {
          int to = mostTouched(l, contacts, voxels);
          if (to < 0) {
            to = nearestLarge(l, extent, voxels);
          }
          if (to > 0) {
            home[l] = to;
            fragments++;
          } else if (voxels[l] * fineMm3 >= o.speckMm3()) {
            home[l] = l;
          } else {
            home[l] = -1;
            specks++;
          }
        }
      }
      List<Piece> pieces = new ArrayList<>();
      for (int l = 1; l <= count; l++) {
        if (home[l] != l) {
          continue;
        }
        int[] e = null;
        int joined = 0;
        for (int m = 1; m <= count; m++) {
          if (home[m] == l && extent[m] != null) {
            e = e == null ? extent[m].clone() : union(e, extent[m]);
            joined += m == l ? 0 : 1;
          }
        }
        pieces.add(new Piece(mask(label, home, l, e), anchor(l, e), joined));
      }
      pieces.sort(Comparator.comparingLong((Piece p) -> p.mask().count()).reversed());
      return new Result(List.copyOf(pieces), fragments, specks, f, millis(start));
    }

    /** Touching faces between two pieces, by the pair (smaller label, larger label). */
    private Map<Long, Integer> contacts(int[] label) {
      Map<Long, Integer> contacts = new HashMap<>();
      for (int z = 0; z < wz; z++) {
        for (int y = 0; y < wy; y++) {
          for (int x = 0; x < wx; x++) {
            int a = label[index(x, y, z)];
            if (a == 0) {
              continue;
            }
            for (int dir = 1; dir < 6; dir += 2) {
              int j = neighbour(x, y, z, dir);
              int b = j < 0 ? 0 : label[j];
              if (b != 0 && b != a) {
                long key = ((long) Math.min(a, b) << 32) | Math.max(a, b);
                contacts.merge(key, 1, Integer::sum);
              }
            }
          }
        }
      }
      return contacts;
    }

    private int mostTouched(int l, Map<Long, Integer> contacts, long[] voxels) {
      int best = -1;
      int bestFaces = 0;
      for (Map.Entry<Long, Integer> e : contacts.entrySet()) {
        int a = (int) (e.getKey() >>> 32);
        int b = (int) (long) e.getKey();
        int other = a == l ? b : b == l ? a : -1;
        if (other > 0 && voxels[other] * fineMm3 >= o.fragmentMm3() && e.getValue() > bestFaces) {
          best = other;
          bestFaces = e.getValue();
        }
      }
      return best;
    }

    /** The large piece whose extent lies nearest the piece's centre, within the reach. */
    private int nearestLarge(int l, int[][] extent, long[] voxels) {
      int[] e = extent[l];
      double cx = (e[0] + e[1]) / 2.0;
      double cy = (e[2] + e[3]) / 2.0;
      double cz = (e[4] + e[5]) / 2.0;
      int best = -1;
      double bestMm = o.attachMm();
      for (int m = 1; m < extent.length; m++) {
        if (m == l || extent[m] == null || voxels[m] * fineMm3 < o.fragmentMm3()) {
          continue;
        }
        int[] b = extent[m];
        double dx = Math.max(0, Math.max(b[0] - cx, cx - b[1])) * o.mmX();
        double dy = Math.max(0, Math.max(b[2] - cy, cy - b[3])) * o.mmY();
        double dz = Math.max(0, Math.max(b[4] - cz, cz - b[5])) * o.mmZ();
        double mm = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (mm <= bestMm) {
          best = m;
          bestMm = mm;
        }
      }
      return best;
    }

    private BoxMask mask(int[] label, int[] home, int l, int[] e) {
      BitVolume bits = new BitVolume(e[1] - e[0] + 1, e[3] - e[2] + 1, e[5] - e[4] + 1);
      for (int z = e[4]; z <= e[5]; z++) {
        for (int y = e[2]; y <= e[3]; y++) {
          for (int x = e[0]; x <= e[1]; x++) {
            if (solid.get(x, y, z)) {
              int m = label[index(x / f, y / f, z / f)];
              if (m > 0 && home[m] == l) {
                bits.set(x - e[0], y - e[2], z - e[4]);
              }
            }
          }
        }
      }
      return new BoxMask(
          new VoxelBox(
              block.x0() + e[0],
              block.x0() + e[1],
              block.y0() + e[2],
              block.y0() + e[3],
              block.z0() + e[4],
              block.z0() + e[5]),
          bits);
    }

    /** The deepest voxel of the piece, else the centre of its extent, on the CT grid. */
    private int[] anchor(int l, int[] e) {
      Integer peak = peakOfPiece.get(l);
      int x;
      int y;
      int z;
      if (peak != null) {
        x = Math.min(solid.sizeX() - 1, (peak % wx) * f + f / 2);
        y = Math.min(solid.sizeY() - 1, ((peak / wx) % wy) * f + f / 2);
        z = Math.min(solid.sizeZ() - 1, (peak / (wx * wy)) * f + f / 2);
      } else {
        x = (e[0] + e[1]) / 2;
        y = (e[2] + e[3]) / 2;
        z = (e[4] + e[5]) / 2;
      }
      return new int[] {block.x0() + x, block.y0() + y, block.z0() + z};
    }

    private static int[] grow(int[] e, int x, int y, int z) {
      if (e == null) {
        return new int[] {x, x, y, y, z, z};
      }
      e[0] = Math.min(e[0], x);
      e[1] = Math.max(e[1], x);
      e[2] = Math.min(e[2], y);
      e[3] = Math.max(e[3], y);
      e[4] = Math.min(e[4], z);
      e[5] = Math.max(e[5], z);
      return e;
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
  }

  /**
   * Squared Euclidean distance in mm² to the nearest 0 of a grid, in place: 0 stays 0, {@link
   * #INF} gets its distance. Along x, then y, then z: each pass is the exact 1D transform of the
   * one before.
   */
  static void squaredDistance(
      float[] g, int nx, int ny, int nz, double ux, double uy, double uz, Thread caller) {
    int longest = Math.max(nx, Math.max(ny, nz));
    IntStream.range(0, nz)
        .parallel()
        .forEach(
            z -> {
              RegionGrow.checkCancelled(caller);
              Line line = new Line(longest);
              for (int y = 0; y < ny; y++) {
                int base = (z * ny + y) * nx;
                System.arraycopy(g, base, line.in, 0, nx);
                line.transform(nx, ux);
                System.arraycopy(line.out, 0, g, base, nx);
              }
              for (int x = 0; x < nx; x++) {
                for (int y = 0; y < ny; y++) {
                  line.in[y] = g[(z * ny + y) * nx + x];
                }
                line.transform(ny, uy);
                for (int y = 0; y < ny; y++) {
                  g[(z * ny + y) * nx + x] = line.out[y];
                }
              }
            });
    IntStream.range(0, ny)
        .parallel()
        .forEach(
            y -> {
              RegionGrow.checkCancelled(caller);
              Line line = new Line(longest);
              for (int x = 0; x < nx; x++) {
                for (int z = 0; z < nz; z++) {
                  line.in[z] = g[(z * ny + y) * nx + x];
                }
                line.transform(nz, uz);
                for (int z = 0; z < nz; z++) {
                  g[(z * ny + y) * nx + x] = line.out[z];
                }
              }
            });
  }

  /**
   * Splits one bone in two between two marks, one on each part: a watershed on the bone's distance
   * to its surface, flooded from the two marks deepest first, so the two parts meet at the
   * narrowest neck between the marks and not half-way. A part not connected to the second mark's
   * part stays with the first.
   *
   * @param keep the mark on the part that keeps the bone, CT grid voxel
   * @param cut the mark on the part split off, CT grid voxel
   * @throws BoneSplit.SplitException when a mark is not on the bone, or both marks are on one spot
   */
  public static BoneSplit.Split splitBetween(
      BoxMask bone, int[] keep, int[] cut, double mmX, double mmY, double mmZ)
      throws BoneSplit.SplitException {
    Thread caller = Thread.currentThread();
    VoxelBox box = bone.box();
    // One clear voxel around the bone, so its faces on the block's edges are surface too.
    int nx = box.sizeX() + 2;
    int ny = box.sizeY() + 2;
    int nz = box.sizeZ() + 2;
    int[] a = nearestOnBone(bone, keep);
    int[] b = nearestOnBone(bone, cut);
    if (a == null || b == null) {
      throw new BoneSplit.SplitException(BoneSplit.SplitException.Reason.NOT_IN_BONE);
    }
    float[] g = new float[nx * ny * nz];
    for (int z = 0; z < box.sizeZ(); z++) {
      for (int y = 0; y < box.sizeY(); y++) {
        for (int x = 0; x < box.sizeX(); x++) {
          if (bone.bits().get(x, y, z)) {
            g[((z + 1) * ny + y + 1) * nx + x + 1] = INF;
          }
        }
      }
    }
    squaredDistance(g, nx, ny, nz, mmX, mmY, mmZ, caller);
    int ia = ((a[2] - box.z0() + 1) * ny + a[1] - box.y0() + 1) * nx + a[0] - box.x0() + 1;
    int ib = ((b[2] - box.z0() + 1) * ny + b[1] - box.y0() + 1) * nx + b[0] - box.x0() + 1;
    if (ia == ib) {
      throw new BoneSplit.SplitException(BoneSplit.SplitException.Reason.NOT_SEPARABLE);
    }
    // Priority flood by depth, in steps of 0.05 mm, deepest first.
    int levels = 1;
    for (int i = 0; i < g.length; i++) {
      if (g[i] > 0) {
        g[i] = (float) Math.sqrt(g[i]);
        levels = Math.max(levels, (int) (g[i] * 20) + 1);
      }
    }
    IntQueue[] buckets = new IntQueue[levels];
    byte[] side = new byte[g.length];
    side[ia] = 1;
    side[ib] = 2;
    push(buckets, ia, level(g[ia]));
    push(buckets, ib, level(g[ib]));
    int[] step = {1, -1, nx, -nx, nx * ny, -nx * ny};
    long visited = 0;
    for (int lv = levels - 1; lv >= 0; ) {
      IntQueue q = buckets[lv];
      if (q == null || q.isEmpty()) {
        lv--;
        continue;
      }
      if ((++visited & 0xFFFF) == 0) {
        RegionGrow.checkCancelled(caller);
      }
      int i = q.poll();
      for (int s : step) {
        int j = i + s;
        if (g[j] > 0 && side[j] == 0) {
          side[j] = side[i];
          // A deeper voxel reached from a shallower one is taken at the current level.
          int l = Math.min(level(g[j]), lv);
          push(buckets, j, l);
        }
      }
    }
    BitVolume cutBits = new BitVolume(box.sizeX(), box.sizeY(), box.sizeZ());
    for (int z = 0; z < box.sizeZ(); z++) {
      for (int y = 0; y < box.sizeY(); y++) {
        for (int x = 0; x < box.sizeX(); x++) {
          if (side[((z + 1) * ny + y + 1) * nx + x + 1] == 2) {
            cutBits.set(x, y, z);
          }
        }
      }
    }
    BitVolume keptBits = bone.bits().copy();
    keptBits.andNot(cutBits);
    BoxMask kept = BoxMask.trimmed(box, keptBits);
    BoxMask gone = BoxMask.trimmed(box, cutBits);
    if (kept == null || gone == null) {
      throw new BoneSplit.SplitException(BoneSplit.SplitException.Reason.NOT_SEPARABLE);
    }
    return new BoneSplit.Split(kept, gone, 0);
  }

  private static int level(float depth) {
    return (int) (depth * 20);
  }

  private static void push(IntQueue[] buckets, int voxel, int level) {
    if (buckets[level] == null) {
      buckets[level] = new IntQueue(256);
    }
    buckets[level].add(voxel);
  }

  /** The voxel of the bone at or nearest the given one, within 3 voxels; {@code null} if none. */
  private static int[] nearestOnBone(BoxMask bone, int[] v) {
    if (bone.contains(v[0], v[1], v[2])) {
      return v;
    }
    int[] best = null;
    long bestD = Long.MAX_VALUE;
    int r = 3;
    for (int z = v[2] - r; z <= v[2] + r; z++) {
      for (int y = v[1] - r; y <= v[1] + r; y++) {
        for (int x = v[0] - r; x <= v[0] + r; x++) {
          long d =
              (long) (x - v[0]) * (x - v[0])
                  + (long) (y - v[1]) * (y - v[1])
                  + (long) (z - v[2]) * (z - v[2]);
          if (d < bestD && bone.contains(x, y, z)) {
            bestD = d;
            best = new int[] {x, y, z};
          }
        }
      }
    }
    return best;
  }

  /** The parts of the watershed: a union-find with each root's peak depth and voxel count. */
  private static final class Nodes {
    int[] parent = new int[1024];
    float[] peak = new float[1024];
    int[] peakVoxel = new int[1024];
    long[] volume = new long[1024];
    int size;

    int add(float depth, int voxel) {
      if (size == parent.length) {
        int n = 2 * size;
        parent = Arrays.copyOf(parent, n);
        peak = Arrays.copyOf(peak, n);
        peakVoxel = Arrays.copyOf(peakVoxel, n);
        volume = Arrays.copyOf(volume, n);
      }
      parent[size] = size;
      peak[size] = depth;
      peakVoxel[size] = voxel;
      volume[size] = 1;
      return size++;
    }

    int find(int a) {
      int root = a;
      while (parent[root] != root) {
        root = parent[root];
      }
      while (parent[a] != root) {
        int next = parent[a];
        parent[a] = root;
        a = next;
      }
      return root;
    }

    /** Joins {@code b} into {@code into}, which keeps its peak. */
    void union(int into, int b) {
      parent[b] = into;
      volume[into] += volume[b];
    }
  }

  /** The 1D squared distance transform of Felzenszwalb and Huttenlocher, on a line of voxels. */
  private static final class Line {
    final float[] in;
    final float[] out;
    private final int[] v;
    private final double[] z;

    Line(int n) {
      in = new float[n];
      out = new float[n];
      v = new int[n];
      z = new double[n + 1];
    }

    /** {@code out[q]} = min over p of ((q - p) × unit)² + {@code in[p]}. */
    void transform(int n, double unit) {
      int k = -1;
      for (int q = 0; q < n; q++) {
        if (in[q] >= INF) {
          continue;
        }
        double pq = q * unit;
        double s = 0;
        while (k >= 0) {
          double pp = v[k] * unit;
          s = ((in[q] + pq * pq) - (in[v[k]] + pp * pp)) / (2 * (pq - pp));
          if (s <= z[k]) {
            k--;
          } else {
            break;
          }
        }
        k++;
        v[k] = q;
        z[k] = k == 0 ? Double.NEGATIVE_INFINITY : s;
        z[k + 1] = Double.POSITIVE_INFINITY;
      }
      if (k < 0) {
        Arrays.fill(out, 0, n, INF);
        return;
      }
      int j = 0;
      for (int q = 0; q < n; q++) {
        double pq = q * unit;
        while (z[j + 1] < pq) {
          j++;
        }
        double d = pq - v[j] * unit;
        out[q] = (float) (d * d + in[v[j]]);
      }
    }
  }
}
