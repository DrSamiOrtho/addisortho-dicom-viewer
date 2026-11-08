/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer.library;

import java.awt.Component;
import java.awt.GridLayout;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.dicom.explorer.Messages;

/** Asks whose pictures these are before a copy of them is kept. */
final class PictureConfirmDialog {
  private static final int FIELD_COLUMNS = 28;

  private PictureConfirmDialog() {}

  /**
   * @return the confirmed identity, or empty when the user does not want them kept
   */
  static Optional<PictureIdentity> ask(Component parent, List<Path> pictures) {
    JTextField name = new JTextField(FIELD_COLUMNS);
    JTextField id = new JTextField(FIELD_COLUMNS);
    JTextField date = new JTextField(FIELD_COLUMNS);
    JPanel fields = new JPanel(new GridLayout(0, 1, 0, GuiUtils.getScaleLength(4)));
    fields.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
    fields.add(labelled("library.picture.name", name));
    fields.add(name);
    fields.add(labelled("library.picture.id", id));
    fields.add(id);
    fields.add(labelled("library.picture.date", date));
    fields.add(date);

    String intro =
        MessageFormat.format(
            Messages.getString("library.picture.intro"),
            pictures.size(),
            pictures.getFirst().getFileName());
    Object[] content = {intro, fields, Messages.getString("library.picture.note")};
    String keep = Messages.getString("library.picture.keep");
    String no = Messages.getString("library.picture.decline");
    String title = Messages.getString("library.picture.title");
    while (true) {
      int choice =
          JOptionPane.showOptionDialog(
              parent,
              content,
              title,
              JOptionPane.DEFAULT_OPTION,
              JOptionPane.QUESTION_MESSAGE,
              null,
              new Object[] {keep, no},
              no);
      if (choice != 0) {
        return Optional.empty();
      }
      try {
        PictureIdentity identity =
            PictureIdentity.parse(name.getText(), id.getText(), date.getText(), LocalDate.now());
        if (!identity.isAnonymous() || confirmedAnonymous(parent, title)) {
          return Optional.of(identity);
        }
      } catch (PictureIdentity.Invalid e) {
        JOptionPane.showMessageDialog(
            parent, Messages.getString(e.getMessage()), title, JOptionPane.WARNING_MESSAGE);
      }
    }
  }

  private static JLabel labelled(String key, JTextField field) {
    JLabel label = new JLabel(Messages.getString(key));
    label.setLabelFor(field);
    return label;
  }

  private static boolean confirmedAnonymous(Component parent, String title) {
    return JOptionPane.showConfirmDialog(
            parent,
            Messages.getString("library.picture.anonymous"),
            title,
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE)
        == JOptionPane.YES_OPTION;
  }
}
