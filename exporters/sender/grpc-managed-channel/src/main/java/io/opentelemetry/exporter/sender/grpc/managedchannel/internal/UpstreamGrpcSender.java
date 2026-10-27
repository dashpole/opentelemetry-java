/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.exporter.sender.grpc.managedchannel.internal;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.CallCredentials;
import io.grpc.CallOptions;
import io.grpc.Compressor;
import io.grpc.CompressorRegistry;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCalls;
import io.opentelemetry.api.impl.InstrumentationUtil;
import io.opentelemetry.exporter.internal.grpc.MarshalerInputStream;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.export.GrpcResponse;
import io.opentelemetry.sdk.common.export.GrpcSender;
import io.opentelemetry.sdk.common.export.GrpcStatusCode;
import io.opentelemetry.sdk.common.export.MessageWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * A {@link GrpcSender} which uses the upstream grpc-java library.
 *
 * <p>This class is internal and is hence not for public use. Its APIs are unstable and can change
 * at any time.
 */
public final class UpstreamGrpcSender implements GrpcSender {

  private static final Logger logger = Logger.getLogger(UpstreamGrpcSender.class.getName());

  private static final MethodDescriptor.Marshaller<MessageWriter> REQUEST_MARSHALER =
      new MethodDescriptor.Marshaller<MessageWriter>() {
        @Override
        public InputStream stream(MessageWriter value) {
          return new MarshalerInputStream(value);
        }

        @Override
        public MessageWriter parse(InputStream stream) {
          throw new UnsupportedOperationException("Only for serializing");
        }
      };

  private static final MethodDescriptor.Marshaller<byte[]> RESPONSE_MARSHALER =
      new MethodDescriptor.Marshaller<byte[]>() {
        @Override
        public InputStream stream(byte[] value) {
          throw new UnsupportedOperationException("Only for parsing");
        }

        @Override
        public byte[] parse(InputStream inputStream) {
          try {
            int bufLen = 4 * 0x400; // 4KB
            byte[] buf = new byte[bufLen];
            int readLen;
            IOException exception = null;

            try {
              try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                while ((readLen = inputStream.read(buf, 0, bufLen)) != -1) {
                  outputStream.write(buf, 0, readLen);
                }
                return outputStream.toByteArray();
              }
            } catch (IOException e) {
              exception = e;
              throw e;
            } finally {
              if (exception == null) {
                inputStream.close();
              } else {
                try {
                  inputStream.close();
                } catch (IOException e) {
                  exception.addSuppressed(e);
                }
              }
            }
          } catch (IOException e) {
            throw new IllegalStateException("Failed to read response", e);
          }
        }
      };

  private final ManagedChannel channel;
  private final MethodDescriptor<MessageWriter, byte[]> methodDescriptor;
  @Nullable private final String compressorName;
  private final boolean shutdownChannel;
  private final Duration timeout;
  private final Supplier<Map<String, List<String>>> headersSupplier;
  private final Executor executor;

  /** Creates a new {@link UpstreamGrpcSender}. */
  public UpstreamGrpcSender(
      ManagedChannel channel,
      String fullMethodName,
      @Nullable io.opentelemetry.sdk.common.export.Compressor compressor,
      boolean shutdownChannel,
      Duration timeout,
      Supplier<Map<String, List<String>>> headersSupplier,
      @Nullable ExecutorService executorService) {
    this.channel = channel;
    this.methodDescriptor =
        MethodDescriptor.<MessageWriter, byte[]>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(fullMethodName)
            .setRequestMarshaller(REQUEST_MARSHALER)
            .setResponseMarshaller(RESPONSE_MARSHALER)
            .build();
    if (compressor != null) {
      CompressorRegistry.getDefaultInstance()
          .register(
              new Compressor() {
                @Override
                public String getMessageEncoding() {
                  return compressor.getEncoding();
                }

                @Override
                public OutputStream compress(OutputStream os) throws IOException {
                  return compressor.compress(os);
                }
              });
      this.compressorName = compressor.getEncoding();
    } else {
      this.compressorName = null;
    }
    this.shutdownChannel = shutdownChannel;
    this.timeout = timeout;
    this.headersSupplier = headersSupplier;
    this.executor = executorService == null ? MoreExecutors.directExecutor() : executorService;
  }

  @Override
  public void send(
      MessageWriter messageWriter, Consumer<GrpcResponse> onResponse, Consumer<Throwable> onError) {
    CallOptions requestCallOptions = CallOptions.DEFAULT;
    if (timeout.toNanos() > 0) {
      requestCallOptions = requestCallOptions.withDeadlineAfter(timeout);
    }
    Map<String, List<String>> initialHeaders;
    try {
      initialHeaders = invokeHeadersSupplier();
    } catch (RuntimeException e) {
      onError.accept(e);
      return;
    }
    if (initialHeaders != null) {
      for (Map.Entry<String, List<String>> entry : initialHeaders.entrySet()) {
        // Header names are case-insensitive, so "Host" must override the authority too.
        if (entry.getKey().equalsIgnoreCase("host") && !entry.getValue().isEmpty()) {
          requestCallOptions = requestCallOptions.withAuthority(entry.getValue().get(0));
        }
      }
    }
    AtomicBoolean firstAttempt = new AtomicBoolean(true);
    AtomicReference<RuntimeException> retryHeaderError = new AtomicReference<>();
    requestCallOptions =
        requestCallOptions.withCallCredentials(
            new CallCredentials() {
              @Override
              public void applyRequestMetadata(
                  RequestInfo requestInfo, Executor appExecutor, MetadataApplier applier) {
                Map<String, List<String>> attemptHeaders;
                if (firstAttempt.compareAndSet(true, false)) {
                  attemptHeaders = initialHeaders;
                } else {
                  try {
                    attemptHeaders = invokeHeadersSupplier();
                  } catch (RuntimeException e) {
                    retryHeaderError.set(e);
                    applier.fail(
                        Status.UNAUTHENTICATED
                            .withDescription("Failed to get headers for retry")
                            .withCause(e));
                    return;
                  }
                }
                Metadata metadata = new Metadata();
                if (attemptHeaders != null) {
                  for (Map.Entry<String, List<String>> entry : attemptHeaders.entrySet()) {
                    metadata.put(
                        Metadata.Key.of(
                            entry.getKey().toLowerCase(Locale.ROOT),
                            Metadata.ASCII_STRING_MARSHALLER),
                        String.join(",", entry.getValue()));
                  }
                }
                applier.apply(metadata);
              }
            });
    if (this.compressorName != null) {
      requestCallOptions = requestCallOptions.withCompression(compressorName);
    }

    Futures.addCallback(
        ClientCalls.futureUnaryCall(
            channel.newCall(methodDescriptor, requestCallOptions), messageWriter),
        new FutureCallback<byte[]>() {
          @Override
          public void onSuccess(@Nullable byte[] result) {
            onResponse.accept(
                ImmutableGrpcResponse.create(
                    GrpcStatusCode.OK,
                    Status.OK.getDescription(),
                    result == null ? new byte[0] : result));
          }

          @Override
          public void onFailure(Throwable t) {
            RuntimeException headerError = retryHeaderError.get();
            if (headerError != null) {
              onError.accept(headerError);
              return;
            }
            Status status = fromThrowable(t);
            if (status == null) {
              onError.accept(t);
            } else {
              onResponse.accept(
                  ImmutableGrpcResponse.create(
                      GrpcStatusCode.fromValue(status.getCode().value()),
                      status.getDescription(),
                      new byte[0]));
            }
          }
        },
        executor);
  }

  @Nullable
  private Map<String, List<String>> invokeHeadersSupplier() {
    AtomicReference<Map<String, List<String>>> result = new AtomicReference<>();
    InstrumentationUtil.suppressInstrumentation(() -> result.set(headersSupplier.get()));
    return result.get();
  }

  /**
   * Copy of {@link Status#fromThrowable(Throwable)} which returns null instead of {@link
   * Status#UNKNOWN} when no status can be found.
   */
  @Nullable
  private static Status fromThrowable(Throwable cause) {
    while (cause != null) {
      if (cause instanceof StatusException) {
        return ((StatusException) cause).getStatus();
      } else if (cause instanceof StatusRuntimeException) {
        return ((StatusRuntimeException) cause).getStatus();
      }
      cause = cause.getCause();
    }
    return null;
  }

  @Override
  public CompletableResultCode shutdown() {
    if (shutdownChannel) {
      // Use shutdownNow() to cancel in flight calls immediately
      channel.shutdownNow();

      // Wait for the channel to terminate in a background thread
      CompletableResultCode result = new CompletableResultCode();
      Thread terminationThread =
          new Thread(
              () -> {
                try {
                  // Wait up to 5 seconds for the channel to terminate
                  // Even if timeout occurs, we succeed since these are daemon threads
                  boolean terminated = channel.awaitTermination(5, TimeUnit.SECONDS);
                  if (!terminated) {
                    logger.log(
                        Level.WARNING,
                        "Channel did not terminate within 5 seconds, proceeding with shutdown since threads are daemon threads.");
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                } finally {
                  result.succeed();
                }
              },
              "grpc-shutdown");
      terminationThread.setDaemon(true);
      terminationThread.start();
      return result;
    }

    return CompletableResultCode.ofSuccess();
  }
}
