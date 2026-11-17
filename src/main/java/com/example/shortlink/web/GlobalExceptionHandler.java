package com.example.shortlink.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@ControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  public Object method(
      org.springframework.web.HttpRequestMethodNotSupportedException ex,
      HttpServletRequest request,
      jakarta.servlet.http.HttpServletResponse response) {
    if (ex.getSupportedMethods() != null)
      response.setHeader("Allow", String.join(", ", ex.getSupportedMethods()));
    return render(request, 405, "METHOD_NOT_ALLOWED", "此接口不支持该请求方法");
  }

  @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
  public Object media(Exception ex, HttpServletRequest request) {
    return render(request, 415, "UNSUPPORTED_MEDIA_TYPE", "请使用 application/json 请求格式");
  }

  @ExceptionHandler(AppException.class)
  public Object application(AppException ex, HttpServletRequest request) {
    return render(request, ex.status(), ex.code(), ex.getMessage());
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentNotValidException.class,
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class
  })
  public Object invalid(Exception ex, HttpServletRequest request) {
    return render(request, 400, "INVALID_REQUEST", "请求参数不正确，请检查后重试");
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public Object missing(Exception ex, HttpServletRequest request) {
    return render(request, 404, "NOT_FOUND", "链接不存在或页面已移除");
  }

  @ExceptionHandler(DataAccessException.class)
  public Object database(Exception ex, HttpServletRequest request) {
    LOG.error(
        "database_failure requestId={} type={}",
        request.getAttribute("requestId"),
        ex.getClass().getSimpleName());
    return render(request, 503, "SERVICE_UNAVAILABLE", "服务暂时不可用，请稍后再试");
  }

  @ExceptionHandler(Exception.class)
  public Object unexpected(Exception ex, HttpServletRequest request) {
    LOG.error(
        "unexpected_failure requestId={} type={}",
        request.getAttribute("requestId"),
        ex.getClass().getSimpleName());
    return render(request, 500, "INTERNAL_ERROR", "暂时无法完成请求，请稍后重试");
  }

  public static Object render(HttpServletRequest request, int status, String code, String message) {
    String id = String.valueOf(request.getAttribute("requestId"));
    if (request.getRequestURI().startsWith("/api/")) {
      return ResponseEntity.status(status)
          .body(Map.of("code", code, "message", message, "requestId", id));
    }
    var view = new ModelAndView(status == 410 ? "unavailable" : "error");
    view.setStatus(org.springframework.http.HttpStatus.valueOf(status));
    view.addObject("status", status);
    view.addObject("title", status == 404 ? "这条链接暂时找不到了" : message);
    view.addObject("message", message);
    view.addObject("requestId", id);
    return view;
  }
}
