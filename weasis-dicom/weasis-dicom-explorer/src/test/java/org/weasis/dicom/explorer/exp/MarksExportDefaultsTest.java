/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer.exp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Sequence;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.junit.jupiter.api.Test;

class MarksExportDefaultsTest {
  private static final MarksExportDefaults.Probe PROBE =
      new MarksExportDefaults.Probe(
          o -> o instanceof Attributes a ? a : null,
          o -> o instanceof String s && s.startsWith("S") ? s : null,
          "GRAPHICS"::equals);

  private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("all");
  private final DefaultMutableTreeNode study = new DefaultMutableTreeNode("study");
  private final DefaultMutableTreeNode images = new DefaultMutableTreeNode("S1");
  private final DefaultMutableTreeNode marks = new DefaultMutableTreeNode("marks");
  private final DefaultMutableTreeNode older = new DefaultMutableTreeNode(pr("AO_MARKS", "001000"));
  private final DefaultMutableTreeNode newer = new DefaultMutableTreeNode(pr("AO_MARKS", "002000"));
  private final DefaultMutableTreeNode radiology = new DefaultMutableTreeNode("radiology");
  private final DefaultMutableTreeNode drawnNow = new DefaultMutableTreeNode("GRAPHICS");

  MarksExportDefaultsTest() {
    DefaultMutableTreeNode patient = new DefaultMutableTreeNode("patient");
    root.add(patient);
    patient.add(study);
    study.add(images);
    images.add(new DefaultMutableTreeNode("image"));
    study.add(marks);
    marks.add(older);
    marks.add(newer);
    study.add(radiology);
    radiology.add(new DefaultMutableTreeNode(pr("GSPS", "003000")));
    study.add(drawnNow);
  }

  private static Attributes pr(String label, String time) {
    Attributes a = new Attributes();
    a.setString(Tag.ContentLabel, VR.CS, label);
    a.setString(Tag.PresentationCreationDate, VR.DA, "20261114");
    a.setString(Tag.PresentationCreationTime, VR.TM, time);
    Attributes image = new Attributes();
    image.setString(Tag.ReferencedSOPInstanceUID, VR.UI, "IMG1");
    Attributes series = new Attributes();
    series.setString(Tag.SeriesInstanceUID, VR.UI, "S1");
    series.newSequence(Tag.ReferencedImageSequence, 1).add(image);
    Sequence seq = a.newSequence(Tag.ReferencedSeriesSequence, 1);
    seq.add(series);
    return a;
  }

  private static TreePath path(DefaultMutableTreeNode n) {
    return new TreePath(n.getPath());
  }

  @Test
  void olderVersionsAndTheGraphicsRowAreUnticked() {
    List<TreePath> off = MarksExportDefaults.toUntick(root, PROBE);

    assertTrue(off.contains(path(older)), "a superseded version stays behind");
    assertTrue(off.contains(path(drawnNow)), "no second copy under a generic label");
    assertFalse(off.contains(path(newer)), "the newest marks go with the image");
    assertFalse(off.contains(path(images)));
    assertFalse(off.contains(path(radiology)), "other presentation states are untouched");
    assertFalse(off.contains(path(marks)), "the series still holds the newest version");
  }

  @Test
  void aSeriesHoldingOnlyOldVersionsIsUntickedWhole() {
    DefaultMutableTreeNode oldSeries = new DefaultMutableTreeNode("old marks");
    DefaultMutableTreeNode oldest = new DefaultMutableTreeNode(pr("AO_MARKS", "000500"));
    oldSeries.add(oldest);
    study.add(oldSeries);

    List<TreePath> off = MarksExportDefaults.toUntick(root, PROBE);

    assertTrue(off.contains(path(oldSeries)));
    assertTrue(off.contains(path(older)));
    assertFalse(off.contains(path(newer)));
  }

  @Test
  void withoutSavedMarksNothingIsUnticked() {
    marks.removeAllChildren();

    assertEquals(List.of(), MarksExportDefaults.toUntick(root, PROBE));
  }
}
