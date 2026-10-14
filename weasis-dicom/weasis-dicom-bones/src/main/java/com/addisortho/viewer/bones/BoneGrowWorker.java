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
import com.addisortho.viewer.bones.grow.GrowOptions;
import com.addisortho.viewer.bones.grow.GrowProgress.Stage;
import com.addisortho.viewer.bones.grow.RegionGrow;
import com.addisortho.viewer.bones.grow.RegionGrow.Result;
import com.addisortho.viewer.bones.grow.RegionGrow.SeedException;
import com.addisortho.viewer.bones.grow.VoxelBox;
import com.addisortho.viewer.bones.seg.CtGrid;
import com.addisortho.viewer.bones.seg.SegmentationBuilder;
import com.addisortho.viewer.bones.seg.SegmentationBuilder.Segment;
import java.awt.Color;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingWorker;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.weasis.core.api.gui.util.AppProperties;

/**
 * Grows one bone and writes the session's segmentation with it, or rewrites the segmentation with
 * the given segments only, off the event dispatch thread. Fires the bound property {@link #STAGE}
 * with the text of each step, and {@code progress} from 0 to 100.
 */
final class BoneGrowWorker extends SwingWorker<BoneGrowWorker.Outcome, Void> {

  static final String STAGE = "stage"; // NON-NLS

  /**
   * What to grow.
   *
   * @param seed voxel of the CT grid
   * @param crop block of the grid to grow in, or {@code null} for the whole CT
   */
  record Request(
      int[] seed, VoxelBox crop, GrowOptions options, int number, String label, Color color) {}

  /**
   * @param result the grown bone, or {@code null} for a rewrite
   * @param segments every segment of the segmentation written
   * @param file the segmentation, or {@code null} when no segment is left
   * @param sopInstanceUID its instance, or {@code null}
   */
  record Outcome(Result result, List<Segment> segments, Path file, String sopInstanceUID) {}

  private final CtSource ct;
  private final Request request;
  private final List<Segment> kept;
  private final String seriesUID;
  private final int instanceNumber;
  private final AtomicReference<String> stage = new AtomicReference<>();
  private final AtomicInteger percent = new AtomicInteger();

  /**
   * @param request the bone to grow, or {@code null} to write {@code kept} alone
   * @param kept segments already in the segmentation
   */
  BoneGrowWorker(
      CtSource ct, Request request, List<Segment> kept, String seriesUID, int instanceNumber) {
    this.ct = ct;
    this.request = request;
    this.kept = List.copyOf(kept);
    this.seriesUID = seriesUID;
    this.instanceNumber = instanceNumber;
  }

  CtSource ct() {
    return ct;
  }

  Request request() {
    return request;
  }

  @Override
  protected Outcome doInBackground() throws Problem {
    CtGrid grid = ct.grid();
    Result result = request == null ? null : grow(grid);
    List<Segment> segments = new ArrayList<>(kept);
    if (result != null) {
      segments.add(
          new Segment(
              request.number(),
              request.label(),
              request.color(),
              result,
              description(request, result)));
    }
    if (segments.isEmpty()) {
      return new Outcome(null, segments, null, null);
    }
    if (isCancelled()) {
      throw new CancellationException();
    }
    showStage(Messages.getString("BoneGrowTool.stage.writing"));
    advance(90);
    Attributes dataset =
        SegmentationBuilder.build(
            ct.attributes(),
            grid,
            segments,
            seriesUID,
            instanceNumber,
            AppProperties.WEASIS_VERSION,
            new Date());
    String sopUID = dataset.getString(Tag.SOPInstanceUID);
    Path dir =
        AppProperties.buildAccessibleTempDirectory(AppProperties.CACHE_NAME, "seg"); // NON-NLS
    Path file = dir.resolve(sopUID + ".dcm"); // NON-NLS
    try {
      SegmentationBuilder.write(dataset, file);
    } catch (IOException e) {
      throw new Problem(
          String.format(Messages.getString("BoneGrowTool.failed"), e.getLocalizedMessage()));
    }
    advance(100);
    return new Outcome(result, List.copyOf(segments), file, sopUID);
  }

  private Result grow(CtGrid grid) throws Problem {
    GrowOptions options = request.options();
    int[] s = request.seed();
    try {
      return RegionGrow.grow(
          grid.columns(),
          grid.rows(),
          grid.sliceCount(),
          ct.huSource(),
          s[0],
          s[1],
          s[2],
          request.crop(),
          options,
          this::stageProgress);
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
  private static String description(Request request, Result result) {
    GrowOptions o = request.options();
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

  /** Maps the grow stages onto 0 to 90 percent. Called from pool threads. */
  private void stageProgress(Stage s, double fraction) {
    int[] span =
        switch (s) {
          case GROWING -> new int[] {0, 60};
          case SEPARATING -> new int[] {60, 72};
          case CLOSING -> new int[] {72, 82};
          case FILLING -> new int[] {82, 87};
          case PIECES -> new int[] {87, 90};
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
