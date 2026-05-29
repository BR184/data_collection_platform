package com.data.collection.platform.service;

import com.data.collection.platform.common.exception.BizException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PreviewSessionStore<T> {
  private final Clock clock;
  private final Duration ttl;
  private final int maximumSize;
  private final Map<String, Entry<T>> sessions = new ConcurrentHashMap<>();

  public PreviewSessionStore(Clock clock, Duration ttl, int maximumSize) {
    if (ttl == null || ttl.isNegative() || ttl.isZero()) {
      throw new BizException("preview session ttl must be positive");
    }
    if (maximumSize < 1) {
      throw new BizException("preview session maximum size must be positive");
    }
    this.clock = clock == null ? Clock.systemUTC() : clock;
    this.ttl = ttl;
    this.maximumSize = maximumSize;
  }

  public String put(T value) {
    cleanupExpired();
    String token = UUID.randomUUID().toString();
    sessions.put(token, new Entry<>(value, expiresAt()));
    trimToMaximumSize();
    return token;
  }

  public Optional<T> getValid(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    Entry<T> entry = sessions.get(token);
    if (entry == null) {
      return Optional.empty();
    }
    if (entry.expired(now())) {
      sessions.remove(token);
      return Optional.empty();
    }
    return Optional.ofNullable(entry.value());
  }

  public void remove(String token) {
    if (token != null) {
      sessions.remove(token);
    }
  }

  int size() {
    return sessions.size();
  }

  private Instant now() {
    return Instant.now(clock);
  }

  private Instant expiresAt() {
    return now().plus(ttl);
  }

  private void cleanupExpired() {
    Instant now = now();
    sessions.entrySet().removeIf(entry -> entry.getValue().expired(now));
  }

  private void trimToMaximumSize() {
    cleanupExpired();
    if (sessions.size() <= maximumSize) {
      return;
    }
    Iterator<Map.Entry<String, Entry<T>>> iterator =
        sessions.entrySet().stream()
            .sorted(Comparator.comparing(entry -> entry.getValue().expiresAt()))
            .iterator();
    while (sessions.size() > maximumSize && iterator.hasNext()) {
      sessions.remove(iterator.next().getKey());
    }
  }

  private record Entry<T>(T value, Instant expiresAt) {
    boolean expired(Instant now) {
      return !expiresAt.isAfter(now);
    }
  }
}
