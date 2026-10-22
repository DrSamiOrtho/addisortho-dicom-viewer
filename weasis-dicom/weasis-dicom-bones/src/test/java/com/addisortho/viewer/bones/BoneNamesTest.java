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

import com.addisortho.viewer.bones.BoneNames.Name;
import com.addisortho.viewer.bones.BoneNames.Region;
import com.addisortho.viewer.bones.BoneNames.Side;
import com.addisortho.viewer.bones.grow.BonePreset;
import java.util.List;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

class BoneNamesTest {

  private static final ResourceBundle TEXT =
      ResourceBundle.getBundle("com.addisortho.viewer.bones.messages"); // NON-NLS

  private static Name name(String key) {
    return BoneNames.all().stream().filter(n -> n.key().equals(key)).findFirst().orElseThrow();
  }

  @Test
  void everyRegionAndBoneHasItsName() {
    for (Region region : BoneNames.REGIONS) {
      assertTrue(TEXT.containsKey(region.key()), region.key());
      assertFalse(region.bones().isEmpty(), region.key());
    }
    for (Name name : BoneNames.all()) {
      assertTrue(TEXT.containsKey(name.key()), name.key());
    }
    assertEquals(
        BoneNames.all().size(),
        BoneNames.all().stream().map(Name::key).distinct().count(),
        "no bone twice");
    assertTrue(BoneNames.all().size() > 80, "the whole skeleton");
  }

  @Test
  void bonesTakeTheSettingsOfTheirRegion() {
    assertEquals(BonePreset.FEMUR, name("bone.femur").preset());
    assertEquals(BonePreset.SPINE, name("bone.l3").preset());
    assertEquals(BonePreset.HAND, name("bone.scaphoid").preset());
    assertTrue(name("bone.femur").paired());
    assertFalse(name("bone.l3").paired());
    assertFalse(name("bone.sacrum").paired());
  }

  @Test
  void aNewBoneIsNamedWithItsSideAndNumberedWhenTaken() {
    assertEquals("Right femur", BoneNames.label(name("bone.femur"), Side.RIGHT, List.of()));
    assertEquals(
        "Left hip bone (whole)", BoneNames.label(name("bone.hipBone"), Side.LEFT, List.of()));
    assertEquals("L3", BoneNames.label(name("bone.l3"), Side.RIGHT, List.of()));
    assertEquals(
        "Right femur 3",
        BoneNames.label(name("bone.femur"), Side.RIGHT, List.of("Right femur", "Right femur 2")));
    assertEquals("C1 (atlas)", BoneNames.lowerFirst("C1 (atlas)"));
  }

  @Test
  void renameOffersBothSidesOfThePairedBones() {
    List<String> labels = BoneNames.labels();
    assertTrue(labels.contains("Right femur"));
    assertTrue(labels.contains("Left femur"));
    assertTrue(labels.contains("L5"));
    assertFalse(labels.contains("Femur"));
  }
}
