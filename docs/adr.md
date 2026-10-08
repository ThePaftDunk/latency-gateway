# ADR: bounded work and explicit failure

Status: accepted for the demo.

Virtual threads simplify blocking request code, but they do not bound upstream work. A semaphore caps active quote handlers. A concurrent map coalesces misses for one key; the cache itself uses a short synchronized section with LRU eviction. The leader rechecks the cache after acquiring ownership, preventing the race where a previous leader completes between the first cache lookup and map insertion.

The leader applies a deadline and records a breaker outcome. Followers share its result and do not independently increment upstream failure counts. Failures never enter the cache. Breaker permits carry a generation: work admitted before a circuit transition cannot close or reopen a later generation. Only one half-open call probes recovery.

Stale-while-revalidate could keep serving expired prices but changes the API's freshness contract. It is omitted. Hedged requests could reduce tail latency but add upstream load and require cancellation semantics. They are omitted until measurement establishes a need.

The design bounds cache cardinality and admitted application work, not every network buffer, accepted connection, or virtual thread. A production edge proxy, connection limits, body limits, and load tests are still necessary. Metrics are bounded in label cardinality; symbol-level diagnostics belong in sampled logs, not labels.
