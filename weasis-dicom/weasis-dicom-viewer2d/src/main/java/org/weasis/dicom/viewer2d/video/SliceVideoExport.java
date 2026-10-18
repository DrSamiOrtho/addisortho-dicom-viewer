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

import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.util.Optional;
import javax.swing.JOptionPane;
import org.weasis.core.api.gui.util.ActionW;
import org.weasis.core.api.gui.util.SliderCineListener;
import org.weasis.core.ui.editor.image.DefaultView2d;
import org.weasis.core.ui.editor.image.ImageViewerEventManager;
import org.weasis.core.ui.editor.image.ViewTransferHandler;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.viewer2d.Messages;
import org.weasis.dicom.viewer2d.video.VideoExportDialog.SliceSettings;

/**
 * Video of a stack scroll in a 2D or MPR view: the view moves through the chosen slices with its
 * current zoom, window and annotations, as the view export draws it. The patient name and the other
 * identifying annotations are left out unless the user unticks that option.
 */
public final class SliceVideoExport {

  private SliceVideoExport() {}

  /** Asks the settings and records the video of the view, which must be the selected one. */
  public static void export(DefaultView2d<DicomImageElement> view) {
    ImageViewerEventManager<DicomImageElement> manager = view.getEventManager();
    Optional<SliderCineListener> scroll = manager.getAction(ActionW.SCROLL_SERIES);
    if (scroll.isEmpty() || view.getSeries() == null || manager.getSelectedViewPane() != view) {
      JOptionPane.showMessageDialog(
          view,
          Messages.getString("video.noSeries"),
          Messages.getString("video.title"),
          JOptionPane.WARNING_MESSAGE);
      return;
    }
    SliderCineListener cine = scroll.get();
    cine.stop();
    int min = cine.getSliderMin();
    int max = cine.getSliderMax();
    int current = cine.getSliderValue();
    Optional<SliceSettings> asked =
        VideoExportDialog.askSlices(view, max - min + 1, current - min + 1);
    if (asked.isEmpty()) {
      return;
    }
    SliceSettings s = asked.get();
    int[] slices = FrameSequence.sliceIndices(s.first(), s.last(), s.step());
    VideoRecorder.record(
        view,
        s.file(),
        s.format(),
        s.fps(),
        slices.length,
        i -> {
          cine.setSliderValue(min + slices[i] - 1);
          RenderedImage image = ViewTransferHandler.createComponentImage(view, s.anonymize());
          return image instanceof BufferedImage b ? b : null;
        },
        () -> cine.setSliderValue(current));
  }
}
