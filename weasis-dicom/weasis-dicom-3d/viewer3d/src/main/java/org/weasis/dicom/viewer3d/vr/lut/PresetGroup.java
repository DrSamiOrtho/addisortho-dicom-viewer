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
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;

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

  /**
   * The lowest intensity at which the opacity of {@code groups} reaches {@code level}, read along
   * the straight lines between their points as the colour map draws them: where a preset shows a
   * surface. Empty when the opacity never gets there.
   *
   * <p>The first point with any opacity was used at first (8 Nov 2026). That is 200 HU for AO Bone,
   * whose points go 0 at 120 and 0.4 at 200, but for a preset that ramps from 0 at -100 to 0.875 at
   * 500 it was the ramp's top, 500, although the composite picture shows bone from far lower
   * (Astra, 11 Nov 2026).
   */
  public static OptionalDouble opacityCrossing(List<PresetGroup> groups, double level) {
    // one line through the points of every group, as the colour map is filled (Preset.initColors
    // joins the groups end to end and draws from each point to the next, across groups too)
    PresetPoint[] pts =
        groups.stream()
            .flatMap(g -> Arrays.stream(g.getPoints()))
            .sorted(Comparator.comparingInt(PresetPoint::getIntensity))
            .toArray(PresetPoint[]::new);
    for (int i = 0; i < pts.length; i++) {
      if (pts[i].getOpacity() >= level) {
        double at = pts[i].getIntensity();
        if (i > 0 && pts[i - 1].getOpacity() < level) {
          double rise = pts[i].getOpacity() - pts[i - 1].getOpacity();
          double span = pts[i].getIntensity() - pts[i - 1].getIntensity();
          at = pts[i - 1].getIntensity() + span * (level - pts[i - 1].getOpacity()) / rise;
        }
        return OptionalDouble.of(at);
      }
    }
    return OptionalDouble.empty();
  }

  public PresetGroup copy() {
    PresetPoint[] copiedPoints =
        Arrays.stream(points).map(PresetPoint::copy).toArray(PresetPoint[]::new);
    return new PresetGroup(name, copiedPoints);
  }
}
