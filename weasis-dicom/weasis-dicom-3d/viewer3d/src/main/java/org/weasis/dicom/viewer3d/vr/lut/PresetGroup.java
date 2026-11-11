/*
 * Copyright (c) 2023 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.viewer3d.vr.lut;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;

public class PresetGroup {
  @JsonProperty("name")
  private String name;

  @JsonProperty("point")
  PresetPoint[] points;

  public PresetGroup() {
    // Used by Jackson
  }

  public PresetGroup(String name, PresetPoint[] points) {
    this.name = name;
    this.points = points;
  }

  public String getName() {
    return name;
  }

  public PresetPoint[] getPoints() {
    return points;
  }

  public void setName(String name) {
    this.name = name;
  }

  public void setPoints(PresetPoint[] points) {
    this.points = points;
  }

  /** The lowest intensity of {@code groups} whose opacity is above zero: where a preset shows. */
  public static OptionalInt opacityOnset(List<PresetGroup> groups) {
    return groups.stream()
        .flatMap(g -> Arrays.stream(g.getPoints()))
        .filter(p -> p.getOpacity() > 0f)
        .mapToInt(PresetPoint::getIntensity)
        .min();
  }

  public PresetGroup copy() {
    PresetPoint[] copiedPoints =
        Arrays.stream(points).map(PresetPoint::copy).toArray(PresetPoint[]::new);
    return new PresetGroup(name, copiedPoints);
  }
}
