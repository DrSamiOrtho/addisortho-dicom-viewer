/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.viewer2d;

import java.awt.Color;
import java.awt.Component;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.dcm4che3.util.UIDUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.util.ActionW;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.api.media.data.MediaSeries;
import org.weasis.core.api.media.data.TagW;
import org.weasis.core.ui.editor.SeriesViewerEvent;
import org.weasis.core.ui.editor.SeriesViewerEvent.EVENT;
import org.weasis.core.ui.editor.image.ImageViewerPlugin;
import org.weasis.core.ui.editor.image.ViewCanvas;
import org.weasis.core.ui.model.AbstractGraphicModel;
import org.weasis.core.ui.model.GraphicModel;
import org.weasis.core.ui.model.graphic.Graphic;
import org.weasis.core.ui.model.imp.XmlGraphicModel;
import org.weasis.core.util.LangUtil;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.codec.PRSpecialElement;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.explorer.DicomModel;
import org.weasis.dicom.explorer.HangingProtocols.OpeningViewer;
import org.weasis.dicom.explorer.LoadLocalDicom;
import org.weasis.dicom.explorer.library.StudyLibrary;
import org.weasis.dicom.explorer.pr.DicomPrSerializer;
import org.weasis.dicom.explorer.pr.PrGraphicUtil;

/**
 * Marks drawn on images (a biopsy site, a label, a measurement), kept with the study as DICOM
 * presentation states. The image pixels are never changed. When a study opens, the newest marks
 * this viewer saved come back as ordinary drawings, so a misplaced mark can be moved or deleted
 * before the next save. Every save is a new object; earlier ones stay as history.
 */
public final class SavedMarks {
  private static final Logger LOGGER = LoggerFactory.getLogger(SavedMarks.class);

  /** The content label that tells this viewer's marks from other presentation states. */
  public static final String LABEL = "AO_MARKS"; // NON-NLS

  static final String SERIES_DESCRIPTION = "Marks (Addis Ortho)"; // NON-NLS
  static final String CONTENT_DESCRIPTION = "Marks saved in Addis Ortho Viewer"; // NON-NLS
  static final int SERIES_NUMBER = 9100;

  /**
   * The newest marks known for each image: restored from the study, or saved in this session. An
   * image listed here is saved again even when its marks were all deleted, so the next opening
   * shows none.
   */
  private static final Map<DicomImageElement, String> held =
      Collections.synchronizedMap(new WeakHashMap<>());

  /**
   * The drawings each image got from its restored marks, so that a newer saved version that
   * finishes loading later replaces them instead of being drawn on top.
   */
  private static final Map<DicomImageElement, List<Graphic>> restoredGraphics =
      Collections.synchronizedMap(new WeakHashMap<>());

  private SavedMarks() {}

  /** Whether the presentation state was written by {@link #save}. */
  public static boolean isOurs(Attributes pr) {
    return pr != null && LABEL.equals(pr.getString(Tag.ContentLabel));
  }

  public static boolean isOurs(PRSpecialElement pr) {
    return pr != null
        && pr.getMediaReader() != null
        && isOurs(pr.getMediaReader().getDicomObject());
  }

  /**
   * @param newestFirst the presentation states of one image, newest first
   * @return the newest one written by this viewer
   */
  static Optional<PRSpecialElement> newest(List<PRSpecialElement> newestFirst) {
    return newestFirst.stream().filter(SavedMarks::isOurs).findFirst();
  }

  /** Marks this content as this viewer's own, with its series description. */
  static void label(Attributes pr) {
    pr.setString(Tag.ContentLabel, VR.CS, LABEL);
    pr.setString(Tag.ContentDescription, VR.LO, CONTENT_DESCRIPTION);
    pr.setString(Tag.SeriesDescription, VR.LO, SERIES_DESCRIPTION);
    pr.setInt(Tag.SeriesNumber, VR.IS, SERIES_NUMBER);
  }

  /**
   * Brings back the newest saved marks of the image shown in {@code view} as editable drawings,
   * once per saved version: also when the saved marks finish loading after the image appeared.
   */
  static void restore(ViewCanvas<DicomImageElement> view, MediaSeries<DicomImageElement> series) {
    DicomImageElement img = view.getImage();
    if (img == null || series == null) {
      return;
    }
    Optional<PRSpecialElement> pr = newest(DicomModel.getPrSpecialElements(series, img));
    if (pr.isEmpty()) {
      return;
    }
    Attributes dcm = pr.get().getMediaReader().getDicomObject();
    String sop = dcm.getString(Tag.SOPInstanceUID);
    if (sop == null || sop.equals(held.get(img))) {
      return;
    }
    GraphicModel saved = PrGraphicUtil.getPresentationModel(dcm);
    held.put(img, sop);
    if (saved == null) {
      return;
    }
    GraphicModel model = view.getGraphicManager();
    List<Graphic> previous = restoredGraphics.remove(img);
    if (previous != null) {
      previous.forEach(model::removeGraphic);
    }
    List<Graphic> added = new ArrayList<>();
    for (Graphic graphic : saved.getModels()) {
      AbstractGraphicModel.addGraphicToModel(view, null, graphic); // the drawing's own layer
      added.add(graphic);
    }
    restoredGraphics.put(img, added);
    view.getJComponent().repaint();
  }

  /** The outcome of one save, for the message to the user. */
  record Outcome(int saved, int failed, String problem) {}

  /**
   * Saves the marks of every image in the viewer that has marks, or had saved ones, into the study
   * library, and shows the new objects in the explorer.
   */
  static Outcome save(ImageViewerPlugin<DicomImageElement> container) {
    Set<MediaSeries<DicomImageElement>> seriesList = new LinkedHashSet<>();
    for (ViewCanvas<DicomImageElement> v : container.getImagePanels()) {
      if (v.getSeries() != null) {
        seriesList.add(v.getSeries());
      }
    }
    Map<String, String> seriesOfStudy = new java.util.HashMap<>();
    List<File> stored = new ArrayList<>();
    int failed = 0;
    String problem = null;
    DicomModel model = null;
    for (MediaSeries<DicomImageElement> series : seriesList) {
      for (DicomImageElement img : series.copyOfMedias(null, null)) {
        GraphicModel marks = (GraphicModel) img.getTagValue(TagW.PresentationModel);
        boolean has = marks != null && marks.hasSerializableGraphics();
        if (!has && !held.containsKey(img)) {
          continue;
        }
        GraphicModel toWrite = marks != null ? marks : new XmlGraphicModel(img);
        String study = TagD.getTagValue(img, Tag.StudyInstanceUID, String.class);
        String seriesUid =
            seriesOfStudy.computeIfAbsent(
                String.valueOf(study),
                _ -> marksSeries(series, img).orElseGet(UIDUtils::createUID));
        String sop = UIDUtils.createUID();
        try {
          Path file = write(toWrite, img, seriesUid, sop);
          held.put(img, sop);
          restoredGraphics.remove(img); // what is drawn now is what was saved
          stored.add(file.toFile());
          if (series.getTagValue(TagW.ExplorerModel) instanceof DicomModel m) {
            model = m;
          }
        } catch (IOException | RuntimeException e) {
          failed++;
          problem = e.getMessage();
          LOGGER.error("Marks not saved on one image", e);
        }
      }
    }
    if (model != null && !stored.isEmpty()) {
      DicomModel.LOADING_EXECUTOR.execute(
          new LoadLocalDicom(stored.toArray(File[]::new), false, model, OpeningViewer.NONE));
    }
    return new Outcome(stored.size(), failed, problem);
  }

  /** Save marks, from the menu or Ctrl+S: saves and says what happened. */
  static void saveAndTell(ImageViewerPlugin<DicomImageElement> container) {
    if (container == null) {
      return;
    }
    Outcome o = save(container);
    String title = Messages.getString("SavedMarks.title");
    Component parent = container.getComponent();
    if (o.failed() > 0) {
      JOptionPane.showMessageDialog(
          parent,
          Messages.getString("SavedMarks.failed").formatted(o.failed(), o.problem()),
          title,
          JOptionPane.ERROR_MESSAGE);
    } else if (o.saved() == 0) {
      JOptionPane.showMessageDialog(
          parent, Messages.getString("SavedMarks.none"), title, JOptionPane.INFORMATION_MESSAGE);
    } else {
      JOptionPane.showMessageDialog(
          parent,
          Messages.getString("SavedMarks.saved").formatted(o.saved()),
          title,
          JOptionPane.INFORMATION_MESSAGE);
    }
  }

  /** The series of marks this viewer already saved in the study, so every save joins it. */
  private static Optional<String> marksSeries(
      MediaSeries<DicomImageElement> series, DicomImageElement img) {
    return newest(DicomModel.getPrSpecialElements(series, img))
        .map(pr -> pr.getMediaReader().getDicomObject().getString(Tag.SeriesInstanceUID));
  }

  private static Path write(GraphicModel marks, DicomImageElement img, String seriesUid, String sop)
      throws IOException {
    return StudyLibrary.getInstance()
        .store()
        .saveDerived(
            file -> {
              if (DicomPrSerializer.writeLabelled(
                      marks, img, file.toFile(), seriesUid, sop, SavedMarks::label)
                  == null) {
                throw new IOException("The marks could not be written");
              }
            })
        .instance()
        .file();
  }

  /** Save marks and Show marks, at the end of the Draw menu. */
  static void addMenuItems(JPopupMenu menu, EventManager eventManager) {
    ImageViewerPlugin<DicomImageElement> container = eventManager.getSelectedView2dContainer();
    menu.addSeparator();
    JMenuItem save = new javax.swing.JMenuItem(Messages.getString("SavedMarks.save"));
    save.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK));
    save.addActionListener(_ -> saveAndTell(eventManager.getSelectedView2dContainer()));
    save.setEnabled(container != null);
    menu.add(save);
    JCheckBoxMenuItem show =
        new javax.swing.JCheckBoxMenuItem(Messages.getString("SavedMarks.show"), shown(container));
    show.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_D, 0));
    show.addActionListener(
        _ -> setShown(eventManager.getSelectedView2dContainer(), show.isSelected()));
    show.setEnabled(container != null);
    menu.add(show);
  }

  /** Whether marks are shown in the selected view of {@code container}. */
  static boolean shown(ImageViewerPlugin<DicomImageElement> container) {
    ViewCanvas<DicomImageElement> view =
        container == null ? null : container.getSelectedViewCanvas();
    return view == null
        || LangUtil.nullToTrue((Boolean) view.getActionValue(ActionW.DRAWINGS.cmd()));
  }

  /** Shows or hides every mark in every view of {@code container}; the pixels never change. */
  static void setShown(ImageViewerPlugin<DicomImageElement> container, boolean visible) {
    if (container == null) {
      return;
    }
    for (ViewCanvas<DicomImageElement> v : container.getImagePanels()) {
      v.setDrawingsVisibility(visible);
    }
    ViewCanvas<DicomImageElement> view = container.getSelectedViewCanvas();
    if (view != null) {
      EventManager.getInstance()
          .fireSeriesViewerListeners(
              new SeriesViewerEvent(
                  container, view.getSeries(), view.getImage(), EVENT.SELECT_VIEW));
    }
  }

  /** A note at the top of an image whose marks are hidden, so nobody forgets they exist. */
  static void paintHiddenNote(ViewCanvas<DicomImageElement> view, Graphics2D g2d) {
    if (LangUtil.nullToTrue((Boolean) view.getActionValue(ActionW.DRAWINGS.cmd()))) {
      return;
    }
    String text = Messages.getString("SavedMarks.hidden");
    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    FontMetrics fm = g2d.getFontMetrics();
    int pad = GuiUtils.getScaleLength(6);
    int w = fm.stringWidth(text) + 2 * pad;
    int h = fm.getHeight() + pad;
    int x = (view.getJComponent().getWidth() - w) / 2;
    int y = GuiUtils.getScaleLength(28);
    g2d.setColor(new Color(0x03, 0x45, 0x37, 230));
    g2d.fillRoundRect(x, y, w, h, h, h);
    g2d.setColor(Color.WHITE);
    g2d.drawString(text, x + pad, y + pad / 2 + fm.getAscent());
  }
}
