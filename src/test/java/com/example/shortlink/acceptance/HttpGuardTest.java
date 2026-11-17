package com.example.shortlink.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.shortlink.ShortLinkApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    classes = ShortLinkApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:guards;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=1000",
      "app.api-token=guard-test-token",
      "app.visitor-secret=guard-test-secret-at-least-32-characters"
    })
@AutoConfigureMockMvc
class HttpGuardTest {
  @Autowired MockMvc mvc;

  @Test
  void demoRoutesAreAbsentWithoutProfileAndForwardedHeadersCannotEscapeRateLimit()
      throws Exception {
    mvc.perform(post("/api/demo/reset").header("Authorization", "Bearer guard-test-token"))
        .andExpect(status().isNotFound());
    mvc.perform(get("/workbench")).andExpect(status().isNotFound());
    mvc.perform(get("/api/demo/products").header("Authorization", "Bearer guard-test-token"))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/demo/links").header("Authorization", "Bearer guard-test-token"))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/demo/products/1001/restore")
                .header("Authorization", "Bearer guard-test-token"))
        .andExpect(status().isNotFound());
    int limited = 0;
    for (int i = 0; i < 45; i++) {
      var response =
          mvc.perform(get("/s/missing" + i).header("X-Forwarded-For", "192.0.2." + i))
              .andReturn()
              .getResponse();
      if (response.getStatus() == 429) {
        limited++;
        assertThat(Integer.parseInt(response.getHeader("Retry-After"))).isPositive();
        assertThat(response.getContentAsString()).contains("访问太频繁");
      } else {
        assertThat(response.getStatus()).isEqualTo(404);
      }
    }
    assertThat(limited).isPositive();
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }
}
