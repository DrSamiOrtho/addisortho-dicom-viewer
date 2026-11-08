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
import com.addisortho.viewer.bones.seg.SegmentationBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import javax.swing.SwingWorker;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.weasis.core.api.gui.util.AppProperties;

/**
 * Writes the segmentation of a session's bones to a file of the viewer's temporary folder, off the
 * event dispatch thread. No file when no bone is left.
 */
final class SegWriteWorker extends SwingWorker<SegWriteWorker.Outcome, Void> {

  /**
   * @param file the segmentation, or {@code null} when no bone is left
   * @param sopInstanceUID its instance, or {@code null}
   */
  record Outcome(Path file, String sopInstanceUID) {}

  private final CtSource ct;
  private final GrowSession session;
  private final List<Bone> bones;
  private final int version;
  private final int instanceNumber;

  /** Takes the session's bones as they are now. */
  SegWriteWorker(CtSource ct, GrowSession session) {
    this.ct = ct;
    this.session = session;
    this.bones = session.bones();
    this.version = session.version();
    this.instanceNumber = session.nextInstanceNumber();
  }

  CtSource ct() {
    return ct;
  }

  GrowSession session() {
    return session;
  }

  List<Bone> bones() {
    return bones;
  }

  int version() {
    return version;
  }

  @Override
  protected Outcome doInBackground() throws Problem {
    if (bones.isEmpty()) {
      return new Outcome(null, null);
    }
    Attributes dataset =
        SegmentationBuilder.build(
            ct.attributes(),
            ct.grid(),
            GrowSession.segments(bones),
            session.segSeriesUID(),
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
    return new Outcome(file, sopUID);
  }
}
