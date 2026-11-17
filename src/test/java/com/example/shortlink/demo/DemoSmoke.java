package com.example.shortlink.demo;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

/** Explicit live HTTP demo. Its name deliberately does not match normal Surefire test includes. */
class DemoSmoke {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);
  private static final Path STATE = Path.of("target", "demo-state.json");
  private final URI base =
      URI.create(
          System.getProperty("demo.baseUrl", "http://localhost:8080").replaceAll("/+$", "") + "/");
  private final String token =
      System.getProperty(
          "demo.token", System.getenv().getOrDefault("DEMO_API_TOKEN", "demo-only-token"));
  private final Browser admin = browser();
  private final Browser visitorA = browser();
  private final Browser visitorB = browser();
  private LocalDate from = LocalDate.now(ZoneId.of("Asia/Hong_Kong"));
  private LocalDate to = from.plusDays(1);
  private long lastRequest;
  private String key;
  private String secondKey;
  private String missingKey;
  private String shareUrl;

  @Test
  void runDemoAgainstRunningServer() throws Exception {
    awaitHealth();
    if (Boolean.getBoolean("demo.verifyOnly")) {
      verifyPersistedState();
      return;
    }
    System.out.println("将重置当前 Demo 数据 / Resetting current Demo data at " + base);
    expect(request(admin, "POST", "/api/demo/reset", null, true), 204);

    step(
        1,
        "health, create and reuse",
        () -> {
          var created = create(1001, "user_001", "wechat", 201);
          key = created.path("key").asText();
          shareUrl = created.path("shareUrl").asText();
          assertTrue(key.matches("[A-Za-z0-9]{1,11}"));
          assertFalse(created.path("reused").asBoolean());
          var reused = create(1001, "user_001", "wechat", 200);
          assertEquals(key, reused.path("key").asText());
          assertTrue(reused.path("reused").asBoolean());
          missingKey = key.equals("1") ? "2" : "1";
        });
    step(
        2,
        "server-rendered OG metadata and PNG",
        () -> {
          var card = expect(request(admin, "GET", "/c/" + key, null, false), 200);
          Document document = card(card);
          assertEquals(shareUrl, meta(document, "url"));
          png(request(admin, "GET", URI.create(meta(document, "image")).getRawPath(), null, false));
        });
    step(
        3,
        "visitor A redirect and manual product page",
        () -> {
          var redirect =
              expect(
                  request(
                      visitorA,
                      "GET",
                      "/s/" + key + "?channel=other&sharerId=mallory",
                      null,
                      false),
                  302);
          assertEquals(
              "/products/1001?channel=wechat&sharerId=user_001",
              redirect.headers().firstValue("Location").orElseThrow());
          assertEquals("no-store", redirect.headers().firstValue("Cache-Control").orElseThrow());
          String cookie = redirect.headers().firstValue("Set-Cookie").orElseThrow();
          assertTrue(cookie.contains("HttpOnly"));
          assertTrue(cookie.contains("SameSite=Lax"));
          assertTrue(cookie.contains("Path=/"));
          assertTrue(cookie.contains("Max-Age=2592000"));
          assertTrue(
              visitorA.cookies().getCookieStore().getCookies().stream()
                  .anyMatch(c -> c.getName().equals("sl_vid")));
          expect(
              request(
                  visitorA,
                  "GET",
                  redirect.headers().firstValue("Location").orElseThrow(),
                  null,
                  false),
              200);
        });
    step(
        4,
        "two browser identities produce PV=2 UV=2",
        () -> {
          metrics(1, 1);
          expect(request(visitorB, "GET", "/s/" + key, null, false), 302);
          metrics(2, 2);
        });
    step(
        5,
        "HEAD and card requests leave counters unchanged",
        () -> {
          Browser headVisitor = browser();
          var head = expect(request(headVisitor, "HEAD", "/s/" + key, null, false), 302);
          assertEquals(0, head.body().length);
          assertTrue(head.headers().allValues("Set-Cookie").isEmpty());
          assertTrue(headVisitor.cookies().getCookieStore().getCookies().isEmpty());
          expect(request(visitorA, "GET", "/c/" + key, null, false), 200);
          metrics(2, 2);
        });
    step(
        6,
        "cross-channel attribution preserves global UV",
        () -> {
          secondKey = create(1001, "bob", "moments", 201).path("key").asText();
          var redirect = expect(request(visitorA, "GET", "/s/" + secondKey, null, false), 302);
          assertEquals(
              "/products/1001?channel=moments&sharerId=bob",
              redirect.headers().firstValue("Location").orElseThrow());
          JsonNode result = metrics(3, 2);
          assertGroups(result);
        });
    step(
        7,
        "missing and illegal keys have friendly 404 pages",
        () -> {
          friendly(expect(request(admin, "GET", "/s/" + missingKey, null, false), 404));
          friendly(expect(request(admin, "GET", "/s/000", null, false), 404));
        });
    step(
        8,
        "missing product metadata has title and image fallbacks",
        () -> {
          expect(request(admin, "PATCH", "/api/demo/products/1002", "{\"title\":\"\"}", true), 200);
          String fallbackKey = create(1002, "fallback", "other", 201).path("key").asText();
          Document document =
              card(expect(request(admin, "GET", "/c/" + fallbackKey, null, false), 200));
          assertEquals("精选商品", meta(document, "title"));
          var fallback =
              png(
                  request(
                      admin, "GET", URI.create(meta(document, "image")).getRawPath(), null, false));
          var builtIn =
              expect(request(admin, "GET", "/images/default-product.png", null, false), 200);
          assertArrayEquals(builtIn.body(), fallback.body());
        });
    step(
        9,
        "off-shelf and deleted products retain safe cards without counting",
        () -> {
          expect(
              request(
                  admin, "PATCH", "/api/demo/products/1001", "{\"status\":\"OFF_SHELF\"}", true),
              200);
          expect(request(visitorA, "GET", "/s/" + key, null, false), 410);
          unavailableCard("商品暂不可购买");
          expect(request(admin, "DELETE", "/api/demo/products/1001", null, true), 204);
          expect(request(visitorA, "GET", "/s/" + key, null, false), 410);
          unavailableCard("商品已不可用");
          metrics(3, 2);
          saveState();
        });
    step(
        10,
        "burst receives 429 with integer Retry-After",
        () -> {
          HttpResponse<byte[]> limited = null;
          for (int i = 0; i < 200; i++) {
            var response = raw(admin, "GET", "/c/" + missingKey, null, false);
            if (response.statusCode() == 429) {
              limited = response;
              break;
            }
            expect(response, 404);
          }
          assertNotNull(limited, "200 burst requests must exhaust the IP token bucket");
          assertTrue(
              limited.headers().firstValue("Retry-After").orElseThrow().matches("[1-9][0-9]*"));
          friendly(limited);
        });
    System.out.println(
        "PASS all 10 steps; preserved PV=3 UV=2 and deleted product1001; restart verification state: "
            + STATE.toAbsolutePath());
  }

  private void verifyPersistedState() throws Exception {
    assertTrue(
        Files.isRegularFile(STATE),
        "Run the full demo once before verifyOnly; state is " + STATE.toAbsolutePath());
    JsonNode state = JSON.readTree(Files.readString(STATE));
    assertEquals(
        base.toString(),
        state.path("baseUrl").asText(),
        "Use the same demo.baseUrl as the saved run");
    key = state.path("key").asText();
    secondKey = state.path("secondKey").asText();
    from = LocalDate.parse(state.path("from").asText());
    to = LocalDate.parse(state.path("to").asText());
    step(1, "persisted aggregate and attribution without reset", () -> assertGroups(metrics(3, 2)));
    step(
        2,
        "persisted short links and deleted product state",
        () -> {
          expect(request(visitorA, "GET", "/s/" + key, null, false), 410);
          expect(request(visitorA, "GET", "/s/" + secondKey, null, false), 410);
          unavailableCard("商品已不可用");
          expect(request(admin, "GET", "/products/1001", null, false), 404);
          metrics(3, 2);
        });
    System.out.println("PASS persistence verification; no reset or product mutation performed");
  }

  private void awaitHealth() throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (System.nanoTime() < deadline) {
      try {
        var health = raw(admin, "GET", "/actuator/health", null, false);
        if (health.statusCode() == 200
            && JSON.readTree(health.body()).path("status").asText().equals("UP")) {
          return;
        }
      } catch (IOException ignored) {
        /* The external server may still be starting. */
      }
      Thread.sleep(250);
    }
    fail("Health did not become UP within 60 seconds: " + base);
  }

  private JsonNode create(long product, String sharer, String channel, int status)
      throws Exception {
    String body =
        JSON.writeValueAsString(
            Map.of("productId", product, "sharerId", sharer, "channel", channel));
    return JSON.readTree(expect(request(admin, "POST", "/api/links", body, true), status).body());
  }

  private JsonNode metrics(long pv, long uv) throws Exception {
    String path = "/api/stats?from=" + from + "&to=" + to + "&groupBy=channel,sharerId";
    JsonNode result = JSON.readTree(expect(request(admin, "GET", path, null, true), 200).body());
    assertEquals("Asia/Hong_Kong", result.path("timezone").asText());
    assertEquals("v1-effective-click", result.path("metricVersion").asText());
    assertEquals(from.toString(), result.path("from").asText());
    assertEquals(to.toString(), result.path("to").asText());
    assertEquals(pv, result.path("total").path("pv").asLong(-1), "total PV");
    assertEquals(uv, result.path("total").path("uv").asLong(-1), "total UV");
    return result;
  }

  private void assertGroups(JsonNode result) {
    assertEquals(2, result.path("groups").size());
    Map<String, JsonNode> groups = new HashMap<>();
    long uvSum = 0;
    long pvSum = 0;
    for (JsonNode group : result.path("groups")) {
      groups.put(group.path("channel").asText() + ":" + group.path("sharerId").asText(), group);
      uvSum += group.path("uv").asLong();
      pvSum += group.path("pv").asLong();
    }
    assertEquals(2, groups.get("wechat:user_001").path("pv").asLong());
    assertEquals(2, groups.get("wechat:user_001").path("uv").asLong());
    assertEquals(1, groups.get("moments:bob").path("pv").asLong());
    assertEquals(1, groups.get("moments:bob").path("uv").asLong());
    assertEquals(3, pvSum);
    assertEquals(3, uvSum);
    assertTrue(uvSum > result.path("total").path("uv").asLong());
  }

  private Document card(HttpResponse<byte[]> response) {
    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    Document document = Jsoup.parse(text(response));
    assertEquals(5, document.select("meta[property^=og:]").size());
    assertEquals("product", meta(document, "type"));
    assertFalse(meta(document, "title").isBlank());
    assertFalse(meta(document, "description").isBlank());
    for (String name : new String[] {"image", "url"}) {
      URI uri = URI.create(meta(document, name));
      assertTrue(uri.isAbsolute());
      assertTrue(uri.getScheme().equals("http") || uri.getScheme().equals("https"));
    }
    return document;
  }

  private String meta(Document document, String name) {
    var element = document.selectFirst("meta[property=og:" + name + "]");
    assertNotNull(element, "Missing og:" + name);
    return element.attr("content");
  }

  private HttpResponse<byte[]> png(HttpResponse<byte[]> response) {
    expect(response, 200);
    assertEquals("image/png", response.headers().firstValue("Content-Type").orElseThrow());
    assertTrue(response.body().length >= 8);
    assertArrayEquals(
        new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10},
        java.util.Arrays.copyOf(response.body(), 8));
    return response;
  }

  private void unavailableCard(String title) throws Exception {
    Document document = card(expect(request(admin, "GET", "/c/" + key, null, false), 200));
    assertEquals(title, meta(document, "title"));
    assertTrue(document.select("a[href='/s/" + key + "']").isEmpty());
  }

  private void friendly(HttpResponse<byte[]> response) {
    Document document = Jsoup.parse(text(response));
    assertFalse(document.select("a[href='/']").isEmpty(), "Friendly page links home");
    assertFalse(text(response).contains("Exception"));
    assertFalse(text(response).contains("at com.example"));
  }

  private void saveState() throws Exception {
    var state = JSON.createObjectNode();
    state.put("baseUrl", base.toString());
    state.put("recordedAt", Instant.now().toString());
    state.put("from", from.toString());
    state.put("to", to.toString());
    state.put("key", key);
    state.put("secondKey", secondKey);
    state.put("pv", 3);
    state.put("uv", 2);
    state.put("product1001", "DELETED");
    Files.createDirectories(STATE.getParent());
    Files.writeString(STATE, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(state));
  }

  private HttpResponse<byte[]> request(
      Browser browser, String method, String path, String body, boolean authenticated)
      throws Exception {
    for (int attempt = 0; attempt < 10; attempt++) {
      long waitNanos = Duration.ofMillis(600).toNanos() - (System.nanoTime() - lastRequest);
      if (waitNanos > 0) {
        java.util.concurrent.TimeUnit.NANOSECONDS.sleep(waitNanos);
      }
      lastRequest = System.nanoTime();
      var response = raw(browser, method, path, body, authenticated);
      if (response.statusCode() != 429) {
        return response;
      }
      int seconds = Integer.parseInt(response.headers().firstValue("Retry-After").orElseThrow());
      assertTrue(seconds > 0 && seconds <= 60, "Bounded integer Retry-After");
      Thread.sleep(Duration.ofSeconds(seconds).toMillis());
    }
    throw new AssertionError(
        "Normal request remained rate limited after 10 attempts: " + method + " " + path);
  }

  private HttpResponse<byte[]> raw(
      Browser browser, String method, String path, String body, boolean authenticated)
      throws IOException, InterruptedException {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(base.resolve(path))
            .timeout(REQUEST_TIMEOUT)
            .header("User-Agent", "DemoSmoke Mozilla/5.0 MicroMessenger/8.0");
    if (authenticated) {
      builder.header("Authorization", "Bearer " + token);
    }
    if (body != null) {
      builder.header("Content-Type", "application/json");
    }
    builder.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    return browser.client().send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  private HttpResponse<byte[]> expect(HttpResponse<byte[]> response, int status) {
    assertEquals(
        status,
        response.statusCode(),
        () ->
            response.request().method()
                + " "
                + response.uri()
                + " body="
                + text(response).substring(0, Math.min(300, text(response).length())));
    return response;
  }

  private String text(HttpResponse<byte[]> response) {
    return new String(response.body(), StandardCharsets.UTF_8);
  }

  private static Browser browser() {
    var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    return new Browser(
        HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .cookieHandler(cookies)
            .build(),
        cookies);
  }

  private void step(int number, String label, CheckedAction action) throws Exception {
    try {
      action.run();
      System.out.printf("PASS %02d %s%n", number, label);
    } catch (Exception | AssertionError failure) {
      System.out.printf("FAIL %02d %s: %s%n", number, label, failure.getMessage());
      throw failure;
    }
  }

  private record Browser(HttpClient client, CookieManager cookies) {}

  @FunctionalInterface
  private interface CheckedAction {
    void run() throws Exception;
  }
}
