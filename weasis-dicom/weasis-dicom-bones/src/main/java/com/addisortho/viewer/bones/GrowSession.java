/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones;

import com.addisortho.viewer.bones.seg.SegmentationBuilder.Segment;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import org.dcm4che3.util.UIDUtils;

/**
 * The bones grown on one CT in this session: they all go into one segmentation series, rewritten
 * with every segment each time one is added or removed. Used on the event dispatch thread.
 */
final class GrowSession {

  /** Colours given to the segments in turn: bone, then distinct hues for the neighbours. */
  private static final Color[] COLORS = {
    new Color(241, 214, 145),
    new Color(111, 184, 210),
    new Color(216, 101, 79),
    new Color(128, 174, 128),
    new Color(183, 156, 220),
    new Color(220, 200, 60),
    new Color(240, 140, 190),
    new Color(90, 130, 220)
  };

  private final String ctSeriesUID;
  private final String segSeriesUID = UIDUtils.createUID();
  private List<Segment> segments = List.of();
  private String sopInstanceUID;
  private int instanceNumber;

  GrowSession(String ctSeriesUID) {
    this.ctSeriesUID = ctSeriesUID;
  }

  String ctSeriesUID() {
    return ctSeriesUID;
  }

  String segSeriesUID() {
    return segSeriesUID;
  }

  List<Segment> segments() {
    return segments;
  }

  int nextSegmentNumber() {
    return segments.stream().mapToInt(Segment::number).max().orElse(0) + 1;
  }

  Color nextColor() {
    return COLORS[(nextSegmentNumber() - 1) % COLORS.length];
  }

  int nextInstanceNumber() {
    return instanceNumber + 1;
  }

  List<Segment> with(Segment segment) {
    List<Segment> all = new ArrayList<>(segments);
    all.add(segment);
    return all;
  }

  List<Segment> withoutLast() {
    return segments.isEmpty() ? List.of() : segments.subList(0, segments.size() - 1);
  }

  /** The segmentation instance currently in the explorer, or {@code null}. */
  String sopInstanceUID() {
    return sopInstanceUID;
  }

  /** Records the segmentation just written, which replaces the previous one. */
  void written(List<Segment> now, String sopUID) {
    this.segments = List.copyOf(now);
    this.sopInstanceUID = sopUID;
    if (sopUID != null) {
      instanceNumber++;
    }
  }
}
