package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class PreviewSessionStoreTest {
  @Test
  void shouldReturnValidSessionBeforeExpiry() {
    MutableClock clock = new MutableClock(Instant.parse("2026-05-29T00:00:00Z"));
    PreviewSessionStore<String> store = new PreviewSessionStore<>(clock, Duration.ofMinutes(30), 10);

    String token = store.put("preview");

    assertThat(store.getValid(token)).contains("preview");
  }

  @Test
  void shouldExpireSessionsByTtl() {
    MutableClock clock = new MutableClock(Instant.parse("2026-05-29T00:00:00Z"));
    PreviewSessionStore<String> store = new PreviewSessionStore<>(clock, Duration.ofMinutes(30), 10);
    String token = store.put("preview");

    clock.instant = Instant.parse("2026-05-29T00:31:00Z");

    assertThat(store.getValid(token)).isEmpty();
    assertThat(store.size()).isZero();
  }

  @Test
  void shouldTrimOldestSessionsWhenCapacityIsExceeded() {
    MutableClock clock = new MutableClock(Instant.parse("2026-05-29T00:00:00Z"));
    PreviewSessionStore<String> store = new PreviewSessionStore<>(clock, Duration.ofMinutes(30), 2);
    String first = store.put("first");
    clock.instant = Instant.parse("2026-05-29T00:01:00Z");
    String second = store.put("second");
    clock.instant = Instant.parse("2026-05-29T00:02:00Z");
    String third = store.put("third");

    assertThat(store.getValid(first)).isEmpty();
    assertThat(store.getValid(second)).contains("second");
    assertThat(store.getValid(third)).contains("third");
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
