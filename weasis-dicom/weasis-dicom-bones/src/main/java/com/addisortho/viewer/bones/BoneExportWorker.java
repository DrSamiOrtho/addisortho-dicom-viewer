/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones;

import com.addisortho.viewer.bones.mesh.StlWriter;
import com.addisortho.viewer.bones.surface.BoneSurfaceBuilder;
import com.addisortho.viewer.bones.surface.BoneSurfaceBuilder.Result;
import com.addisortho.viewer.bones.surface.HuSampler;
import com.addisortho.viewer.bones.surface.Progress.Stage;
import com.addisortho.viewer.bones.surface.SurfaceOptions;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingWorker;
import org.joml.Vector3d;
import org.weasis.dicom.codec.display.Modality;
import org.weasis.dicom.codec.seg.SegmentationVolume;
import org.weasis.dicom.viewer2d.mpr.Volume;

/**
 * Builds the surface of one segment and writes it as STL, off the event dispatch thread. Fires the
 * bound property {@link #STAGE} with the text of each step, and {@code progress} from 0 to 100.
 */
final class BoneExportWorker extends SwingWorker<BoneExportWorker.Outcome, Void> {

  static final String STAGE = "stage"; // NON-NLS

  /** Extent of the SEG copied around the bone: more than the grown label at any working spacing. */
  static final double LABEL_MARGIN_MM = 12;

  /**
   * @param withoutCt why the CT was not used, or {@code null} when it was
   */
  record Outcome(Path file, Result result, String withoutCt) {}

  /** A reason the export cannot go on, worded for the user. */
  static final class Problem extends Exception {
    Problem(String message) {
      super(message);
    }
  }

  private final SegmentChoice choice;
  private final Volume<?, ?> ct;
  private final Modality modality;
  private final SurfaceOptions options;
  private final Path file;
  private final AtomicReference<String> stage = new AtomicReference<>();
  private final AtomicInteger percent = new AtomicInteger();

  /**
   * @param ct the volume of the 3D view
   */
  BoneExportWorker(
      SegmentChoice choice, Volume<?, ?> ct, Modality modality, SurfaceOptions options, Path file) {
    this.choice = choice;
    this.ct = ct;
    this.modality = modality;
    this.options = options;
    this.file = file;
  }

  SurfaceOptions options() {
    return options;
  }

  @Override
  protected Outcome doInBackground() throws Problem {
    showStage(Messages.getString("BoneExportTool.stage.reading"));
    SegmentationVolume volume = choice.seg().getOrBuildSegmentationVolume();
    if (volume == null || volume.isDisposed()) {
      throw new Problem(Messages.getString("BoneExportTool.noVolume"));
    }
    int segment = volume.getSegmentNumber(choice.region());
    if (segment < 0) {
      throw new Problem(Messages.getString("BoneExportTool.noVoxels"));
    }
    SegmentLabels labels;
    try {
      labels =
          SegmentLabels.extract(
                  volume, choice.seg().getSegAttributes().keySet(), segment, LABEL_MARGIN_MM)
              .orElseThrow(() -> new Problem(Messages.getString("BoneExportTool.noVoxels")));
    } catch (IllegalStateException e) {
      if (!volume.isDisposed()) {
        throw e;
      }
      throw new Problem(Messages.getString("BoneExportTool.unloaded"));
    }
    advance(10);

    String withoutCt = withoutCtReason();
    HuSampler hu = withoutCt == null ? huSampler(ct) : null;
    Result result =
        BoneSurfaceBuilder.build(labels, labels.boneBox(), hu, options, this::stageProgress);
    if (result.mesh().isEmpty()) {
      throw new Problem(
          String.format(Messages.getString("BoneExportTool.empty"), options.thresholdHu()));
    }
    if (isCancelled()) {
      throw new CancellationException();
    }
    showStage(Messages.getString("BoneExportTool.stage.writing"));
    advance(95);
    try {
      StlWriter.write(result.mesh(), StlWriter.BONE_HEADER, file);
    } catch (IOException e) {
      throw new Problem(
          String.format(Messages.getString("BoneExportTool.failed"), e.getLocalizedMessage()));
    }
    advance(100);
    return new Outcome(file, result, withoutCt);
  }

  /** {@code null} when the threshold applies to the CT, else why it cannot. */
  private String withoutCtReason() {
    if (ct == null || modality != Modality.CT) {
      return Messages.getString("BoneExportTool.notCt");
    }
    if (ct.isBasic() || ct.getStack() == null) {
      // Only a rectified volume has an exact LPS to voxel mapping.
      return Messages.getString("BoneExportTool.notRectified");
    }
    return null;
  }

  /** Trilinear CT value at an LPS position; the volume holds modality LUT output, so HU. */
  private static HuSampler huSampler(Volume<?, ?> volume) {
    return (x, y, z) -> {
      Vector3d p = new Vector3d(x, y, z);
      volume.lpsToVoxel(p, p);
      return volume.getInterpolatedDouble(p.x, p.y, p.z, 0);
    };
  }

  /** Maps the build stages onto 10 to 95 percent. Called from pool threads. */
  private void stageProgress(Stage s, double fraction) {
    int[] span =
        switch (s) {
          case FIELD -> new int[] {10, 50};
          case SMOOTHING_FIELD -> new int[] {50, 60};
          case SURFACE -> new int[] {60, 78};
          case PIECES -> new int[] {78, 82};
          case SMOOTHING_SURFACE -> new int[] {82, 90};
          case REDUCING -> new int[] {90, 95};
        };
    advance(span[0] + (int) Math.round(fraction * (span[1] - span[0])));
    showStage(Messages.getString("BoneExportTool.stage." + s.name()));
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
