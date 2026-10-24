/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones;

import com.addisortho.viewer.bones.grow.BitVolume;
import com.addisortho.viewer.bones.grow.BoxMask;
import com.addisortho.viewer.bones.grow.VoxelBox;
import com.addisortho.viewer.bones.seg.CtGrid;
import java.awt.Color;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.joml.Vector3d;
import org.joml.Vector3i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.dicom.codec.seg.LiveSegmentation;
import org.weasis.dicom.codec.seg.SegmentationVolume;
import org.weasis.dicom.viewer2d.mpr.Volume;
import org.weasis.opencv.seg.RegionAttributes;

/**
 * The bones of a CT as one label volume on the grid of its 3D view's image volume, kept in step
 * with the bone list and shown by the MPR and 3D views straight from memory ({@link
 * LiveSegmentation}). After an edit only the voxels of the bones that changed are written, and the
 * views redraw only the slices they lie in, so an edit shows at once.
 *
 * <p>Bones never share a voxel, so each voxel holds one bone number (the bone's id) or none.
 */
final class LiveBones {

  private static final Logger LOGGER = LoggerFactory.getLogger(LiveBones.class);

  /** Bone numbers made room for beyond the highest in use, so new bones rarely rebuild it all. */
  private static final int SPARE = 32;

  /** Interior opacity of the bones, as in the SEG file the views loaded before. */
  private static final float OPACITY = 0.2f;

  private static final Map<GrowSession, LiveBones> BY_SESSION = new HashMap<>();

  /** Slices of the image volume an update changed, first and last; first > last when none. */
  record Change(int z0, int z1) {
    boolean labels() {
      return z0 <= z1;
    }
  }

  private final String seriesUID;
  private final String segSeriesUID;
  private final Volume<?, ?> volume;
  private final GridMap map;
  private SegmentationVolume labels;
  private Map<Integer, RegionAttributes> regions;

  /** The mask of each bone as it is in {@link #labels}, by bone id. */
  private final Map<Integer, BoxMask> shown = new HashMap<>();

  private LiveBones(String seriesUID, String segSeriesUID, Volume<?, ?> volume, GridMap map) {
    this.seriesUID = seriesUID;
    this.segSeriesUID = segSeriesUID;
    this.volume = volume;
    this.map = map;
  }

  /**
   * Brings the live labels of the session's CT in step with its bones and their look, creating them
   * on first use. Empty when the bones cannot be shown live (the CT grid does not map onto the
   * image volume voxel for voxel): the views then keep showing the written SEG file.
   */
  static java.util.Optional<Change> sync(CtSource ct, GrowSession session, Color selectedColor) {
    LiveBones live = BY_SESSION.get(session);
    if (live != null && live.volume != ct.volume()) {
      live.dispose();
      BY_SESSION.remove(session);
      live = null;
    }
    if (session.bones().isEmpty()) {
      if (live != null) {
        live.dispose();
        BY_SESSION.remove(session);
        return java.util.Optional.of(new Change(0, ct.volume().getSizeZ() - 1));
      }
      return java.util.Optional.empty();
    }
    if (live == null) {
      GridMap map = GridMap.of(ct.grid(), ct.volume());
      if (map == null) {
        LOGGER.info(
            "Bones are shown from the SEG file: the CT grid is not the 3D volume grid ({})",
            GridMap.describe(ct.grid(), ct.volume()));
        return java.util.Optional.empty();
      }
      live = new LiveBones(ct.seriesUID(), session.segSeriesUID(), ct.volume(), map);
      BY_SESSION.put(session, live);
    }
    return java.util.Optional.of(live.update(session.bones(), session.selected(), selectedColor));
  }

  /** Whether the session's bones are shown live. */
  static boolean isLive(GrowSession session) {
    return BY_SESSION.containsKey(session);
  }

  private Change update(List<Bone> bones, Set<Integer> selected, Color selectedColor) {
    int maxId = bones.stream().mapToInt(Bone::id).max().orElse(0);
    int[] dirty = {Integer.MAX_VALUE, Integer.MIN_VALUE};
    if (labels == null || maxId > labels.maxSegmentNumber()) {
      create(maxId + SPARE);
      dirty[0] = 0;
      dirty[1] = volume.getSizeZ() - 1;
    }
    Map<Integer, Bone> byId = new LinkedHashMap<>();
    for (Bone b : bones) {
      byId.put(b.id(), b);
    }
    // Take out first what changed, then stamp: a voxel moving from one bone to another ends in
    // the new one.
    for (Map.Entry<Integer, BoxMask> e : List.copyOf(shown.entrySet())) {
      Bone now = byId.get(e.getKey());
      if (now == null || now.mask() != e.getValue()) {
        clear(e.getValue(), e.getKey(), dirty);
        shown.remove(e.getKey());
      }
    }
    for (Bone b : bones) {
      if (shown.get(b.id()) != b.mask()) {
        stamp(b.mask(), b.id(), dirty);
        shown.put(b.id(), b.mask());
      }
    }
    for (Map.Entry<Integer, RegionAttributes> e : regions.entrySet()) {
      RegionAttributes region = e.getValue();
      Bone b = byId.get(e.getKey());
      if (b == null) {
        region.setVisible(false);
        continue;
      }
      region.setLabel(b.label());
      region.setVisible(!b.hidden());
      region.setColor(selected.contains(b.id()) ? selectedColor : b.color());
    }
    return new Change(dirty[0], dirty[1]);
  }

  /** Makes the label volume afresh, with room for bone numbers up to {@code capacity}. */
  private void create(int capacity) {
    SegmentationVolume old = labels;
    regions = new LinkedHashMap<>();
    for (int id = 1; id <= capacity; id++) {
      RegionAttributes region = new RegionAttributes(id, "Bone " + id, Color.WHITE); // NON-NLS
      region.setInteriorOpacity(OPACITY);
      region.setVisible(false);
      regions.put(id, region);
    }
    Vector3i size = volume.getSize();
    labels =
        new SegmentationVolume(
            size.x,
            size.y,
            size.z,
            new Vector3d(volume.getPixelRatio()),
            volume.getVolumeOrigin(),
            volume.getVolumeAxisX(),
            volume.getVolumeAxisY(),
            volume.getVolumeAxisZ(),
            regions,
            true);
    labels.retain(); // held here: the views' textures come and go
    shown.clear();
    LiveSegmentation.register(
        new LiveSegmentation(
            seriesUID,
            segSeriesUID,
            labels,
            (x, y, z) -> volume.voxelToLps(x, y, z, new Vector3d())));
    if (old != null) {
      old.release();
    }
  }

  private void dispose() {
    LiveSegmentation.unregister(seriesUID);
    if (labels != null) {
      labels.release();
      labels = null;
    }
    shown.clear();
  }

  private void stamp(BoxMask mask, int id, int[] dirty) {
    forEachVoxel(
        mask,
        dirty,
        (x, y, z) -> {
          labels.setLabel(x, y, z, id);
        });
  }

  private void clear(BoxMask mask, int id, int[] dirty) {
    forEachVoxel(
        mask,
        dirty,
        (x, y, z) -> {
          if (labels.hasLabel(x, y, z, id)) {
            labels.setLabel(x, y, z, 0);
          }
        });
  }

  @FunctionalInterface
  private interface VoxelAction {
    void at(int x, int y, int z);
  }

  /** Runs {@code action} on the image-volume voxel of every voxel of {@code mask}. */
  private void forEachVoxel(BoxMask mask, int[] dirty, VoxelAction action) {
    VoxelBox box = mask.box();
    BitVolume bits = mask.bits();
    int[] src = new int[3];
    int[] dst = new int[3];
    for (int z = 0; z < box.sizeZ(); z++) {
      if (bits.countSlice(z) == 0) {
        continue;
      }
      for (int y = 0; y < box.sizeY(); y++) {
        for (int x = 0; x < box.sizeX(); x++) {
          if (!bits.get(x, y, z)) {
            continue;
          }
          src[0] = box.x0() + x;
          src[1] = box.y0() + y;
          src[2] = box.z0() + z;
          map.apply(src, dst);
          action.at(dst[0], dst[1], dst[2]);
          if (dst[2] < dirty[0]) {
            dirty[0] = dst[2];
          }
          if (dst[2] > dirty[1]) {
            dirty[1] = dst[2];
          }
        }
      }
    }
  }

  /**
   * How a voxel of the CT grid (the bones' grid) lands on a voxel of the image volume: the linear
   * map through patient space, rounded to the nearest image voxel. Only taken when one CT voxel
   * step moves at most one image voxel along each axis, so the bones keep their shape (no holes, no
   * stretching); a flipped or swapped axis or a half-voxel offset is fine.
   *
   * @param m image-voxel change per CT voxel step, m[t][i] for image axis t and CT axis i
   * @param origin image voxel of CT voxel (0, 0, 0), not rounded
   */
  record GridMap(double[][] m, double[] origin) {

    void apply(int[] src, int[] dst) {
      for (int t = 0; t < 3; t++) {
        dst[t] =
            (int) Math.round(origin[t] + m[t][0] * src[0] + m[t][1] * src[1] + m[t][2] * src[2]);
      }
    }

    /** The mapping, or {@code null} when CT voxels are not image voxels one for one. */
    static GridMap of(CtGrid grid, Volume<?, ?> volume) {
      int[] n = {grid.columns(), grid.rows(), grid.sliceCount()};
      double[] o = toVoxel(grid, volume, 0, 0, 0);
      double[][] m = new double[3][3];
      for (int i = 0; i < 3; i++) {
        int[] step = new int[3];
        step[i] = 1;
        double[] p = toVoxel(grid, volume, step[0], step[1], step[2]);
        double length = 0;
        for (int t = 0; t < 3; t++) {
          m[t][i] = p[t] - o[t];
          length += m[t][i] * m[t][i];
        }
        // One CT step must be one image voxel: else bones would get holes or be squeezed.
        if (Math.abs(Math.sqrt(length) - 1) > 0.05) {
          return null;
        }
      }
      GridMap map = new GridMap(m, o);
      // The far corner lands where the linear map says, inside the volume.
      int[] far = {n[0] - 1, n[1] - 1, n[2] - 1};
      double[] p = toVoxel(grid, volume, far[0], far[1], far[2]);
      int[] size = {volume.getSizeX(), volume.getSizeY(), volume.getSizeZ()};
      int[] mapped = new int[3];
      for (int[] corner : new int[][] {{0, 0, 0}, far}) {
        map.apply(corner, mapped);
        for (int t = 0; t < 3; t++) {
          if (mapped[t] < -1 || mapped[t] > size[t]) {
            return null;
          }
        }
      }
      map.apply(far, mapped);
      for (int t = 0; t < 3; t++) {
        if (Math.abs(p[t] - mapped[t]) > 0.6) {
          return null;
        }
      }
      return map;
    }

    /** Where CT voxels land in the image volume, for the log. */
    static String describe(CtGrid grid, Volume<?, ?> volume) {
      int[] n = {grid.columns(), grid.rows(), grid.sliceCount()};
      return String.format(
          "CT %dx%dx%d, volume %dx%dx%d, (0,0,0)->%s (1,0,0)->%s (0,1,0)->%s (0,0,1)->%s far->%s",
          n[0],
          n[1],
          n[2],
          volume.getSizeX(),
          volume.getSizeY(),
          volume.getSizeZ(),
          java.util.Arrays.toString(toVoxel(grid, volume, 0, 0, 0)),
          java.util.Arrays.toString(toVoxel(grid, volume, 1, 0, 0)),
          java.util.Arrays.toString(toVoxel(grid, volume, 0, 1, 0)),
          java.util.Arrays.toString(toVoxel(grid, volume, 0, 0, 1)),
          java.util.Arrays.toString(toVoxel(grid, volume, n[0] - 1, n[1] - 1, n[2] - 1)));
    }

    private static double[] toVoxel(CtGrid grid, Volume<?, ?> volume, int x, int y, int z) {
      double[] lps = grid.voxelToLps(x, y, z);
      Vector3d v = volume.lpsToVoxel(new Vector3d(lps[0], lps[1], lps[2]), new Vector3d());
      return new double[] {v.x, v.y, v.z};
    }
  }
}
