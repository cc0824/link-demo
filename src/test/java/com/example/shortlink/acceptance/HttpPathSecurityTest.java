package com.example.shortlink.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.shortlink.ShortLinkApplication;
import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    classes = ShortLinkApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:pathsecurity;DB_CLOSE_DELAY=-1",
      "app.rate-limit-enabled=false"
    })
@ActiveProfiles("demo")
class HttpPathSecurityTest {
  @LocalServerPort int port;

  @Test
  void nonCanonicalPathsCannotBypassApiAuthentication() throws Exception {
    var client = HttpClient.newHttpClient();
    for (String path :
        new String[] {"/%61pi/links", "/api;v=1/links", "/./api/links", "//api/links"}) {
      var request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
              .header("Content-Type", "application/json")
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      "{\"productId\":1001,\"sharerId\":\"bypass\",\"channel\":\"wechat\"}"))
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).as(path).isEqualTo(400);
    }
    var normal =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/links"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build();
    assertThat(client.send(normal, HttpResponse.BodyHandlers.discarding()).statusCode())
        .isEqualTo(401);
  }
}
