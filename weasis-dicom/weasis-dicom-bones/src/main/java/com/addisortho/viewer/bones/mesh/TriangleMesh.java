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

/**
 * An indexed triangle mesh in mm. Triangles are wound counter-clockwise seen from outside, so the
 * right-hand normal points out of the enclosed volume. Instances are not modified after creation.
 */
public final class TriangleMesh {

  /** x, y, z of each vertex. */
  final double[] vertices;

  /** Three vertex indexes per triangle. */
  final int[] triangles;

  /**
   * @param vertices x, y, z of each vertex; kept, not copied
   * @param triangles three vertex indexes per triangle; kept, not copied
   */
  public TriangleMesh(double[] vertices, int[] triangles) {
    if (vertices.length % 3 != 0 || triangles.length % 3 != 0) {
      throw new IllegalArgumentException("Arrays must hold triples");
    }
    int count = vertices.length / 3;
    for (int v : triangles) {
      if (v < 0 || v >= count) {
        throw new IllegalArgumentException("Vertex index out of range: " + v);
      }
    }
    this.vertices = vertices;
    this.triangles = triangles;
  }

  public int vertexCount() {
    return vertices.length / 3;
  }

  public int triangleCount() {
    return triangles.length / 3;
  }

  public boolean isEmpty() {
    return triangles.length == 0;
  }

  /** Coordinate {@code axis} (0 = x, 1 = y, 2 = z) of vertex {@code v}. */
  public double coordinate(int v, int axis) {
    return vertices[3 * v + axis];
  }

  /** Vertex index at {@code corner} (0 to 2) of triangle {@code t}. */
  public int corner(int t, int corner) {
    return triangles[3 * t + corner];
  }

  /** Enclosed volume in mm³: positive for an outward-facing closed surface. */
  public double signedVolume() {
    return moments()[0];
  }

  /** Centre of the enclosed volume, or {@code NaN} coordinates when the volume is zero. */
  public double[] centroid() {
    double[] m = moments();
    return new double[] {m[1] / m[0], m[2] / m[0], m[3] / m[0]};
  }

  /** Surface area in mm². */
  public double area() {
    double sum = 0;
    double[] n = new double[3];
    for (int t = 0; t < triangleCount(); t++) {
      sum += crossProduct(t, n);
    }
    return sum / 2;
  }

  /**
   * Writes the unit normal of triangle {@code t} into {@code dst}; zero for a degenerate triangle.
   *
   * @return {@code dst}
   */
  public double[] faceNormal(int t, double[] dst) {
    double length = crossProduct(t, dst);
    if (length > 0) {
      dst[0] /= length;
      dst[1] /= length;
      dst[2] /= length;
    } else {
      Arrays.fill(dst, 0);
    }
    return dst;
  }

  /** Smallest x, y, z then largest x, y, z of the vertices used by a triangle. */
  public double[] bounds() {
    double[] b = {
      Double.POSITIVE_INFINITY,
      Double.POSITIVE_INFINITY,
      Double.POSITIVE_INFINITY,
      Double.NEGATIVE_INFINITY,
      Double.NEGATIVE_INFINITY,
      Double.NEGATIVE_INFINITY
    };
    for (int v : triangles) {
      for (int axis = 0; axis < 3; axis++) {
        double c = vertices[3 * v + axis];
        b[axis] = Math.min(b[axis], c);
        b[axis + 3] = Math.max(b[axis + 3], c);
      }
    }
    return b;
  }

  /** Keeps the given triangles and the vertices they use, renumbered in first-use order. */
  public TriangleMesh subset(int[] keptTriangles) {
    int[] newIndex = new int[vertexCount()];
    Arrays.fill(newIndex, -1);
    int[] tris = new int[keptTriangles.length * 3];
    double[] verts = new double[Math.min(vertices.length, tris.length * 3)];
    int used = 0;
    for (int n = 0; n < keptTriangles.length; n++) {
      for (int c = 0; c < 3; c++) {
        int v = triangles[3 * keptTriangles[n] + c];
        if (newIndex[v] < 0) {
          newIndex[v] = used;
          System.arraycopy(vertices, 3 * v, verts, 3 * used, 3);
          used++;
        }
        tris[3 * n + c] = newIndex[v];
      }
    }
    return new TriangleMesh(Arrays.copyOf(verts, 3 * used), tris);
  }

  /** The same triangles on moved vertices. */
  TriangleMesh withVertices(double[] newVertices) {
    return new TriangleMesh(newVertices, triangles);
  }

  /** Signed volume of the tetrahedron joining triangle {@code t} to vertex {@code apex}. */
  double tetrahedronVolume(int t, int apex) {
    double ox = vertices[3 * apex];
    double oy = vertices[3 * apex + 1];
    double oz = vertices[3 * apex + 2];
    int a = 3 * triangles[3 * t];
    int b = 3 * triangles[3 * t + 1];
    int c = 3 * triangles[3 * t + 2];
    return det(
            vertices[a] - ox,
            vertices[a + 1] - oy,
            vertices[a + 2] - oz,
            vertices[b] - ox,
            vertices[b + 1] - oy,
            vertices[b + 2] - oz,
            vertices[c] - ox,
            vertices[c + 1] - oy,
            vertices[c + 2] - oz)
        / 6;
  }

  private static double det(
      double ax,
      double ay,
      double az,
      double bx,
      double by,
      double bz,
      double cx,
      double cy,
      double cz) {
    return ax * (by * cz - bz * cy) - ay * (bx * cz - bz * cx) + az * (bx * cy - by * cx);
  }

  /** Volume and first moments, summed over tetrahedra from a point near the mesh. */
  private double[] moments() {
    if (isEmpty()) {
      return new double[] {0, Double.NaN, Double.NaN, Double.NaN};
    }
    // Measuring from a vertex keeps the terms small for meshes far from the LPS origin.
    int ref = triangles[0];
    double ox = vertices[3 * ref];
    double oy = vertices[3 * ref + 1];
    double oz = vertices[3 * ref + 2];
    double volume = 0;
    double mx = 0;
    double my = 0;
    double mz = 0;
    for (int t = 0; t < triangleCount(); t++) {
      int a = 3 * triangles[3 * t];
      int b = 3 * triangles[3 * t + 1];
      int c = 3 * triangles[3 * t + 2];
      double ax = vertices[a] - ox;
      double ay = vertices[a + 1] - oy;
      double az = vertices[a + 2] - oz;
      double bx = vertices[b] - ox;
      double by = vertices[b + 1] - oy;
      double bz = vertices[b + 2] - oz;
      double cx = vertices[c] - ox;
      double cy = vertices[c + 1] - oy;
      double cz = vertices[c + 2] - oz;
      double v = det(ax, ay, az, bx, by, bz, cx, cy, cz) / 6;
      volume += v;
      // The centroid of a tetrahedron with one corner at the reference is (a + b + c) / 4.
      mx += v * (ax + bx + cx) / 4;
      my += v * (ay + by + cy) / 4;
      mz += v * (az + bz + cz) / 4;
    }
    return new double[] {volume, mx + volume * ox, my + volume * oy, mz + volume * oz};
  }

  /** Writes (b - a) x (c - a) of triangle {@code t} into {@code dst} and returns its length. */
  private double crossProduct(int t, double[] dst) {
    int a = 3 * triangles[3 * t];
    int b = 3 * triangles[3 * t + 1];
    int c = 3 * triangles[3 * t + 2];
    double ux = vertices[b] - vertices[a];
    double uy = vertices[b + 1] - vertices[a + 1];
    double uz = vertices[b + 2] - vertices[a + 2];
    double vx = vertices[c] - vertices[a];
    double vy = vertices[c + 1] - vertices[a + 1];
    double vz = vertices[c + 2] - vertices[a + 2];
    dst[0] = uy * vz - uz * vy;
    dst[1] = uz * vx - ux * vz;
    dst[2] = ux * vy - uy * vx;
    return Math.sqrt(dst[0] * dst[0] + dst[1] * dst[1] + dst[2] * dst[2]);
  }
}
