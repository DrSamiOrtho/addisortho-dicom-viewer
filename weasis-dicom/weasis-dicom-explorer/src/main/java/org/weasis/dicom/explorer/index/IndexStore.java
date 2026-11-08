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
package org.weasis.dicom.explorer.index;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Saves the index (one line per DICOM instance, tab separated) and the folder list (a small JSON
 * file the user can back up) in the viewer's data folder, readable by the user only where the
 * system allows it. Writes go to a temporary file first, so a crash never leaves half an index.
 */
public final class IndexStore {

  private static final String HEADER = "AO-PATIENT-INDEX\t1"; // NON-NLS
  private static final int FIELDS = 14;

  private final Path dir;

  public IndexStore(Path dir) {
    this.dir = dir;
  }

  public Path indexFile() {
    return dir.resolve("patient-index.tsv"); // NON-NLS
  }

  public Path foldersFile() {
    return dir.resolve("search-folders.json"); // NON-NLS
  }

  public List<IndexedFile> loadIndex() {
    Path file = indexFile();
    List<IndexedFile> out = new ArrayList<>();
    if (!Files.isRegularFile(file)) {
      return out;
    }
    try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      if (!HEADER.equals(r.readLine())) {
        return out; // another version: rebuilt by the next scan
      }
      String line;
      while ((line = r.readLine()) != null) {
        String[] p = line.split("\t", -1);
        if (p.length != FIELDS) {
          continue;
        }
        out.add(
            new IndexedFile(
                unescape(p[0]),
                Long.parseLong(p[1]),
                Long.parseLong(p[2]),
                nul(p[3]),
                nul(p[4]),
                nul(p[5]),
                nul(p[6]),
                nul(p[7]),
                nul(p[8]),
                nul(p[9]),
                nul(p[10]),
                nul(p[11]),
                nul(p[12]),
                nul(p[13])));
      }
    } catch (IOException | RuntimeException e) {
      return new ArrayList<>(); // damaged: rebuilt by the next scan
    }
    return out;
  }

  public void saveIndex(List<IndexedFile> files) throws IOException {
    Files.createDirectories(dir);
    Path tmp = dir.resolve("patient-index.tmp"); // NON-NLS
    try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
      w.write(HEADER);
      w.newLine();
      for (IndexedFile f : files) {
        w.write(
            String.join(
                "\t",
                escape(f.location()),
                Long.toString(f.size()),
                Long.toString(f.modified()),
                escape(f.studyUid()),
                escape(f.seriesUid()),
                escape(f.sopUid()),
                escape(f.modality()),
                escape(f.patientName()),
                escape(f.patientId()),
                escape(f.birthDate()),
                escape(f.sex()),
                escape(f.studyDate()),
                escape(f.studyDescription()),
                escape(f.accession())));
        w.newLine();
      }
    }
    userOnly(tmp);
    Files.move(
        tmp, indexFile(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  /** Deletes the index; the folder list stays. */
  public void clearIndex() throws IOException {
    Files.deleteIfExists(indexFile());
  }

  public List<Path> loadFolders() {
    List<Path> out = new ArrayList<>();
    try {
      if (Files.isRegularFile(foldersFile())) {
        Matcher m =
            Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(Files.readString(foldersFile()));
        boolean first = true;
        while (m.find()) {
          String s = m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
          if (first && s.equals("folders")) { // NON-NLS
            first = false;
            continue;
          }
          first = false;
          out.add(Path.of(s));
        }
      }
    } catch (IOException | RuntimeException e) {
      // no list yet
    }
    return out;
  }

  public void saveFolders(List<Path> folders) throws IOException {
    Files.createDirectories(dir);
    StringBuilder json = new StringBuilder("{\n  \"folders\": ["); // NON-NLS
    for (int i = 0; i < folders.size(); i++) {
      String s = folders.get(i).toString().replace("\\", "\\\\").replace("\"", "\\\"");
      json.append(i == 0 ? "\n    \"" : ",\n    \"").append(s).append('"');
    }
    json.append(folders.isEmpty() ? "]\n}\n" : "\n  ]\n}\n");
    Files.writeString(foldersFile(), json.toString(), StandardCharsets.UTF_8);
  }

  private static void userOnly(Path file) {
    try {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); // NON-NLS
    } catch (IOException | UnsupportedOperationException e) {
      // Windows: the profile folder is already the user's
    }
  }

  static String escape(String s) {
    if (s == null) {
      return "\\0";
    }
    return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
  }

  static String unescape(String s) {
    StringBuilder out = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\' && i + 1 < s.length()) {
        char n = s.charAt(++i);
        out.append(
            switch (n) {
              case 't' -> '\t';
              case 'n' -> '\n';
              case 'r' -> '\r';
              default -> n;
            });
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }

  private static String nul(String s) {
    return "\\0".equals(s) ? null : unescape(s);
  }
}
