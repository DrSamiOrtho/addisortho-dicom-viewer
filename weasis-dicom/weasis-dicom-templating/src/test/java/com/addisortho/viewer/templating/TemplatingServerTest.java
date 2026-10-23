/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.templating;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplatingServerTest {

  /** A clock the test moves forward. */
  static final class TestClock extends Clock {
    Instant now = Instant.parse("2026-10-14T08:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @TempDir Path tmp;
  private final TestClock clock = new TestClock();
  private final List<Handover.Plan> received = new CopyOnWriteArrayList<>();
  private final HttpClient http = HttpClient.newHttpClient();
  private TemplatingServer server;
  private Path xray;
  private URI page;
  private String base;

  @BeforeEach
  void start() throws IOException {
    PageFiles pages = PageFiles.load(getClass().getClassLoader(), "templating-web-test/");
    server = new TemplatingServer(0, pages, tmp.resolve("work"), (h, p) -> received.add(p), clock);
    xray = Files.write(tmp.resolve("xray.dcm"), Dicom.part10(Dicom.xray()));
    page = server.handOver(xray, "explorer");
    base = page.toString().substring(0, page.toString().indexOf("templating/"));
  }

  @AfterEach
  void stop() {
    server.close();
  }

  private HttpResponse<byte[]> get(String url) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create(url)).build(), BodyHandlers.ofByteArray());
  }

  private HttpResponse<String> post(byte[] body, boolean header, String origin, String type)
      throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(base + "templating/store"))
            .POST(BodyPublishers.ofByteArray(body))
            .header("Content-Type", type);
    if (origin != null) {
      b.header("Origin", origin);
    }
    if (header) {
      b.header("X-AOC-Templating", "1");
    }
    return http.send(b.build(), BodyHandlers.ofString());
  }

  private HttpResponse<String> post(byte[] body) throws Exception {
    return post(body, true, "http://127.0.0.1:" + server.port(), "application/dicom");
  }

  private static String text(HttpResponse<byte[]> r) {
    return new String(r.body(), StandardCharsets.UTF_8);
  }

  @Test
  void thePageAddressNamesTheXrayUnderAOneTimeToken() {
    assertTrue(
        page.toString()
            .matches(
                "http://127\\.0\\.0\\.1:"
                    + server.port()
                    + "/t/[0-9a-f]{32}/templating/\\?study="
                    + Dicom.STUDY.replace(".", "\\.")
                    + "&instance="
                    + Dicom.SOP.replace(".", "\\.")),
        page.toString());
  }

  @Test
  void servesThePageFilesWithItsSecurityHeaders() throws Exception {
    HttpResponse<byte[]> r = get(base + "templating/");
    assertEquals(200, r.statusCode());
    assertEquals(
        "<!doctype html><html lang=\"en\" data-mode=\"viewer\" data-release=\"r1\"><title>t</title>\n",
        text(r));
    assertEquals("text/html; charset=utf-8", r.headers().firstValue("Content-Type").orElse(""));
    assertEquals(TemplatingServer.CSP, r.headers().firstValue("Content-Security-Policy").orElse(""));
    assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
    assertEquals("nosniff", r.headers().firstValue("X-Content-Type-Options").orElse(""));
    assertEquals(200, get(base + "templating/library.json").statusCode());
    assertEquals(404, get(base + "templating/BUILD.txt").statusCode());
    assertEquals(404, get(base + "templating/missing.js").statusCode());
    assertEquals(404, get(base + "templating/%2e%2e/%2e%2e/xray.dcm").statusCode());
  }

  @Test
  void answersForTheArchive() throws Exception {
    String q = text(get(base + "dicomweb/studies/" + Dicom.STUDY + "/instances?includefield=x"));
    assertTrue(q.contains("\"00080060\":{\"vr\":\"CS\",\"Value\":[\"DX\"]}"), q);
    assertTrue(q.contains("\"00080018\":{\"vr\":\"UI\",\"Value\":[\"" + Dicom.SOP + "\"]}"), q);
    assertTrue(q.contains("\"Pelvis AP \\\"standing\\\"\""), q);
    assertEquals("[]", text(get(base + "dicomweb/studies/2.25.5/instances")));
    String wado =
        base
            + "wado?requestType=WADO&studyUID="
            + Dicom.STUDY
            + "&seriesUID="
            + Dicom.SERIES
            + "&objectUID="
            + Dicom.SOP
            + "&contentType=application/dicom";
    HttpResponse<byte[]> w = get(wado);
    assertEquals(200, w.statusCode());
    assertEquals("application/dicom", w.headers().firstValue("Content-Type").orElse(""));
    assertArrayEquals(Files.readAllBytes(xray), w.body());
    assertEquals(404, get(wado.replace("objectUID=" + Dicom.SOP, "objectUID=2.25.7")).statusCode());
  }

  @Test
  void storesAPlanOnceAndFindsItAgain() throws Exception {
    byte[] plan = Dicom.part10(Dicom.plan("2.25.11", "2.25.12"));
    HttpResponse<String> r = post(plan);
    assertEquals(200, r.statusCode());
    assertEquals(
        "{\"ok\":true,\"sop\":\"2.25.12\",\"series\":\"2.25.11\",\"result\":\"stored\"}", r.body());
    assertEquals(1, received.size());
    assertArrayEquals(plan, Files.readAllBytes(received.get(0).file()));
    HttpResponse<String> again = post(plan);
    assertEquals(409, again.statusCode());
    assertTrue(again.body().contains("\"sop\":\"2.25.12\""), again.body());
    assertEquals(1, received.size());
    String held =
        text(get(base + "dicomweb/studies/" + Dicom.STUDY + "/instances?SOPInstanceUID=2.25.12"));
    assertTrue(held.contains("\"2.25.12\"") && held.contains("\"2.25.11\""), held);
    assertEquals(
        "[]",
        text(get(base + "dicomweb/studies/" + Dicom.STUDY + "/instances?SOPInstanceUID=2.25.13")));
  }

  @Test
  void refusesEverythingElseAtTheDoor() throws Exception {
    byte[] plan = Dicom.part10(Dicom.plan("2.25.11", "2.25.12"));
    String own = "http://127.0.0.1:" + server.port();
    assertEquals(403, post(plan, false, own, "application/dicom").statusCode());
    assertEquals(403, post(plan, true, "https://example.org", "application/dicom").statusCode());
    assertEquals(403, post(plan, true, "null", "application/dicom").statusCode());
    assertEquals(403, post(plan, false, null, "application/dicom").statusCode());
    assertEquals(415, post(plan, true, own, "application/octet-stream").statusCode());
    Attributes other = Dicom.plan("2.25.11", "2.25.14");
    other.setString(Tag.PatientID, VR.LO, "SOMEONE-ELSE");
    assertEquals(422, post(Dicom.part10(other)).statusCode());
    assertEquals(415, post(Files.readAllBytes(xray)).statusCode());
    assertEquals(413, post(new byte[0]).statusCode());
    assertEquals(413, post(new byte[TemplatingServer.MAX_PLAN + 1]).statusCode());
    assertTrue(received.isEmpty());
  }

  @Test
  void aSaveWithoutOriginIsAcceptedOnTheTemplatingHeader() throws Exception {
    HttpResponse<String> r =
        post(Dicom.part10(Dicom.plan("2.25.21", "2.25.22")), true, null, "application/dicom");
    assertEquals(200, r.statusCode());
    assertEquals(1, received.size());
  }

  @Test
  void anUnknownOrEndedTokenFindsNothing() throws Exception {
    HttpResponse<byte[]> unknown = get(base.replaceAll("/t/[0-9a-f]{32}/", "/t/" + "0".repeat(32) + "/") + "templating/");
    assertEquals(404, unknown.statusCode());
    assertTrue(text(unknown).contains("Press Template in the viewer again"));
    assertEquals(404, get("http://127.0.0.1:" + server.port() + "/").statusCode());
    clock.now = clock.now.plus(TemplatingServer.LIFETIME).plus(Duration.ofMinutes(1));
    assertEquals(404, get(base + "templating/").statusCode());
  }

  @Test
  void refusesAnotherHostName() throws Exception {
    try (Socket s = new Socket("127.0.0.1", server.port())) {
      OutputStream out = s.getOutputStream();
      out.write(
          ("GET " + URI.create(base).getRawPath() + "templating/ HTTP/1.1\r\nHost: localhost:"
                  + server.port()
                  + "\r\nConnection: close\r\n\r\n")
              .getBytes(StandardCharsets.US_ASCII));
      out.flush();
      InputStream in = s.getInputStream();
      String status = new String(in.readNBytes(12), StandardCharsets.US_ASCII);
      assertEquals("HTTP/1.1 400", status);
    }
  }

  @Test
  void aFileWithoutFileMetaGetsIt() throws Exception {
    Path cd = Files.write(tmp.resolve("cd.dcm"), Dicom.withoutMeta(Dicom.xray()));
    XrayFile.withFileMeta(cd);
    assertNotNull(server.handOver(cd, null));
    byte[] b = Files.readAllBytes(cd);
    assertEquals("DICM", new String(b, 128, 4, StandardCharsets.US_ASCII));
  }
}
