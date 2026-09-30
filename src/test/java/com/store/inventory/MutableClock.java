package com.store.inventory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Controllable clock for tests: lets a test "advance" time without {@code Thread.sleep}.
 * NEW (tests only).
 */
final class MutableClock extends Clock {

    private volatile Instant now = Instant.parse("2026-01-01T10:00:00Z");

    void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
