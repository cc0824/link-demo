package com.example.shortlink.stats;

import java.time.LocalDate;
import java.util.List;

public record StatsResponse(
    String timezone,
    LocalDate from,
    LocalDate to,
    String metricVersion,
    Metric total,
    List<StatsGroup> groups) {
  public record Metric(long pv, long uv) {}
}
