/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.templating;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.io.DicomInputStream.IncludeBulkData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the templating page offline: serves its viewer build on 127.0.0.1 and answers for the archive
 * for each X-ray handed over, under /t/&lt;token&gt;/:
 *
 * <ul>
 *   <li>{@code templating/...}: the page's files;
 *   <li>{@code dicomweb/studies/<study>/instances}: that X-ray, or with SOPInstanceUID= the plans
 *       held for it;
 *   <li>{@code wado?...}: the X-ray's file, byte for byte;
 *   <li>{@code templating/store} (POST): a plan, checked by {@link PlanCheck}.
 * </ul>
 *
 * Every request must name the Host 127.0.0.1:&lt;port&gt;; a POST must carry the X-AOC-Templating
 * header (another site cannot add it without a CORS preflight, which is never granted) and, when the
 * browser sends an Origin, the page's own; an unknown or ended token is answered 404. The protocol and
 * its reference implementation are in the templating project's workflow/28o_templating_viewer.py.
 */
public final class TemplatingServer implements Closeable {

  private static final Logger LOGGER = LoggerFactory.getLogger(TemplatingServer.class);

  public static final int PREFERRED_PORT = 47210;
  static final int MAX_PLAN = 30 * 1024 * 1024;
  static final Duration LIFETIME = Duration.ofHours(12);
  static final int MAX_HANDOVERS = 64;
  static final String CSP =
      "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline';"
          + " img-src 'self' data: blob:; connect-src 'self'; worker-src 'self' blob:;"
          + " object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
  private static final Pattern TOKEN_PATH = Pattern.compile("/t/([0-9a-f]{32})/(.*)");
  private static final Pattern QIDO = Pattern.compile("dicomweb/studies/([0-9.]{1,64})/instances");
  private static final byte[] ENDED =
      ("<!doctype html><meta charset=\"utf-8\"><title>Templating</title>"
              + "<p>This templating link has ended. Press Template in the viewer again.</p>")
          .getBytes(StandardCharsets.UTF_8);
  private static final String JSON = "application/json";

  /** Receives a plan the page saved, once it passed every check and was written to disk. */
  @FunctionalInterface
  public interface PlanSink {
    void accept(Handover handover, Handover.Plan plan);
  }

  private final HttpServer server;
  private final ExecutorService executor;
  private final int port;
  private final String host;
  private final String origin;
  private final PageFiles pages;
  private final Path workDir;
  private final PlanSink sink;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();
  private final Map<String, Handover> handovers = new ConcurrentHashMap<>();

  /**
   * Starts the server on {@code preferredPort} of 127.0.0.1, or on any free port when that one is
   * taken or is 0. The page keeps its in-browser plans per address, so a steady port keeps them.
   */
  public TemplatingServer(
      int preferredPort, PageFiles pages, Path workDir, PlanSink sink, Clock clock)
      throws IOException {
    this.pages = pages;
    this.workDir = Files.createDirectories(workDir);
    this.sink = sink;
    this.clock = clock;
    this.server = bind(preferredPort);
    this.port = server.getAddress().getPort();
    this.host = "127.0.0.1:" + port;
    this.origin = "http://" + host;
    this.executor =
        Executors.newFixedThreadPool(
            4,
            r -> {
              Thread t = new Thread(r, "templating-http");
              t.setDaemon(true);
              return t;
            });
    server.setExecutor(executor);
    server.createContext("/", this::handle);
    server.start();
    LOGGER.info("Templating page (build {}) on {}", pages.build(), origin);
  }

  private static HttpServer bind(int preferredPort) throws IOException {
    InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
    if (preferredPort > 0) {
      try {
        return HttpServer.create(new InetSocketAddress(loopback, preferredPort), 16);
      } catch (BindException e) {
        LOGGER.info("Port {} is taken: the templating page uses another one", preferredPort);
      }
    }
    return HttpServer.create(new InetSocketAddress(loopback, 0), 16);
  }

  public int port() {
    return port;
  }

  /**
   * Hands over one X-ray (a DICOM file with its file meta information, kept until the hand-over
   * ends) and returns the page's address for it.
   */
  public URI handOver(Path xray, Object owner) throws IOException {
    Attributes header;
    try (DicomInputStream in = new DicomInputStream(xray.toFile())) {
      in.setIncludeBulkData(IncludeBulkData.NO);
      header = in.readDatasetUntilPixelData();
    }
    Handover h = new Handover(newToken(), xray, header, clock.instant(), owner);
    if (!PlanCheck.isUid(h.study()) || !PlanCheck.isUid(h.sop())) {
      throw new IOException("The X-ray has no valid study or instance UID");
    }
    prune();
    handovers.put(h.token(), h);
    return URI.create(
        origin
            + "/t/"
            + h.token()
            + "/templating/?study="
            + URLEncoder.encode(h.study(), StandardCharsets.UTF_8)
            + "&instance="
            + URLEncoder.encode(h.sop(), StandardCharsets.UTF_8));
  }

  /** The live hand-over for a token, or null. */
  Handover handover(String token) {
    Handover h = handovers.get(token);
    if (h != null && h.created().plus(LIFETIME).isBefore(clock.instant())) {
      handovers.remove(token, h);
      return null;
    }
    return h;
  }

  private void prune() {
    handovers.values().removeIf(h -> h.created().plus(LIFETIME).isBefore(clock.instant()));
    while (handovers.size() >= MAX_HANDOVERS) {
      handovers.values().stream()
          .min(Comparator.comparing(Handover::created))
          .ifPresent(old -> handovers.remove(old.token(), old));
    }
  }

  private String newToken() {
    byte[] b = new byte[16];
    random.nextBytes(b);
    return HexFormat.of().formatHex(b);
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
    handovers.clear();
  }

  private void handle(HttpExchange ex) {
    try {
      respond(ex);
    } catch (IOException | RuntimeException e) {
      LOGGER.debug("Templating request {} failed", ex.getRequestURI().getRawPath(), e);
    } finally {
      ex.close();
    }
  }

  private void respond(HttpExchange ex) throws IOException {
    String method = ex.getRequestMethod();
    byte[] body = null;
    if ("POST".equals(method)) {
      long n = contentLength(ex.getRequestHeaders());
      if (n <= 0 || n > MAX_PLAN) {
        send(ex, n < 0 ? 411 : 413, error("the plan is empty or over 30 MB"), JSON);
        return;
      }
      body = ex.getRequestBody().readNBytes((int) n); // read first, so a refusal reaches the page
      if (body.length != n) {
        send(ex, 400, error("the plan arrived incomplete"), JSON);
        return;
      }
    } else if (!"GET".equals(method) && !"HEAD".equals(method)) {
      send(ex, 405, error("not allowed"), JSON);
      return;
    }
    if (!host.equals(ex.getRequestHeaders().getFirst("Host"))) {
      send(ex, 400, error("wrong host"), JSON);
      return;
    }
    Matcher m = TOKEN_PATH.matcher(ex.getRequestURI().getRawPath());
    Handover h = m.matches() ? handover(m.group(1)) : null;
    if (h == null) {
      send(ex, 404, ENDED, "text/html; charset=utf-8");
      return;
    }
    String rest = m.group(2);
    if (body != null) {
      store(ex, h, rest, body);
    } else if ("templating".equals(rest)) {
      ex.getResponseHeaders().set("Location", "/t/" + h.token() + "/templating/");
      send(ex, 301, new byte[0], "text/plain; charset=utf-8");
    } else if (rest.startsWith("templating/")) {
      String name = rest.substring("templating/".length());
      byte[] file = pages.get(name.isEmpty() ? "index.html" : name);
      if (file == null) {
        send(ex, 404, error("no such file"), JSON);
      } else {
        send(ex, 200, file, PageFiles.type(name.isEmpty() ? "index.html" : name));
      }
    } else if (QIDO.matcher(rest).matches()) {
      qido(ex, h, rest);
    } else if ("wado".equals(rest)) {
      wado(ex, h);
    } else {
      send(ex, 404, error("no such address"), JSON);
    }
  }

  private void qido(HttpExchange ex, Handover h, String rest) throws IOException {
    Matcher m = QIDO.matcher(rest);
    if (!m.matches() || !m.group(1).equals(h.study())) {
      send(ex, 200, "[]".getBytes(StandardCharsets.UTF_8), "application/dicom+json");
      return;
    }
    Map<String, String> q = query(ex.getRequestURI().getRawQuery());
    String json;
    if (q.containsKey("SOPInstanceUID")) {
      Handover.Plan p = h.plan(q.get("SOPInstanceUID"));
      json =
          p == null
              ? "[]"
              : "["
                  + instance(
                      org.dcm4che3.data.UID.SecondaryCaptureImageStorage,
                      p.sop(),
                      "OT",
                      "",
                      h.study(),
                      p.series(),
                      "1")
                  + "]";
    } else {
      json =
          "["
              + instance(
                  h.sopClass(),
                  h.sop(),
                  h.modality(),
                  h.seriesDescription(),
                  h.study(),
                  h.series(),
                  h.instanceNumber())
              + "]";
    }
    send(ex, 200, json.getBytes(StandardCharsets.UTF_8), "application/dicom+json");
  }

  private void wado(HttpExchange ex, Handover h) throws IOException {
    Map<String, String> q = query(ex.getRequestURI().getRawQuery());
    if (!h.sop().equals(q.get("objectUID")) || !h.study().equals(q.get("studyUID"))) {
      send(ex, 404, error("not this X-ray"), JSON);
      return;
    }
    headers(ex, "application/dicom");
    long size = Files.size(h.xray());
    if ("HEAD".equals(ex.getRequestMethod())) {
      ex.sendResponseHeaders(200, -1);
      return;
    }
    ex.sendResponseHeaders(200, size);
    try (OutputStream out = ex.getResponseBody()) {
      Files.copy(h.xray(), out);
    }
  }

  private void store(HttpExchange ex, Handover h, String rest, byte[] body) throws IOException {
    if (!"templating/store".equals(rest)) {
      send(ex, 404, error("no such address"), JSON);
      return;
    }
    Headers in = ex.getRequestHeaders();
    String from = in.getFirst("Origin");
    if (!"1".equals(in.getFirst("X-AOC-Templating")) || (from != null && !origin.equals(from))) {
      send(ex, 403, error("only the templating page can save here"), JSON);
      return;
    }
    String type = in.getFirst("Content-Type");
    if (type == null
        || !type.split(";")[0].trim().toLowerCase(Locale.ROOT).equals("application/dicom")) {
      send(ex, 415, error("not a DICOM file"), JSON);
      return;
    }
    PlanCheck.Result r = PlanCheck.check(body, h);
    if (!r.accepted()) {
      send(ex, r.status(), error(r.error()), JSON);
      return;
    }
    Handover.Plan plan;
    synchronized (h) {
      if (h.plan(r.sop()) != null) {
        send(
            ex,
            409,
            ("{\"error\":\"this plan is in the viewer already\",\"sop\":" + str(r.sop()) + "}")
                .getBytes(StandardCharsets.UTF_8),
            JSON);
        return;
      }
      Path dir = Files.createDirectories(workDir.resolve(h.token()));
      Path part = Files.createTempFile(dir, "plan-", ".part");
      Files.write(part, body);
      Path file = dir.resolve("plan-" + r.sop() + ".dcm");
      Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      plan = new Handover.Plan(r.sop(), r.series(), file);
      h.addPlan(plan);
    }
    try {
      sink.accept(h, plan);
    } catch (RuntimeException e) {
      LOGGER.error("Templating plan stored but not loaded in the explorer", e);
    }
    send(
        ex,
        200,
        ("{\"ok\":true,\"sop\":"
                + str(plan.sop())
                + ",\"series\":"
                + str(plan.series())
                + ",\"result\":\"stored\"}")
            .getBytes(StandardCharsets.UTF_8),
        JSON);
  }

  private static long contentLength(Headers h) {
    String v = h.getFirst("Content-Length");
    if (v == null || h.containsKey("Transfer-Encoding")) {
      return -1;
    }
    try {
      return Long.parseLong(v.trim());
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private static Map<String, String> query(String raw) {
    Map<String, String> q = new HashMap<>();
    if (raw == null) {
      return q;
    }
    for (String part : raw.split("&")) {
      int eq = part.indexOf('=');
      String k = URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), StandardCharsets.UTF_8);
      String v = eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
      q.putIfAbsent(k, v);
    }
    return q;
  }

  private static String instance(
      String sopClass,
      String sop,
      String modality,
      String description,
      String study,
      String series,
      String number) {
    return "{"
        + tag("00080016", "UI", sopClass)
        + ","
        + tag("00080018", "UI", sop)
        + ","
        + tag("00080060", "CS", modality)
        + ","
        + tag("0008103E", "LO", description)
        + ","
        + tag("0020000D", "UI", study)
        + ","
        + tag("0020000E", "UI", series)
        + ","
        + tag("00200013", "IS", number)
        + "}";
  }

  private static String tag(String tag, String vr, String value) {
    return "\""
        + tag
        + "\":{\"vr\":\""
        + vr
        + "\""
        + (value == null || value.isEmpty() ? "" : ",\"Value\":[" + str(value) + "]")
        + "}";
  }

  /** A JSON string literal. */
  static String str(String s) {
    StringBuilder b = new StringBuilder(s.length() + 2).append('"');
    for (char c : s.toCharArray()) {
      switch (c) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> {
          if (c < 0x20 || c == '<' || c == '>' || c == '&' || c == 0x2028 || c == 0x2029) {
            b.append(String.format("\\u%04x", (int) c));
          } else {
            b.append(c);
          }
        }
      }
    }
    return b.append('"').toString();
  }

  private static byte[] error(String message) {
    return ("{\"error\":" + str(message) + "}").getBytes(StandardCharsets.UTF_8);
  }

  private static void headers(HttpExchange ex, String type) {
    Headers out = ex.getResponseHeaders();
    out.set("Content-Type", type);
    out.set("Cache-Control", "no-store");
    out.set("X-Content-Type-Options", "nosniff");
    out.set("Referrer-Policy", "no-referrer");
    out.set("Content-Security-Policy", CSP);
    out.set("Cross-Origin-Resource-Policy", "same-origin");
    out.set("X-Frame-Options", "DENY");
  }

  private static void send(HttpExchange ex, int status, byte[] body, String type)
      throws IOException {
    headers(ex, type);
    if ("HEAD".equals(ex.getRequestMethod()) || body.length == 0) {
      ex.sendResponseHeaders(status, -1);
      return;
    }
    ex.sendResponseHeaders(status, body.length);
    try (OutputStream out = ex.getResponseBody()) {
      out.write(body);
    }
  }
}
