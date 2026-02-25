# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Plan File

**IMPORTANT:** Before starting any implementation work, always read `bookshelf-api-plan.md` in the project root. This is the master plan for the entire project — it contains the detailed build order, design decisions, data model, API specs, architecture, integration tests, and version roadmap (V1 through V4). All implementation should follow the plan file. For barcode scanner work specifically, also read `isbn-scanner-plan.md`. For search/sorting/reading-progress work, read `docs/plans/2026-02-20-search-sorting-reading-progress.md`.

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
- **`BookRepository`** (interface) — `findAll()`, `findByGenre()`, `findByReadStatus()`, `findBySearch()`, `findById()`, `findByIsbn()`, `save()`, `update()`, `delete()`. V1 uses `InMemoryBookRepository` (ConcurrentHashMap), V3 uses `JdbcBookRepository` (PostgreSQL + HikariCP)
- **`ResponseWriter`** — Writes formatted HTTP response to socket `OutputStream`

### Open Library Integration (V2)
- **`OpenLibraryService`** — Single-thread `ExecutorService` that asynchronously fetches metadata and cover images from Open Library by ISBN. Enrichment is best-effort; POST returns immediately. Sends `User-Agent: MyBookShelf/1.0` header for better rate limits. Also provides `reEnrichAll()` for batch re-enrichment with 3-second rate-limit delays.
- **`BookMetadata`** — Model for parsed Open Library data (title, author, publisher, publishDate, pageCount, subjects, genre, coverUrl)
- **`StaticFileHandler`** — Serves static frontend files from `/static` directory

### Database Layer (V3)
- **`DatabaseConfig`** — HikariCP connection pool, reads config from env vars, runs schema migration on startup
- **`JdbcBookRepository`** — JDBC implementation of `BookRepository` against PostgreSQL

### Frontend (V4)
- Vanilla HTML/CSS/JS in `/static` directory, served by the same Java server
- ISBN-only input flow: POST with just ISBN → placeholder card → polls until enrichment completes
- **Barcode scanner**: zbar-wasm (WASM C decoder) in `static/lib/zbar-wasm.js` (inlined UMD, 326 KB). Multi-pass pipeline: raw grayscale → sharpen → global thresholds → adaptive threshold. Scans camera ROI via `getUserMedia`. See `isbn-scanner-plan.md` for details.
- **Client-side search bar**: filters `allBooks` in memory by title/author; no extra API call
- **Sort dropdown**: sorts by title, author, rating, or date added (asc/desc); applied client-side after filters

## Data Model — Book

| Field            | Type                                        | Required | Notes |
|------------------|---------------------------------------------|----------|-------|
| `id`             | UUID                                        | auto     | Server-generated |
| `title`          | String                                      | yes*     | *Optional if `isbn` provided (V4+) |
| `author`         | String                                      | yes*     | *Optional if `isbn` provided (V4+) |
| `genre`          | String                                      | no       | |
| `rating`         | Integer (1–5, default 0)                    | no       | 0 = not rated; user cannot explicitly set 0. Boxed type for nullable partial updates |
| `isbn`           | String                                      | no       | 10-char (last may be 'X') or 13-digit |
| `publisher`      | String                                      | no       | Auto-filled from Open Library |
| `publishDate`    | String                                      | no       | Auto-filled from Open Library |
| `pageCount`      | Integer                                     | no       | Auto-filled from Open Library. Boxed type for nullable |
| `subjects`       | List\<String\>                              | no       | Auto-filled; stored as JSON array string in DB |
| `readStatus`     | enum: `WANT_TO_READ`, `READING`, `FINISHED`, `DNF` | yes      | |
| `readingProgress`| Integer (0–100)                             | no       | Only meaningful when `readStatus == READING`. Nullable. |
| `coverData`      | byte[] (transient)                          | no       | Cover image bytes, stored as BYTEA in DB. Not serialized to JSON |
| `coverUrl`       | String                                      | no       | Original Open Library URL |
| `createdAt`      | Instant                                     | auto     | Serialized as ISO-8601 string in JSON responses |
| `updatedAt`      | Instant (transient)                         | auto     | Not serialized to JSON |

## API Endpoints

| Method   | Path                  | Description                              | Success Code     |
|----------|-----------------------|------------------------------------------|------------------|
| `GET`    | `/books`              | List all books. Supports `?genre=`, `?readStatus=`, `?search=`, `?sort=` | `200 OK`         |
| `GET`    | `/books/{id}`         | Get a single book by ID                  | `200 OK`         |
| `GET`    | `/books/isbn/{isbn}`  | Look up a book by ISBN                   | `200 OK`         |
| `POST`   | `/books`              | Add a new book                           | `201 Created`    |
| `PUT`    | `/books/{id}`         | Partial update (only sent fields change) | `200 OK`         |
| `DELETE` | `/books/{id}`         | Delete a book                            | `204 No Content` |
| `POST`   | `/books/re-enrich`    | Re-enrich all books with ISBNs from Open Library | `202 Accepted` |
| `GET`    | `/books/{id}/cover`   | Serve cover image from DB (V2+)          | `200 OK`         |

### `GET /books` Query Parameters

| Parameter   | Example                | Description |
|-------------|------------------------|-------------|
| `genre`     | `?genre=Fiction`       | Filter by genre (case-insensitive in SQL, exact in memory) |
| `readStatus`| `?readStatus=READING`  | Filter by read status enum value |
| `search`    | `?search=dune`         | Case-insensitive substring search on title and author |
| `sort`      | `?sort=title,asc`      | Sort results. Fields: `title`, `author`, `rating`, `created`. Directions: `asc`, `desc` |

Parameters can be combined: `?search=frank&readStatus=FINISHED&sort=rating,desc`

`search` takes priority over `genre` and `readStatus` for the base query; `readStatus` is applied as a post-filter on top of search/genre results. Sorting is applied last.

### Error Responses
| Code  | When |
|-------|------|
| `400` | Missing required fields, malformed JSON, invalid rating (must be 1–5 when provided), invalid ISBN format, `readingProgress` out of 0–100 range |
| `404` | Book ID not found, ISBN not found, cover not available |
| `405` | Unsupported HTTP method on a route |

## Key Design Decisions

- **PUT = partial update**: only fields present in the request body are overwritten; missing fields unchanged; explicit `null` clears a field
- **Gson** is the only external dependency in V1 (V3 adds PostgreSQL driver and HikariCP). Gson is configured with `serializeNulls()` and a custom `Instant` serializer that writes ISO-8601 strings
- **ISBN validation**: accepts 10-char (last char may be 'X') or 13-digit format
- **Duplicate ISBNs allowed** — users may own multiple copies; `findByIsbn` returns the oldest (first-created)
- **Rating**: Integer 1–5, default `0` (not rated). User cannot explicitly set `0`; it's only the default
- **`rating`/`pageCount`/`readingProgress` use `Integer`** (boxed) — nullable to distinguish "not provided" from 0 in partial updates
- **Subjects**: stored as JSON array string in a TEXT column
- **Open Library enrichment** only fills in `null` fields — user-provided values are never overwritten. On ISBN change (PUT), previously-enriched fields are cleared before re-enrichment. Genre is auto-derived from subjects during enrichment
- **Re-enrichment** — `POST /books/re-enrich` queues all ISBN-bearing books for background re-enrichment (null-title books first), with 3-second delays between requests to respect rate limits
- **Cover images stored in DB** — as `BYTEA` in PostgreSQL (`cover_data` column), not on filesystem. `Book.coverData` is `transient` (not serialized to JSON)
- **No genre filtering in V1** — added in V3 as SQL `findByGenre()`. Read status filtering (`?readStatus=`) added in V4
- **DB schema allows NULL for `title`/`author`** from V1 onward to avoid migration when V4 makes them optional
- **1x1 pixel detection**: Open Library returns a tiny placeholder instead of 404 for missing covers; check file size < 1KB
- **V4 validation change**: `title`/`author` become optional if `isbn` is provided (enriched async)
- **Frontend add default**: new books added via ISBN always get `WANT_TO_READ` status initially
- **Search is client-side in the frontend** and server-side via `?search=` for API consumers. The frontend does not call `?search=` — it filters `allBooks` in memory
- **Sorting is in-memory in `BookController`** — a `Comparator` is applied to the result list after repository fetch. No `ORDER BY` is added to SQL queries
- **`readingProgress`** is validated as 0–100 on both create and update. Setting it to `null` via PUT clears it. Only displayed in the UI for `READING` books

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

Tests use JUnit 5 with `java.net.HttpClient`. The server starts on a random port (`new ServerSocket(0)`) before each test class and shuts down after. Repository is cleaned between tests for isolation. Tests run against `InMemoryBookRepository` only (no DB required for `./gradlew test`).

Test classes:
- **`BookApiTest`** — full HTTP integration tests covering CRUD, filtering, search, sorting, reading progress, partial updates, validation, and cover endpoints
- **`OpenLibraryTest`** — tests for Open Library enrichment service

## Database Column Mapping

DB uses `snake_case`, Java uses `camelCase`. Map explicitly in `JdbcBookRepository`:

| Java field        | DB column          |
|-------------------|--------------------|
| `readStatus`      | `read_status`      |
| `coverData`       | `cover_data`       |
| `publishDate`     | `publish_date`     |
| `pageCount`       | `page_count`       |
| `coverUrl`        | `cover_url`        |
| `createdAt`       | `created_at`       |
| `updatedAt`       | `updated_at`       |
| `readingProgress` | `reading_progress` |

## DB Schema Migrations

Migrations run on startup via `DatabaseConfig.runMigrations()`. The schema uses `IF NOT EXISTS` / `ADD COLUMN IF NOT EXISTS` throughout so it is safe to re-run on an existing database. Current columns added as `ALTER TABLE` migrations (beyond the base `CREATE TABLE`):

1. `cover_data BYTEA` — cover image bytes
2. `reading_progress INTEGER DEFAULT NULL` — reading progress (0–100)

## Source File Overview

| File | Role |
|------|------|
| `App.java` | Entry point; wires repository, controller, router, and server |
| `HttpServer.java` | `ServerSocket` + `ExecutorService` connection handler |
| `RequestParser.java` | Raw socket stream → `HttpRequest` |
| `ResponseWriter.java` | `HttpResponse` → raw socket stream |
| `Router.java` | Method + path pattern → handler dispatch |
| `HttpRequest.java` | Request model (method, path, pathParams, queryParams, headers, body) |
| `HttpResponse.java` | Response model + factory methods (`ok`, `created`, `notFound`, etc.) |
| `Book.java` | Book entity with all fields and getters/setters |
| `ReadStatus.java` | Enum: `WANT_TO_READ`, `READING`, `FINISHED` |
| `BookRepository.java` | Repository interface |
| `InMemoryBookRepository.java` | `ConcurrentHashMap`-backed implementation (used in tests) |
| `JdbcBookRepository.java` | PostgreSQL JDBC implementation |
| `BookController.java` | HTTP handler methods; validation, JSON parsing, sorting |
| `DatabaseConfig.java` | HikariCP pool setup + schema migrations |
| `OpenLibraryService.java` | Async Open Library metadata + cover fetching |
| `BookMetadata.java` | DTO for Open Library response |
| `StaticFileHandler.java` | Serves `static/` files |
