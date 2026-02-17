# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Plan File

**IMPORTANT:** Before starting any implementation work, always read `bookshelf-api-plan.md` in the project root. This is the master plan for the entire project — it contains the detailed build order, design decisions, data model, API specs, architecture, integration tests, and version roadmap (V1 through V4). All implementation should follow the plan file.

## Project Overview

A personal bookshelf REST API built from scratch in Java 17+ using only `java.net.ServerSocket` — no Spring, no Javalin, no frameworks. The HTTP layer, routing, request parsing, and response writing are all hand-built.

## Build & Run Commands

```bash
# Build
./gradlew build

# Run the server (default port 8080)
./gradlew run

# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.bookshelf.BookApiTest"

# Run a single test method
./gradlew test --tests "com.bookshelf.BookApiTest.testCreateAndRetrieveBook"

# Build fat JAR (V3+)
./gradlew shadowJar

# Docker (V3+)
docker compose up --build
docker compose down
docker compose down -v   # removes data volumes too
```

## Architecture

The project is built in progressive versions (V1 → V4), each adding a layer:

### Core Components (V1)
- **`HttpServer`** — `ServerSocket` listener with a fixed thread pool (10 threads via `ExecutorService`)
- **`RequestParser`** — Reads raw socket `InputStream`, produces `HttpRequest` (method, path, queryParams, headers, body)
- **`HttpRequest` / `HttpResponse`** — Simple model classes
- **`Router`** — Maps method + path patterns (with `{param}` extraction) to handler functions. Static segments (`isbn`) take priority over parameters (`{id}`) to avoid route conflicts
- **`BookController`** — Endpoint handlers: deserializes JSON via Gson, validates input, calls repository, returns `HttpResponse`
- **`BookRepository`** (interface) — `findAll()`, `findById()`, `findByIsbn()`, `save()`, `update()`, `delete()`. V1 uses `InMemoryBookRepository` (ConcurrentHashMap), V3 uses `JdbcBookRepository` (PostgreSQL + HikariCP)
- **`ResponseWriter`** — Writes formatted HTTP response to socket `OutputStream`

### Open Library Integration (V2)
- **`OpenLibraryService`** — Single-thread `ExecutorService` that asynchronously fetches metadata and cover images from Open Library by ISBN. Enrichment is best-effort; POST returns immediately.
- **`BookMetadata`** — Model for parsed Open Library data (publisher, publishDate, pageCount, subjects)
- **`StaticFileHandler`** — Serves cover images from `/covers` directory with `Content-Type: image/jpeg`

### Database Layer (V3)
- **`DatabaseConfig`** — HikariCP connection pool, reads config from env vars, runs schema migration on startup
- **`JdbcBookRepository`** — JDBC implementation of `BookRepository` against PostgreSQL

### Frontend (V4)
- Vanilla HTML/CSS/JS in `/static` directory, served by the same Java server
- ISBN-only input flow: POST with just ISBN → placeholder card → polls until enrichment completes
- **ISBN barcode scanner** — uses `html5-qrcode` library (vendored in `static/lib/`) for webcam-based EAN-13/EAN-8 barcode scanning. Auto-adds new ISBNs, prompts on duplicates. See `isbn-scanner-plan.md` for details

## Data Model — Book

| Field        | Type                                        | Required | Notes |
|--------------|---------------------------------------------|----------|-------|
| `id`         | UUID                                        | auto     | Server-generated |
| `title`      | String                                      | yes*     | *Optional if `isbn` provided (V4+) |
| `author`     | String                                      | yes*     | *Optional if `isbn` provided (V4+) |
| `genre`      | String                                      | no       | |
| `rating`     | Integer (1–5, default 0)                    | no       | 0 = not rated; user cannot explicitly set 0. Boxed type for nullable partial updates |
| `isbn`       | String                                      | no       | 10-char (last may be 'X') or 13-digit |
| `publisher`  | String                                      | no       | Auto-filled from Open Library |
| `publishDate`| String                                      | no       | Auto-filled from Open Library |
| `pageCount`  | Integer                                     | no       | Auto-filled from Open Library. Boxed type for nullable |
| `subjects`   | List\<String\>                              | no       | Auto-filled; stored as JSON array string in DB |
| `readStatus` | enum: `WANT_TO_READ`, `READING`, `FINISHED` | yes      | |
| `coverPath`  | String                                      | no       | Local file path to saved cover image |
| `coverUrl`   | String                                      | no       | Original Open Library URL |

## API Endpoints

| Method   | Path                  | Description                              | Success Code     |
|----------|-----------------------|------------------------------------------|------------------|
| `GET`    | `/books`              | List all books. V3 adds `?genre=`, V4 adds `?readStatus=` | `200 OK`         |
| `GET`    | `/books/{id}`         | Get a single book by ID                  | `200 OK`         |
| `GET`    | `/books/isbn/{isbn}`  | Look up a book by ISBN                   | `200 OK`         |
| `POST`   | `/books`              | Add a new book                           | `201 Created`    |
| `PUT`    | `/books/{id}`         | Partial update (only sent fields change) | `200 OK`         |
| `DELETE` | `/books/{id}`         | Delete a book                            | `204 No Content` |
| `GET`    | `/books/{id}/cover`   | Serve cover image (V2+)                  | `200 OK`         |

### Error Responses
| Code  | When |
|-------|------|
| `400` | Missing required fields, malformed JSON, invalid rating (must be 1–5 when provided), invalid ISBN format |
| `404` | Book ID not found, ISBN not found, cover not available |
| `405` | Unsupported HTTP method on a route |

## Key Design Decisions

- **PUT = partial update**: only fields present in the request body are overwritten; missing fields unchanged; explicit `null` clears a field
- **Gson** is the only external dependency in V1 (V3 adds PostgreSQL driver and HikariCP)
- **ISBN validation**: accepts 10-char (last char may be 'X') or 13-digit format
- **Duplicate ISBNs allowed** — users may own multiple copies; `findByIsbn` returns the oldest (first-created)
- **Rating**: Integer 1–5, default `0` (not rated). User cannot explicitly set `0`; it's only the default
- **`rating`/`pageCount` use `Integer`** (boxed) — nullable to distinguish "not provided" from 0 in partial updates
- **Subjects**: stored as JSON array string in a TEXT column
- **Open Library enrichment** only fills in `null` fields — user-provided values are never overwritten. On ISBN change (PUT), previously-enriched fields are cleared before re-enrichment
- **No genre filtering in V1** — added in V3 as SQL `findByGenre()`. Read status filtering (`?readStatus=`) added in V4
- **DB schema allows NULL for `title`/`author`** from V1 onward to avoid migration when V4 makes them optional
- **1x1 pixel detection**: Open Library returns a tiny placeholder instead of 404 for missing covers; check file size < 1KB
- **V4 validation change**: `title`/`author` become optional if `isbn` is provided (enriched async)
- **Frontend add default**: new books added via ISBN always get `WANT_TO_READ` status initially

## Environment Variables (V3+)

| Variable    | Default     | Description        |
|-------------|-------------|--------------------|
| `DB_HOST`   | `localhost` | PostgreSQL host    |
| `DB_PORT`   | `5432`      | PostgreSQL port    |
| `DB_NAME`   | `bookshelf` | Database name      |
| `DB_USER`   | `bookshelf` | Database user      |
| `DB_PASS`   | `bookshelf` | Database password  |
| `APP_PORT`  | `8080`      | Server listen port |

## Testing

Tests use JUnit 5 with `java.net.HttpClient`. The server starts on a random port (`new ServerSocket(0)`) before each test class and shuts down after. Repository is cleaned between tests for isolation.

## Database Column Mapping

DB uses `snake_case` (`read_status`, `cover_path`, `publish_date`, `page_count`, `cover_url`, `created_at`, `updated_at`), Java uses `camelCase`. Map explicitly in `JdbcBookRepository`.
