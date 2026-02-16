# URL Shortener — Deep Dive

A comprehensive technical walkthrough of every design decision, trade-off, and implementation detail in this project. Structured for interview preparation.

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Architecture](#2-architecture)
3. [Request Flow — Step by Step](#3-request-flow--step-by-step)
4. [Short Code Generation (Base62 + Atomic Counter)](#4-short-code-generation-base62--atomic-counter)
5. [Redis Key Design](#5-redis-key-design)
6. [Lua Scripts — Why and How](#6-lua-scripts--why-and-how)
7. [Caching Strategy (Two Layers)](#7-caching-strategy-two-layers)
8. [Rate Limiting](#8-rate-limiting)
9. [Background Sync (Dirty-Set Pattern)](#9-background-sync-dirty-set-pattern)
10. [Expiry Strategy](#10-expiry-strategy)
11. [Fallback and Resilience](#11-fallback-and-resilience)
12. [Exception Handling](#12-exception-handling)
13. [MongoDB Indexing](#13-mongodb-indexing)
14. [Docker Deployment](#14-docker-deployment)
15. [What's Good — Talking Points](#15-whats-good--talking-points)
16. [Known Limitations and Future Improvements](#16-known-limitations-and-future-improvements)
17. [Interview Q&A](#17-interview-qa)
18. [Alternative Approaches](#18-alternative-approaches)

---

## 1. System Overview

This is a URL shortening service that converts long URLs into short codes (like `bit.ly`), redirects users, and tracks click analytics. The system is designed with performance and reliability in mind:

- **Write path** (shorten): Client → validate → check duplicate → generate ID → Base62 encode → save to MongoDB + Redis
- **Read path** (redirect): Client → cache lookup → Redis Lua script (check expiry, increment clicks, mark dirty) → 302 redirect
- **Background**: Scheduled job syncs dirty click counts from Redis to MongoDB for durability

**Tech choices:**
- **MongoDB** — document store for URL mappings. Schema-flexible, good for this use case where each document is a self-contained URL record.
- **Redis** — in-memory store for click counters, expiry metadata, caching, and rate limiting. Chosen for its sub-millisecond latency and atomic operations.
- **Spring Boot** — opinionated Java framework with built-in support for caching (`@Cacheable`), scheduling (`@Scheduled`), validation (`@Valid`), and dependency injection.

---

## 2. Architecture

```
                                ┌─────────────────────────────────┐
                                │           Controller            │
                                │   POST /shorten                 │
                                │   GET  /{shortCode}             │
                                │   GET  /{shortCode}/stats       │
                                └──────┬──────────────────────────┘
                                       │
                          ┌────────────▼─────────────┐
                          │    RateLimiterService     │
                          │  (Lua script in Redis)    │
                          └────────────┬─────────────┘
                                       │
                          ┌────────────▼─────────────┐
                          │   UrlShortenerService     │
                          │   (core business logic)   │
                          └──┬──────────┬─────────┬──┘
                             │          │         │
              ┌──────────────▼──┐  ┌────▼─────┐  ┌▼───────────────┐
              │ CacheService    │  │  Redis   │  │ CounterService  │
              │ (@Cacheable)    │  │ (Lua)    │  │ (range alloc)   │
              └───────┬─────────┘  └────┬─────┘  └───────┬────────┘
                      │                 │                 │
              ┌───────▼─────────────────▼─────────────────▼───────┐
              │                    MongoDB                         │
              │  url_mapping (indexed: shortCode, originalUrl)     │
              │  counters (atomic sequence)                        │
              └────────────────────────▲──────────────────────────┘
                                       │
                          ┌────────────┴─────────────┐
                          │    RedisSyncService       │
                          │  (dirty-set → MongoDB)    │
                          │  (expired URL cleanup)    │
                          └──────────────────────────┘
```

**Data flow summary:**
- Immutable URL mapping data lives in **MongoDB** (source of truth) and is cached in **Redis** via `@Cacheable`.
- Mutable state (click counts, expiry) lives in **Redis** as manual keys and is periodically synced to MongoDB.
- This separation means reads are fast (Redis) and writes are durable (MongoDB), with eventual consistency for click counts.

---

## 3. Request Flow — Step by Step

### 3.1 Shorten URL (`POST /api/shorten`)

```
1. Controller receives request with { "originalUrl": "https://..." }
2. @Valid validates the URL (must start with http:// or https://)
3. Extract client IP from X-Forwarded-For header (or getRemoteAddr() fallback)
4. RateLimiterService.isAllowed(ip):
   └─ Execute Lua script: INCR rate_limit:{ip}, set EXPIRE if count==1
   └─ If count > maxRequests (5), return 429 Too Many Requests
5. Check if originalUrl already shortened:
   └─ UrlShortenerCacheService.getShortCodeByOriginalURL(url)
   └─ @Cacheable: checks Redis cache first, then MongoDB
   └─ If found, return existing shortCode (deduplication)
6. Generate new short code:
   └─ CounterService.getNextSequence("url_sequence")
   └─ Returns next ID from in-memory range (hits MongoDB only when range exhausted)
   └─ Base62Encoder.encode(id) → short code string
7. Save to MongoDB:
   └─ UrlMapping { originalUrl, shortCode, clickCount=0, expiryDate=now+30d }
8. Initialize Redis keys:
   └─ SET url:{shortCode}:clicks "0" EX 30d
   └─ SET url:{shortCode}:expiry "2026-03-14T..." EX 30d
9. Return shortCode to client (200 OK)
```

### 3.2 Redirect (`GET /api/{shortCode}`)

```
1. Controller receives shortCode path variable
2. UrlShortenerCacheService.getByShortCode(shortCode):
   └─ @Cacheable: checks Redis cache, then MongoDB
   └─ Returns UrlMapping or null
3. If null → throw NotFoundException → 404
4. Execute redirect Lua script (single Redis round-trip):
   └─ KEYS: url:{shortCode}:clicks, url:{shortCode}:expiry, dirty_urls
   └─ If click/expiry keys missing → populate from DB fallback values
   └─ Compare expiry with current time
   └─ If expired → return -1
   └─ If valid → INCR clicks, SADD dirty_urls {shortCode}
5. If Lua returns -1 → throw ExpiredException → 410 Gone
6. If Redis fails entirely → catch Exception:
   └─ Check expiry from DB
   └─ Increment clicks in MongoDB directly (fallback)
7. Return 302 redirect with Location: {originalUrl}
```

### 3.3 Stats (`GET /api/{shortCode}/stats`)

```
1. Load UrlMapping from cache/DB (same as redirect step 2-3)
2. Read clicks from Redis key url:{shortCode}:clicks
   └─ If null, fall back to mapping.getClickCount() from DB
3. Read expiry from Redis key url:{shortCode}:expiry
   └─ If null, fall back to mapping.getExpiryDate() from DB
4. If Redis fails, use DB values entirely
5. Return UrlStatsResponse { originalUrl, shortCode, clickCount, createdAt, expiryDate }
```

---

## 4. Short Code Generation (Base62 + Atomic Counter)

### Why Base62?

Base62 uses `[a-zA-Z0-9]` — 62 characters. Advantages:
- URL-safe (no special characters that need encoding)
- Compact: 6 characters can represent 62^6 = **56.8 billion** unique URLs
- Deterministic: same ID always produces the same code (unlike hashing)
- No collisions by design (sequential IDs)

### Why MongoDB Atomic Counter (not UUID, not hash)?

| Approach | Pros | Cons |
|----------|------|------|
| **UUID** | No coordination needed | 128 bits → long Base62 string (22 chars) |
| **Hash (MD5/SHA)** | Deterministic | Collisions possible; need collision handling |
| **Random** | Simple | Collision risk; need retry logic |
| **Atomic counter + Base62** | Short codes, zero collisions, sequential | Single point of contention (counter doc) |

We chose atomic counter because:
- Short codes are **minimal length** (1 char for ID 1, 2 chars for ID 62, etc.)
- **Zero collision risk** — each ID is unique by construction
- MongoDB's `findAndModify` with `$inc` is atomic even under concurrency

### Range-Based Allocation (Optimization)

The original approach hit MongoDB for every single URL creation. Under high write load, the single counter document becomes a bottleneck.

**Solution:** Allocate IDs in batches of 100.

```
Thread calls getNextSequence():
  if currentId >= maxId:
    → MongoDB findAndModify: inc("seq", 100), returns newMax
    → currentId = newMax - 100
    → maxId = newMax
  return currentId++  (from memory, no DB call)
```

This reduces MongoDB calls by **100x** under sustained write load. The method is `synchronized` to prevent multiple threads from allocating overlapping ranges.

**Trade-off:** If the app crashes, up to 99 IDs in the current range are "wasted" (never used). This is acceptable — short codes don't need to be perfectly sequential.

### Base62 Encoder

```java
public static String encode(long num) {
    StringBuilder sb = new StringBuilder();
    while (num > 0) {
        sb.append(BASE62.charAt((int)(num % 62)));
        num /= 62;
    }
    return sb.reverse().toString();
}
```

- Extracts digits in reverse order (like converting decimal to another base)
- Reverses at the end for correct ordering
- Note: `encode(0)` returns `""`, so the counter starts from 1

---

## 5. Redis Key Design

```
url:{shortCode}:clicks   →  "42"                          (click count as string)
url:{shortCode}:expiry   →  "2026-03-14T10:30:00"         (ISO datetime string)
rate_limit:{ip}           →  "3"                           (request count in current window)
dirty_urls                →  Set { "abc", "xyz", "def" }   (shortCodes modified since last sync)
```

**Why separate keys instead of a Redis Hash?**
- `INCR` works on string keys but not on hash fields (need `HINCRBY` which is fine, but `INCR` is more natural)
- Each key can have its own TTL (important for rate limiting)
- Lua scripts can operate on keys directly with `KEYS[1]`, `KEYS[2]`

**Key TTL:**
- `url:{shortCode}:clicks` and `:expiry` — set to 30 days at creation. If they expire from Redis, the Lua script repopulates them from DB fallback values.
- `rate_limit:{ip}` — set to `windowSeconds` (60s) on first request.

---

## 6. Lua Scripts — Why and How

### Why Lua Scripts?

Redis is single-threaded but operations are not atomic across multiple commands sent from a client. Between two commands, another client could modify the same key. Lua scripts execute **atomically** on the Redis server — no other commands can interleave.

### Rate Limiter Lua Script

```lua
local count = redis.call('INCR', KEYS[1])
if count == 1 then
    redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))
end
return count
```

**What it solves:** Without Lua, `INCR` and `EXPIRE` are two separate commands. If the app crashes between them, the key has no TTL and persists forever — permanently rate-limiting that IP. The Lua script makes it atomic.

**How it works:**
1. `INCR` the counter (creates key with value 1 if it doesn't exist)
2. If this was the first request (`count == 1`), set the TTL
3. Return the count to Java, which checks `count <= maxRequests`

### Redirect Lua Script

```lua
-- Populate missing keys from DB fallback
if redis.call('EXISTS', KEYS[1]) == 0 then
    redis.call('SET', KEYS[1], ARGV[1])
end
if redis.call('EXISTS', KEYS[2]) == 0 then
    redis.call('SET', KEYS[2], ARGV[2])
end
-- Check expiry (lexicographic comparison works for ISO dates)
local expiry = redis.call('GET', KEYS[2])
if expiry < ARGV[3] then
    return -1
end
-- Increment clicks + mark dirty
local clicks = redis.call('INCR', KEYS[1])
redis.call('SADD', KEYS[3], ARGV[4])
return clicks
```

**What it solves:** The original code made 5-7 separate Redis calls per redirect (hasKey x2, set x2, get, increment, set). Each is a network round-trip (~0.5ms). The Lua script does everything in **one round-trip**.

**Key design detail:** The expiry comparison `expiry < ARGV[3]` works because ISO 8601 datetime strings like `"2026-02-12T10:30:00"` are **lexicographically orderable**. An earlier date is always "less than" a later date when compared as strings.

---

## 7. Caching Strategy (Two Layers)

### Layer 1: `@Cacheable` (Spring Cache → Redis)

`UrlShortenerCacheService` uses Spring's `@Cacheable` annotation to cache `UrlMapping` objects:

```java
@Cacheable(value = "urlMappingsByShortCode", key = "#shortCode", unless = "#result == null")
public UrlMapping getByShortCode(String shortCode) {
    return urlMappingRepository.findByShortCode(shortCode).orElse(null);
}
```

**What it caches:** The full `UrlMapping` Java object (serialized to Redis).
**Why it's safe:** The cached fields that matter (`originalUrl`, `shortCode`, `createdAt`) are **immutable** — they never change after creation.
**Why `unless = "#result == null"`:** Prevents caching null results (miss lookups). Without this, a lookup for a non-existent short code would cache `null`, and even after the URL is created, the cache would still return `null`.

### Layer 2: Manual Redis Keys

`url:{shortCode}:clicks` and `url:{shortCode}:expiry` are managed manually via `RedisTemplate`.

**Why not use `@Cacheable` for clicks too?** Because `@Cacheable` caches the entire object on first access and never updates it. Click counts change on every redirect. You'd need `@CachePut` or `@CacheEvict` on every click, which defeats the purpose.

### How They Work Together

```
Redirect request for shortCode "abc":
  1. @Cacheable returns UrlMapping from Redis (fast, no MongoDB hit)
  2. Lua script reads url:abc:clicks and url:abc:expiry from Redis
  3. Click count is incremented in Redis (not in the cached UrlMapping)
  4. Stats endpoint reads clicks from manual Redis keys, not from cached object
```

**Rule:** Immutable data (URL, shortCode) → `@Cacheable`. Mutable data (clicks, expiry) → manual Redis keys.

---

## 8. Rate Limiting

### Approach: Fixed-Window Counter

```
Window: 60 seconds
Max requests: 5

Time 0s:  Request 1 → INCR key (=1), SET EXPIRE 60s → allowed
Time 10s: Request 2 → INCR key (=2) → allowed
Time 50s: Request 5 → INCR key (=5) → allowed
Time 55s: Request 6 → INCR key (=6) → BLOCKED (429)
Time 60s: Key expires, counter resets → allowed again
```

### Why IP-Based?

- Simplest approach for a public API with no authentication
- No API keys needed
- Standard practice (used by GitHub API, many public APIs)

### Trade-offs

| Aspect | Detail |
|--------|--------|
| **Shared IPs (NAT/corporate)** | Multiple users behind one IP share the same limit. Could unfairly throttle. |
| **Proxy detection** | Uses `X-Forwarded-For` header, but this can be spoofed. In production, trust only the last proxy hop. |
| **Fixed window vs sliding window** | Fixed window allows burst at window boundary (5 at second 59, 5 at second 61 = 10 in 2s). A sorted-set sliding window is more accurate but more complex. |
| **Fail-open** | If Redis is down, all requests are allowed. This is intentional — availability over strictness. |

### Alternatives for Discussion

- **Token bucket** — allows bursts but enforces average rate. More complex.
- **Sliding window log** — Redis sorted set with timestamps. Exact but memory-intensive.
- **Sliding window counter** — hybrid of fixed window + interpolation. Good balance.
- **API key-based** — per-user limits instead of per-IP. More fair but requires auth.

---

## 9. Background Sync (Dirty-Set Pattern)

### Problem

Click counts live in Redis for speed, but Redis is volatile. If Redis crashes, click data is lost. We need to periodically persist click counts to MongoDB for durability.

### Naive Approach (What We Had Before)

```java
List<UrlMapping> allMappings = urlMappingRepository.findAll();  // load ALL URLs
for (UrlMapping mapping : allMappings) {
    // 2 Redis GETs + 1 MongoDB save per URL — even if it wasn't clicked
}
```

With 1 million URLs, this does 2M Redis reads + 1M MongoDB writes every 5 minutes. 99% of URLs haven't been clicked since last sync.

### Dirty-Set Approach (Current Implementation)

```
On each redirect (in Lua script):
  SADD dirty_urls {shortCode}    ← O(1), marks this URL as "changed"

Every 5 minutes (sync job):
  RENAME dirty_urls → dirty_urls:processing    ← atomic swap
  SMEMBERS dirty_urls:processing               ← get all dirty shortCodes
  For each shortCode:
    GET url:{shortCode}:clicks                 ← read latest count
    findByShortCode(shortCode)                 ← load from MongoDB
    save(mapping)                              ← update click count
  DELETE dirty_urls:processing                 ← cleanup
  deleteByExpiryDateBefore(now)                ← cleanup expired URLs
```

**Why RENAME?** During sync, new redirects might add entries to `dirty_urls`. By renaming to a processing key first, we:
- Don't lose entries added during sync (they go into the new `dirty_urls`)
- Don't process the same entry twice
- Achieve atomic separation of "in-progress" vs "new" dirty entries

**Complexity:** O(K) where K = number of URLs clicked since last sync (typically << N total).

### `@Scheduled` Behavior

```java
@Scheduled(fixedDelayString = "${scheduler.sync.interval}")
```

- `fixedDelay` = wait X ms **after** the previous execution finishes, then run again
- Runs on a single thread in the JVM (Spring's `TaskScheduler`)
- If the sync takes 10 seconds and interval is 300,000ms: runs every ~310 seconds

### Multi-Pod Consideration

`@Scheduled` is in-process — each pod runs its own independent scheduler. With 3 pods:
- The RENAME is atomic, so only one pod "wins" the swap
- The other two pods see that `dirty_urls` doesn't exist (RENAME fails) and return early
- This naturally provides leader-like behavior without external coordination
- For stronger guarantees, use **ShedLock** (a library that uses a distributed lock)

---

## 10. Expiry Strategy

### Design: Fixed 30-Day Expiry

- Set once at creation: `expiryDate = LocalDateTime.now().plusDays(30)`
- Never extended, even if the URL is clicked
- Checked on every redirect (in the Lua script)
- Expired URLs return `410 Gone`
- Expired documents cleaned up by the sync job

### Why Fixed Instead of Sliding?

| Fixed Expiry | Sliding Expiry |
|---|---|
| Predictable — URL expires exactly 30 days after creation | Unpredictable — a URL clicked once a month never expires |
| Simpler implementation | Requires updating expiry on every click |
| Easier to reason about cleanup | Cleanup is harder (can't predict when URLs will finally expire) |
| Storage is bounded (expired URLs get deleted) | Storage grows unboundedly for popular URLs |

### Why Have Expiry at All?

- **Storage management** — prevents unbounded growth of the URL collection
- **Security** — limits the lifetime of potentially malicious shortened URLs
- **Interview talking point** — shows you've thought about data lifecycle

### Alternative: No Expiry

Most real URL shorteners (bit.ly, TinyURL) don't expire links by default. This is simpler and avoids the expiry-check overhead on every redirect. For a personal project, either approach is defensible.

---

## 11. Fallback and Resilience

The service is designed to remain functional even when Redis is completely down.

### Redirect Fallback

```java
try {
    // Lua script: check expiry + increment clicks + mark dirty
    Long result = redisTemplate.execute(REDIRECT_SCRIPT, ...);
    if (result == -1) throw new ExpiredException(...);
} catch (ExpiredException | NotFoundException e) {
    throw e;  // propagate business exceptions
} catch (Exception e) {
    // Redis is down — fall back to MongoDB
    log.warn("Redis failed, falling back to DB for shortCode={}", shortCode, e);
    if (mapping.getExpiryDate().isBefore(LocalDateTime.now())) {
        throw new ExpiredException("Short code expired");
    }
    mapping.setClickCount(mapping.getClickCount() + 1);
    urlMappingRepository.save(mapping);  // direct DB update
}
```

**Key detail:** The `catch (ExpiredException | NotFoundException e)` block comes BEFORE `catch (Exception e)`. Without this, business exceptions thrown inside the try block would be swallowed and treated as Redis failures.

### Rate Limiter Fallback

```java
} catch (Exception e) {
    log.error("Redis rate limit failed for IP={}", ipAddress, e);
    return true;  // fail-open: allow the request
}
```

**Fail-open vs fail-closed:**
- **Fail-open** (our choice): Redis down → all requests allowed. Prioritizes availability.
- **Fail-closed**: Redis down → all requests blocked. Prioritizes security.
- For a URL shortener, availability is more important than strict rate limiting.

### Stats Fallback

```java
try {
    clicks = redisTemplate.opsForValue().get(clickKey);
    expiryStr = redisTemplate.opsForValue().get(expiryKey);
} catch (Exception e) {
    clicks = mapping.getClickCount();      // from MongoDB
    expiryStr = mapping.getExpiryDate();   // from MongoDB
}
```

Stats gracefully shows MongoDB values (which may be slightly stale due to sync delay).

---

## 12. Exception Handling

### Custom Exceptions

```
NotFoundException extends RuntimeException  → 404 Not Found
ExpiredException extends RuntimeException   → 410 Gone
```

### Global Exception Handler (`@RestControllerAdvice`)

| Exception | HTTP Status | When |
|-----------|-------------|------|
| `MethodArgumentNotValidException` | 400 Bad Request | Invalid URL format (no http/https prefix) |
| `ExpiredException` | 410 Gone | URL has passed its 30-day expiry |
| `NotFoundException` | 404 Not Found | Short code doesn't exist in DB |

This centralizes error handling — controllers don't need try-catch blocks. Spring automatically routes exceptions to the appropriate handler method.

---

## 13. MongoDB Indexing

```java
@Indexed(unique = true)
private String shortCode;

@Indexed
private String originalUrl;
```

- **`shortCode` (unique index):** Every redirect queries by shortCode. Without an index, this is a full collection scan — O(N). With the index, it's O(log N) B-tree lookup. `unique = true` also enforces uniqueness at the database level as a safety net.
- **`originalUrl` (regular index):** The shorten endpoint checks if a URL was already shortened (`findByOriginalUrl`). Without an index, this is O(N) for every URL creation.

Spring Data MongoDB auto-creates these indexes on application startup when `spring.data.mongodb.auto-index-creation` is not explicitly disabled.

---

## 14. Docker Deployment

```dockerfile
# Build stage: compile with JDK
FROM eclipse-temurin:17-jdk AS build
COPY . .
RUN ./mvnw package -DskipTests

# Run stage: lightweight JRE
FROM eclipse-temurin:17-jre
COPY --from=build /app/target/*.jar app.jar
CMD ["sh", "-c", "java -Dserver.port=$PORT -jar app.jar"]
```

**Multi-stage build benefits:**
- Build image has JDK + Maven (large, ~500MB)
- Runtime image has only JRE + app JAR (~200MB)
- Source code never ships in the production image

---

## 15. What's Good — Talking Points

1. **Collision-free ID generation** — Atomic counter + Base62 is a clean, proven approach. No retry logic needed, minimal code.

2. **Lua scripts for atomicity** — Shows understanding of Redis's single-threaded execution model and why multi-step operations need scripting.

3. **Dirty-set sync pattern** — O(K) instead of O(N). This is the kind of optimization interviewers love — it shows you're thinking about scale.

4. **Separation of mutable vs immutable caching** — Two-layer cache where `@Cacheable` handles static data and manual keys handle dynamic data. Avoids cache invalidation complexity.

5. **Graceful degradation** — The system doesn't crash when Redis goes down. Every Redis operation has a MongoDB fallback path.

6. **Proper exception propagation** — The `catch (ExpiredException | NotFoundException)` before `catch (Exception)` pattern prevents business exceptions from being swallowed.

7. **Range-based counter allocation** — Reduces MongoDB writes by 100x under load. Simple but effective optimization.

8. **Input validation** — `@Valid` + `@Pattern` for URL format. `@RestControllerAdvice` for centralized error handling.

9. **Constructor injection** — `@RequiredArgsConstructor` with `final` fields. Immutable, testable, Spring-recommended.

10. **SLF4J logging** — Proper log levels (`info`, `warn`, `error`), parameterized messages, exception stack traces where appropriate.

---

## 16. Known Limitations and Future Improvements

### Things to Mention Proactively in an Interview

| Limitation | What You'd Say |
|---|---|
| **No tests** | "I'd add integration tests with Testcontainers (MongoDB + Redis) and unit tests with Mockito for service layer." |
| **Fixed-window rate limiter** | "A sliding window (Redis sorted set) would be more accurate but more complex. Fixed window is good enough for this use case." |
| **Single-instance counter** | "Range allocation helps, but for a truly distributed system, I'd consider Snowflake IDs or UUIDs with a shorter encoding." |
| **No custom short codes** | "bit.ly allows custom aliases. I'd add an optional `customCode` field to the shorten request." |
| **No auth/multi-tenancy** | "For a production service, I'd add API keys, per-user rate limits, and link ownership." |
| **No analytics beyond click count** | "Real URL shorteners track referrer, device, geo-location, timestamps. I'd add an analytics events collection." |
| **Redis key TTL mismatch** | "The Redis key TTL (30 days) and the stored expiry value can drift. For fixed expiry, I could use MongoDB TTL index instead and skip Redis expiry entirely." |
| **CacheConfig.java is commented out** | "The commented-out config was intended to disable caching nulls. The `unless = #result == null` in @Cacheable handles this instead." |
| **No HTTPS redirect** | "The redirect doesn't enforce HTTPS. In production, a reverse proxy (nginx/cloudflare) would handle TLS termination." |

### Future Improvements

- **ShedLock** for distributed scheduler safety across pods
- **Redis Streams** for event-driven sync instead of polling
- **MongoDB TTL index** on `expiryDate` for automatic document deletion
- **Redis pipelining** for the `shortenUrl` method (two SETs could be pipelined)
- **Metrics** — Micrometer + Prometheus for monitoring (request latency, cache hit ratio, sync duration)
- **Circuit breaker** — Resilience4j around Redis calls for more sophisticated fallback

---

## 17. Interview Q&A

### "How do you ensure short codes are unique?"

> "I use a MongoDB atomic counter (`findAndModify` with `$inc`) that returns a strictly increasing sequence. Each number maps to a unique Base62 string. Since the counter is atomic, even under concurrent requests, no two threads get the same number. I also have a unique index on `shortCode` in MongoDB as a safety net."

### "What happens if Redis goes down?"

> "The service continues to work. Every Redis operation is wrapped in try-catch with a MongoDB fallback. Redirects check expiry and increment clicks directly in MongoDB. The rate limiter fails open — it allows all requests rather than blocking everyone. Stats fall back to MongoDB values, which may be slightly stale (up to 5 minutes behind due to sync interval)."

### "Why not just use MongoDB for everything? Why Redis?"

> "Redirects are the hottest path — potentially thousands per second. MongoDB queries involve disk I/O and network round-trips to the database. Redis serves from memory with sub-millisecond latency. For the redirect path, the Lua script checks expiry, increments clicks, and marks the URL as dirty in a single ~0.1ms operation. Without Redis, each redirect would need multiple MongoDB queries and writes."

### "How does the sync work? What if the app crashes during sync?"

> "The sync job uses a dirty-set pattern. Each redirect adds the shortCode to a Redis Set called `dirty_urls`. The sync job atomically renames this set to `dirty_urls:processing`, reads its members, and updates only those URLs in MongoDB. If the app crashes mid-sync, the `processing` key stays in Redis and those entries won't be synced until the next click triggers a new SADD. The click counts in Redis are still correct — they're just not persisted to MongoDB yet. On the next successful sync, the fresh Redis values will be written."

### "How would you scale this to millions of URLs?"

> "The current architecture handles this well: (1) MongoDB with indexes serves lookups efficiently, (2) @Cacheable prevents most MongoDB reads, (3) the dirty-set sync is O(K active) not O(N total), (4) range-based counter allocation reduces write contention by 100x. For horizontal scaling, I'd add ShedLock for the scheduler, use Redis Cluster for cache sharding, and consider read replicas for MongoDB. The stateless REST API scales horizontally by adding more pods behind a load balancer."

### "Why Base62 instead of hashing?"

> "Hashing (e.g., MD5/SHA truncated to 6 chars) introduces collision risk. You'd need retry logic or a collision table. With an atomic counter, every ID is unique by construction — no collisions, no retries, and the short codes are as short as possible (1 char for ID 1, 2 chars for ID 62, etc.). The downside is it requires a centralized counter, but the range-based allocation makes this a non-issue in practice."

### "Why fixed expiry instead of sliding?"

> "Sliding expiry means any URL that's clicked at least once a month never expires. This defeats the purpose of expiration — storage grows unboundedly for popular URLs. Fixed 30-day expiry is predictable: every URL has a known end-of-life, enabling automatic cleanup. It also simplifies the Lua script (no need to write the expiry key on every click)."

### "What's the Lua script doing and why can't you use regular Redis commands?"

> "The redirect Lua script does 5 things atomically: (1) populates missing keys from DB fallback, (2) checks expiry, (3) increments click count, (4) marks the shortCode as dirty for sync, (5) returns the result. Without Lua, these would be 5-7 separate Redis round-trips, each adding ~0.5ms of network latency. More importantly, between individual commands, another request could modify the same keys — leading to race conditions (e.g., two requests both see 'not expired', but one sets clicks while the other checks expiry). Lua scripts execute atomically on the Redis server."

---

## 18. Alternative Approaches

### Short Code Generation Alternatives

| Approach | When to Use |
|----------|-------------|
| **Counter + Base62** (ours) | Simple, short codes, no collisions |
| **Snowflake ID** | Distributed systems, no central counter needed |
| **UUID shortened** | When you can't have a centralized counter |
| **Hash (MD5/SHA)** | When you want deterministic codes from the URL itself |
| **Pre-generated pool** | When you need to decouple ID generation from URL creation |

### Caching Alternatives

| Approach | When to Use |
|----------|-------------|
| **Redis @Cacheable** (ours) | When data is mostly read, rarely written |
| **Local in-memory cache (Caffeine)** | Single-instance, ultra-low latency, small dataset |
| **Two-tier (Caffeine + Redis)** | High-throughput, minimize even Redis round-trips |
| **No cache** | If MongoDB is fast enough with proper indexes |

### Sync Alternatives

| Approach | When to Use |
|----------|-------------|
| **Dirty-set polling** (ours) | Simple, effective, good enough for most cases |
| **Redis Streams + consumer groups** | Event-driven, exactly-once processing, multi-pod safe |
| **MongoDB Change Streams** | When MongoDB is the write source and you need reactive updates |
| **Write-through cache** | When you can tolerate write latency (write to both DB + cache on every request) |
| **No sync (Redis as source of truth)** | When you accept click data loss on Redis failure |

### Rate Limiting Alternatives

| Approach | When to Use |
|----------|-------------|
| **Fixed window counter** (ours) | Simple, minimal Redis storage |
| **Sliding window log** | Precise, no burst at boundary, but memory-heavy |
| **Sliding window counter** | Good balance of precision and memory |
| **Token bucket** | When you want to allow controlled bursts |
| **Leaky bucket** | When you want constant output rate |

---

*This document is meant for interview prep. Read through it, understand the "why" behind each decision, and practice explaining the trade-offs in your own words.*
