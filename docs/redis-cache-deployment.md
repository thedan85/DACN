# Redis Catalog Cache — Deployment Notes

## Lettuce timeouts (set in `RedisConfig`)

- command timeout: `catalog.cache.redis.command-timeout`, default 200 ms
- connect timeout: `catalog.cache.redis.connect-timeout`, default 500 ms
- `disconnectedBehavior = REJECT_COMMANDS` (commands fail immediately while disconnected instead of queueing)

Raise the timeouts only if Redis is remote or TLS adds latency; keep them well below the endpoint's own latency budget. Catalog reads fall back to Postgres on any Redis failure; these settings bound the cost of that fallback.

## Eviction policy

Set `maxmemory-policy volatile-lru` (or `volatile-ttl`). Generation counters (`pim:product:gen`, `pim:variant:gen`, `pim:category:gen`) have no TTL and must not be evicted while keys from their generation still exist.

## Direct SQL and Flyway data changes

Direct SQL or Flyway data changes bypass service-layer invalidation. After applying one, delete the counters:

    DEL pim:product:gen pim:variant:gen pim:category:gen

This is safe: a missing counter is re-seeded with epoch millis, so new generations cannot collide with the old ones. Keys written under the old generation become unreachable and expire by TTL.

## Measuring hit vs miss latency

A cache hit costs two Redis round trips (generation + payload) plus the live stock query, so it is not free. Measure a public read on a warm key (hit) against the same read right after deleting the counters (DB miss), and compare p50/p95. If a hit is not clearly cheaper, lower the TTLs or drop caching for that endpoint instead of carrying the invalidation complexity.

## Known limit: cache write inside the DB transaction

On a miss, the cache write happens while the request's DB transaction is still open, so a slow-but-alive Redis can hold a DB connection for up to the command timeout (200 ms default) per miss. Bounded and accepted for a local Redis; revisit (write after commit, or a bounded executor) if Redis becomes remote or the DB pool runs hot during Redis slowness.

## Auth rate limiting

`RateLimiter` (`security/RateLimiter.java`) keeps a sorted set per key:
`rl:login:<ip>` 10/min, `rl:register:<ip>` 20/hour, `rl:refresh:<ip>` 30/min,
`rl:pwd:<email>` 5/min. Keys carry a TTL equal to their window, so there is
no cleanup job. If Redis is unreachable the limiter fails open and logs a
warning at most once per minute; auth keeps working with throttling disabled.

The app binds to `127.0.0.1` (`server.address`), so only the local Cloudflare
tunnel can reach it; point the tunnel at `http://127.0.0.1:8080`. The limiter
keys on `CF-Connecting-IP`, not `X-Forwarded-For`: Cloudflare appends to a
client-supplied `X-Forwarded-For`, and Spring's forwarded-header handling takes
its leftmost value, so `X-Forwarded-For` alone is forgeable. Requests without a
valid `CF-Connecting-IP` share one `local` bucket, so a missing header cannot
reset the limit per request.
