# URL Shortener

A production-grade URL shortening service built with Java 17 and Spring Boot, backed by MongoDB for persistence and Redis for caching, click tracking, and rate limiting.

## Features

- **Short URL creation** with collision-free Base62-encoded short codes
- **302 redirect** with per-click tracking
- **Per-link analytics** — click count, creation date, expiry date
- **Fixed 30-day expiry** with automatic cleanup of expired URLs
- **Redis caching** for sub-millisecond URL lookups
- **Lua scripts** for atomic multi-step Redis operations (redirect + rate limiting)
- **Per-IP rate limiting** (configurable requests/window)
- **Graceful degradation** — falls back to MongoDB when Redis is unavailable
- **Background sync** — periodically persists Redis click counts to MongoDB (dirty-set approach, O(K) not O(N))
- **Dockerized** with multi-stage build

## Tech Stack

| Layer       | Technology            |
|-------------|----------------------|
| Language    | Java 17              |
| Framework   | Spring Boot 3.5.3    |
| Database    | MongoDB              |
| Cache       | Redis                |
| Build       | Maven (wrapper)      |
| Container   | Docker (Temurin 17)  |

## API Endpoints

| Method | Path                  | Description                     |
|--------|-----------------------|---------------------------------|
| POST   | `/api/shorten`        | Shorten a URL                   |
| GET    | `/api/{shortCode}`    | Redirect to original URL (302)  |
| GET    | `/api/{shortCode}/stats` | Get click stats for a short URL |

### Shorten URL

```bash
curl -X POST http://localhost:8080/api/shorten \
  -H "Content-Type: application/json" \
  -d '{"originalUrl": "https://example.com/very/long/path"}'
```

Response: `b` (the short code)

### Redirect

```bash
curl -L http://localhost:8080/api/b
# → 302 redirect to https://example.com/very/long/path
```

### Stats

```bash
curl http://localhost:8080/api/b/stats
```

```json
{
  "originalUrl": "https://example.com/very/long/path",
  "shortCode": "b",
  "clickCount": 42,
  "createdAt": "2026-02-12T10:30:00",
  "expiryDate": "2026-03-14T10:30:00"
}
```

## Environment Variables

| Variable         | Description              |
|------------------|--------------------------|
| `MONGODB_URI`    | MongoDB connection URI   |
| `MONGODB_DB`     | MongoDB database name    |
| `REDIS_HOST`     | Redis host               |
| `REDIS_PORT`     | Redis port               |
| `REDIS_USERNAME` | Redis username           |
| `REDIS_PASSWORD` | Redis password           |
| `PORT`           | Server port (Docker)     |

## Configuration

Key properties in `application.properties`:

```properties
scheduler.sync.interval=300000       # Redis-to-MongoDB sync interval (ms)
rate.limit.maxRequests=5             # Max requests per IP per window
rate.limit.windowSeconds=60          # Rate limit window (seconds)
spring.cache.type=redis              # Cache provider
```

## Running Locally

```bash
# Set environment variables (or create application.properties)
export MONGODB_URI=mongodb://localhost:27017
export MONGODB_DB=urlshortener
export REDIS_HOST=localhost
export REDIS_PORT=6379

./mvnw spring-boot:run
```

## Docker

```bash
docker build -t url-shortener .
docker run -p 8080:8080 \
  -e MONGODB_URI=mongodb://host.docker.internal:27017 \
  -e MONGODB_DB=urlshortener \
  -e REDIS_HOST=host.docker.internal \
  -e REDIS_PORT=6379 \
  -e PORT=8080 \
  url-shortener
```

## Architecture

```
Client → Controller → Service → Redis (Lua script)
                          ↓              ↓
                     CacheService    Dirty Set
                          ↓              ↓
                       MongoDB    ← SyncService (scheduled)
```

See [DEEP_DIVE.md](DEEP_DIVE.md) for a detailed architectural walkthrough.
