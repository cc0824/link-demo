package com.example.shortlink.stats;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/stats")
public class StatsController {
  private final StatsService stats;

  public StatsController(StatsService stats) {
    this.stats = stats;
  }

  @GetMapping
  public StatsResponse query(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String groupBy,
      @RequestParam(required = false) String key,
      @RequestParam(required = false) String channel,
      @RequestParam(required = false) String sharerId) {
    return stats.query(from, to, groupBy, key, channel, sharerId);
  }
}
