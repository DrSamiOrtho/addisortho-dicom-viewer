/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones;

import com.addisortho.viewer.bones.grow.BonePreset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The bones of the skeleton a surgeon may select, by region, each with the grow settings of its
 * region ({@link BonePreset}) and whether it has a right and a left. A selected bone is named after
 * the bone and side chosen, such as "Right femur" or "L3".
 */
final class BoneNames {

  /**
   * A bone of the list.
   *
   * @param key message key of its name, {@code bone.} followed by its id
   * @param preset its grow settings
   * @param paired whether it has a right and a left
   */
  record Name(String key, BonePreset preset, boolean paired) {
    String text() {
      return Messages.getString(key);
    }

    @Override
    public String toString() {
      return text();
    }
  }

  /**
   * A region of the body and its bones.
   *
   * @param key message key of its name, {@code region.} followed by its id
   */
  record Region(String key, List<Name> bones) {
    @Override
    public String toString() {
      return Messages.getString(key);
    }
  }

  /** Right, left or none, for the bones that come in pairs. */
  enum Side {
    RIGHT("side.right"),
    LEFT("side.left");

    final String key;

    Side(String key) {
      this.key = key;
    }

    @Override
    public String toString() {
      return Messages.getString(key);
    }
  }

  static final List<Region> REGIONS =
      List.of(
          region(
              "pelvis",
              BonePreset.PELVIS,
              paired("hipBone", "ilium", "ischium", "pubis", "acetabulum"),
              single("pelvis", "sacrum")),
          region(
              "thigh",
              BonePreset.FEMUR,
              paired(
                  "femur",
                  "femoralHead",
                  "proximalFemur",
                  "femoralShaft",
                  "distalFemur",
                  "greaterTrochanter")),
          region("knee", BonePreset.KNEE, paired("patella", "tibia", "proximalTibia")),
          region(
              "leg",
              BonePreset.LEG,
              paired(
                  "tibialShaft",
                  "distalTibia",
                  "fibula",
                  "medialMalleolus",
                  "lateralMalleolus",
                  "talus",
                  "calcaneus",
                  "navicular",
                  "cuboid",
                  "medialCuneiform",
                  "intermediateCuneiform",
                  "lateralCuneiform",
                  "metatarsal1",
                  "metatarsal2",
                  "metatarsal3",
                  "metatarsal4",
                  "metatarsal5",
                  "toePhalanges")),
          region(
              "shoulder",
              BonePreset.SHOULDER,
              paired(
                  "clavicle",
                  "scapula",
                  "glenoid",
                  "humerus",
                  "humeralHead",
                  "proximalHumerus",
                  "humeralShaft")),
          region(
              "arm",
              BonePreset.ARM,
              paired("distalHumerus", "radius", "radialHead", "distalRadius", "ulna", "olecranon")),
          region(
              "hand",
              BonePreset.HAND,
              paired(
                  "scaphoid",
                  "lunate",
                  "triquetrum",
                  "pisiform",
                  "trapezium",
                  "trapezoid",
                  "capitate",
                  "hamate",
                  "metacarpal1",
                  "metacarpal2",
                  "metacarpal3",
                  "metacarpal4",
                  "metacarpal5",
                  "fingerPhalanges")),
          region(
              "spine",
              BonePreset.SPINE,
              single(
                  "c1", "c2", "c3", "c4", "c5", "c6", "c7", "t1", "t2", "t3", "t4", "t5", "t6",
                  "t7", "t8", "t9", "t10", "t11", "t12", "l1", "l2", "l3", "l4", "l5", "coccyx")),
          region("chest", BonePreset.CHEST, single("sternum"), paired("rib")),
          region("other", BonePreset.OTHER, single("skull", "mandible", "other")));

  private BoneNames() {}

  /** The bones given to {@link #region}: their ids and whether they are paired. */
  private record Ids(boolean paired, String... ids) {}

  private static Ids paired(String... ids) {
    return new Ids(true, ids);
  }

  private static Ids single(String... ids) {
    return new Ids(false, ids);
  }

  private static Region region(String id, BonePreset preset, Ids... groups) {
    List<Name> bones = new ArrayList<>();
    for (Ids group : groups) {
      for (String bone : group.ids()) {
        bones.add(new Name("bone." + bone, preset, group.paired())); // NON-NLS
      }
    }
    return new Region("region." + id, List.copyOf(bones)); // NON-NLS
  }

  /** Every bone of every region, in the order of the list. */
  static List<Name> all() {
    return REGIONS.stream().flatMap(r -> r.bones().stream()).toList();
  }

  /**
   * The name of a selected bone: "Right femur", "L3"; with a number when the list already holds a
   * bone of that name ("Right femur 2").
   *
   * @param side the side, ignored for an unpaired bone; {@code null} for none
   * @param taken the names already in the list
   */
  static String label(Name name, Side side, List<String> taken) {
    String base =
        name.paired() && side != null
            ? String.format(Messages.getString(side.key), lowerFirst(name.text()))
            : name.text();
    if (!taken.contains(base)) {
      return base;
    }
    for (int n = 2; ; n++) {
      String numbered = base + " " + n;
      if (!taken.contains(numbered)) {
        return numbered;
      }
    }
  }

  /** Every name a bone can get from the list: each paired bone on the right, then on the left. */
  static List<String> labels() {
    List<String> labels = new ArrayList<>();
    for (Name name : all()) {
      if (name.paired()) {
        for (Side side : Side.values()) {
          labels.add(label(name, side, List.of()));
        }
      } else {
        labels.add(name.text());
      }
    }
    return labels;
  }

  /** "Femur" to "femur" after "Right"; "C1 (atlas)" stays. */
  static String lowerFirst(String text) {
    if (text.length() > 1
        && Character.isUpperCase(text.charAt(0))
        && Character.isLowerCase(text.charAt(1))) {
      return text.substring(0, 1).toLowerCase(Locale.ROOT) + text.substring(1);
    }
    return text;
  }
}
