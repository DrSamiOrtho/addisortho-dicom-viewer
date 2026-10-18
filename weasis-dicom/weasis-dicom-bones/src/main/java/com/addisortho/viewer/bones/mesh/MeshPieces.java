/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.mesh;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Connected pieces of a mesh: triangles joined through shared vertices. */
public final class MeshPieces {

  /** One piece: its triangles and the volume it encloses. */
  public record Piece(int[] triangles, double volume) {}

  private MeshPieces() {}

  /** The pieces, largest enclosed volume first. */
  public static List<Piece> split(TriangleMesh mesh) {
    int[] parent = new int[mesh.vertexCount()];
    for (int v = 0; v < parent.length; v++) {
      parent[v] = v;
    }
    int[] t = mesh.triangles;
    for (int n = 0; n < t.length; n += 3) {
      union(parent, t[n], t[n + 1]);
      union(parent, t[n], t[n + 2]);
    }
    // Number the pieces by root, then gather their triangles and volumes.
    int[] pieceOfRoot = new int[parent.length];
    int pieces = 0;
    int[] sizes = new int[mesh.triangleCount()];
    int[] pieceOfTriangle = new int[mesh.triangleCount()];
    // A closed piece encloses the same volume from any point; measuring from one of its own
    // vertices keeps the terms small.
    int[] reference = new int[mesh.triangleCount()];
    double[] volumes = new double[mesh.triangleCount()];
    for (int n = 0; n < pieceOfTriangle.length; n++) {
      int root = find(parent, t[3 * n]);
      if (pieceOfRoot[root] == 0) {
        reference[pieces] = t[3 * n];
        pieceOfRoot[root] = ++pieces;
      }
      int p = pieceOfRoot[root] - 1;
      pieceOfTriangle[n] = p;
      sizes[p]++;
      volumes[p] += mesh.tetrahedronVolume(n, reference[p]);
    }
    int[][] members = new int[pieces][];
    for (int p = 0; p < pieces; p++) {
      members[p] = new int[sizes[p]];
    }
    int[] filled = new int[pieces];
    for (int n = 0; n < pieceOfTriangle.length; n++) {
      int p = pieceOfTriangle[n];
      members[p][filled[p]++] = n;
    }
    List<Piece> list = new ArrayList<>(pieces);
    for (int p = 0; p < pieces; p++) {
      list.add(new Piece(members[p], Math.abs(volumes[p])));
    }
    list.sort(
        Comparator.comparingDouble(Piece::volume)
            .thenComparingInt(p -> p.triangles().length)
            .reversed());
    return list;
  }

  /** The piece that encloses the largest volume; an empty mesh stays empty. */
  public static TriangleMesh keepLargest(TriangleMesh mesh) {
    List<Piece> pieces = split(mesh);
    return pieces.isEmpty() ? mesh : mesh.subset(pieces.getFirst().triangles());
  }

  private static int find(int[] parent, int v) {
    while (parent[v] != v) {
      parent[v] = parent[parent[v]];
      v = parent[v];
    }
    return v;
  }

  private static void union(int[] parent, int a, int b) {
    int ra = find(parent, a);
    int rb = find(parent, b);
    if (ra != rb) {
      parent[Math.max(ra, rb)] = Math.min(ra, rb);
    }
  }
}
