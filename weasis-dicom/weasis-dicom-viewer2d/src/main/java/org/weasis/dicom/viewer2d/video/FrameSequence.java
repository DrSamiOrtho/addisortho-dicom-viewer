/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
// Modified by Addis Ortho Trading PLC, 2026.
package org.weasis.dicom.viewer2d.video;

/** Which picture each frame of an exported video shows: rotation angles or slice numbers. */
public final class FrameSequence {

  /** Most frames of one video, about 3 minutes at 30 fps. */
  public static final int MAX_FRAMES = 6000;

  private FrameSequence() {}

  /**
   * Angles, in degrees from the starting view, of the frames of a turn of {@code degrees} in {@code
   * frames} frames. A whole number of full turns loops: the frames are spread evenly and the last
   * one stops one step short of the first, so a player looping the video does not show the same
   * view twice. A partial turn shows both ends: the first frame at 0, the last at {@code degrees}.
   * A negative angle turns the other way.
   *
   * @param frames 1 to {@link #MAX_FRAMES}
   */
  public static double[] rotationAngles(double degrees, int frames) {
    checkFrames(frames);
    if (!Double.isFinite(degrees)) {
      throw new IllegalArgumentException("Invalid rotation angle");
    }
    double[] angles = new double[frames];
    if (frames == 1) {
      return angles;
    }
    boolean loop = degrees != 0 && Math.abs(Math.IEEEremainder(degrees, 360)) < 1e-9;
    double step = degrees / (loop ? frames : frames - 1);
    for (int i = 0; i < frames; i++) {
      angles[i] = step * i;
    }
    return angles;
  }

  /**
   * The turn from one frame to the next, in degrees: the first entry is 0 (the view as it is), then
   * the difference between consecutive {@link #rotationAngles}, so a camera turned by each step in
   * turn shows exactly those angles.
   */
  public static double[] rotationSteps(double degrees, int frames) {
    double[] angles = rotationAngles(degrees, frames);
    double[] steps = new double[frames];
    for (int i = 1; i < frames; i++) {
      steps[i] = angles[i] - angles[i - 1];
    }
    return steps;
  }

  /**
   * Slice numbers from {@code first} to {@code last}, both included, every {@code step} slices, in
   * either direction. The last slice is always included, even when the step skips past it.
   *
   * @param step 1 or more
   */
  public static int[] sliceIndices(int first, int last, int step) {
    if (step < 1) {
      throw new IllegalArgumentException("The step must be at least 1");
    }
    int span = Math.abs(last - first);
    int count = span / step + 1;
    boolean addLast = span % step != 0;
    int total = count + (addLast ? 1 : 0);
    checkFrames(total);
    int dir = last >= first ? 1 : -1;
    int[] indices = new int[total];
    for (int i = 0; i < count; i++) {
      indices[i] = first + dir * i * step;
    }
    if (addLast) {
      indices[total - 1] = last;
    }
    return indices;
  }

  /** How long the video plays, in seconds. */
  public static double durationSeconds(int frames, int fps) {
    if (fps < 1) {
      throw new IllegalArgumentException("Invalid frame rate");
    }
    return frames / (double) fps;
  }

  private static void checkFrames(int frames) {
    if (frames < 1 || frames > MAX_FRAMES) {
      throw new IllegalArgumentException(
          "A video has 1 to " + MAX_FRAMES + " frames, not " + frames);
    }
  }
}
