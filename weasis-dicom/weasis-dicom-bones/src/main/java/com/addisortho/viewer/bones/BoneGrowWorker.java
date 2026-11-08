/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones;

import com.addisortho.viewer.bones.BoneExportWorker.Problem;
import com.addisortho.viewer.bones.grow.BoxMask;
import com.addisortho.viewer.bones.grow.GrowOptions;
import com.addisortho.viewer.bones.grow.GrowProgress.Stage;
import com.addisortho.viewer.bones.grow.HuSource;
import com.addisortho.viewer.bones.grow.RegionGrow;
import com.addisortho.viewer.bones.grow.RegionGrow.Result;
import com.addisortho.viewer.bones.grow.RegionGrow.SeedException;
import com.addisortho.viewer.bones.grow.VoxelBox;
import com.addisortho.viewer.bones.seg.CtGrid;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingWorker;

/**
 * Grows one bone off the event dispatch thread. The bones already selected act as walls: the grow
 * does not pass through them into the bones beyond. Fires the bound property {@link #STAGE} with
 * the text of each step, and {@code progress} from 0 to 100.
 */
final class BoneGrowWorker extends SwingWorker<Result, Void> {

  static final String STAGE = "stage"; // NON-NLS

  /**
   * What to grow.
   *
   * @param seed voxel of the CT grid
   * @param crop block of the grid to grow in, or {@code null} for the whole CT
   */
  record Request(int[] seed, VoxelBox crop, GrowOptions options) {}

  private final CtSource ct;
  private final Request request;
  private final List<BoxMask> walls;
  private final AtomicReference<String> stage = new AtomicReference<>();
  private final AtomicInteger percent = new AtomicInteger();

  /**
   * @param walls the bones already selected
   */
  BoneGrowWorker(CtSource ct, Request request, List<BoxMask> walls) {
    this.ct = ct;
    this.request = request;
    this.walls = List.copyOf(walls);
  }

  CtSource ct() {
    return ct;
  }

  Request request() {
    return request;
  }

  @Override
  protected Result doInBackground() throws Problem {
    CtGrid grid = ct.grid();
    GrowOptions options = request.options();
    int[] s = request.seed();
    HuSource hu = ct.huSource();
    HuSource walled =
        walls.isEmpty()
            ? hu
            : (x, y, z) -> {
              for (BoxMask wall : walls) {
                if (wall.contains(x, y, z)) {
                  return Double.NaN; // in no range
                }
              }
              return hu.huAt(x, y, z);
            };
    try {
      Result result =
          RegionGrow.grow(
              grid.columns(),
              grid.rows(),
              grid.sliceCount(),
              walled,
              s[0],
              s[1],
              s[2],
              request.crop(),
              options,
              this::stageProgress);
      advance(100);
      return result;
    } catch (SeedException e) {
      throw new Problem(
          switch (e.reason()) {
            case OUTSIDE_BOX -> Messages.getString("BoneGrowTool.seedOutsideBox");
            case OUT_OF_RANGE ->
                String.format(
                    Messages.getString("BoneGrowTool.seedOutOfRange"),
                    options.lowerHu(),
                    options.upperHu(),
                    e.hu());
          });
    }
  }

  /** The settings, kept in the segment so the result can be traced. */
  static String description(GrowOptions o, Result result) {
    return String.format(
        Locale.ROOT,
        "Region grow %.0f to %.0f HU, closing %d, separation %d%s, holes %s, seed voxel %d %d %d"
            + " (%.0f HU); not reviewed", // NON-NLS
        o.lowerHu(),
        o.upperHu(),
        o.closingRadius(),
        o.separationVoxels(),
        o.separationVoxels() > 0 && !result.separated() ? " (skipped)" : "", // NON-NLS
        o.fillHoles() ? "filled" : "kept", // NON-NLS
        result.seed()[0],
        result.seed()[1],
        result.seed()[2],
        result.seedHu());
  }

  /** Maps the grow stages onto 0 to 100 percent. Called from pool threads. */
  private void stageProgress(Stage s, double fraction) {
    int[] span =
        switch (s) {
          case GROWING -> new int[] {0, 65};
          case SEPARATING -> new int[] {65, 80};
          case CLOSING -> new int[] {80, 90};
          case FILLING -> new int[] {90, 96};
          case PIECES -> new int[] {96, 100};
        };
    advance(span[0] + (int) Math.round(Math.min(1, fraction) * (span[1] - span[0])));
    showStage(Messages.getString("BoneGrowTool.stage." + s.name()));
  }

  /** Never moves back, although the parallel steps report out of order. */
  private void advance(int value) {
    setProgress(percent.accumulateAndGet(value, Math::max));
  }

  private void showStage(String text) {
    String old = stage.getAndSet(text);
    if (!text.equals(old)) {
      // SwingWorker delivers bound property changes on the event dispatch thread.
      firePropertyChange(STAGE, old, text);
    }
  }
}
