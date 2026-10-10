# Development session: bounded upstream responses

Date: 10 October 2026. Branch: `portfolio/upstream-body-limit`.

## Why this change

The request semaphore and cache size already bound parts of the service, but the real HTTP backend accumulated its whole response before checking length. This session fixes that specific gap instead of adding another service or unrelated feature.

## Result

An incoming response may contribute at most 65,536 bytes to the String accumulator. A batch that exceeds the remaining allowance is rejected before forwarding, and the subscription is cancelled. Exactly-at-limit responses still succeed. JDK charset handling is preserved. Failed bodies return HTTP 503 through the existing error path and stay out of the cache.

The [decision record](adr-upstream-body-limit.md) explains the subscriber wrapper, alternatives and remaining memory boundaries.

## Validation

`./scripts/test.sh` runs six Java invariants, the existing HTTP smoke test and a new real upstream HTTP test. They pass locally with OpenJDK 24.0.2, compiling for Java 21 using `--release 21`.

The upstream test accepts exactly 65,536 bytes with both Content-Length and chunked framing. It rejects a one-byte overflow, a chunked overflow that never finishes, a UTF-8 payload with fewer than 65,536 characters but more bytes, a truncated response and a non-200 response. Each rejected symbol is requested twice to verify it was not cached. A fresh successful load follows each failure case. A stalled body verifies that a deadline still applies and that subsequent requests can succeed.

No deployment or new performance benchmark was run. CI provides a separate Java 21 check after pushing; its result belongs in the PR checks, not an assumed local result.

## Walkthrough for Shubham

1. Run `./scripts/test.sh`, then compare the old post-buffer check with `BoundedBodyHandler` and the HTTP fixture.
2. Explain why virtual threads and a request semaphore do not by themselves bound response payloads.
3. Explain the difference between String characters, body bytes and HTTP chunk framing.
4. Describe how cancellation can sacrifice connection reuse, and why no failed response should enter the cache.
5. State the boundary precisely: accumulated payload bytes are limited, while transport buffers, decoded Strings and concurrent requests add overhead.

## Next candidate

Profile allocations and retained heap under controlled concurrent oversized responses, then decide whether an early Content-Length check or another admission limit is justified. Keep that experiment distinct from throughput claims based on the initial synthetic benchmark.
