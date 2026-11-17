package com.example.shortlink.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.shortlink.ShortLinkApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    classes = ShortLinkApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:acceptance;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=1000",
      "app.rate-limit-enabled=false"
    })
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class ShareFlowTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  private static final String TOKEN = "Bearer demo-only-token";

  @BeforeEach
  void reset() throws Exception {
    mvc.perform(post("/api/demo/reset").header("Authorization", TOKEN))
        .andExpect(status().isNoContent());
  }

  String create(long id) throws Exception {
    var response =
        mvc.perform(
                post("/api/links")
                    .header("Authorization", TOKEN)
                    .contentType("application/json")
                    .content(
                        "{\"productId\":" + id + ",\"sharerId\":\"alice\",\"channel\":\"wechat\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse();
    return mapper.readTree(response.getContentAsString()).get("key").asText();
  }

  @Test
  void jsonTypesAndHttpProtocolErrorsAreNotSilentlyCoercedOrReportedAs500() throws Exception {
    for (String body :
        new String[] {
          "{\"productId\":1001.9,\"sharerId\":\"alice\",\"channel\":\"wechat\"}",
          "{\"productId\":1001,\"sharerId\":123,\"channel\":\"wechat\"}"
        }) {
      mvc.perform(
              post("/api/links")
                  .header("Authorization", TOKEN)
                  .contentType("application/json")
                  .content(body))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(get("/api/links").header("Authorization", TOKEN))
        .andExpect(status().isMethodNotAllowed());
    mvc.perform(
            post("/api/links")
                .header("Authorization", TOKEN)
                .contentType("text/plain")
                .content("{}"))
        .andExpect(status().isUnsupportedMediaType());
  }

  @Test
  void creationCardHeadRedirectAndStatisticsFormOneFlow() throws Exception {
    String key = create(1001);
    mvc.perform(
            post("/api/links")
                .header("Authorization", TOKEN)
                .contentType("application/json")
                .content("{\"productId\":1001,\"sharerId\":\"alice\",\"channel\":\"wechat\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.key").value(key))
        .andExpect(jsonPath("$.reused").value(true));
    String html =
        mvc.perform(get("/c/" + key).header("Host", "evil.example"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var doc = Jsoup.parse(html);
    assertThat(doc.select("meta[property^=og:]")).hasSize(5);
    assertThat(doc.selectFirst("meta[property=og:url]").attr("content"))
        .isEqualTo("http://localhost:8080/c/" + key);
    mvc.perform(head("/s/" + key))
        .andExpect(status().isFound())
        .andExpect(header().doesNotExist("Set-Cookie"));
    assertThat(jdbc.queryForObject("select count(*) from visit_event", Long.class)).isZero();
    var redirect =
        mvc.perform(get("/s/" + key + "?channel=other&sharerId=mallory"))
            .andExpect(status().isFound())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn()
            .getResponse();
    assertThat(redirect.getHeader("Location"))
        .isEqualTo("/products/1001?channel=wechat&sharerId=alice");
    assertThat(redirect.getHeader("Set-Cookie")).contains("HttpOnly", "SameSite=Lax");
    assertThat(jdbc.queryForObject("select count(*) from visit_event", Long.class)).isEqualTo(1);
    mvc.perform(get(redirect.getHeader("Location"))).andExpect(status().isOk());
    assertThat(jdbc.queryForObject("select count(*) from visit_event", Long.class)).isEqualTo(1);
  }

  @Test
  void cachedLinkObservesOffShelfAndDeletedProduct() throws Exception {
    String key = create(1001);
    mvc.perform(get("/c/" + key)).andExpect(status().isOk());
    mvc.perform(
            patch("/api/demo/products/1001")
                .header("Authorization", TOKEN)
                .contentType("application/json")
                .content("{\"status\":\"OFF_SHELF\"}"))
        .andExpect(status().isOk());
    mvc.perform(get("/s/" + key)).andExpect(status().isGone());
    String html =
        mvc.perform(get("/c/" + key))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(html).contains("商品暂不可购买").doesNotContain("href=\"/s/");
    mvc.perform(delete("/api/demo/products/1001").header("Authorization", TOKEN))
        .andExpect(status().isNoContent());
    mvc.perform(get("/s/" + key)).andExpect(status().isGone());
    assertThat(
            mvc.perform(get("/c/" + key))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
        .contains("商品已不可用");
    assertThat(jdbc.queryForObject("select count(*) from visit_event", Long.class)).isZero();
  }

  @Test
  void missingMetadataReturnsServerSideFallbackAndSafeImage() throws Exception {
    String key = create(1002);
    String html =
        mvc.perform(get("/c/" + key))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(html).contains("精选商品");
    byte[] image =
        mvc.perform(get("/media/products/1002.png"))
            .andExpect(status().isOk())
            .andExpect(content().contentType("image/png"))
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    assertThat(image).startsWith((byte) 137, (byte) 80, (byte) 78, (byte) 71);
    mvc.perform(
            patch("/api/demo/products/1002")
                .header("Authorization", TOKEN)
                .contentType("application/json")
                .content("{\"title\":\"<script>alert(1)</script>\\\"中文🙂\"}"))
        .andExpect(status().isOk());
    var doc =
        Jsoup.parse(mvc.perform(get("/c/" + key)).andReturn().getResponse().getContentAsString());
    assertThat(doc.select("script")).isEmpty();
    assertThat(doc.selectFirst("meta[property=og:title]").attr("content"))
        .contains("<script>", "中文🙂");
  }

  @Test
  void validationAuthAndMissingLinksHaveSafeErrors() throws Exception {
    mvc.perform(post("/api/links").contentType("application/json").content("{}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.requestId").isNotEmpty());
    mvc.perform(get("/api/stats")).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/links")
                .header("Authorization", TOKEN)
                .contentType("application/json")
                .content(
                    "{\"productId\":1001,\"sharerId\":\"alice\",\"channel\":\"wechat\",\"targetUrl\":\"https://evil.example\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/links")
                .header("Authorization", TOKEN)
                .contentType("application/json")
                .content("{\"sharerId\":\"" + "x".repeat(9000) + "\"}"))
        .andExpect(status().isPayloadTooLarge());
    for (String key : new String[] {"000", "aaaaaaaaaaaa", "zzzzzzzzzzz", "missing"}) {
      String html =
          mvc.perform(get("/s/" + key))
              .andExpect(status().isNotFound())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(html).contains("链接").doesNotContain("Exception", "at com.example");
    }
  }

  @Test
  void previewBotsDoNotCountButWechatBrowserDoes() throws Exception {
    String key = create(1001);
    mvc.perform(get("/s/" + key).header("User-Agent", "facebookexternalhit/1.1"))
        .andExpect(status().isFound())
        .andExpect(header().doesNotExist("Set-Cookie"));
    assertThat(jdbc.queryForObject("select count(*) from visit_event", Long.class)).isZero();
    mvc.perform(get("/s/" + key).header("User-Agent", "Mozilla/5.0 MicroMessenger/8.0"))
        .andExpect(status().isFound());
    assertThat(jdbc.queryForObject("select count(*) from visit_event", Long.class)).isEqualTo(1);
  }
}
