package com.example.shortlink.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.shortlink.ShortLinkApplication;
import com.example.shortlink.link.LinkService;
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
      "spring.datasource.url=jdbc:h2:mem:workbench;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=1000",
      "app.rate-limit-enabled=false",
      "app.api-token=private-workbench-test-token"
    })
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class WorkbenchTest {
  private static final String AUTH = "Bearer private-workbench-test-token";
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired LinkService links;
  @Autowired ObjectMapper mapper;

  @BeforeEach
  void reset() throws Exception {
    mvc.perform(post("/api/demo/reset").header("Authorization", AUTH))
        .andExpect(status().isNoContent());
  }

  @Test
  void productSelectionIncludesDeletedDemoSlotsAndRequiresAuthentication() throws Exception {
    mvc.perform(get("/api/demo/products")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/demo/links")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/demo/products/1001/restore")).andExpect(status().isUnauthorized());
    mvc.perform(delete("/api/demo/products/1001").header("Authorization", AUTH))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/demo/products").header("Authorization", AUTH))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(1001))
        .andExpect(jsonPath("$[0].status").value("DELETED"))
        .andExpect(jsonPath("$[1].id").value(1002))
        .andExpect(jsonPath("$[1].status").value("ACTIVE"));
  }

  @Test
  void restorePreservesExistingLinksStatisticsAndEditedProducts() throws Exception {
    String key = links.create(1001, "page_user", "wechat").link().key();
    mvc.perform(get("/s/" + key)).andExpect(status().isFound());
    mvc.perform(delete("/api/demo/products/1001").header("Authorization", AUTH))
        .andExpect(status().isNoContent());
    mvc.perform(get("/s/" + key)).andExpect(status().isGone());
    mvc.perform(post("/api/demo/products/1001/restore").header("Authorization", AUTH))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM visit_event", Long.class)).isEqualTo(1L);
    mvc.perform(get("/c/" + key)).andExpect(status().isOk());
    mvc.perform(
            patch("/api/demo/products/1001")
                .header("Authorization", AUTH)
                .contentType("application/json")
                .content("{\"title\":\"我的标题\",\"status\":\"OFF_SHELF\"}"))
        .andExpect(status().isOk());
    mvc.perform(post("/api/demo/products/1001/restore").header("Authorization", AUTH))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("我的标题"))
        .andExpect(jsonPath("$.status").value("OFF_SHELF"));
    mvc.perform(post("/api/demo/products/999/restore").header("Authorization", AUTH))
        .andExpect(status().isNotFound());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM short_link", Long.class)).isEqualTo(1L);
  }

  @Test
  void linkHistoryIsBoundedAndCarriesAttributionAndDeletedStatus() throws Exception {
    for (int i = 0; i < 102; i++) links.create(1001, "user_" + i, "wechat");
    mvc.perform(delete("/api/demo/products/1001").header("Authorization", AUTH))
        .andExpect(status().isNoContent());
    String json =
        mvc.perform(get("/api/demo/links").header("Authorization", AUTH))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(100))
            .andExpect(jsonPath("$[0].productStatus").value("DELETED"))
            .andExpect(jsonPath("$[0].channel").value("wechat"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var items = mapper.readTree(json);
    assertThat(items.get(0).get("shortUrl").asText()).startsWith("http://localhost:8080/s/");
    assertThat(items.get(0).get("shareUrl").asText()).startsWith("http://localhost:8080/c/");
    var newest =
        jdbc.queryForObject(
            "SELECT link_key FROM short_link ORDER BY created_at DESC,link_key DESC FETCH FIRST 1 ROW ONLY",
            String.class);
    assertThat(items.get(0).get("key").asText()).isEqualTo(newest);
  }

  @Test
  void workbenchAndHomepageExposeControlsWithoutServerTokenOrInlineScripts() throws Exception {
    var response =
        mvc.perform(get("/workbench")).andExpect(status().isOk()).andReturn().getResponse();
    String html = response.getContentAsString();
    assertThat(html).doesNotContain("private-workbench-test-token");
    var doc = Jsoup.parse(html);
    assertThat(doc.select("script[src=/workbench.js]")).hasSize(1);
    assertThat(doc.select("script:not([src])")).isEmpty();
    assertThat(doc.select("#create-link-form, #stats-form, #product-list")).hasSize(3);
    assertThat(response.getHeader("Content-Security-Policy"))
        .contains("script-src 'self'", "connect-src 'self'")
        .doesNotContain("unsafe-inline");
    String home =
        mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(Jsoup.parse(home).select("a[href=/workbench]")).isNotEmpty();
    assertThat(Jsoup.parse(home).select("a[href=/products/1002]")).isNotEmpty();
  }
}
