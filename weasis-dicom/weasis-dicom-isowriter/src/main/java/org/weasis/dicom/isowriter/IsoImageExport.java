/*
 * Copyright (c) 2016 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
// Modified by Addis Ortho Trading PLC, 2026.
package org.weasis.dicom.isowriter;

import com.formdev.flatlaf.util.SystemFileChooser;
import com.formdev.flatlaf.util.SystemFileChooser.FileNameExtensionFilter;
import com.formdev.flatlaf.util.SystemInfo;
import com.github.stephenc.javaisotools.iso9660.ConfigException;
import com.github.stephenc.javaisotools.iso9660.ISO9660RootDirectory;
import com.github.stephenc.javaisotools.iso9660.impl.CreateISO;
import com.github.stephenc.javaisotools.iso9660.impl.ISO9660Config;
import com.github.stephenc.javaisotools.iso9660.impl.ISOImageFileHandler;
import com.github.stephenc.javaisotools.joliet.impl.JolietConfig;
import com.github.stephenc.javaisotools.rockridge.impl.RockRidgeConfig;
import com.github.stephenc.javaisotools.sabre.HandlerException;
import com.github.stephenc.javaisotools.sabre.StreamHandler;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.tree.TreePath;
import org.dcm4che3.data.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.explorer.ObservableEvent;
import org.weasis.core.api.gui.util.AppProperties;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.api.gui.util.WinUtil;
import org.weasis.core.api.media.data.MediaSeriesGroup;
import org.weasis.core.api.util.ResourceUtil;
import org.weasis.core.util.FileUtil;
import org.weasis.core.util.StreamUtil;
import org.weasis.core.util.StringUtil;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.explorer.DicomModel;
import org.weasis.dicom.explorer.exp.CheckTreeModel;
import org.weasis.dicom.explorer.exp.ExplorerTask;
import org.weasis.dicom.explorer.exp.LocalExport;

public class IsoImageExport extends LocalExport {

  private static final Logger LOGGER = LoggerFactory.getLogger(IsoImageExport.class);

  private static final String LAST_FOLDER = "last_folder"; // NON-NLS
  private static final String ADD_JPEG = "add_jpeg"; // NON-NLS
  private static final String ADD_VIEWER = "add_viewer"; // NON-NLS
  private static final String NAME_LABEL = "name_label"; // NON-NLS
  private static final String BURN = "burn"; // NON-NLS
  private static final String DICOM = "DICOM";

  private JCheckBox checkBoxAddWeasisViewer;
  private JCheckBox checkBoxAddJpeg;
  private JCheckBox checkBoxNameLabel;
  private JCheckBox checkBoxBurn;

  private File outputFile;

  public IsoImageExport(DicomModel dicomModel, CheckTreeModel treeModel) {
    super(Messages.getString("cd.dvd.image"), 25, dicomModel, treeModel);
    initialize(true);
  }

  @Override
  protected List<JComponent> getAdditionalOption() {
    getPreferences().put("force.dicomdir", Boolean.TRUE.toString());
    checkBoxAddJpeg = new JCheckBox(Messages.getString("add.jpeg.images"));
    checkBoxAddWeasisViewer =
        new JCheckBox(Messages.getString("add") + StringUtil.SPACE + AppProperties.WEASIS_NAME);
    comboBoxImgFormat.setModel(new DefaultComboBoxModel<>(new Format[] {Format.DICOM}));
    checkBoxAddWeasisViewer.setEnabled(SystemInfo.isWindows);
    // Off to send the disc outside the hospital: the label and file name are then "DICOM".
    checkBoxNameLabel = new JCheckBox(Messages.getString("name.label"));
    checkBoxBurn = new JCheckBox(Messages.getString("burn.eject"));
    checkBoxBurn.setEnabled(DiscBurner.isSupported());

    return List.of(
        GuiUtils.boxHorizontalStrut(ITEM_SEPARATOR_LARGE),
        checkBoxAddJpeg,
        GuiUtils.boxHorizontalStrut(ITEM_SEPARATOR_LARGE),
        checkBoxAddWeasisViewer,
        GuiUtils.boxHorizontalStrut(ITEM_SEPARATOR_LARGE),
        checkBoxNameLabel,
        GuiUtils.boxHorizontalStrut(ITEM_SEPARATOR_LARGE),
        checkBoxBurn);
  }

  protected void initialize(boolean first) {
    if (first) {
      Properties pref = getPreferences();
      checkBoxAddJpeg.setSelected(Boolean.parseBoolean(pref.getProperty(ADD_JPEG, "false")));
      checkBoxAddWeasisViewer.setSelected(
          Boolean.parseBoolean(pref.getProperty(ADD_VIEWER, "false")));
      checkBoxNameLabel.setSelected(Boolean.parseBoolean(pref.getProperty(NAME_LABEL, "true")));
      checkBoxBurn.setSelected(
          DiscBurner.isSupported() && Boolean.parseBoolean(pref.getProperty(BURN, "true")));
    }
  }

  protected Properties getPreferences() {
    return ExportIsoFactory.EXPORT_PERSISTENCE;
  }

  public void resetSettingsToDefault() {
    initialize(false);
  }

  @Override
  public void closeAdditionalWindow() {
    Properties pref = getPreferences();
    pref.setProperty(ADD_JPEG, String.valueOf(checkBoxAddJpeg.isSelected()));
    pref.setProperty(ADD_VIEWER, String.valueOf(checkBoxAddWeasisViewer.isSelected()));
    pref.setProperty(NAME_LABEL, String.valueOf(checkBoxNameLabel.isSelected()));
    pref.setProperty(BURN, String.valueOf(checkBoxBurn.isSelected()));
  }

  @Override
  public void resetToDefaultValues() {
    // Do nothing
  }

  String getLocalViewerPath() {
    return GuiUtils.getUICore().getSystemPreferences().getProperty("weasis.codebase.local", null);
  }

  @Override
  public void exportDICOM(final CheckTreeModel model, JProgressBar info) throws IOException {
    String patient = checkBoxNameLabel.isSelected() ? getPatientOfDisc(model) : null;
    final String isoLabel = isoVolumeId(patient);
    final String jolietLabel = jolietVolumeId(patient);
    String fileName =
        patient == null
            ? "cdrom-DICOM.iso" // NON-NLS
            : FileUtil.getValidFileNameWithoutHTML(patient) + ".iso"; // NON-NLS
    final boolean burn = checkBoxBurn.isEnabled() && checkBoxBurn.isSelected();
    if (burn) {
      // The image only goes to the disc: build it in the temporary folder, no file to choose.
      outputFile =
          AppProperties.buildAccessibleTempDirectory("tmp", "burn")
              .resolve(fileName)
              .toFile(); // NON-NLS
    } else {
      browseImgFile(fileName);
    }
    if (outputFile != null) {
      final File exportFile = outputFile.getCanonicalFile();
      ExplorerTask<Boolean, String> task =
          new ExplorerTask<>(Messages.getString("exporting"), false) {
            private String message;

            @Override
            protected Boolean doInBackground() throws Exception {
              dicomModel.firePropertyChange(
                  new ObservableEvent(
                      ObservableEvent.BasicAction.LOADING_START, dicomModel, null, this));
              File exportDir =
                  FileUtil.createTempDir(AppProperties.buildAccessibleTempDirectory("tmp", "burn"))
                      .toFile(); // NON-NLS
              try {
                Properties pref = getPreferences();
                pref.setProperty(INC_DICOMDIR, Boolean.TRUE.toString());
                pref.setProperty(CD_COMPATIBLE, Boolean.TRUE.toString());
                writeDicom(this, exportDir, model, pref);
                File readmeFile = ResourceUtil.getResource("isowriter/README.htm"); // NON-NLS
                StreamUtil.copyFile(
                    readmeFile.toPath(), new File(exportDir, "README.HTM").toPath());

                if (checkBoxAddJpeg.isSelected()) {
                  writeOther(
                      this, new File(exportDir, "JPEG"), model, Format.JPEG, new Properties());
                }

                String localPath = getLocalViewerPath();
                if (StringUtil.hasText(localPath)) {
                  Path appPath = Paths.get(localPath);
                  if (checkBoxAddWeasisViewer.isEnabled()
                      && checkBoxAddWeasisViewer.isSelected()
                      && Files.isReadable(appPath)) {
                    Path out = Paths.get(exportDir.toString(), "viewer");
                    Files.createDirectory(out);
                    Path in = appPath.getParent();
                    copyFolder(in, out, StandardCopyOption.COPY_ATTRIBUTES);
                    File autorun = ResourceUtil.getResource("isowriter/Autorun.inf"); // NON-NLS
                    StreamUtil.copyFile(
                        autorun.toPath(), new File(exportDir, "AUTORUN.INF").toPath());
                    File run = ResourceUtil.getResource("isowriter/RUN.bat"); // NON-NLS
                    StreamUtil.copyFile(run.toPath(), new File(exportDir, "RUN.BAT").toPath());
                  }
                }

                if (this.isCancelled()) {
                  return false;
                }
                if (makeISO(exportDir, exportFile, isoLabel, jolietLabel, true, true) == null) {
                  message = Messages.getString("burn.failed");
                  return false;
                }
                if (burn) {
                  burnDisc(exportDir, exportFile, jolietLabel);
                }
                return true;
              } finally {
                FileUtil.recursiveDelete(exportDir.toPath());
                if (burn) {
                  Files.deleteIfExists(exportFile.toPath());
                }
              }
            }

            /**
             * Burns and ejects the disc; asks for a blank one until there is one or the user stops.
             */
            private void burnDisc(File exportDir, File isoFile, String label) throws Exception {
              while (true) {
                DiscBurner.Result ready = DiscBurner.checkBlankDisc();
                if (ready == DiscBurner.Result.DONE) {
                  break;
                }
                String text =
                    Messages.getString(
                        ready == DiscBurner.Result.NO_BURNER ? "burn.no.drive" : "burn.insert");
                Object[] options = {
                  Messages.getString("burn.retry"),
                  Messages.getString("burn.save.iso"),
                  Messages.getString("burn.cancel")
                };
                int[] choice = {JOptionPane.CLOSED_OPTION};
                SwingUtilities.invokeAndWait(
                    () ->
                        choice[0] =
                            JOptionPane.showOptionDialog(
                                WinUtil.getValidComponent(
                                    WinUtil.getParentWindow(IsoImageExport.this)),
                                text,
                                Messages.getString("cd.dvd.image"),
                                JOptionPane.DEFAULT_OPTION,
                                JOptionPane.WARNING_MESSAGE,
                                null,
                                options,
                                options[0]));
                if (choice[0] == 0) {
                  continue;
                }
                if (choice[0] == 1) {
                  SwingUtilities.invokeAndWait(() -> browseImgFile(isoFile.getName()));
                  if (outputFile != null && !outputFile.equals(isoFile)) {
                    Files.copy(
                        isoFile.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    message = Messages.getString("burn.saved") + StringUtil.SPACE + outputFile;
                  }
                }
                return;
              }
              DiscBurner.Result result = DiscBurner.burn(exportDir, isoFile, label);
              message =
                  Messages.getString(
                      result == DiscBurner.Result.DONE ? "burn.done" : "burn.failed");
            }

            @Override
            protected void done() {
              dicomModel.firePropertyChange(
                  new ObservableEvent(
                      ObservableEvent.BasicAction.LOADING_STOP, dicomModel, null, this));
              if (message != null) {
                JOptionPane.showMessageDialog(
                    WinUtil.getValidComponent(WinUtil.getParentWindow(IsoImageExport.this)),
                    message,
                    Messages.getString("cd.dvd.image"),
                    JOptionPane.INFORMATION_MESSAGE);
              }
            }
          };
      task.execute();
    }
  }

  /**
   * The patient of the disc as "NAME FIRSTNAME ID", or null when the selection holds several
   * patients or none.
   */
  static String getPatientOfDisc(CheckTreeModel model) {
    return getSelectedPatient(model);
  }

  /** ISO 9660 volume label: at most 32 of A-Z, 0-9 and _. */
  static String isoVolumeId(String patient) {
    if (patient == null) {
      return DICOM;
    }
    String id = patient.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    id = id.replaceAll("^_+|_+$", "");
    if (id.length() > 32) {
      id = id.substring(0, 32);
    }
    return id.isEmpty() ? DICOM : id;
  }

  /** Joliet volume label, the one Windows and macOS show: at most 16 characters. */
  static String jolietVolumeId(String patient) {
    if (patient == null) {
      return DICOM;
    }
    String id = patient.replaceAll("[*/:;?\\\\\"<>|]", "").trim();
    if (id.length() > 16) {
      id = id.substring(0, 16).trim();
    }
    return id.isEmpty() ? DICOM : id;
  }

  // TODO use lib
  public void copyFolder(Path source, Path target, CopyOption... options) throws IOException {
    Files.walkFileTree(
        source,
        new SimpleFileVisitor<>() {

          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            Files.createDirectories(target.resolve(source.relativize(dir)));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.copy(file, target.resolve(source.relativize(file)), options);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  public void browseImgFile(String fileName) {
    Properties pref = getPreferences();
    String lastFolder = pref.getProperty(LAST_FOLDER, null);
    if (lastFolder == null) {
      lastFolder = System.getProperty("user.home", "");
    }
    outputFile = new File(lastFolder, fileName);

    SystemFileChooser fileChooser = new SystemFileChooser(outputFile);
    fileChooser.setFileSelectionMode(SystemFileChooser.FILES_ONLY);
    fileChooser.setMultiSelectionEnabled(false);
    FileNameExtensionFilter filter = new FileNameExtensionFilter("ISO image", "iso"); // NON-NLS
    fileChooser.addChoosableFileFilter(filter);
    fileChooser.setFileFilter(filter);

    fileChooser.setSelectedFile(outputFile);
    File file;
    if (fileChooser.showSaveDialog(this) != 0 || (file = fileChooser.getSelectedFile()) == null) {
      outputFile = null;
    } else {
      outputFile = file;
      pref.setProperty(LAST_FOLDER, file.getParent());
    }
  }

  private File makeISO(
      File exportDir,
      File exportFile,
      String isoLabel,
      String jolietLabel,
      boolean enableRockRidge,
      boolean enableJoliet) {
    // Directory hierarchy, starting from the root
    ISO9660RootDirectory root = new ISO9660RootDirectory();

    try {
      File[] files = exportDir.listFiles();
      if (files != null) {
        for (File file : files) {
          if (file.exists()) {
            if (file.isDirectory()) {
              root.addRecursively(file);
            } else {
              root.addFile(file);
            }
          }
        }
      }
    } catch (HandlerException e) {
      LOGGER.error("Error when adding files to ISO", e);
    }

    try {
      // ISO9660 support
      ISO9660Config iso9660Config = new ISO9660Config();
      iso9660Config.allowASCII(false);
      iso9660Config.setInterchangeLevel(1);
      iso9660Config.restrictDirDepthTo8(true);
      iso9660Config.setPublisher(AppProperties.WEASIS_NAME);
      iso9660Config.setVolumeID(isoLabel);
      iso9660Config.setDataPreparer(DICOM);
      iso9660Config.forceDotDelimiter(false);

      RockRidgeConfig rrConfig = null;

      if (enableRockRidge) {
        // Rock Ridge support
        rrConfig = new RockRidgeConfig();
        rrConfig.setMkisofsCompatibility(false);
        rrConfig.hideMovedDirectoriesStore(true);
        rrConfig.forcePortableFilenameCharacterSet(true);
      }

      JolietConfig jolietConfig = null;
      if (enableJoliet) {
        // Joliet support
        jolietConfig = new JolietConfig();
        jolietConfig.setPublisher(AppProperties.WEASIS_NAME);
        jolietConfig.setVolumeID(jolietLabel);
        jolietConfig.setDataPreparer(DICOM);
        jolietConfig.forceDotDelimiter(false);
      }

      // Create ISO
      StreamHandler streamHandler = new ISOImageFileHandler(exportFile);
      CreateISO iso = new CreateISO(streamHandler, root);
      iso.process(iso9660Config, rrConfig, jolietConfig, null);
      return exportFile;

    } catch (ConfigException | HandlerException | FileNotFoundException e) {
      LOGGER.error("Error when building ISO", e);
    }
    return null;
  }
}
