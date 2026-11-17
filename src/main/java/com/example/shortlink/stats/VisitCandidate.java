package com.example.shortlink.stats;

import java.time.Instant;

public record VisitCandidate(
    String linkKey, String visitorHash, Instant visitedAt, long bucketStart) {}
