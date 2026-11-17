package com.example.shortlink.link;

import java.time.Instant;

public record ShortLink(
    String key, long productId, String sharerId, String channel, Instant createdAt) {}
