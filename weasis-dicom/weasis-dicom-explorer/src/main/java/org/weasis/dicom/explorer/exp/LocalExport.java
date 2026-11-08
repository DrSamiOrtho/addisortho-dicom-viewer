/*
 * Copyright (c) 2009-2020 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
// Modified by Addis Ortho Trading PLC, 2026.
package org.weasis.dicom.explorer.exp;

import com.formdev.flatlaf.util.SystemFileChooser;
import com.formdev.flatlaf.util.SystemFileChooser.FileNameExtensionFilter;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ItemEvent;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSlider;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.border.TitledBorder;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.imageio.codec.TransferSyntaxType;
import org.dcm4che3.img.util.DicomUtils;
import org.dcm4che3.media.DicomDirWriter;
import org.dcm4che3.media.RecordType;
import org.dcm4che3.util.UIDUtils;
import org.opencv.core.MatOfInt;
import org.opencv.imgcodecs.Imgcodecs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.explorer.ObservableEvent;
import org.weasis.core.api.gui.util.AbstractItemDialogPage;
import org.weasis.core.api.gui.util.ActionW;
import org.weasis.core.api.gui.util.AppProperties;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.api.gui.util.WinUtil;
import org.weasis.core.api.image.SimpleOpManager;
import org.weasis.core.api.image.WindowOp;
import org.weasis.core.api.image.ZoomOp;
import org.weasis.core.api.media.data.MediaElement;
import org.weasis.core.api.media.data.MediaSeries;
import org.weasis.core.api.media.data.MediaSeriesGroup;
import org.weasis.core.api.media.data.Series;
import org.weasis.core.api.media.data.TagW;
import org.weasis.core.api.util.FontItem;
import org.weasis.core.ui.model.GraphicModel;
import org.weasis.core.ui.serialize.XmlSerializer;
import org.weasis.core.ui.util.SavedFile;
import org.weasis.core.util.FileUtil;
import org.weasis.core.util.LangUtil;
import org.weasis.core.util.StreamUtil;
import org.weasis.core.util.StringUtil;
import org.weasis.core.util.StringUtil.Suffix;
import org.weasis.core.util.ZipUtil;
import org.weasis.dicom.codec.DcmMediaReader;
import org.weasis.dicom.codec.DicomElement;
import org.weasis.dicom.codec.DicomElement.DicomExportParameters;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.codec.DicomSeries;
import org.weasis.dicom.codec.FileExtractor;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.codec.TransferSyntax;
import org.weasis.dicom.codec.display.WindowAndPresetsOp;
import org.weasis.dicom.codec.utils.DicomFileLabel;
import org.weasis.dicom.explorer.DicomModel;
import org.weasis.dicom.explorer.DiscDrive;
import org.weasis.dicom.explorer.HangingProtocols;
import org.weasis.dicom.explorer.LoadLocalDicom;
import org.weasis.dicom.explorer.LocalPersistence;
import org.weasis.dicom.explorer.Messages;
import org.weasis.dicom.explorer.imp.DicomDirLoader;
import org.weasis.dicom.explorer.library.StudyStore;
import org.weasis.dicom.explorer.pr.DicomPrSerializer;
import org.weasis.dicom.param.AttributeEditor;
import org.weasis.dicom.param.DefaultAttributeEditor;
import org.weasis.opencv.data.PlanarImage;
import org.weasis.opencv.op.ImageConversion;
import org.weasis.opencv.op.ImageIOHandler;

/**
 * Saves the selection to a folder, a zip or, through {@code IsoImageExport}, a disc image.
 *
 * <p>An export is many separate writes, not one transaction. If it is interrupted: every file that
 * has its real name is complete, because each is written under a hidden ".saving-" name first; a
 * crash can leave one such hidden file, which is safe to delete. A DICOMDIR is finished only when
 * its writer closes, so a folder from an interrupted export can hold files its DICOMDIR does not
 * list, and is not a finished export. A zip or disc image gets its real name only when complete,
 * and its working folder is removed afterwards. Exporting again makes a new patient folder.
 */
public class LocalExport extends AbstractItemDialogPage implements ExportDicom {
  private static final Logger LOGGER = LoggerFactory.getLogger(LocalExport.class);

  public static final String LAST_DIR = "lastExportDir";
  public static final String INC_DICOMDIR = "exp.include.dicomdir";
  public static final String KEEP_INFO_DIR = "exp.keep.dir.name";
  public static final String NAMED_FILES = "exp.named.files";
  private static final String DICOM_EXTENSION = ".dcm"; // NON-NLS
  private static final int MEDIA_ID_LENGTH = 8;
  private static final long MEDIA_ID_VALUES = 2_821_109_907_456L; // 36 to the power 8
  public static final String IMG_QUALITY = "exp.img.quality";
  public static final String IMG_JXL_QUALITY = "exp.img.jxl.quality";
  public static final String IMG_16_BIT = "exp.16-bit"; // NON-NLS
  public static final String IMG_PIXEL_PADDING = "exp.padding"; // NON-NLS
  public static final String IMG_SHUTTER = "exp.shutter"; // NON-NLS
  public static final String IMG_OVERLAY = "exp.overlay"; // NON-NLS
  public static final String DICOM_TSUID = "exp.dicom.tsuid"; // NON-NLS
  public static final String DICOM_ONLY_RAW = "exp.dicom.only.raw"; // NON-NLS
  public static final String DICOM_NEW_UID = "exp.dicom.new.uid"; // NON-NLS
  public static final String CD_COMPATIBLE = "exp.cd";

  /** Addis Ortho: a single patient's export goes into a folder named after the patient. */
  public static final String PATIENT_FOLDER = "exp.patient.folder"; // NON-NLS

  public enum Format {
    DICOM("DICOM", "dcm"),
    DICOM_ZIP("DICOM ZIP", "zip"), // NON-NLS
    JPEG("JPEG Lossy", "jpg"), // NON-NLS
    JPEG_XL("JPEG XL", "jxl"), // NON-NLS
    PNG("PNG", "png"),
    TIFF("TIFF", "tif"),
    JP2("JPEG 2000", "jp2"); // NON-NLS

    private final String title;
    private final String extension;

    Format(String title, String extension) {
      this.title = title;
      this.extension = extension;
    }

    @Override
    public String toString() {
      return title;
    }

    public String getTitle() {
      return title;
    }

    public String getExtension() {
      return extension;
    }
  }

  protected final DicomModel dicomModel;
  protected File outputFolder;
  protected final ExportTree exportTree;
  protected final List<TransferSyntax> transferSyntaxList =
      List.of(
          TransferSyntax.NONE,
          TransferSyntax.EXPLICIT_VR_LE,
          TransferSyntax.JPEG_LOSSY_8,
          TransferSyntax.JPEG_LOSSY_12,
          TransferSyntax.JPEG_LOSSLESS_70,
          TransferSyntax.JPEGLS_LOSSLESS,
          TransferSyntax.JPEGLS_NEAR_LOSSLESS,
          TransferSyntax.JPEG2000_LOSSLESS,
          TransferSyntax.JPEG2000,
          TransferSyntax.JPEGXL_LOSSLESS,
          TransferSyntax.JPEGXL_RECOMPRESSION,
          TransferSyntax.JPEGXL);
  protected final JComboBox<Format> comboBoxImgFormat;
  private final JCheckBox checkBoxPatientFolder =
      new JCheckBox(Messages.getString("LocalExport.patient_folder"));

  public LocalExport(DicomModel dicomModel, CheckTreeModel treeModel) {
    this(Messages.getString("LocalExport.local_dev"), 0, dicomModel, treeModel);
  }

  public LocalExport(
      String title, int pagePosition, DicomModel dicomModel, CheckTreeModel treeModel) {
    super(title, pagePosition);
    this.dicomModel = dicomModel;
    this.exportTree = new ExportTree(treeModel);
    this.comboBoxImgFormat = new JComboBox<>(new DefaultComboBoxModel<>(Format.values()));
    initGUI();
  }

  public void initGUI() {
    JLabel lblImportAFolder = new JLabel(Messages.getString("LocalExport.exp") + StringUtil.COLON);
    JButton btnNewButton = new JButton(Messages.getString("LocalExport.options"));
    btnNewButton.addActionListener(e -> showExportingOptions());

    List<JComponent> list = new ArrayList<>();
    list.add(lblImportAFolder);
    list.add(comboBoxImgFormat);
    list.add(GuiUtils.boxHorizontalStrut(ITEM_SEPARATOR));
    list.add(btnNewButton);
    list.addAll(getAdditionalOption());

    add(GuiUtils.getFlowLayoutPanel(ITEM_SEPARATOR_SMALL, 0, list.toArray(JComponent[]::new)));
    add(GuiUtils.boxVerticalStrut(ITEM_SEPARATOR));
    exportTree.setBorder(UIManager.getBorder("ScrollPane.border"));
    add(exportTree);
  }

  protected List<JComponent> getAdditionalOption() {
    // Untick for an export leaving the hospital: the folder or zip is then not named.
    checkBoxPatientFolder.setToolTipText(Messages.getString("LocalExport.patient_folder_tip"));
    checkBoxPatientFolder.setSelected(
        Boolean.parseBoolean(getPreferences().getProperty(PATIENT_FOLDER, "true")));
    checkBoxPatientFolder.addActionListener(
        _ ->
            getPreferences()
                .setProperty(PATIENT_FOLDER, String.valueOf(checkBoxPatientFolder.isSelected())));
    return List.of(GuiUtils.boxHorizontalStrut(ITEM_SEPARATOR), checkBoxPatientFolder);
  }

  /**
   * The patient of the selection as "Firstname Middlename Lastname ID", or null when the selection
   * holds several patients or none.
   */
  public static String getSelectedPatient(CheckTreeModel model) {
    MediaSeriesGroup pt = selectedPatientGroup(model);
    if (pt == null) {
      return null;
    }
    String id = TagD.getTagValue(pt, Tag.PatientID, String.class);
    String text = (fullName(pt) + " " + (id == null ? "" : id)).trim();
    return text.isEmpty() ? null : text;
  }

  private static String fullName(MediaSeriesGroup pt) {
    return fullName(TagD.getTagValue(pt, Tag.PatientName, String.class));
  }

  static String fullName(String dicomName) {
    return DicomFileLabel.fullName(dicomName);
  }

  /**
   * The name of the folder, zip or disc image of the selection: "Full Name MRN 00123 2026-01-15",
   * or null when the selection holds several patients or none, or names nobody.
   */
  public static String getSelectedPatientLabel(CheckTreeModel model) {
    MediaSeriesGroup pt = selectedPatientGroup(model);
    if (pt == null) {
      return null;
    }
    String label =
        DicomFileLabel.folderLabel(
            knownOrEmpty(fullName(pt)),
            knownOrEmpty(TagD.getTagValue(pt, Tag.PatientID, String.class)),
            selectedImageDates(model));
    return label.isEmpty() ? null : label;
  }

  /** The viewer writes "UNKNOWN" for a missing identity; on a file name that would be invented. */
  private static String knownOrEmpty(String value) {
    return value == null || TagW.NO_VALUE.equals(value) ? "" : value;
  }

  /** The dates of every selected image: a series can run past midnight. */
  private static List<DicomFileLabel.ImageDate> selectedImageDates(CheckTreeModel model) {
    List<DicomFileLabel.ImageDate> dates = new ArrayList<>();
    TreePath[] paths = model.getCheckingPaths();
    if (paths != null) {
      for (TreePath path : paths) {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        if (node.getUserObject() instanceof DicomElement dcm) {
          try {
            dates.addAll(DicomFileLabel.imageDates(dcm.getMediaReader().getDicomObject()));
          } catch (RuntimeException e) {
            LOGGER.warn(
                "Cannot read the date of a selected image: {}", e.getClass().getSimpleName());
          }
        }
      }
    }
    return dates;
  }

  private static MediaSeriesGroup selectedPatientGroup(CheckTreeModel model) {
    Set<MediaSeriesGroup> patients = new LinkedHashSet<>();
    TreePath[] paths = model.getCheckingPaths();
    if (paths != null) {
      for (TreePath path : paths) {
        if (path.getPathCount() > 1
            && path.getPathComponent(1) instanceof DefaultMutableTreeNode node
            && node.getUserObject() instanceof MediaSeriesGroup pt) {
          patients.add(pt);
        }
      }
    }
    return patients.size() == 1 ? patients.iterator().next() : null;
  }

  /** A folder named after the patient inside {@code parent}, numbered if one already exists. */
  static File patientFolder(File parent, String patient) {
    String name = FileUtil.getValidFileNameWithoutHTML(patient).trim();
    if (name.isEmpty()) {
      return parent;
    }
    File dir = new File(parent, name);
    for (int i = 2; dir.exists(); i++) {
      dir = new File(parent, name + " (" + i + ")");
    }
    return dir;
  }

  protected Properties getPreferences() {
    return LocalPersistence.getProperties();
  }

  protected void showExportingOptions() {
    Properties pref = getPreferences();
    boolean forceDICOMDIR =
        Boolean.parseBoolean(pref.getProperty("force.dicomdir", Boolean.FALSE.toString()));
    final JCheckBox boxKeepNames =
        new JCheckBox(
            Messages.getString("LocalExport.keep_dir"),
            Boolean.parseBoolean(pref.getProperty(KEEP_INFO_DIR, Boolean.TRUE.toString())));

    Format selected = (Format) comboBoxImgFormat.getSelectedItem();
    boolean dicomZip = Format.DICOM_ZIP == selected;
    if (Format.DICOM == selected || dicomZip) {
      if (dicomZip) {
        forceDICOMDIR = true;
      }

      TransferSyntax tsuid =
          TransferSyntax.getTransferSyntax(
              pref.getProperty(DICOM_TSUID, TransferSyntax.NONE.name()));
      boolean jxl =
          tsuid == TransferSyntax.JPEGXL_LOSSLESS
              || tsuid == TransferSyntax.JPEGXL_RECOMPRESSION
              || tsuid == TransferSyntax.JPEGXL;
      String qualityKey = jxl ? IMG_JXL_QUALITY : IMG_QUALITY;
      List<Component> options = new ArrayList<>();
      JSlider slider = buildQualitySlider(pref, qualityKey);
      JCheckBox onlyUncompressed =
          new JCheckBox(
              Messages.getString("transcode.only.uncompressed"),
              Boolean.parseBoolean(pref.getProperty(DICOM_ONLY_RAW, Boolean.TRUE.toString())));
      JComboBox<TransferSyntax> syntaxComboBox = new JComboBox<>(new Vector<>(transferSyntaxList));
      syntaxComboBox.setSelectedItem(null);
      syntaxComboBox.addItemListener(
          e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) {
              TransferSyntax t = (TransferSyntax) e.getItem();
              boolean realTsuid = t != null && t != TransferSyntax.NONE;
              slider.setEnabled(
                  realTsuid
                      && TransferSyntaxType.isLossyCompression(t.getTransferSyntaxUID())
                      && t != TransferSyntax.JPEGXL_RECOMPRESSION);
              onlyUncompressed.setEnabled(
                  realTsuid && !DicomUtils.isNative(t.getTransferSyntaxUID()));
            }
          });

      syntaxComboBox.setSelectedItem(tsuid);
      JPanel transcodingPanel =
          GuiUtils.getVerticalBoxLayoutPanel(
              GuiUtils.getFlowLayoutPanel(syntaxComboBox),
              GuiUtils.getFlowLayoutPanel(onlyUncompressed),
              slider);
      Border spaceY = GuiUtils.getEmptyBorder(10, 5, 10, 5);
      transcodingPanel.setBorder(
          BorderFactory.createCompoundBorder(
              spaceY, GuiUtils.getTitledBorder(Messages.getString("transcoding"))));
      options.add(transcodingPanel);

      JCheckBox newUidCheckBox =
          new JCheckBox(
              Messages.getString("generate.new.unique.identifiers"),
              Boolean.parseBoolean(pref.getProperty(DICOM_NEW_UID, Boolean.FALSE.toString())));
      options.add(newUidCheckBox);

      final JCheckBox box1 =
          new JCheckBox(
              Messages.getString("LocalExport.inc_dicomdir"),
              Boolean.parseBoolean(pref.getProperty(INC_DICOMDIR, Boolean.TRUE.toString())));
      final JCheckBox box2 =
          new JCheckBox(
              Messages.getString("LocalExport.cd_folders"),
              Boolean.parseBoolean(pref.getProperty(CD_COMPATIBLE, Boolean.FALSE.toString())));
      final JCheckBox boxNamed =
          new JCheckBox(
              Messages.getString("LocalExport.named_files"),
              Boolean.parseBoolean(pref.getProperty(NAMED_FILES, Boolean.TRUE.toString())));
      Runnable syncBoxes =
          () -> {
            // A DICOMDIR can only point to short names, so it goes with the short names.
            box1.setEnabled(!boxNamed.isSelected());
            box2.setEnabled(!boxNamed.isSelected() && box1.isSelected());
            boxKeepNames.setEnabled(boxNamed.isSelected() || !box1.isSelected());
          };
      syncBoxes.run();
      if (!forceDICOMDIR) {
        options.add(boxNamed);
        options.add(box1);
        options.add(box2);
        options.add(boxKeepNames);
      }
      box1.addActionListener(e -> syncBoxes.run());
      boxNamed.addActionListener(e -> syncBoxes.run());

      int response =
          JOptionPane.showOptionDialog(
              WinUtil.getValidComponent(
                  WinUtil.getParentWindow(this)), // Use parent because this has large size
              options.toArray(),
              Messages.getString("LocalExport.export_message"),
              JOptionPane.OK_CANCEL_OPTION,
              JOptionPane.PLAIN_MESSAGE,
              null,
              null,
              null);
      if (response == JOptionPane.OK_OPTION) {
        pref.setProperty(qualityKey, String.valueOf(slider.getValue()));
        pref.setProperty(
            DICOM_TSUID,
            StringUtil.getEmptyStringIfNullEnum((TransferSyntax) syntaxComboBox.getSelectedItem()));
        pref.setProperty(DICOM_ONLY_RAW, String.valueOf(onlyUncompressed.isSelected()));
        pref.setProperty(DICOM_NEW_UID, String.valueOf(newUidCheckBox.isSelected()));
        if (!forceDICOMDIR) {
          pref.setProperty(NAMED_FILES, String.valueOf(boxNamed.isSelected()));
          pref.setProperty(INC_DICOMDIR, String.valueOf(box1.isSelected()));
          pref.setProperty(KEEP_INFO_DIR, String.valueOf(boxKeepNames.isSelected()));
          pref.setProperty(CD_COMPATIBLE, String.valueOf(box2.isSelected()));
        }
      }
    } else if (Format.JPEG == selected
        || Format.PNG == selected
        || Format.TIFF == selected
        || Format.JP2 == selected
        || Format.JPEG_XL == selected) {
      boolean jxl = Format.JPEG_XL == selected;
      boolean lossy = Format.JPEG == selected || jxl;
      String qualityKey = jxl ? IMG_JXL_QUALITY : IMG_QUALITY;
      JSlider slider = null;
      JCheckBox preservePixelCheckBox;
      String dicom = "DICOM "; // NON-NLS
      JCheckBox paddingCheckBox =
          new JCheckBox(
              dicom + ActionW.IMAGE_PIX_PADDING.getTitle(),
              Boolean.parseBoolean(pref.getProperty(IMG_PIXEL_PADDING, Boolean.TRUE.toString())));
      JCheckBox overlayCheckBox =
          new JCheckBox(
              dicom + ActionW.IMAGE_OVERLAY.getTitle(),
              Boolean.parseBoolean(pref.getProperty(IMG_OVERLAY, Boolean.TRUE.toString())));
      JCheckBox shutterCheckBox =
          new JCheckBox(
              dicom + ActionW.IMAGE_SHUTTER.getTitle(),
              Boolean.parseBoolean(pref.getProperty(IMG_SHUTTER, Boolean.TRUE.toString())));
      List<Component> options = new ArrayList<>();

      if (lossy) {
        slider = buildQualitySlider(pref, qualityKey);
        options.add(slider);
      }

      preservePixelCheckBox =
          new JCheckBox(
              Messages.getString("LocalExport.ch_16"),
              Boolean.parseBoolean(pref.getProperty(IMG_16_BIT, Boolean.FALSE.toString())));
      preservePixelCheckBox.setEnabled(Format.JPEG != selected);
      options.add(preservePixelCheckBox);
      options.add(paddingCheckBox);
      options.add(shutterCheckBox);
      options.add(overlayCheckBox);
      options.add(boxKeepNames);

      int response =
          JOptionPane.showOptionDialog(
              WinUtil.getValidComponent(
                  WinUtil.getParentWindow(this)), // Use parent because this has large size
              options.toArray(),
              Messages.getString("LocalExport.export_message"),
              JOptionPane.OK_CANCEL_OPTION,
              JOptionPane.PLAIN_MESSAGE,
              null,
              null,
              null);
      if (response == JOptionPane.OK_OPTION) {
        if (slider != null) {
          pref.setProperty(qualityKey, String.valueOf(slider.getValue()));
        }
        pref.setProperty(IMG_16_BIT, String.valueOf(preservePixelCheckBox.isSelected()));
        pref.setProperty(IMG_PIXEL_PADDING, String.valueOf(paddingCheckBox.isSelected()));
        pref.setProperty(IMG_SHUTTER, String.valueOf(shutterCheckBox.isSelected()));
        pref.setProperty(IMG_OVERLAY, String.valueOf(overlayCheckBox.isSelected()));
        pref.setProperty(KEEP_INFO_DIR, String.valueOf(boxKeepNames.isSelected()));
      }
    }
  }

  private static JSlider buildQualitySlider(Properties pref, String qualityKey) {
    int defaultQuality = StringUtil.getInt(pref.getProperty(qualityKey, null), 85);
    JSlider slider = new JSlider(1, 100, defaultQuality);
    TitledBorder titledBorder =
        new TitledBorder(
            BorderFactory.createEmptyBorder(),
            Messages.getString("LocalExport.jpeg_quality")
                + StringUtil.COLON_AND_SPACE
                + slider.getValue(),
            TitledBorder.LEADING,
            TitledBorder.DEFAULT_POSITION,
            FontItem.MEDIUM.getFont(),
            null);
    slider.setBorder(titledBorder);
    slider.setPaintTicks(true);
    slider.setSnapToTicks(false);
    slider.setMajorTickSpacing(10);
    slider.addChangeListener(
        e -> {
          JSlider source = (JSlider) e.getSource();
          ((TitledBorder) slider.getBorder())
              .setTitle(
                  Messages.getString("LocalExport.jpeg_quality")
                      + StringUtil.COLON_AND_SPACE
                      + source.getValue());
          slider.repaint();
        });
    return slider;
  }

  public void browseImgFile(Format format) {
    browseImgFile(format, null);
  }

  /**
   * @param patient the patient of the selection, or null: names the zip file
   */
  public void browseImgFile(Format format, String patient) {
    Properties pref = getPreferences();
    String targetDirectoryPath = pref.getProperty(LAST_DIR, "");

    boolean isSaveFileMode = format == Format.DICOM_ZIP;

    SystemFileChooser fileChooser = new SystemFileChooser(targetDirectoryPath);

    if (isSaveFileMode) {
      fileChooser.setFileSelectionMode(SystemFileChooser.FILES_ONLY);
      fileChooser.setAcceptAllFileFilterUsed(false);
      FileNameExtensionFilter filter = new FileNameExtensionFilter("Zip file", "zip"); // NON-NLS
      fileChooser.addChoosableFileFilter(filter);
      fileChooser.setFileFilter(filter);

    } else {
      fileChooser.setFileSelectionMode(SystemFileChooser.DIRECTORIES_ONLY);
    }

    fileChooser.setMultiSelectionEnabled(false);

    // Set default selection name to enable save button
    if (StringUtil.hasText(targetDirectoryPath)) {
      File targetFile = new File(targetDirectoryPath);
      if (targetFile.exists()) {
        if (targetFile.isFile()) {
          fileChooser.setSelectedFile(targetFile);
        } else if (targetFile.isDirectory()) {
          String newExportSelectionName = Messages.getString("LocalExport.newExportSelectionName");
          fileChooser.setSelectedFile(new File(newExportSelectionName));
        }
      }
    }
    if (isSaveFileMode && patient != null) {
      String zipName = FileUtil.getValidFileNameWithoutHTML(patient) + ".zip";
      // new File("", name) would be the root of the disk on the very first export
      fileChooser.setSelectedFile(
          StringUtil.hasText(targetDirectoryPath)
              ? new File(targetDirectoryPath, zipName)
              : new File(zipName));
    }

    File selectedFile;

    if (fileChooser.showSaveDialog(WinUtil.getParentWindow(this))
            != SystemFileChooser.APPROVE_OPTION // Use parent window because this has large size
        || (selectedFile = fileChooser.getSelectedFile()) == null) {
      outputFolder = null;
    } else {
      if (isSaveFileMode) {
        outputFolder =
            ".zip".equals(FileUtil.getExtension(selectedFile.getName()))
                ? selectedFile
                : new File(selectedFile + ".zip");
      } else {
        outputFolder = selectedFile.isDirectory() ? selectedFile : selectedFile.getParentFile();
      }
      pref.setProperty(
          LAST_DIR, outputFolder.isDirectory() ? outputFolder.getPath() : outputFolder.getParent());
    }
  }

  @Override
  public void closeAdditionalWindow() {
    // Do nothing
  }

  @Override
  public void resetToDefaultValues() {
    // Do nothing
  }

  @Override
  public void exportDICOM(final CheckTreeModel model, JProgressBar info) throws IOException {
    if (!confirmSelection(model)) {
      return;
    }
    Format format = (Format) comboBoxImgFormat.getSelectedItem();
    String patient = checkBoxPatientFolder.isSelected() ? getSelectedPatientLabel(model) : null;
    browseImgFile(format, patient);
    if (outputFolder != null) {
      File chosen = outputFolder.getCanonicalFile();
      final File exportDir =
          patient == null || format == Format.DICOM_ZIP ? chosen : patientFolder(chosen, patient);
      // Pictures carry the folder's name, so a picture copied out of it still says whose it is.
      final String filePrefix = patient == null ? null : exportDir.getName();
      final ExportOutcome outcome = new ExportOutcome();
      final ExportNaming naming =
          new ExportNaming(checkBoxPatientFolder.isSelected(), patient != null);

      final ExplorerTask<Boolean, String> task =
          new ExplorerTask<>(Messages.getString("LocalExport.exporting"), false) {
            private volatile boolean onDisc;
            // ExplorerTask.cancel() runs done() again when the task has already finished
            private boolean reported;

            @Override
            protected Boolean doInBackground() throws Exception {
              dicomModel.firePropertyChange(
                  new ObservableEvent(
                      ObservableEvent.BasicAction.LOADING_START, dicomModel, null, this));
              Properties pref = getPreferences();
              if (format == Format.DICOM) {
                boolean named =
                    Boolean.parseBoolean(pref.getProperty(NAMED_FILES, Boolean.TRUE.toString()));
                writeDicom(this, exportDir, model, pref, outcome, named ? naming : null);
              } else if (format == Format.DICOM_ZIP) {
                writeDicomZip(this, exportDir, model, pref, outcome);
              } else {
                writeOther(this, exportDir, model, format, pref, filePrefix, outcome, naming);
              }
              onDisc = DiscDrive.isOnDisc(exportDir.toPath());
              // Written straight onto a CD/DVD: eject it, ready to hand over.
              if (onDisc
                  && !isCancelled()
                  && outcome.status(false, null) == ExportOutcome.Status.COMPLETED) {
                DiscDrive.eject(exportDir.toPath());
              }
              return true;
            }

            @Override
            protected void done() {
              dicomModel.firePropertyChange(
                  new ObservableEvent(
                      ObservableEvent.BasicAction.LOADING_STOP, dicomModel, null, this));
              if (!reported) {
                reported = true;
                File zipFolder = format == Format.DICOM_ZIP ? exportDir.getParentFile() : exportDir;
                reportResult(
                    exportDir,
                    onDisc ? null : zipFolder,
                    outcome,
                    failure(this).orElse(null),
                    isCancelled());
              }
            }
          };
      task.execute();
    }
  }

  /** Stops an export with nothing ticked before the user is asked where to put it. */
  protected boolean confirmSelection(CheckTreeModel model) {
    if (hasSomethingToExport(model)) {
      return true;
    }
    JOptionPane.showMessageDialog(
        WinUtil.getValidComponent(WinUtil.getParentWindow(this)),
        Messages.getString("LocalExport.empty_selection"),
        Messages.getString("DicomExport.exp_dicom"),
        JOptionPane.WARNING_MESSAGE);
    return false;
  }

  static boolean hasSomethingToExport(CheckTreeModel model) {
    TreePath[] paths = model.getCheckingPaths();
    if (paths != null) {
      for (TreePath path : paths) {
        Object object = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        if (object instanceof MediaElement
            || (object instanceof MediaSeries<?> s
                && LangUtil.nullToFalse((Boolean) s.getTagValue(TagW.ObjectToSave)))) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * The error that stopped a finished export, or empty when it ran through or was cancelled. A
   * SwingWorker keeps an exception thrown in {@code doInBackground} until someone asks for it, so
   * without this the export would end in silence with nothing written.
   */
  protected static Optional<Throwable> failure(Future<?> task) {
    if (!task.isDone() || task.isCancelled()) {
      return Optional.empty();
    }
    try {
      task.get();
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } catch (ExecutionException e) {
      return Optional.of(e.getCause() == null ? e : e.getCause());
    }
  }

  /** What the user is told when the export ends; counts only files confirmed at the destination. */
  static String resultMessage(
      ExportOutcome.Status status, File target, ExportOutcome outcome, Throwable error) {
    String problem =
        error != null
            ? ExportOutcome.reason(error)
            : outcome.firstProblem() != null
                ? outcome.firstProblem()
                : Messages.getString("LocalExport.nothing_written");
    return switch (status) {
      case COMPLETED ->
          MessageFormat.format(Messages.getString("LocalExport.done"), outcome.written(), target);
      case PARTIAL ->
          MessageFormat.format(
              Messages.getString("LocalExport.partial"),
              outcome.written(),
              target,
              outcome.failed(),
              problem);
      case FAILED ->
          MessageFormat.format(Messages.getString("LocalExport.failed"), target, problem);
      case CANCELLED ->
          MessageFormat.format(
              Messages.getString("LocalExport.cancelled"), outcome.written(), target);
    };
  }

  private void reportResult(
      File target, File folder, ExportOutcome outcome, Throwable error, boolean cancelled) {
    if (error != null) {
      LOGGER.error("Cannot export: {}", error.getClass().getSimpleName());
    }
    ExportOutcome.Status status = outcome.status(cancelled, error);
    if (status == ExportOutcome.Status.CANCELLED && outcome.written() == 0) {
      return;
    }
    Component parent = WinUtil.getValidComponent(WinUtil.getParentWindow(this));
    String title = Messages.getString("DicomExport.exp_dicom");
    String message = resultMessage(status, target, outcome, error);
    switch (status) {
      case FAILED ->
          JOptionPane.showMessageDialog(parent, message, title, JOptionPane.ERROR_MESSAGE);
      case PARTIAL ->
          SavedFile.showSaved(parent, title, message, folder, JOptionPane.WARNING_MESSAGE);
      default ->
          SavedFile.showSaved(parent, title, message, folder, JOptionPane.INFORMATION_MESSAGE);
    }
  }

  /** Writes the selection in a temporary folder, then publishes one complete, readable zip. */
  private void writeDicomZip(
      ExplorerTask task, File zipFile, CheckTreeModel model, Properties pref, ExportOutcome outcome)
      throws IOException {
    // A zip always holds a standard DICOMDIR; that must not become the user's choice for later.
    Properties zipPref = new Properties();
    zipPref.putAll(pref);
    zipPref.setProperty(INC_DICOMDIR, Boolean.TRUE.toString());
    zipPref.setProperty(CD_COMPATIBLE, Boolean.TRUE.toString());
    File writeDir =
        FileUtil.createTempDir(
                AppProperties.buildAccessibleTempDirectory("tmp", Format.DICOM_ZIP.extension))
            .toFile(); // NON-NLS
    try {
      writeDicom(task, writeDir, model, zipPref, outcome, null);
      if (!task.isCancelled() && outcome.written() > 0) {
        try {
          publishZip(writeDir.toPath(), zipFile.toPath());
        } catch (IOException | RuntimeException e) {
          LOGGER.error("Cannot export the DICOM ZIP file: {}", e.getClass().getSimpleName());
          outcome.lostAll(e);
        }
      }
    } finally {
      FileUtil.recursiveDelete(writeDir.toPath());
    }
  }

  static void publishZip(Path content, Path zipFile) throws IOException {
    long expected;
    try (Stream<Path> files = Files.walk(content)) {
      expected = files.filter(Files::isRegularFile).count();
    }
    SavedFile.write(
        zipFile,
        staged -> {
          ZipUtil.zip(content, staged);
          return expected > 0 && readableEntries(staged) == expected;
        });
  }

  /** Reads the whole archive back, which checks each entry's size and checksum. */
  private static long readableEntries(Path zip) throws IOException {
    long files = 0;
    try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
      for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
        in.transferTo(OutputStream.nullOutputStream());
        if (!entry.isDirectory()) {
          files++;
        }
      }
    }
    return files;
  }

  private static String instanceFileName(MediaElement img) {
    String iUid = makeFileIDs(TagD.getTagValue(img, Tag.SOPInstanceUID, String.class));
    Integer instance = TagD.getTagValue(img, Tag.InstanceNumber, Integer.class);
    if (instance != null) {
      String val = instance.toString();
      if (val.length() < 5) {
        char[] chars = new char[5 - val.length()];
        Arrays.fill(chars, '0');
        return new String(chars) + val + "-" + iUid;
      } else {
        return val + "-" + iUid;
      }
    }
    return iUid;
  }

  /**
   * @param filePrefix the patient folder's name, put before each picture's name and replacing the
   *     patient level of the folders; null for the plain names
   * @param outcome receives one result per file
   * @param naming the readable picture names, or null for the short names a disc needs
   */
  protected void writeOther(
      ExplorerTask task,
      File exportDir,
      CheckTreeModel model,
      Format format,
      Properties pref,
      String filePrefix,
      ExportOutcome outcome,
      ExportNaming naming) {
    String namePrefix = filePrefix == null ? "" : filePrefix + " ";
    // Short picture names are hashes too: never let two images, or an earlier export, share one.
    ExportNaming shortNames = new ExportNaming(false, false);
    boolean jxl = Format.JPEG_XL == format;
    String qualityKey = jxl ? IMG_JXL_QUALITY : IMG_QUALITY;
    boolean keepNames =
        Boolean.parseBoolean(pref.getProperty(KEEP_INFO_DIR, Boolean.TRUE.toString()));
    int jpegQuality = StringUtil.getInt(pref.getProperty(qualityKey), 85);
    boolean padding =
        Boolean.parseBoolean(pref.getProperty(IMG_PIXEL_PADDING, Boolean.TRUE.toString()));
    boolean overlay = Boolean.parseBoolean(pref.getProperty(IMG_OVERLAY, Boolean.TRUE.toString()));
    boolean shutter = Boolean.parseBoolean(pref.getProperty(IMG_SHUTTER, Boolean.TRUE.toString()));
    boolean img16 = Boolean.parseBoolean(pref.getProperty(IMG_16_BIT, Boolean.FALSE.toString()));
    if (format == Format.JPEG) {
      img16 = false;
    }

    try {
      synchronized (exportTree) {
        ArrayList<String> seriesGph = new ArrayList<>();
        TreePath[] paths = model.getCheckingPaths();
        for (TreePath treePath : paths) {
          DefaultMutableTreeNode node = (DefaultMutableTreeNode) treePath.getLastPathComponent();
          if (node.getUserObject() instanceof Series) {
            MediaSeries<?> s = (MediaSeries<?>) node.getUserObject();
            if (LangUtil.nullToFalse((Boolean) s.getTagValue(TagW.ObjectToSave))) {
              Series<?> series = (Series<?>) s.getTagValue(CheckTreeModel.SourceSeriesForPR);
              if (series != null) {
                seriesGph.add((String) series.getTagValue(TagD.get(Tag.SeriesInstanceUID)));
              }
            }
          }
        }

        for (TreePath treePath : paths) {
          if (task.isCancelled()) {
            return;
          }

          DefaultMutableTreeNode node = (DefaultMutableTreeNode) treePath.getLastPathComponent();
          Object object = node.getUserObject();

          if (object instanceof MediaElement dcm && object instanceof FileExtractor) {
            String label = instanceFileName(dcm);
            try {
              File fileSrc = ((FileExtractor) dcm).getExtractFile();
              if (fileSrc == null) {
                throw new IOException("The source file is not available");
              }
              // Get instance number instead SOPInstanceUID to handle multiframe
              String instance = namePrefix + label;
              String path = buildPath(dcm, keepNames, filePrefix != null, node);
              String extension = FileUtil.getExtension(fileSrc.getName());
              File destinationFile =
                  naming != null && object instanceof DicomElement named
                      ? namedFile(exportDir, dcm, named, keepNames, node, naming, label, extension)
                      : shortNames.uniqueFile(new File(exportDir, path), instance, extension);
              SavedFile.write(
                  destinationFile.toPath(), s -> StreamUtil.copyFile(fileSrc.toPath(), s));
              outcome.saved();
            } catch (IOException | RuntimeException e) {
              LOGGER.error("Cannot export file {}: {}", label, e.getClass().getSimpleName());
              outcome.failed(label, e);
            }
          } else if (object instanceof DicomImageElement img) {
            String label = instanceFileName(img);
            try {
              // Get instance number instead SOPInstanceUID to handle multiframe
              String instance = namePrefix + label;
              String path = buildPath(img, keepNames, filePrefix != null, node);
              File destinationFile =
                  naming == null
                      ? shortNames.uniqueFile(
                          new File(exportDir, path), instance, "." + format.extension)
                      : namedFile(
                          exportDir,
                          img,
                          img,
                          keepNames,
                          node,
                          naming,
                          label,
                          "." + format.extension);
              SimpleOpManager manager =
                  img.buildSimpleOpManager(img16, padding, shutter, overlay, 1.0);
              writePicture(manager, destinationFile.toPath(), format, jpegQuality);
              outcome.saved();
              if (seriesGph.contains(img.getTagValue(TagD.get(Tag.SeriesInstanceUID)))
                  && !XmlSerializer.writePresentation(img, destinationFile)) {
                outcome.failed(label + " annotations", new IOException("They could not be saved"));
              }
            } catch (IOException | RuntimeException e) {
              LOGGER.error(
                  "Cannot export image {} to {}: {}", label, format, e.getClass().getSimpleName());
              outcome.failed(label, e);
            }
          } else if (object instanceof MediaElement other) {
            String label = instanceFileName(other);
            outcome.failed(label, new IOException("This object cannot be saved as " + format));
          }
        }
      }
    } catch (Exception e) {
      LOGGER.error("Cannot extract media from DICOM: {}", e.getClass().getSimpleName());
      outcome.failed("export", e);
    }
  }

  private static void writePicture(
      SimpleOpManager manager, Path destination, Format format, int jpegQuality)
      throws IOException {
    Optional<PlanarImage> input = manager.getFirstNodeInputImage();
    if (input.isEmpty()) {
      throw new IOException("The image could not be read");
    }
    PlanarImage result = manager.process().orElse(input.get());
    try {
      SavedFile.write(
          destination,
          staged -> {
            if (format == Format.PNG) {
              return ImageIOHandler.writePNG(result.toMat(), staged);
            }
            MatOfInt map = new MatOfInt();
            if (format == Format.JPEG) {
              map.fromArray(Imgcodecs.IMWRITE_JPEG_QUALITY, jpegQuality);
            } else if (format == Format.JPEG_XL) {
              map.fromArray(Imgcodecs.IMWRITE_JPEGXL_QUALITY, jpegQuality);
            }
            return ImageIOHandler.writeImage(result.toMat(), staged, map);
          });
    } finally {
      if (!Objects.equals(result, input.get())) {
        ImageConversion.releasePlanarImage(result);
      }
    }
  }

  private static Attributes getUIDs(DefaultAttributeEditor editor, MediaElement dcm) {
    Attributes uidTags = new Attributes();
    uidTags.setString(Tag.PatientID, VR.LO, (String) dcm.getTagValue(TagW.PatientPseudoUID));
    uidTags.setString(
        Tag.StudyInstanceUID, VR.UI, TagD.getTagValue(dcm, Tag.StudyInstanceUID, String.class));
    uidTags.setString(
        Tag.SeriesInstanceUID, VR.UI, TagD.getTagValue(dcm, Tag.SeriesInstanceUID, String.class));
    uidTags.setString(
        Tag.SOPInstanceUID, VR.UI, TagD.getTagValue(dcm, Tag.SOPInstanceUID, String.class));
    editor.apply(uidTags, null);
    return uidTags;
  }

  /**
   * @param outcome receives one result per file
   * @param naming the readable .dcm names, or null for the short names a DICOMDIR needs
   * @throws IOException when the destination or its DICOMDIR cannot be created or finished
   */
  protected void writeDicom(
      ExplorerTask task,
      File exportDir,
      CheckTreeModel model,
      Properties pref,
      ExportOutcome outcome,
      ExportNaming naming)
      throws IOException {
    boolean keepNames;
    boolean writeDicomdir;
    boolean cdCompatible;

    TransferSyntax tsuid =
        TransferSyntax.getTransferSyntax(pref.getProperty(DICOM_TSUID, TransferSyntax.NONE.name()));
    boolean jxl =
        tsuid == TransferSyntax.JPEGXL_LOSSLESS
            || tsuid == TransferSyntax.JPEGXL_RECOMPRESSION
            || tsuid == TransferSyntax.JPEGXL;
    String qualityKey = jxl ? IMG_JXL_QUALITY : IMG_QUALITY;
    int jpegQuality = StringUtil.getInt(pref.getProperty(qualityKey), 85);
    int compressionRatio = 100 - jpegQuality; // Ratio from 0 to 99
    boolean newUID =
        Boolean.parseBoolean(pref.getProperty(DICOM_NEW_UID, Boolean.FALSE.toString()));
    boolean onlyRaw =
        Boolean.parseBoolean(pref.getProperty(DICOM_ONLY_RAW, Boolean.TRUE.toString()));
    boolean realTsuid = tsuid != TransferSyntax.NONE;
    if (realTsuid && DicomUtils.isNative(tsuid.getTransferSyntaxUID())) {
      onlyRaw = false;
    }
    DefaultAttributeEditor editor = new DefaultAttributeEditor(newUID, null);

    writeDicomdir =
        naming == null
            && Boolean.parseBoolean(pref.getProperty(INC_DICOMDIR, Boolean.TRUE.toString()));
    keepNames =
        !writeDicomdir
            && Boolean.parseBoolean(pref.getProperty(KEEP_INFO_DIR, Boolean.TRUE.toString()));
    cdCompatible = Boolean.parseBoolean(pref.getProperty(CD_COMPATIBLE, Boolean.FALSE.toString()));

    Set<String> mediaFiles = new HashSet<>();
    DicomDirWriter writer = null;
    try {
      // The patient's folder does not exist yet, and the DICOMDIR is created inside it.
      Files.createDirectories(exportDir.toPath());
      if (writeDicomdir) {
        File dcmdirFile = new File(exportDir, "DICOMDIR");
        writer = DicomDirLoader.open(dcmdirFile);
      }

      synchronized (exportTree) {
        ArrayList<String> uids = new ArrayList<>();
        TreePath[] paths = model.getCheckingPaths();
        for (TreePath treePath : paths) {
          if (task.isCancelled()) {
            return;
          }

          DefaultMutableTreeNode node = (DefaultMutableTreeNode) treePath.getLastPathComponent();
          if (node.getUserObject() instanceof DicomImageElement img) {
            Attributes uidTags = getUIDs(editor, img);
            String iuid = uidTags.getString(Tag.SOPInstanceUID);
            int index = uids.indexOf(iuid);
            if (index == -1) {
              uids.add(iuid);
            } else {
              // Write only once the file for multiframe
              continue;
            }
            File shortFile =
                naming != null
                    ? null
                    : mediaFile(
                        new File(exportDir, buildPath(img, keepNames, cdCompatible, node, uidTags)),
                        iuid,
                        keepNames,
                        mediaFiles);
            if (!keepNames) {
              iuid = makeFileIDs(iuid);
            }

            File destinationFile =
                naming == null
                    ? shortFile
                    : namedFile(
                        exportDir, img, img, keepNames, node, naming, iuid, DICOM_EXTENSION);
            DicomExportParameters dicomExportParameters =
                new DicomExportParameters(
                    tsuid, onlyRaw, getAttributeEditors(editor), jpegQuality, compressionRatio);
            saveDicomFile(img, destinationFile, dicomExportParameters, writer, node, iuid, outcome);
          } else if (node.getUserObject() instanceof DicomElement dcm) {
            Attributes uidTags = getUIDs(editor, (MediaElement) dcm);
            String iuid = uidTags.getString(Tag.SOPInstanceUID);
            MediaElement element = (MediaElement) dcm;
            File shortFile =
                naming != null
                    ? null
                    : mediaFile(
                        new File(
                            exportDir, buildPath(element, keepNames, cdCompatible, node, uidTags)),
                        iuid,
                        keepNames,
                        mediaFiles);
            if (!keepNames) {
              iuid = makeFileIDs(iuid);
            }

            File destinationFile =
                naming == null
                    ? shortFile
                    : namedFile(
                        exportDir, element, dcm, keepNames, node, naming, iuid, DICOM_EXTENSION);
            DicomExportParameters dicomExportParameters =
                new DicomExportParameters(
                    null, onlyRaw, getAttributeEditors(editor), jpegQuality, compressionRatio);
            saveDicomFile(dcm, destinationFile, dicomExportParameters, writer, node, iuid, outcome);
          } else if (node.getUserObject() instanceof Series) {
            MediaSeries<?> s = (MediaSeries<?>) node.getUserObject();
            if (LangUtil.nullToFalse((Boolean) s.getTagValue(TagW.ObjectToSave))) {
              Series<?> series = (Series<?>) s.getTagValue(CheckTreeModel.SourceSeriesForPR);
              if (series != null) {
                String seriesInstanceUID = UIDUtils.createUID();
                for (MediaElement dcm : series.getMedias(null, null)) {
                  GraphicModel grModel = (GraphicModel) dcm.getTagValue(TagW.PresentationModel);
                  if (grModel != null && grModel.hasSerializableGraphics()) {
                    String path =
                        naming == null
                            ? buildPath(dcm, keepNames, cdCompatible, node, getUIDs(editor, dcm))
                            : namedFolder(dcm, keepNames, node, naming);
                    File folder = new File(exportDir, path);
                    String prUid = UIDUtils.createUID();
                    File prFile =
                        naming == null
                            ? mediaFile(folder, prUid, keepNames, mediaFiles)
                            : naming.uniqueFile(
                                folder,
                                naming.stem(headerOf(dcm), makeFileIDs(prUid)) + " PR",
                                DICOM_EXTENSION);
                    String outputFile =
                        writePresentationState(dcm, prFile, writer, node, seriesInstanceUID, prUid);
                    if (outputFile == null) {
                      outcome.failed(
                          "presentation state", new IOException("It could not be written"));
                    } else {
                      outcome.saved();
                      File f = new File(outputFile);
                      DicomModel.LOADING_EXECUTOR.execute(
                          new LoadLocalDicom(
                              new File[] {f},
                              false,
                              dicomModel,
                              HangingProtocols.OpeningViewer.ALL_PATIENTS));
                    }
                  }
                }
              }
            }
          }
        }
      }
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      LOGGER.error("Cannot export DICOM: {}", e.getClass().getSimpleName());
      outcome.failed("export", e);
    } finally {
      if (writer != null) {
        // Commit DICOMDIR changes and close the file
        writer.close();
      }
    }
  }

  private static File mediaFile(File folder, String sop, boolean keepNames, Set<String> used) {
    return keepNames ? new File(folder, sop) : freeMediaFile(folder, sop, used);
  }

  /**
   * A short DICOMDIR-style file for one instance that no other instance has, in this export or from
   * an earlier one in the same folder. The same instance exported again takes its own file back.
   */
  static File freeMediaFile(File folder, String sop, Set<String> used) {
    for (int n = 0; ; n++) {
      File candidate = new File(folder, makeFileIDs(n == 0 ? sop : sop + "#" + n));
      if (used.add(candidate.getPath().toUpperCase(Locale.ROOT))
          && (!candidate.exists() || holdsInstance(candidate, sop))) {
        return candidate;
      }
    }
  }

  private static boolean holdsInstance(File file, String sop) {
    Attributes header = StudyStore.readHeader(file.toPath());
    return header != null && sop.equals(header.getString(Tag.SOPInstanceUID));
  }

  private static String namedFolder(
      MediaElement element, boolean keepNames, DefaultMutableTreeNode node, ExportNaming naming) {
    return keepNames ? buildPath(element, true, naming.inPatientFolder(), node) : "";
  }

  /**
   * @param fallback the name when the header cannot be read, carrying no patient details
   * @param extension with its dot, or empty
   */
  private static File namedFile(
      File exportDir,
      MediaElement element,
      DicomElement dcm,
      boolean keepNames,
      DefaultMutableTreeNode node,
      ExportNaming naming,
      String fallback,
      String extension) {
    Attributes header = null;
    try {
      header = dcm.getMediaReader().getDicomObject();
    } catch (RuntimeException e) {
      LOGGER.warn("Cannot read the header for the name of {}", fallback, e);
    }
    return naming.uniqueFile(
        new File(exportDir, namedFolder(element, keepNames, node, naming)),
        naming.stem(header, fallback),
        extension);
  }

  private static Attributes headerOf(MediaElement element) {
    try {
      return element.getMediaReader() instanceof DcmMediaReader reader
          ? reader.getDicomObject()
          : null;
    } catch (RuntimeException e) {
      LOGGER.warn("Cannot read the header of an image", e);
      return null;
    }
  }

  /** One DICOM file, which goes in the DICOMDIR only once it is complete under its real name. */
  static void saveDicomFile(
      DicomElement dcm,
      File destinationFile,
      DicomExportParameters parameters,
      DicomDirWriter writer,
      DefaultMutableTreeNode node,
      String iuid,
      ExportOutcome outcome) {
    try {
      Attributes[] written = new Attributes[1];
      SavedFile.write(
          destinationFile.toPath(),
          staged -> {
            written[0] = dcm.saveToFile(staged.toFile(), parameters);
            return written[0] != null;
          });
      if (writer != null) {
        Attributes attributes =
            written[0].isEmpty() ? dcm.getMediaReader().getDicomObject() : written[0];
        writeInDicomDir(writer, attributes, node, destinationFile);
      }
      outcome.saved();
    } catch (IOException | RuntimeException e) {
      LOGGER.error("Cannot export a DICOM file: {}", e.getClass().getSimpleName());
      outcome.failed(iuid, e);
    }
  }

  private static List<AttributeEditor> getAttributeEditors(DefaultAttributeEditor editor) {
    if (editor.isGenerateUIDs() || editor.getTagToOverride() != null) {
      return List.of(editor);
    }
    return Collections.emptyList();
  }

  public static String buildAndWritePR(
      MediaElement img,
      boolean keepNames,
      File destinationDir,
      DicomDirWriter writer,
      DefaultMutableTreeNode node,
      String seriesInstanceUID) {
    String prUid = UIDUtils.createUID();
    File outputFile = new File(destinationDir, keepNames ? prUid : makeFileIDs(prUid));
    return writePresentationState(img, outputFile, writer, node, seriesInstanceUID, prUid);
  }

  /**
   * Writes the presentation state under a temporary name and gives it its real name once complete.
   *
   * @return the path of the saved file, or null when it, or its DICOMDIR entry, could not be saved
   */
  private static String writePresentationState(
      MediaElement img,
      File outputFile,
      DicomDirWriter writer,
      DefaultMutableTreeNode node,
      String seriesInstanceUID,
      String prUid) {
    if (headerOf(img) == null) {
      return null;
    }
    GraphicModel grModel = (GraphicModel) img.getTagValue(TagW.PresentationModel);
    if (grModel == null || !grModel.hasSerializableGraphics()) {
      return null;
    }
    try {
      Attributes[] written = new Attributes[1];
      SavedFile.write(
          outputFile.toPath(),
          staged -> {
            written[0] =
                DicomPrSerializer.writePresentation(
                    grModel,
                    img instanceof DicomImageElement image ? image : null,
                    staged.toFile(),
                    seriesInstanceUID,
                    prUid);
            return written[0] != null;
          });
      writeInDicomDir(writer, written[0], node, outputFile);
      return outputFile.getPath();
    } catch (IOException | RuntimeException e) {
      LOGGER.error("Cannot save a presentation state: {}", e.getClass().getSimpleName());
      return null;
    }
  }

  public static String buildPath(
      MediaElement img,
      boolean preservePath,
      boolean cdCompatible,
      DefaultMutableTreeNode node,
      Attributes uidTags) {
    StringBuilder buffer = new StringBuilder();
    // Cannot keep folders names with DICOMDIR (could be not valid)
    if (preservePath) {
      TreeNode[] objects = node.getPath();
      if (objects.length > 2) {
        for (int i = 1; i < objects.length - 1; i++) {
          buffer.append(buildFolderName(objects[i].toString(), 30));
          buffer.append(File.separator);
        }
      }
    } else {
      if (cdCompatible) {
        buffer.append("DICOM");
        buffer.append(File.separator);
      }

      String patientUID = null;
      String studyUID = null;
      String seriesUID = null;
      if (uidTags != null) {
        patientUID = uidTags.getString(Tag.PatientID);
        studyUID = uidTags.getString(Tag.StudyInstanceUID);
        seriesUID = uidTags.getString(Tag.SeriesInstanceUID);
      }

      if (!StringUtil.hasText(patientUID)) {
        patientUID = (String) img.getTagValue(TagW.PatientPseudoUID);
      }
      if (!StringUtil.hasText(studyUID)) {
        studyUID = TagD.getTagValue(img, Tag.StudyInstanceUID, String.class);
      }
      if (!StringUtil.hasText(seriesUID)) {
        seriesUID = TagD.getTagValue(img, Tag.SeriesInstanceUID, String.class);
      }
      buffer.append(makeFileIDs(patientUID));
      buffer.append(File.separator);
      buffer.append(makeFileIDs(studyUID));
      buffer.append(File.separator);
      buffer.append(makeFileIDs(seriesUID));
    }
    return buffer.toString();
  }

  public static String buildPath(MediaElement img, boolean keepNames, DefaultMutableTreeNode node) {
    return buildPath(img, keepNames, false, node);
  }

  /**
   * @param inPatientFolder the export folder is already the patient's: leave out the patient level
   */
  public static String buildPath(
      MediaElement img, boolean keepNames, boolean inPatientFolder, DefaultMutableTreeNode node) {
    StringBuilder buffer = new StringBuilder();
    if (keepNames) {
      TreeNode[] objects = node.getPath();
      if (objects.length > 3) {
        if (!inPatientFolder) {
          buffer.append(buildFolderName(objects[1].toString(), 30));
          buffer.append(File.separator);
        }
        buffer.append(buildFolderName(objects[2].toString(), 30));
        buffer.append(File.separator);
        buffer.append(buildFolderName(objects[3].toString(), 25));
        buffer.append('-');
        // Hash of UID to guaranty the unique behavior of the name.
        buffer.append(makeFileIDs(TagD.getTagValue(img, Tag.SeriesInstanceUID, String.class)));
      }
    } else {
      buffer.append(makeFileIDs((String) img.getTagValue(TagW.PatientPseudoUID)));
      buffer.append(File.separator);
      buffer.append(makeFileIDs(TagD.getTagValue(img, Tag.StudyInstanceUID, String.class)));
      buffer.append(File.separator);
      buffer.append(makeFileIDs(TagD.getTagValue(img, Tag.SeriesInstanceUID, String.class)));
    }
    return buffer.toString();
  }

  private static String buildFolderName(String str, int length) {
    String value = FileUtil.getValidFileNameWithoutHTML(str);
    value = StringUtil.getTruncatedString(value, length, Suffix.UNDERSCORE).trim();
    return value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
  }

  private static boolean writeInDicomDir(
      DicomDirWriter writer, Attributes dataset, DefaultMutableTreeNode node, File destinationFile)
      throws IOException {
    if (writer != null && dataset != null) {
      Attributes fmi = dataset.createFileMetaInformation(UID.ImplicitVRLittleEndian);

      String miuid = fmi.getString(Tag.MediaStorageSOPInstanceUID, null);

      String pid = dataset.getString(Tag.PatientID, null);
      String styuid = dataset.getString(Tag.StudyInstanceUID, null);
      String seruid = dataset.getString(Tag.SeriesInstanceUID, null);

      if (styuid != null && seruid != null) {
        if (pid == null) {
          pid = styuid;
          dataset.setString(Tag.PatientID, VR.LO, pid);
        }
        Attributes patRec = writer.findPatientRecord(pid);
        if (patRec == null) {
          patRec =
              DicomDirLoader.RecordFactory.createRecord(
                  RecordType.PATIENT, null, dataset, null, null);
          writer.addRootDirectoryRecord(patRec);
        }
        Attributes studyRec = writer.findStudyRecord(patRec, styuid);
        if (studyRec == null) {
          studyRec =
              DicomDirLoader.RecordFactory.createRecord(
                  RecordType.STUDY, null, dataset, null, null);
          writer.addLowerDirectoryRecord(patRec, studyRec);
        }
        Attributes seriesRec = writer.findSeriesRecord(studyRec, seruid);
        if (seriesRec == null) {
          seriesRec =
              DicomDirLoader.RecordFactory.createRecord(
                  RecordType.SERIES, null, dataset, null, null);
          /*
           * Icon Image Sequence (0088,0200).This Icon Image is representative of the Series. It may or may
           * not correspond to one of the images of the Series.
           */
          if (seriesRec != null && node.getParent() instanceof DefaultMutableTreeNode treeNode) {
            if (treeNode.getUserObject() instanceof DicomSeries dicomSeries) {
              DicomImageElement midImage =
                  dicomSeries.getMedia(MediaSeries.MEDIA_POSITION.MIDDLE, null, null);
              Attributes iconItem = mkIconItem(midImage);
              if (iconItem != null) {
                seriesRec.newSequence(Tag.IconImageSequence, 1).add(iconItem);
              }
            }
          }
          writer.addLowerDirectoryRecord(studyRec, seriesRec);
        }
        Attributes instRec;
        if (writer.findLowerInstanceRecord(seriesRec, false, miuid) == null) {
          instRec =
              DicomDirLoader.RecordFactory.createRecord(
                  dataset, fmi, writer.toFileIDs(destinationFile));
          writer.addLowerDirectoryRecord(seriesRec, instRec);
        }
      } else {
        if (writer.findRootInstanceRecord(false, miuid) == null) {
          Attributes instRec =
              DicomDirLoader.RecordFactory.createRecord(
                  dataset, fmi, writer.toFileIDs(destinationFile));
          writer.addRootDirectoryRecord(instRec);
        }
      }
    }
    return true;
  }

  /**
   * An 8-character ID for a UID: a DICOMDIR file ID may only hold capitals, digits and "_" (DICOM
   * PS3.10 section 8.2). Eight characters cannot make every UID different, so a file name must come
   * from {@link #freeMediaFile}, which checks.
   */
  public static String makeFileIDs(String uid) {
    if (uid == null) {
      return null;
    }
    byte[] digest;
    try {
      digest = MessageDigest.getInstance("SHA-256").digest(uid.getBytes(StandardCharsets.US_ASCII));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    long value = 0;
    for (int i = 0; i < 6; i++) {
      value = value << 8 | digest[i] & 0xFF;
    }
    String id = Long.toString(value % MEDIA_ID_VALUES, Character.MAX_RADIX);
    return ("0".repeat(MEDIA_ID_LENGTH - id.length()) + id).toUpperCase(Locale.ROOT);
  }

  public static Attributes mkIconItem(DicomImageElement image) {
    if (image == null) {
      return null;
    }
    PlanarImage thumbnail = null;
    PlanarImage imgPl = image.getImage(null);
    if (imgPl != null) {
      SimpleOpManager manager = new SimpleOpManager();
      manager.addImageOperationAction(new WindowAndPresetsOp());
      manager.setParamValue(WindowOp.OP_NAME, WindowOp.P_IMAGE_ELEMENT, image);
      manager.setParamValue(WindowOp.OP_NAME, ActionW.IMAGE_PIX_PADDING.cmd(), true);
      manager.setParamValue(WindowOp.OP_NAME, ActionW.DEFAULT_PRESET.cmd(), true);

      ZoomOp rectifyZoomOp = image.getRectifyAspectRatioZoomOp();
      if (rectifyZoomOp != null) {
        manager.addImageOperationAction(rectifyZoomOp);
      }
      manager.setFirstNode(imgPl);
      thumbnail =
          ImageIOHandler.buildThumbnail(
              manager.process().orElse(null), new Dimension(128, 128), true);
    }

    if (thumbnail == null) {
      return null;
    }
    int w = thumbnail.width();
    int h = thumbnail.height();

    String pmi = TagD.getTagValue(image, Tag.PhotometricInterpretation, String.class);
    if (thumbnail.channels() >= 3) {
      pmi = "PALETTE COLOR"; // NON-NLS
    }

    byte[] iconPixelData = new byte[w * h];
    Attributes iconItem = new Attributes();

    if ("PALETTE COLOR".equals(pmi)) { // NON-NLS
      BufferedImage bi =
          ImageConversion.convertTo(
              ImageConversion.toBufferedImage(thumbnail), BufferedImage.TYPE_BYTE_INDEXED);
      IndexColorModel cm = (IndexColorModel) bi.getColorModel();
      int[] lutDesc = {cm.getMapSize(), 0, 8};
      byte[] r = new byte[lutDesc[0]];
      byte[] g = new byte[lutDesc[0]];
      byte[] b = new byte[lutDesc[0]];
      cm.getReds(r);
      cm.getGreens(g);
      cm.getBlues(b);
      iconItem.setInt(Tag.RedPaletteColorLookupTableDescriptor, VR.US, lutDesc);
      iconItem.setInt(Tag.GreenPaletteColorLookupTableDescriptor, VR.US, lutDesc);
      iconItem.setInt(Tag.BluePaletteColorLookupTableDescriptor, VR.US, lutDesc);
      iconItem.setBytes(Tag.RedPaletteColorLookupTableData, VR.OW, r);
      iconItem.setBytes(Tag.GreenPaletteColorLookupTableData, VR.OW, g);
      iconItem.setBytes(Tag.BluePaletteColorLookupTableData, VR.OW, b);

      Raster raster = bi.getRaster();
      for (int y = 0, i = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x, ++i) {
          iconPixelData[i] = (byte) raster.getSample(x, y, 0);
        }
      }
    } else {
      pmi = "MONOCHROME2";
      thumbnail.get(0, 0, iconPixelData);
    }
    iconItem.setString(Tag.PhotometricInterpretation, VR.CS, pmi);
    iconItem.setInt(Tag.Rows, VR.US, h);
    iconItem.setInt(Tag.Columns, VR.US, w);
    iconItem.setInt(Tag.SamplesPerPixel, VR.US, 1);
    iconItem.setInt(Tag.BitsAllocated, VR.US, 8);
    iconItem.setInt(Tag.BitsStored, VR.US, 8);
    iconItem.setInt(Tag.HighBit, VR.US, 7);
    iconItem.setBytes(Tag.PixelData, VR.OW, iconPixelData);
    return iconItem;
  }
}
