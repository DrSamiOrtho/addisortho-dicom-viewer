/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.addisortho.viewer.bones.GrowSession.Found;
import com.addisortho.viewer.bones.grow.BitVolume;
import com.addisortho.viewer.bones.grow.BoxMask;
import com.addisortho.viewer.bones.grow.VoxelBox;
import com.addisortho.viewer.bones.seg.SegmentationBuilder.Segment;
import java.awt.Color;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GrowSessionTest {

  /** A bone filling a block of the grid. */
  private static Bone bone(GrowSession s, String label, VoxelBox box) {
    BitVolume bits = new BitVolume(box.sizeX(), box.sizeY(), box.sizeZ());
    for (int z = 0; z < box.sizeZ(); z++) {
      for (int y = 0; y < box.sizeY(); y++) {
        for (int x = 0; x < box.sizeX(); x++) {
          bits.set(x, y, z);
        }
      }
    }
    int id = s.newId();
    return new Bone(
        id,
        label,
        s.colorOf(id),
        new BoxMask(box, bits),
        new int[] {box.x0(), box.y0(), box.z0()},
        null);
  }

  @Test
  void everyChangeReplacesTheListAndCountsAVersion() {
    GrowSession s = new GrowSession("ct");
    Bone femur = bone(s, "Femur 1", new VoxelBox(0, 4, 0, 4, 0, 4));
    Bone pelvis = bone(s, "Pelvis 2", new VoxelBox(10, 14, 0, 4, 0, 4));
    s.set(s.with(femur));
    s.set(s.with(pelvis));
    assertEquals(List.of(femur, pelvis), s.bones());
    assertEquals(2, s.version());

    s.set(s.without(femur.id()));
    assertEquals(List.of(pelvis), s.bones());
    assertEquals(3, s.version());
    assertTrue(s.newId() > pelvis.id(), "a number is never given twice");
  }

  @Test
  void replacedKeepsThePlaceAndPutsTheNewPartRightAfter() {
    GrowSession s = new GrowSession("ct");
    Bone a = bone(s, "A", new VoxelBox(0, 1, 0, 1, 0, 1));
    Bone b = bone(s, "B", new VoxelBox(5, 6, 0, 1, 0, 1));
    Bone c = bone(s, "C", new VoxelBox(9, 9, 0, 0, 0, 0));
    s.set(List.of(a, b, c));
    Bone b2 = b.withLabel("B kept");
    Bone part = bone(s, "Part of B", new VoxelBox(7, 7, 0, 0, 0, 0));
    assertEquals(List.of(a, b2, part, c), s.replaced(b.id(), b2, part));
    assertEquals(List.of(a, c), s.replaced(b.id(), null, null));
  }

  @Test
  void segmentsAreNumberedInListOrderAfterADeletion() {
    GrowSession s = new GrowSession("ct");
    Bone a = bone(s, "A", new VoxelBox(0, 1, 0, 1, 0, 1));
    Bone b = bone(s, "B", new VoxelBox(5, 6, 0, 1, 0, 1));
    Bone c = bone(s, "C", new VoxelBox(9, 9, 0, 0, 0, 0));
    s.set(List.of(a, b, c));
    s.set(s.without(a.id()));
    List<Segment> segments = GrowSession.segments(s.bones());
    assertEquals(List.of(1, 2), segments.stream().map(Segment::number).toList());
    assertEquals(List.of("B", "C"), segments.stream().map(Segment::label).toList());
  }

  @Test
  void theWrittenNumberFollowsTheLastWriteOfTheSameVoxels() {
    GrowSession s = new GrowSession("ct");
    Bone a = bone(s, "A", new VoxelBox(0, 1, 0, 1, 0, 1));
    Bone b = bone(s, "B", new VoxelBox(5, 6, 0, 1, 0, 1));
    s.set(List.of(a, b));
    assertFalse(s.isWritten());
    assertEquals(0, s.writtenNumber(b), "not written yet");

    s.written(s.bones(), s.version(), "sop1");
    assertTrue(s.isWritten());
    assertEquals(2, s.writtenNumber(b));
    assertEquals(2, s.nextInstanceNumber());

    Bone renamed = b.withColor(Color.RED);
    s.set(s.replaced(b.id(), renamed, null));
    assertFalse(s.isWritten());
    assertEquals(2, s.writtenNumber(renamed), "the same voxels are still written");

    s.set(s.without(a.id()));
    assertEquals(2, s.writtenNumber(renamed), "until the next write numbers it 1");
    s.written(s.bones(), s.version(), "sop2");
    assertEquals(1, s.writtenNumber(renamed));
  }

  @Test
  void aClickNearABoneFindsItsNearestVoxel() {
    GrowSession s = new GrowSession("ct");
    Bone a = bone(s, "A", new VoxelBox(10, 14, 10, 14, 10, 14));
    s.set(List.of(a));

    Optional<Found> inside = s.boneNear(new int[] {12, 12, 12}, 3);
    assertTrue(inside.isPresent());
    assertArrayEquals(new int[] {12, 12, 12}, inside.get().voxel());

    Optional<Found> near = s.boneNear(new int[] {16, 12, 12}, 3);
    assertTrue(near.isPresent());
    assertEquals(a, near.get().bone());
    assertArrayEquals(new int[] {14, 12, 12}, near.get().voxel());

    assertTrue(s.boneNear(new int[] {20, 12, 12}, 3).isEmpty(), "too far");
    assertTrue(s.boneNear(new int[] {16, 12, 12}, 0).isEmpty(), "on the bone only");
  }
}
