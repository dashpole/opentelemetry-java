/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.common.export.internal;

import io.opentelemetry.sdk.common.CompletableResultCode;
import java.io.Closeable;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.annotation.concurrent.ThreadSafe;

/**
 * An extension interface for providing per-request authentication headers (such as OAuth 2.0 Bearer
 * tokens, signed headers, or rotated API keys) to telemetry exporters.
 *
 * <p>Unlike static configuration headers, an {@link Authenticator} is invoked for each outgoing
 * export request (including retry attempts) and may perform credential caching, automatic token
 * refresh, and lifecycle management via {@link #shutdown()}.
 *
 * <p>Implementations of {@link Authenticator} MUST adhere to the following requirements:
 *
 * <ul>
 *   <li><b>Concurrency Safety:</b> An {@link Authenticator} may be invoked concurrently by multiple
 *       exporter worker threads or shared across multiple signal exporters, and therefore MUST be
 *       thread-safe.
 *   <li><b>Credential Redaction:</b> Implementations MUST NOT expose or log raw credentials,
 *       secrets, or bearer tokens in {@link Object#toString()}, exception messages, or diagnostic
 *       logs.
 * </ul>
 *
 * <p>This class is internal and experimental. Its APIs are unstable and can change at any time. Its
 * APIs (or a version of them) may be promoted to the public stable API in the future, but no
 * guarantees are made.
 */
@ThreadSafe
@FunctionalInterface
public interface Authenticator extends Closeable {

  /**
   * Returns a map of header names to header values to include on an outgoing telemetry export
   * request.
   *
   * <p>If authentication fails (for example, if a token endpoint is unreachable or credentials are
   * invalid), implementations may throw a {@link RuntimeException} to fail the current export
   * attempt.
   *
   * @return a non-null map of header names to header values
   */
  Map<String, String> getHeaders();

  /**
   * Shuts down this {@link Authenticator}, releasing any background resources, caches, or network
   * connections.
   *
   * @return a {@link CompletableResultCode} which completes when shutdown finishes
   */
  default CompletableResultCode shutdown() {
    return CompletableResultCode.ofSuccess();
  }

  /** Closes this {@link Authenticator}, waiting up to 10 seconds for {@link #shutdown()}. */
  @Override
  default void close() {
    shutdown().join(10, TimeUnit.SECONDS);
  }
}
