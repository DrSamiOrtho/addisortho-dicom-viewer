/*
 * Copyright (c) 2009-2020 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.receive;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;

/** Settings of the DICOM receiver, stored in a properties file of the bundle data folder. */
public record ReceiveSettings(
    boolean enabled,
    String aeTitle,
    int port,
    String listenAddress,
    List<AllowedSender> allowedSenders,
    Path storageFolder,
    int maxAssociations,
    long maxFileSizeBytes,
    boolean includeNameInFileNames) {

  public static final String DEFAULT_AE_TITLE = "AOVIEWER";
  public static final int DEFAULT_PORT = 11112;
  public static final int DEFAULT_MAX_ASSOCIATIONS = 4;
  public static final long DEFAULT_MAX_FILE_MB = 1024;
  private static final long MEGABYTE = 1024L * 1024L;
  private static final int MAX_AE_LENGTH = 16;

  private static final String KEY_ENABLED = "enabled";
  private static final String KEY_AE_TITLE = "ae.title";
  private static final String KEY_PORT = "port";
  private static final String KEY_LISTEN = "listen.address";
  private static final String KEY_SENDERS = "allowed.senders";
  private static final String KEY_FOLDER = "storage.folder";
  private static final String KEY_MAX_ASSOCIATIONS = "max.associations";
  private static final String KEY_MAX_FILE_MB = "max.file.mb";
  private static final String KEY_INCLUDE_NAME = "filename.include.name";
  private static final String SENDER_SEPARATOR = ";";

  public ReceiveSettings {
    allowedSenders = List.copyOf(allowedSenders);
    listenAddress = listenAddress == null ? "" : listenAddress.trim();
  }

  /** The receiver is off and no device is allowed until the user configures them. */
  public static ReceiveSettings defaults() {
    return new ReceiveSettings(
        false,
        DEFAULT_AE_TITLE,
        DEFAULT_PORT,
        "",
        List.of(),
        defaultStorageFolder(),
        DEFAULT_MAX_ASSOCIATIONS,
        DEFAULT_MAX_FILE_MB * MEGABYTE,
        true);
  }

  /** A plain folder of the home directory: Documents is often synchronised to a cloud service. */
  public static Path defaultStorageFolder() {
    return Path.of(System.getProperty("user.home"), "AddisOrthoReceived");
  }

  public long maxFileSizeMb() {
    return maxFileSizeBytes / MEGABYTE;
  }

  public static ReceiveSettings fromProperties(Properties p) {
    ReceiveSettings d = defaults();
    List<AllowedSender> senders = new ArrayList<>();
    for (String item : p.getProperty(KEY_SENDERS, "").split(SENDER_SEPARATOR)) {
      AllowedSender.decode(item).ifPresent(senders::add);
    }
    String folder = p.getProperty(KEY_FOLDER, "");
    return new ReceiveSettings(
        Boolean.parseBoolean(p.getProperty(KEY_ENABLED, "false")),
        p.getProperty(KEY_AE_TITLE, d.aeTitle()).trim(),
        parseInt(p.getProperty(KEY_PORT), d.port()),
        p.getProperty(KEY_LISTEN, ""),
        senders,
        folder.isBlank() ? d.storageFolder() : Path.of(folder),
        parseInt(p.getProperty(KEY_MAX_ASSOCIATIONS), d.maxAssociations()),
        parseInt(p.getProperty(KEY_MAX_FILE_MB), (int) d.maxFileSizeMb()) * MEGABYTE,
        Boolean.parseBoolean(p.getProperty(KEY_INCLUDE_NAME, "true")));
  }

  public Properties toProperties() {
    Properties p = new Properties();
    p.setProperty(KEY_ENABLED, Boolean.toString(enabled));
    p.setProperty(KEY_AE_TITLE, aeTitle);
    p.setProperty(KEY_PORT, Integer.toString(port));
    p.setProperty(KEY_LISTEN, listenAddress);
    p.setProperty(
        KEY_SENDERS,
        allowedSenders.stream()
            .map(AllowedSender::encode)
            .collect(Collectors.joining(SENDER_SEPARATOR)));
    p.setProperty(KEY_FOLDER, storageFolder.toString());
    p.setProperty(KEY_MAX_ASSOCIATIONS, Integer.toString(maxAssociations));
    p.setProperty(KEY_MAX_FILE_MB, Long.toString(maxFileSizeMb()));
    p.setProperty(KEY_INCLUDE_NAME, Boolean.toString(includeNameInFileNames));
    return p;
  }

  /** Returns the message key of the first invalid setting. */
  public Optional<String> firstError() {
    if (!isValidAeTitle(aeTitle)) {
      return Optional.of("receive.error.ae.title");
    }
    if (port < 1 || port > 65535) {
      return Optional.of("receive.error.port");
    }
    if (!listenAddress.isEmpty() && AllowedSender.parseIp(listenAddress).isEmpty()) {
      return Optional.of("receive.error.listen.address");
    }
    if (!allowedSenders.stream().allMatch(AllowedSender::isValid)) {
      return Optional.of("receive.error.sender");
    }
    if (maxAssociations < 1 || maxFileSizeBytes < MEGABYTE) {
      return Optional.of("receive.error.limits");
    }
    return Optional.empty();
  }

  static boolean isValidAeTitle(String aet) {
    return aet != null
        && !aet.isBlank()
        && aet.length() <= MAX_AE_LENGTH
        && aet.equals(aet.trim())
        && aet.chars().allMatch(c -> c >= 0x20 && c < 0x7F && c != '\\' && c != ';');
  }

  private static int parseInt(String value, int fallback) {
    try {
      return value == null ? fallback : Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }
}
