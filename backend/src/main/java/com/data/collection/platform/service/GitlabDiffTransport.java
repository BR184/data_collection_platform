package com.data.collection.platform.service;

import java.net.URI;
import java.time.Duration;

interface GitlabDiffTransport {
  Response get(URI uri, String token, Duration timeout);

  record Response(int statusCode, String body) {}
}
