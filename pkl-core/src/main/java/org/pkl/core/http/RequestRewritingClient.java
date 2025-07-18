/*
 * Copyright © 2024-2025 Apple Inc. and the Pkl project authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.pkl.core.http;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.concurrent.ThreadSafe;
import org.pkl.core.PklBugException;
import org.pkl.core.util.HttpUtils;
import org.pkl.core.util.Nullable;

/**
 * An {@code HttpClient} decorator that
 *
 * <ul>
 *   <li>overrides the {@code User-Agent} header of {@code HttpRequest}s
 *   <li>sets a request timeout if none is present
 *   <li>ensures that {@link #close()} is idempotent.
 *   <li>rewrites outbound URI prefixes with another prefix.
 * </ul>
 *
 * <p>Both {@code User-Agent} header and default request timeout are configurable through {@link
 * HttpClient.Builder}.
 */
@ThreadSafe
final class RequestRewritingClient implements HttpClient {
  // non-private for testing
  final String userAgent;
  final Duration requestTimeout;
  final int testPort;
  final HttpClient delegate;
  private final List<Entry<URI, URI>> rewrites;

  private final AtomicBoolean closed = new AtomicBoolean();

  RequestRewritingClient(
      String userAgent,
      Duration requestTimeout,
      int testPort,
      HttpClient delegate,
      Map<URI, URI> rewrites) {
    this.userAgent = userAgent;
    this.requestTimeout = requestTimeout;
    this.testPort = testPort;
    this.delegate = delegate;
    this.rewrites =
        rewrites.entrySet().stream()
            .map((it) -> Map.entry(normalizeRewrite(it.getKey()), normalizeRewrite(it.getValue())))
            .sorted(Comparator.comparingInt((it) -> -it.getKey().toString().length()))
            .toList();
  }

  @Override
  public <T> HttpResponse<T> send(HttpRequest request, BodyHandler<T> responseBodyHandler)
      throws IOException {
    checkNotClosed(request);
    try {
      return delegate.send(rewriteRequest(request), responseBodyHandler);
    } catch (IOException e) {
      var rewrittenUri = rewriteUri(request.uri());
      if (rewrittenUri != request.uri()) {
        throw new IOException(
            e.getMessage()
                + " (request was rewritten: %s -> %s)".formatted(request.uri(), rewrittenUri),
            e);
      }
      throw e;
    }
  }

  @Override
  public void close() {
    if (!closed.getAndSet(true)) delegate.close();
  }

  // Based on JDK 17's implementation of HttpRequest.newBuilder(HttpRequest, filter).
  private HttpRequest rewriteRequest(HttpRequest original) {
    HttpRequest.Builder builder = HttpRequest.newBuilder();

    builder
        .uri(rewriteUri(original.uri()))
        .expectContinue(original.expectContinue())
        .timeout(original.timeout().orElse(requestTimeout))
        .version(original.version().orElse(java.net.http.HttpClient.Version.HTTP_2));

    original
        .headers()
        .map()
        .forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
    builder.setHeader("User-Agent", userAgent);

    var method = original.method();
    original
        .bodyPublisher()
        .ifPresentOrElse(
            publisher -> builder.method(method, publisher),
            () -> {
              switch (method) {
                case "GET" -> builder.GET();
                case "DELETE" -> builder.DELETE();
                default -> builder.method(method, HttpRequest.BodyPublishers.noBody());
              }
            });

    return builder.build();
  }

  private static boolean notEqualCaseInsensitive(@Nullable String a, @Nullable String b) {
    if (a == null || b == null) {
      return !Objects.equals(a, b);
    }
    return !a.equalsIgnoreCase(b);
  }

  // Our docs say to not include query string or fragment in a rewrite rule, but technically they
  // are supported.
  public static boolean matchesRewriteRule(URI uri, URI rule) {
    if (notEqualCaseInsensitive(uri.getScheme(), rule.getScheme())) {
      return false;
    }

    if (!Objects.equals(uri.getUserInfo(), rule.getUserInfo())) {
      return false;
    }

    if (notEqualCaseInsensitive(uri.getHost(), rule.getHost())) {
      return false;
    }

    if (!Objects.equals(uri.getPath(), rule.getPath())) {
      if (uri.getPath() != null
          && rule.getPath() != null
          && rule.getQuery() == null
          && rule.getFragment() == null) {
        return uri.getPath().startsWith(rule.getPath());
      }
      return false;
    }

    if (!Objects.equals(uri.getQuery(), rule.getQuery())) {
      if (uri.getQuery() != null && rule.getQuery() != null && rule.getFragment() == null) {
        return uri.getQuery().startsWith(rule.getQuery());
      }
      return false;
    }

    if (uri.getFragment() != null && rule.getFragment() != null) {
      return uri.getFragment().startsWith(rule.getFragment());
    }

    return Objects.equals(uri.getFragment(), rule.getFragment());
  }

  private @Nullable Entry<URI, URI> findRewrite(URI uri) {
    for (var entry : rewrites) {
      if (matchesRewriteRule(uri, entry.getKey())) {
        return entry;
      }
    }
    return null;
  }

  private URI normalizeRewrite(URI uri) {
    try {
      return new URI(
          uri.getScheme().toLowerCase(),
          uri.getUserInfo(),
          uri.getHost().toLowerCase(),
          uri.getPort(),
          uri.getPath(),
          uri.getQuery(),
          uri.getFragment());
    } catch (URISyntaxException e) {
      // impossible condition, we started from a valid URI to begin with
      throw PklBugException.unreachableCode();
    }
  }

  private URI rewriteUri(URI uri) {
    var rewrite = findRewrite(uri);
    var ret = uri;
    if (rewrite != null) {
      var normalized = normalizeRewrite(uri);
      var fromUri = rewrite.getKey();
      var toUri = rewrite.getValue();
      var relativePath = fromUri.relativize(normalized);
      ret = toUri.resolve(relativePath);
    }
    if (testPort != -1 && ret.getPort() == 0) {
      ret = HttpUtils.setPort(ret, testPort);
    }
    return ret;
  }

  private void checkNotClosed(HttpRequest request) {
    if (closed.get()) {
      throw new IllegalStateException(
          "Cannot send request " + request + " because this client has already been closed.");
    }
  }
}
