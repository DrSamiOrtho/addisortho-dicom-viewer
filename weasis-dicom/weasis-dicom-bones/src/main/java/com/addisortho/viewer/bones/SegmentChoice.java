/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.weasis.core.ui.model.graphic.imp.seg.SegRegion;
import org.weasis.dicom.codec.SpecialElementRegion;
import org.weasis.dicom.codec.seg.SegSpecialElement;
import org.weasis.opencv.seg.RegionAttributes;

/** One segment of one SEG, as listed in the segment chooser. */
record SegmentChoice(SpecialElementRegion seg, RegionAttributes region, String text) {

  @Override
  public String toString() {
    return text;
  }

  /** Every segment of the given SEGs, by SEG then segment number. */
  static List<SegmentChoice> of(List<SpecialElementRegion> segs) {
    List<SegmentChoice> choices = new ArrayList<>();
    for (SpecialElementRegion seg : segs) {
      Map<Integer, ? extends RegionAttributes> regions = seg.getSegAttributes();
      if (regions == null) {
        continue;
      }
      String prefix = segs.size() > 1 ? labelOf(seg) + ": " : "";
      regions.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(
              e ->
                  choices.add(
                      new SegmentChoice(seg, e.getValue(), prefix + e.getValue().getLabel())));
    }
    return choices;
  }

  /**
   * Index of the first segment ticked in the 3D Segmentation tool, whose tree state is {@code
   * regionMap} (keys start with the SEG's region UID); 0 when none is.
   */
  static int defaultIndex(List<SegmentChoice> choices, Map<String, List<SegRegion<?>>> regionMap) {
    for (int n = 0; n < choices.size(); n++) {
      if (choices.get(n).isTicked(regionMap)) {
        return n;
      }
    }
    return 0;
  }

  /** A segment not in the tree yet counts as ticked when its SEG is shown. */
  boolean isTicked(Map<String, List<SegRegion<?>>> regionMap) {
    if (!seg.isVisible()) {
      return false;
    }
    String prefix = seg.getRegionUID() + "::"; // NON-NLS
    boolean listed = false;
    for (Map.Entry<String, List<SegRegion<?>>> entry : regionMap.entrySet()) {
      if (entry.getKey().startsWith(prefix)) {
        for (SegRegion<?> r : entry.getValue()) {
          if (r.getId() == region.getId()) {
            if (r.isSelected()) {
              return true;
            }
            listed = true;
          }
        }
      }
    }
    return !listed;
  }

  /** Segment label and date, safe as a file name on Windows and macOS. */
  String defaultFileName(LocalDate date) {
    String name =
        region
            .getLabel()
            .replaceAll("[^\\p{L}\\p{N}._-]+", "_") // NON-NLS
            .replaceAll("^[._]+|[._]+$", ""); // NON-NLS
    if (name.isEmpty()) {
      name = "bone"; // NON-NLS
    }
    return name + "_" + date + ".stl"; // NON-NLS
  }

  private static String labelOf(SpecialElementRegion seg) {
    return seg instanceof SegSpecialElement element ? element.getLabel() : seg.toString();
  }
}
