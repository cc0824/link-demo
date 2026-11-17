package com.example.shortlink.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.shortlink.ShortLinkApplication;
import com.example.shortlink.link.*;
import com.example.shortlink.product.JdbcProductGateway;
import com.example.shortlink.stats.StatsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    classes = ShortLinkApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:failure;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=1000",
      "app.rate-limit-enabled=false"
    })
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class FailureIsolationTest {
  @Autowired MockMvc mvc;
  @Autowired LinkService links;
  @MockitoSpyBean StatsRepository statistics;
  @MockitoSpyBean JdbcProductGateway products;
  @MockitoSpyBean LinkRepository repository;

  @Test
  void failedStatsInsertDoesNotBreakRedirect() throws Exception {
    String key = links.create(1001, "failure", "wechat").link().key();
    doThrow(new DataAccessResourceFailureException("simulated unavailable writer"))
        .when(statistics)
        .insert(any());
    mvc.perform(get("/s/" + key))
        .andExpect(status().isFound())
        .andExpect(header().exists("Location"));
  }

  @Test
  void failedProductReadReturns503InsteadOfDeletedPage() throws Exception {
    String key = links.create(1001, "failure_read", "wechat").link().key();
    doThrow(new DataAccessResourceFailureException("simulated read failure"))
        .when(products)
        .findById(1001);
    for (String path : new String[] {"/s/", "/c/"}) {
      var body =
          mvc.perform(get(path + key))
              .andExpect(status().isServiceUnavailable())
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(body).contains("稍后").doesNotContain("simulated", "Exception");
    }
  }

  @Test
  void failedLinkReadDoesNotBecomeNegativeCacheEntry() throws Exception {
    doThrow(new DataAccessResourceFailureException("offline"))
        .doCallRealMethod()
        .when(repository)
        .findByKey("Fault1");
    mvc.perform(get("/s/Fault1")).andExpect(status().isServiceUnavailable());
    mvc.perform(get("/s/Fault1")).andExpect(status().isNotFound());
  }
}
