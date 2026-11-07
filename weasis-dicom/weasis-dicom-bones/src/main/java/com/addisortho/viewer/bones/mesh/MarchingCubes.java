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

/**
 * Iso-surface of a {@link ScalarGrid} by marching cubes (Lorensen and Cline, 1987, with the table
 * of {@link CubeTables}). Samples at or above the iso value are inside. The grid is treated as
 * surrounded by outside samples, so the surface is always closed. Neighbouring cells share the
 * vertex on their common edge, so the mesh comes out welded: every edge belongs to two triangles.
 */
public final class MarchingCubes {

  /** Keeps vertices this fraction of an edge away from its corners, so no triangle collapses. */
  private static final float EDGE_MARGIN = 1e-3f;

  private MarchingCubes() {}

  /**
   * Extracts the surface; checks for thread interruption between slices.
   *
   * @throws CancellationException when the thread is interrupted
   */
  public static TriangleMesh extract(ScalarGrid grid, double iso) {
    return new Extraction(grid, (float) iso).run();
  }

  private static final class Extraction {

    private final ScalarGrid grid;
    private final float iso;
    private final int nx;
    private final int ny;
    private final int nz;

    /** Row length of the edge caches, which cover corners -1 to n on each axis. */
    private final int rowLength;

    // Vertex index of each edge crossing, -1 when not created yet. Low and high are the two corner
    // layers of the current slab of cells; z edges join them.
    private int[] xLow;
    private int[] yLow;
    private int[] xHigh;
    private int[] yHigh;
    private final int[] zEdges;

    private final float[] cornerValues = new float[8];
    private double[] vertices = new double[3 * 1024];
    private int vertexCount;
    private int[] triangles = new int[3 * 2048];
    private int triangleCount;

    Extraction(ScalarGrid grid, float iso) {
      this.grid = grid;
      this.iso = iso;
      this.nx = grid.sizeX();
      this.ny = grid.sizeY();
      this.nz = grid.sizeZ();
      this.rowLength = nx + 2;
      int layer = rowLength * (ny + 2);
      xLow = newCache(layer);
      yLow = newCache(layer);
      xHigh = newCache(layer);
      yHigh = newCache(layer);
      zEdges = newCache(layer);
    }

    TriangleMesh run() {
      for (int k = -1; k < nz; k++) {
        if (Thread.currentThread().isInterrupted()) {
          throw new CancellationException();
        }
        for (int j = -1; j < ny; j++) {
          for (int i = -1; i < nx; i++) {
            march(i, j, k);
          }
        }
        int[] x = xLow;
        int[] y = yLow;
        xLow = xHigh;
        yLow = yHigh;
        xHigh = x;
        yHigh = y;
        Arrays.fill(xHigh, -1);
        Arrays.fill(yHigh, -1);
        Arrays.fill(zEdges, -1);
      }
      return new TriangleMesh(
          Arrays.copyOf(vertices, 3 * vertexCount), Arrays.copyOf(triangles, 3 * triangleCount));
    }

    private void march(int i, int j, int k) {
      int cubeCase = 0;
      for (int c = 0; c < 8; c++) {
        int[] o = CubeTables.CORNERS[c];
        float v = value(i + o[0], j + o[1], k + o[2]);
        cornerValues[c] = v;
        if (v < iso) {
          cubeCase |= 1 << c;
        }
      }
      int[] edges = CubeTables.TRIANGLES[cubeCase];
      for (int n = 0; n < edges.length; n += 3) {
        int a = vertex(i, j, k, edges[n]);
        int b = vertex(i, j, k, edges[n + 1]);
        int c = vertex(i, j, k, edges[n + 2]);
        // The table winds triangles counter-clockwise seen from the side below the iso value, which
        // is the outside here.
        addTriangle(a, b, c);
      }
    }

    private float value(int i, int j, int k) {
      if (i < 0 || j < 0 || k < 0 || i >= nx || j >= ny || k >= nz) {
        return Float.NEGATIVE_INFINITY;
      }
      return grid.get(i, j, k);
    }

    /** Vertex on {@code edge} of the cell at (i, j, k), created on first use. */
    private int vertex(int i, int j, int k, int edge) {
      int[] corners = CubeTables.EDGES[edge];
      int[] low = CubeTables.CORNERS[corners[0]];
      int[] high = CubeTables.CORNERS[corners[1]];
      int slot = (j + low[1] + 1) * rowLength + i + low[0] + 1;
      int[] cache;
      if (high[2] != low[2]) {
        cache = zEdges;
      } else if (high[0] != low[0]) {
        cache = low[2] == 0 ? xLow : xHigh;
      } else {
        cache = low[2] == 0 ? yLow : yHigh;
      }
      int id = cache[slot];
      if (id < 0) {
        float t = crossing(cornerValues[corners[0]], cornerValues[corners[1]]);
        id =
            addVertex(
                grid.x(i + low[0] + t * (high[0] - low[0])),
                grid.y(j + low[1] + t * (high[1] - low[1])),
                grid.z(k + low[2] + t * (high[2] - low[2])));
        cache[slot] = id;
      }
      return id;
    }

    /** Fraction of the way from v0 to v1 where the field reaches the iso value. */
    private float crossing(float v0, float v1) {
      float t;
      if (v0 == Float.NEGATIVE_INFINITY) {
        t = 1;
      } else if (v1 == Float.NEGATIVE_INFINITY) {
        t = 0;
      } else {
        t = (iso - v0) / (v1 - v0);
      }
      return Math.clamp(t, EDGE_MARGIN, 1 - EDGE_MARGIN);
    }

    private int addVertex(double x, double y, double z) {
      if (3 * vertexCount + 3 > vertices.length) {
        vertices = Arrays.copyOf(vertices, vertices.length * 2);
      }
      int v = 3 * vertexCount;
      vertices[v] = x;
      vertices[v + 1] = y;
      vertices[v + 2] = z;
      return vertexCount++;
    }

    private void addTriangle(int a, int b, int c) {
      if (3 * triangleCount + 3 > triangles.length) {
        triangles = Arrays.copyOf(triangles, triangles.length * 2);
      }
      int t = 3 * triangleCount++;
      triangles[t] = a;
      triangles[t + 1] = b;
      triangles[t + 2] = c;
    }

    private static int[] newCache(int size) {
      int[] cache = new int[size];
      Arrays.fill(cache, -1);
      return cache;
    }
  }
}
