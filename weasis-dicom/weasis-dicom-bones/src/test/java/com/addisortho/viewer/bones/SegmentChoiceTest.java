/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.weasis.core.ui.model.graphic.imp.seg.SegRegion;
import org.weasis.dicom.codec.SpecialElementRegion;
import org.weasis.opencv.seg.RegionAttributes;

class SegmentChoiceTest {

  private static final LocalDate DAY = LocalDate.of(2026, 10, 14);

  private static SegmentChoice choice(String label) {
    return new SegmentChoice(
        mock(SpecialElementRegion.class), new RegionAttributes(1, label), label);
  }

  @Test
  void fileNameIsTheLabelAndTheDate() {
    assertEquals("Femur_left_2026-10-14.stl", choice("Femur, left").defaultFileName(DAY));
    assertEquals("Fémur_gauche_2026-10-14.stl", choice(" Fémur gauche ").defaultFileName(DAY));
    assertEquals("etc_2026-10-14.stl", choice("../../etc").defaultFileName(DAY));
    assertEquals("bone_2026-10-14.stl", choice("***").defaultFileName(DAY));
  }

  private static SpecialElementRegion seg(
      String uid, boolean visible, Map<Integer, RegionAttributes> regions) {
    SpecialElementRegion seg = mock(SpecialElementRegion.class);
    when(seg.getRegionUID()).thenReturn(uid);
    when(seg.isVisible()).thenReturn(visible);
    when(seg.getSegAttributes()).thenAnswer(_ -> regions);
    return seg;
  }

  private static SegRegion<?> treeRegion(int id, boolean ticked) {
    SegRegion<?> r = new SegRegion<>(id, "r" + id, Color.WHITE);
    r.setSelected(ticked);
    return r;
  }

  @Test
  void listsEverySegmentInOrder() {
    Map<Integer, RegionAttributes> regions =
        new TreeMap<>(
            Map.of(2, new RegionAttributes(2, "Tibia"), 1, new RegionAttributes(1, "Femur")));
    SpecialElementRegion a = seg("a", true, regions);
    SpecialElementRegion b = seg("b", true, Map.of(7, new RegionAttributes(7, "Patella")));

    List<SegmentChoice> one = SegmentChoice.of(List.of(a));
    List<SegmentChoice> two = SegmentChoice.of(List.of(a, b));

    assertEquals(List.of("Femur", "Tibia"), one.stream().map(SegmentChoice::toString).toList());
    assertEquals(3, two.size());
    assertTrue(two.get(2).toString().endsWith(": Patella"), two.get(2).toString());
  }

  @Test
  void defaultIsTheFirstSegmentTickedInTheTree() {
    Map<Integer, RegionAttributes> regions =
        new TreeMap<>(
            Map.of(
                1,
                new RegionAttributes(1, "Femur"),
                2,
                new RegionAttributes(2, "Tibia"),
                3,
                new RegionAttributes(3, "Patella")));
    List<SegmentChoice> choices = SegmentChoice.of(List.of(seg("uid", true, regions)));
    Map<String, List<SegRegion<?>>> tree =
        Map.of(
            "uid::Femur",
            List.of(treeRegion(1, false)),
            "uid::Tibia",
            List.of(treeRegion(2, true)));

    assertEquals(1, SegmentChoice.defaultIndex(choices, tree));
    assertFalse(choices.get(0).isTicked(tree));
    // Not in the tree yet: shown with its segmentation.
    assertTrue(choices.get(2).isTicked(tree));
    assertEquals(0, SegmentChoice.defaultIndex(choices, Map.of()));
  }

  @Test
  void hiddenSegmentationIsNotTheDefault() {
    Map<Integer, RegionAttributes> regions = Map.of(1, new RegionAttributes(1, "Femur"));
    SegmentChoice hidden = SegmentChoice.of(List.of(seg("uid", false, regions))).getFirst();

    assertFalse(hidden.isTicked(Map.of()));
  }
}
