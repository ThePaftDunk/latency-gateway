# ADR: count upstream bytes before accumulation

Status: accepted for the portfolio implementation, 10 October 2026.

## Problem

The HTTP adapter used `BodyHandlers.ofString()` and checked `String.length()` only after the response completed. A large body could therefore consume unbounded accumulated payload memory before rejection. A character limit also differed from a byte limit for multibyte text. An oversized chunked response that never ended waited for the deadline instead of failing as soon as it crossed the body limit.

## Decision

Wrap the JDK String subscriber with a subscriber that counts `ByteBuffer.remaining()` across each incoming batch. Accept at most 65,536 body bytes. Check a whole batch before forwarding it; when it would cross the limit, cancel the subscription and complete the downstream body exceptionally. Drop late callbacks after a terminal signal. Count actual body bytes regardless of `Content-Length` or chunked framing.

Keep the JDK String handler responsible for charset selection and decoding across buffer boundaries. Keep the existing full-lookup deadline, breaker outcome, single-flight propagation and uncached-failure behavior. A rejected body becomes the same generic HTTP 503 as other upstream failures; this change does not introduce payload logging or public transport details.

The wrapper adds no application dependency. The [JDK BodySubscriber contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpResponse.BodySubscriber.html) describes ordered body buffers and cancellation when an implementation stops consuming a response. Cancellation can close the HTTP connection, so early rejection trades connection reuse for bounded accumulation and prompt failure.

## Alternatives

- Retaining the post-buffer character check leaves the original accumulation problem.
- Trusting `Content-Length` alone misses unknown-length/chunked bodies and does not establish how many bytes actually arrived. Early rejection of a declared oversized length can be added later, but actual-byte checks remain necessary.
- Reading an InputStream can enforce a limit, but introduces separate stream-closing and blocking-read lifecycle code. The subscriber wrapper keeps completion and interruption within the existing `HttpClient.send` path.
- An edge proxy helps with admission and connection bounds, but does not replace a limit in this outbound HTTP adapter.

## Evidence and limits

Java checks cover exact byte-boundary decoding across split UTF-8 buffers and cancellation of an overflowing multi-buffer batch. Loopback HTTP checks cover fixed-length and chunked exact-limit bodies, overflow, UTF-8 bodies with fewer characters than the byte limit, truncation, non-200 status, uncached failures and a stalled-body deadline. The overflow fixture withholds its end marker until cleanup; the gateway must fail without waiting for that marker or recording a timeout.

This bounds bytes forwarded to the body accumulator, not total JVM heap or accepted connections. The JDK transport can already hold the incoming batch, and decoding/caching adds memory overhead. Schema validation, decompression policies, upstream authentication and deployment-wide memory measurements remain separate work. No new throughput or latency benchmark is claimed.
