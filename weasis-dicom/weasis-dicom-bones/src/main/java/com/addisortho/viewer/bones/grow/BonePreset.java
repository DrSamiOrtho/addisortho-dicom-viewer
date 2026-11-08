/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.grow;

/**
 * Starting settings of the region grow for the bones an orthopaedic surgeon selects most, so one
 * click is usually enough. They are a starting point: thin or osteoporotic cortex may need a lower
 * threshold, and a bone fused to its neighbour more separation.
 *
 * @param key message key of the name shown to the user
 * @param lowerHu bone threshold
 * @param closingRadius gaps of the cortex sealed, in voxels
 * @param separationVoxels depth of the cut from the neighbouring bone, in voxels
 * @param minPieceMm3 loose pieces smaller than this are dropped
 */
public record BonePreset(
    String key, double lowerHu, int closingRadius, int separationVoxels, double minPieceMm3) {

  /** Pelvis or hip: thin cortex; the femoral head is cut from the acetabulum. */
  public static final BonePreset PELVIS = new BonePreset("preset.pelvis", 180, 2, 2, 500);

  /** Femur: separated at the hip and at the knee. */
  public static final BonePreset FEMUR = new BonePreset("preset.femur", 200, 1, 2, 200);

  /** One bone of the knee: femur, tibia or patella, whichever is clicked. */
  public static final BonePreset KNEE = new BonePreset("preset.knee", 200, 1, 2, 100);

  /** Tibia, fibula, ankle or foot. */
  public static final BonePreset LEG = new BonePreset("preset.leg", 200, 1, 1, 50);

  /** Shoulder: humeral head cut from the glenoid. */
  public static final BonePreset SHOULDER = new BonePreset("preset.shoulder", 180, 1, 2, 100);

  /** One vertebra, cut from its neighbours. */
  public static final BonePreset SPINE = new BonePreset("preset.spine", 180, 1, 2, 100);

  /** Elbow and forearm: radius and ulna cut from each other and from the humerus. */
  public static final BonePreset ARM = new BonePreset("preset.arm", 200, 1, 2, 50);

  /** Wrist and hand: small bones close together, small pieces kept. */
  public static final BonePreset HAND = new BonePreset("preset.hand", 200, 1, 1, 10);

  /** Ribs and sternum: thin cortex. */
  public static final BonePreset CHEST = new BonePreset("preset.chest", 150, 1, 1, 50);

  /** Any other bone: the plain defaults, every setting at hand. */
  public static final BonePreset OTHER =
      new BonePreset("preset.other", GrowOptions.DEFAULT_LOWER_HU, 1, 0, 100);

  /** In the order of the list. */
  public static final BonePreset[] ALL = {
    PELVIS, FEMUR, KNEE, LEG, SHOULDER, ARM, HAND, SPINE, CHEST, OTHER
  };

  /**
   * The grow options of this preset.
   *
   * @param voxelMm3 volume of one CT voxel, to turn the smallest piece into voxels
   */
  public GrowOptions options(double voxelMm3) {
    long minVoxels = voxelMm3 > 0 ? Math.round(minPieceMm3 / voxelMm3) : 0;
    return GrowOptions.defaults()
        .withRange(lowerHu, GrowOptions.DEFAULT_UPPER_HU)
        .withClosingRadius(closingRadius)
        .withSeparation(separationVoxels)
        .withFillHoles(true)
        .withMinPieceVoxels(minVoxels);
  }
}
