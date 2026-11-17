package com.example.shortlink.stats;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record StatsGroup(String channel, String sharerId, long pv, long uv) {}
