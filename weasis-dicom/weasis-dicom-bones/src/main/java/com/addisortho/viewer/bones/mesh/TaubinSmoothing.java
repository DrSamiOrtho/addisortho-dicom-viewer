/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.mesh;

import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.stream.IntStream;

/**
 * Taubin's lambda/mu smoothing (SIGGRAPH 1995): each iteration moves every vertex towards the mean
 * of its neighbours by {@code lambda}, then away from it by {@code -mu}. It removes the staircase
 * of the voxel grid without the shrinking of plain Laplacian smoothing. The connectivity is
 * unchanged.
 */
public final class TaubinSmoothing {

  /** Usual factors: pass band near 0.1, as for VTK's windowed-sinc filter. */
  public static final double LAMBDA = 0.5;

  public static final double MU = -0.53;

  private TaubinSmoothing() {}

  /**
   * @throws CancellationException when the thread is interrupted
   */
  public static TriangleMesh smooth(TriangleMesh mesh, int iterations) {
    return smooth(mesh, iterations, LAMBDA, MU);
  }

  /**
   * @param lambda positive shrinking factor
   * @param mu negative inflating factor, with {@code -mu > lambda}
   * @throws CancellationException when the thread is interrupted
   */
  public static TriangleMesh smooth(TriangleMesh mesh, int iterations, double lambda, double mu) {
    if (iterations <= 0 || mesh.isEmpty()) {
      return mesh;
    }
    int[][] adjacency = neighbours(mesh);
    double[] current = mesh.vertices.clone();
    double[] next = new double[current.length];
    Thread caller = Thread.currentThread();
    for (int n = 0; n < iterations; n++) {
      if (caller.isInterrupted()) {
        throw new CancellationException();
      }
      step(adjacency, current, next, lambda);
      step(adjacency, next, current, mu);
    }
    return mesh.withVertices(current);
  }

  private static void step(int[][] adjacency, double[] src, double[] dst, double factor) {
    IntStream.range(0, adjacency.length)
        .parallel()
        .forEach(
            v -> {
              int[] around = adjacency[v];
              int p = 3 * v;
              if (around.length == 0) {
                System.arraycopy(src, p, dst, p, 3);
                return;
              }
              double x = 0;
              double y = 0;
              double z = 0;
              for (int u : around) {
                x += src[3 * u];
                y += src[3 * u + 1];
                z += src[3 * u + 2];
              }
              double inv = 1.0 / around.length;
              dst[p] = src[p] + factor * (x * inv - src[p]);
              dst[p + 1] = src[p + 1] + factor * (y * inv - src[p + 1]);
              dst[p + 2] = src[p + 2] + factor * (z * inv - src[p + 2]);
            });
  }

  /** The distinct vertices joined to each vertex by an edge. */
  static int[][] neighbours(TriangleMesh mesh) {
    int vertexCount = mesh.vertexCount();
    int[] t = mesh.triangles;
    int[] degree = new int[vertexCount];
    for (int v : t) {
      degree[v] += 2;
    }
    int[][] raw = new int[vertexCount][];
    for (int v = 0; v < vertexCount; v++) {
      raw[v] = new int[degree[v]];
    }
    int[] filled = new int[vertexCount];
    for (int n = 0; n < t.length; n += 3) {
      for (int c = 0; c < 3; c++) {
        int v = t[n + c];
        raw[v][filled[v]++] = t[n + (c + 1) % 3];
        raw[v][filled[v]++] = t[n + (c + 2) % 3];
      }
    }
    for (int v = 0; v < vertexCount; v++) {
      raw[v] = distinct(raw[v]);
    }
    return raw;
  }

  private static int[] distinct(int[] values) {
    if (values.length == 0) {
      return values;
    }
    Arrays.sort(values);
    int kept = 1;
    for (int n = 1; n < values.length; n++) {
      if (values[n] != values[kept - 1]) {
        values[kept++] = values[n];
      }
    }
    return Arrays.copyOf(values, kept);
  }
}
