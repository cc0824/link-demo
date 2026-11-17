package com.example.shortlink.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(-90)
public class ApiTokenFilter extends OncePerRequestFilter {
  private static final Logger LOG = LoggerFactory.getLogger(ApiTokenFilter.class);
  private final byte[] expected;
  private final ObjectMapper mapper;
  private final AbuseGuard guard;

  public ApiTokenFilter(
      @Value("${app.api-token}") String token, ObjectMapper mapper, AbuseGuard guard) {
    if (token.isBlank())
      throw new IllegalArgumentException("DEMO_API_TOKEN is required; use demo profile locally");
    this.expected = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    this.mapper = mapper;
    this.guard = guard;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI();
    // Reject ambiguous paths before Tomcat/Spring's decoded path can reach an API handler.
    // All public routes use ASCII keys and fixed segments; encoded path aliases are unnecessary.
    if (path.contains("%")
        || path.contains(";")
        || path.contains("//")
        || path.contains("/./")
        || path.contains("/../")
        || path.endsWith("/.")
        || path.endsWith("/..")) {
      reject(request, response, 400, "NON_CANONICAL_PATH", "请使用完整、规范的链接地址");
      return;
    }
    if (path.equals("/actuator/health")
        || path.equals("/style.css")
        || path.equals("/workbench.css")
        || path.equals("/workbench.js")
        || path.startsWith("/images/")) {
      chain.doFilter(request, response);
      return;
    }
    int retry = guard.publicRequest(request.getRemoteAddr());
    if (retry > 0) {
      limited(request, response, retry);
      return;
    }
    if (path.startsWith("/api/")) {
      String supplied = request.getHeader("Authorization");
      if (supplied == null
          || supplied.length() > 1024
          || !MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8))) {
        reject(request, response, 401, "UNAUTHORIZED", "请提供有效的访问凭证");
        return;
      }
      retry = guard.apiRequest(path);
      if (retry > 0) {
        limited(request, response, retry);
        return;
      }
      if (request.getMethod().equals("POST") || request.getMethod().equals("PATCH")) {
        if (request.getContentLengthLong() > 8192) {
          reject(request, response, 413, "BODY_TOO_LARGE", "请求内容过长");
          return;
        }
        byte[] body = request.getInputStream().readNBytes(8193);
        if (body.length > 8192) {
          reject(request, response, 413, "BODY_TOO_LARGE", "请求内容过长");
          return;
        }
        chain.doFilter(new BodyRequest(request, body), response);
        return;
      }
    }
    chain.doFilter(request, response);
  }

  private void limited(HttpServletRequest request, HttpServletResponse response, int retry)
      throws IOException {
    response.setHeader("Retry-After", Integer.toString(retry));
    LOG.warn("rate_limit_rejected requestId={}", request.getAttribute("requestId"));
    reject(request, response, 429, "RATE_LIMITED", "访问太频繁，请稍后再试");
  }

  private void reject(
      HttpServletRequest request,
      HttpServletResponse response,
      int status,
      String code,
      String message)
      throws IOException {
    response.setStatus(status);
    response.setCharacterEncoding("UTF-8");
    if (request.getRequestURI().startsWith("/api/")) {
      response.setContentType("application/json");
      mapper.writeValue(
          response.getOutputStream(),
          Map.of(
              "code",
              code,
              "message",
              message,
              "requestId",
              String.valueOf(request.getAttribute("requestId"))));
    } else {
      response.setContentType("text/html;charset=UTF-8");
      response
          .getWriter()
          .write(
              "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><link rel=\"stylesheet\" href=\"/style.css\"><title>稍后再试</title><body><main class=\"error-page\"><h1>"
                  + message
                  + "</h1><p>请稍候片刻，再打开分享链接。</p><a href=\"/\">返回首页</a></main></body></html>");
    }
  }

  private static class BodyRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    BodyRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
      var input = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public int read() {
          return input.read();
        }

        @Override
        public boolean isFinished() {
          return input.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          throw new UnsupportedOperationException("Synchronous request only");
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
  }
}
