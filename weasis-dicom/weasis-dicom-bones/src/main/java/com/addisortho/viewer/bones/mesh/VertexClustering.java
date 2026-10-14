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
import java.util.HashMap;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Reduces a mesh by vertex clustering (Rossignac and Borrel, 1993): the vertices in each cubic cell
 * merge into their mean, and triangles that collapse disappear. It is fast and keeps the shape to
 * within a cell, but it may join close surfaces and leave edges shared by more than two triangles.
 */
public final class VertexClustering {

  /** Growth of the cell size between attempts to reach the target. */
  private static final double CELL_GROWTH = 1.15;

  private static final int MAX_ATTEMPTS = 40;

  private VertexClustering() {}

  /**
   * Returns a mesh of at most {@code maxTriangles} triangles, or {@code mesh} itself when it is
   * already small enough or {@code maxTriangles} is not positive.
   */
  public static TriangleMesh decimate(TriangleMesh mesh, int maxTriangles) {
    if (maxTriangles <= 0 || mesh.triangleCount() <= maxTriangles) {
      return mesh;
    }
    double[] bounds = mesh.bounds();
    // A closed surface of area A cut into cells of side h has about A / h² vertices and twice as
    // many triangles.
    double cell = Math.sqrt(2 * mesh.area() / maxTriangles);
    TriangleMesh reduced = mesh;
    for (int attempt = 0;
        attempt < MAX_ATTEMPTS && reduced.triangleCount() > maxTriangles;
        attempt++) {
      reduced = cluster(mesh, bounds, cell);
      cell *= CELL_GROWTH;
    }
    return reduced;
  }

  /** Merges the vertices of each cell of side {@code cell}. */
  static TriangleMesh cluster(TriangleMesh mesh, double[] bounds, double cell) {
    long cellsX = (long) Math.floor((bounds[3] - bounds[0]) / cell) + 1;
    long cellsY = (long) Math.floor((bounds[4] - bounds[1]) / cell) + 1;
    int vertexCount = mesh.vertexCount();
    int[] clusterOf = new int[vertexCount];
    Map<Long, Integer> clusters = new HashMap<>();
    double[] sums = new double[3 * Math.min(vertexCount, 1 << 16)];
    int[] members = new int[sums.length / 3];
    double[] v = mesh.vertices;
    for (int n = 0; n < vertexCount; n++) {
      long ix = (long) Math.floor((v[3 * n] - bounds[0]) / cell);
      long iy = (long) Math.floor((v[3 * n + 1] - bounds[1]) / cell);
      long iz = (long) Math.floor((v[3 * n + 2] - bounds[2]) / cell);
      int id = clusters.computeIfAbsent((iz * cellsY + iy) * cellsX + ix, _ -> clusters.size());
      if (3 * id + 3 > sums.length) {
        sums = Arrays.copyOf(sums, sums.length * 2);
        members = Arrays.copyOf(members, members.length * 2);
      }
      sums[3 * id] += v[3 * n];
      sums[3 * id + 1] += v[3 * n + 1];
      sums[3 * id + 2] += v[3 * n + 2];
      members[id]++;
      clusterOf[n] = id;
    }
    double[] positions = new double[3 * clusters.size()];
    for (int id = 0; id < clusters.size(); id++) {
      for (int axis = 0; axis < 3; axis++) {
        positions[3 * id + axis] = sums[3 * id + axis] / members[id];
      }
    }
    TriangleMesh merged = new TriangleMesh(positions, mergedTriangles(mesh, clusterOf));
    // Drops the clusters whose triangles all collapsed.
    return merged.subset(IntStream.range(0, merged.triangleCount()).toArray());
  }

  /**
   * Triangles between three different clusters. A repeated triangle is kept once; a pair facing
   * opposite ways is a two-sided sliver and both go.
   */
  private static int[] mergedTriangles(TriangleMesh mesh, int[] clusterOf) {
    int[] t = mesh.triangles;
    int[] out = new int[t.length];
    boolean[] dropped = new boolean[mesh.triangleCount()];
    Map<Face, Integer> seen = new HashMap<>();
    int count = 0;
    for (int n = 0; n < t.length; n += 3) {
      int a = clusterOf[t[n]];
      int b = clusterOf[t[n + 1]];
      int c = clusterOf[t[n + 2]];
      if (a == b || b == c || a == c) {
        continue;
      }
      Face face = Face.of(a, b, c);
      Integer previous = seen.get(face.unordered());
      if (previous != null && !dropped[previous]) {
        if (!Face.of(out[3 * previous], out[3 * previous + 1], out[3 * previous + 2])
            .equals(face)) {
          dropped[previous] = true;
        }
        continue;
      }
      seen.put(face.unordered(), count);
      out[3 * count] = a;
      out[3 * count + 1] = b;
      out[3 * count + 2] = c;
      count++;
    }
    int[] kept = new int[3 * count];
    int k = 0;
    for (int n = 0; n < count; n++) {
      if (!dropped[n]) {
        System.arraycopy(out, 3 * n, kept, 3 * k++, 3);
      }
    }
    return Arrays.copyOf(kept, 3 * k);
  }

  /** A triangle rotated to start at its smallest vertex, so equal faces compare equal. */
  private record Face(int a, int b, int c) {

    static Face of(int a, int b, int c) {
      if (a < b && a < c) {
        return new Face(a, b, c);
      }
      return b < c ? new Face(b, c, a) : new Face(c, a, b);
    }

    /** The same vertices whatever the winding. */
    Face unordered() {
      return b < c ? this : new Face(a, c, b);
    }
  }
}
