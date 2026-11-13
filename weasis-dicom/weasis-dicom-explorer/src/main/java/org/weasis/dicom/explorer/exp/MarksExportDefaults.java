/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer.exp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Sequence;
import org.dcm4che3.data.Tag;
import org.weasis.core.api.media.data.TagReadable;
import org.weasis.dicom.codec.DicomSpecialElement;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.explorer.pr.DicomPrSerializer;

/**
 * What Export and Send tick by default where marks saved by this viewer (content label {@link
 * DicomPrSerializer#AO_MARKS}) are in the tree: only the newest marks of each image, and only with
 * the image they belong to. Older versions stay in the tree, unticked, so a mark deleted later does
 * not leave with the study. Where a study has saved marks, the extra "[GRAPHICS]" row (what is
 * drawn now, under a generic label) is left unticked too.
 */
public final class MarksExportDefaults {
  private static final int SERIES_LEVEL = 3;

  /** How a tree node's user object is read; the defaults read the export tree's own objects. */
  record Probe(
      Function<Object, Attributes> presentation,
      Function<Object, String> seriesUid,
      Predicate<Object> isDrawnNow) {}

  static final Probe EXPORT_TREE =
      new Probe(
          o ->
              o instanceof DicomSpecialElement e && e.getMediaReader() != null
                  ? e.getMediaReader().getDicomObject()
                  : null,
          o ->
              o instanceof TagReadable t
                  ? TagD.getTagValue(t, Tag.SeriesInstanceUID, String.class)
                  : null,
          o ->
              o instanceof TagReadable t
                  && t.getTagValue(CheckTreeModel.SourceSeriesForPR) != null);

  private MarksExportDefaults() {}

  /**
   * @param root the export tree
   * @return the rows to untick after whole studies are ticked: older versions of the marks, and the
   *     "[GRAPHICS]" row of a study that has saved marks
   */
  public static List<TreePath> toUntick(DefaultMutableTreeNode root) {
    return toUntick(root, EXPORT_TREE);
  }

  static List<TreePath> toUntick(DefaultMutableTreeNode root, Probe probe) {
    Map<DefaultMutableTreeNode, List<Marks>> marksByStudy = new HashMap<>();
    List<DefaultMutableTreeNode> drawnNow = new ArrayList<>();
    for (DefaultMutableTreeNode series : seriesNodes(root)) {
      if (probe.isDrawnNow().test(series.getUserObject())) {
        drawnNow.add(series);
      }
      for (DefaultMutableTreeNode child : children(series)) {
        Attributes pr = probe.presentation().apply(child.getUserObject());
        if (pr != null && DicomPrSerializer.AO_MARKS.equals(pr.getString(Tag.ContentLabel))) {
          marksByStudy
              .computeIfAbsent((DefaultMutableTreeNode) series.getParent(), _ -> new ArrayList<>())
              .add(new Marks(child, pr));
        }
      }
    }
    List<TreePath> off = new ArrayList<>();
    for (DefaultMutableTreeNode series : drawnNow) {
      if (marksByStudy.containsKey(series.getParent())) {
        off.add(new TreePath(series.getPath())); // the saved marks go instead
      }
    }
    for (List<Marks> marks : marksByStudy.values()) {
      Set<DefaultMutableTreeNode> keep = new HashSet<>();
      newestPerImage(marks).forEach(m -> keep.add(m.node()));
      Map<DefaultMutableTreeNode, List<DefaultMutableTreeNode>> oldBySeries = new HashMap<>();
      for (Marks m : marks) {
        if (!keep.contains(m.node())) {
          oldBySeries
              .computeIfAbsent(
                  (DefaultMutableTreeNode) m.node().getParent(), _ -> new ArrayList<>())
              .add(m.node());
        }
      }
      oldBySeries.forEach(
          (series, old) -> {
            if (old.size() == series.getChildCount()) {
              off.add(new TreePath(series.getPath())); // nothing in it is current
            } else {
              old.forEach(n -> off.add(new TreePath(n.getPath())));
            }
          });
    }
    return off;
  }

  /** One saved version of marks and the image it is drawn on. */
  record Marks(DefaultMutableTreeNode node, Attributes pr) {
    String created() {
      return pr.getString(Tag.PresentationCreationDate, "")
          + pr.getString(Tag.PresentationCreationTime, "");
    }

    List<String> referencedSeries() {
      List<String> uids = new ArrayList<>();
      Sequence seq = pr.getSequence(Tag.ReferencedSeriesSequence);
      if (seq != null) {
        seq.forEach(s -> uids.add(s.getString(Tag.SeriesInstanceUID)));
      }
      return uids;
    }

    List<String> referencedImages() {
      List<String> sops = new ArrayList<>();
      Sequence seq = pr.getSequence(Tag.ReferencedSeriesSequence);
      if (seq != null) {
        for (Attributes series : seq) {
          Sequence images = series.getSequence(Tag.ReferencedImageSequence);
          if (images != null) {
            images.forEach(i -> sops.add(i.getString(Tag.ReferencedSOPInstanceUID)));
          }
        }
      }
      return sops;
    }
  }

  private static List<Marks> newestPerImage(List<Marks> marks) {
    Map<String, Marks> newest = new HashMap<>();
    for (Marks m : marks) {
      for (String sop : m.referencedImages()) {
        Marks held = newest.get(sop);
        if (held == null || m.created().compareTo(held.created()) > 0) {
          newest.put(sop, m);
        }
      }
    }
    return new ArrayList<>(new LinkedHashSet<>(newest.values()));
  }

  private static boolean holdsMarks(DefaultMutableTreeNode series, List<Marks> marks) {
    return marks.stream().anyMatch(m -> m.node().getParent() == series);
  }

  private static List<DefaultMutableTreeNode> seriesNodes(DefaultMutableTreeNode root) {
    List<DefaultMutableTreeNode> out = new ArrayList<>();
    for (var e = root.breadthFirstEnumeration(); e.hasMoreElements(); ) {
      DefaultMutableTreeNode n = (DefaultMutableTreeNode) e.nextElement();
      if (n.getLevel() == SERIES_LEVEL) {
        out.add(n);
      }
    }
    return out;
  }

  private static List<DefaultMutableTreeNode> children(DefaultMutableTreeNode node) {
    List<DefaultMutableTreeNode> out = new ArrayList<>();
    for (Object c : Collections.list(node.children())) {
      out.add((DefaultMutableTreeNode) c);
    }
    return out;
  }
}
