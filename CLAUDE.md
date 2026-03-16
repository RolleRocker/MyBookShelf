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
- **`BookRepository`** (interface) — `findAll()`, `findByGenre()`, `findByReadStatus()`, `findBySearch()`, `findById()`, `findByIsbn()`, `save()`, `update()`, `delete()`, `updateFromOpenLibrary()`, `clear()`. V1 uses `InMemoryBookRepository` (ConcurrentHashMap), V3 uses `JdbcBookRepository` (PostgreSQL + HikariCP)
- **`ResponseWriter`** — Writes formatted HTTP response to socket `OutputStream`

### Open Library Integration (V2)
- **`BookEnrichmentService`** — Single-thread `ExecutorService` that asynchronously fetches metadata and cover images by ISBN. Tries Open Library first; falls back to Google Books API if Open Library returns no data. Enrichment is best-effort; POST returns immediately. Sends `User-Agent: MyBookShelf/1.0` header. Also provides `reEnrichAll()` for batch re-enrichment with rate-limit delays between requests.
- **`BookMetadata`** — Model for parsed Open Library data (title, author, publisher, publishDate, pageCount, subjects, genre, coverUrl)
- **`StaticFileHandler`** — Serves static frontend files from `/static` directory

### Database Layer (V3)
- **`DatabaseConfig`** — HikariCP connection pool, reads config from env vars, runs schema migration on startup
- **`JdbcBookRepository`** — JDBC implementation of `BookRepository` against PostgreSQL

### Frontend (V4)
- Vanilla HTML/CSS/JS in `/static` directory, served by the same Java server
- ISBN-only input flow: POST with just ISBN → placeholder card → polls until enrichment completes
- **Barcode scanner**: zbar-wasm (WASM C decoder) in `static/lib/zbar-wasm.js` (inlined UMD, 326 KB). Multi-pass pipeline: raw grayscale → sharpen → global thresholds → adaptive threshold. Scans camera ROI via `getUserMedia`.
- **Client-side search bar**: filters `allBooks` in memory by title/author; no extra API call
- **Sort dropdown**: sorts by title, author, rating, or date added (asc/desc); applied client-side after filters

## Data Model — Book

| Field            | Type                                        | Required | Notes |
|------------------|---------------------------------------------|----------|-------|
| `id`             | UUID                                        | auto     | Server-generated |
| `title`          | String                                      | yes*     | *Optional if `isbn` provided (V4+) |
| `author`         | String                                      | yes*     | *Optional if `isbn` provided (V4+) |
| `genre`          | String                                      | no       | |
| `rating`         | Integer (0–10 internal, 0.5–5.0 display)    | no       | 0 = not rated. Internal scale: 0–10 (doubled); API accepts/returns 0.5–5.0 in 0.5 increments. Boxed type for nullable partial updates |
| `isbn`           | String                                      | no       | 10-char (last may be 'X') or 13-digit |
| `publisher`      | String                                      | no       | Auto-filled from Open Library |
| `publishDate`    | String                                      | no       | Auto-filled from Open Library |
| `pageCount`      | Integer                                     | no       | Auto-filled from Open Library. Boxed type for nullable |
| `subjects`       | List\<String\>                              | no       | Auto-filled; stored as JSON array string in DB |
| `readStatus`     | enum: `WANT_TO_READ`, `READING`, `FINISHED`, `DNF` | yes      | |
| `readingProgress`| Integer (0–100)                             | no       | Only meaningful when `readStatus == READING`. Nullable. |
| `review`         | String                                      | no       | User notes/review for the book |
| `startedAt`      | String (YYYY-MM-DD)                         | no       | Date the user started reading. Cannot be in the future |
| `finishedAt`     | String (YYYY-MM-DD)                         | no       | Date the user finished reading. Cannot be before `startedAt` or in the future |
| `coverData`      | byte[] (transient)                          | no       | Cover image bytes, stored as BYTEA in DB. Not serialized to JSON |
| `coverUrl`       | String                                      | no       | Original Open Library URL |
| `createdAt`      | Instant                                     | auto     | Serialized as ISO-8601 string in JSON responses |
| `updatedAt`      | Instant (transient)                         | auto     | Not serialized to JSON |

## Data Model — Shelf

| Field            | Type           | Required | Notes |
|------------------|----------------|----------|-------|
| `id`             | UUID           | auto     | Server-generated |
| `name`           | String         | yes      | Must be unique |
| `description`    | String         | no       | |
| `notes`          | String         | no       | |
| `color`          | String         | no       | Hex color for UI |
| `sortField`      | String         | no       | Default sort for books in shelf |
| `sortDirection`  | String         | no       | `asc` or `desc` |
| `position`       | int            | auto     | Order in sidebar |
| `createdAt`      | Instant        | auto     | |
| `updatedAt`      | Instant (transient) | auto | Not serialized to JSON |
| `bookCount`      | int (transient)| computed | Number of books on shelf |
| `coverBookIds`   | List\<UUID\> (transient) | computed | Up to 4 book IDs for cover thumbnails |
| `books`          | List\<Book\> (transient) | computed | Populated on GET `/shelves/{id}` |
| `stats`          | Map (transient)| computed | Per-shelf statistics |

## Data Model — ReadingGoal

| Field            | Type           | Required | Notes |
|------------------|----------------|----------|-------|
| `id`             | UUID           | auto     | Server-generated |
| `year`           | int            | yes      | Must be unique, range 2000–2100 |
| `target`         | int            | yes      | Minimum 1 |
| `createdAt`      | Instant        | auto     | |
| `updatedAt`      | Instant (transient) | auto | Not serialized to JSON |

Responses include computed fields: `progress` (finished books in that year), `percentage` (0–100), `onPace` (boolean), `paceDelta` (books ahead/behind target).

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
| `GET`    | `/shelves`            | List all shelves (with book counts)      | `200 OK`         |
| `POST`   | `/shelves`            | Create a new shelf                       | `201 Created`    |
| `GET`    | `/shelves/{id}`       | Get shelf with books and stats           | `200 OK`         |
| `PUT`    | `/shelves/{id}`       | Update a shelf                           | `200 OK`         |
| `DELETE` | `/shelves/{id}`       | Delete a shelf                           | `204 No Content` |
| `PUT`    | `/shelves/reorder`    | Reorder shelves by position              | `200 OK`         |
| `POST`   | `/shelves/{id}/books` | Add a book to a shelf                    | `201 Created`    |
| `PUT`    | `/shelves/{id}/books/reorder` | Reorder books within a shelf     | `200 OK`         |
| `DELETE` | `/shelves/{id}/books/{bookId}` | Remove a book from a shelf      | `204 No Content` |
| `GET`    | `/goals`              | List all reading goals (with progress)   | `200 OK`         |
| `POST`   | `/goals`              | Create a new reading goal                | `201 Created`    |
| `GET`    | `/goals/{year}`       | Get a specific year's goal with progress | `200 OK`         |
| `PUT`    | `/goals/{year}`       | Update goal target                       | `200 OK`         |
| `DELETE` | `/goals/{year}`       | Delete a reading goal                    | `204 No Content` |
| `POST`   | `/mcp`                | MCP Streamable HTTP endpoint (JSON-RPC)  | `200 OK`         |

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
| `400` | Missing required fields, malformed JSON, invalid rating (must be 0.5–5.0 in 0.5 increments when provided), invalid ISBN format, `readingProgress` out of 0–100 range, invalid date format, future dates, `finishedAt` before `startedAt` |
| `404` | Book ID not found, ISBN not found, cover not available, goal not found |
| `405` | Unsupported HTTP method on a route |
| `409` | Duplicate reading goal for a year |

## Key Design Decisions

- **PUT = partial update**: only fields present in the request body are overwritten; missing fields unchanged; explicit `null` clears a field
- **Gson** is the only external dependency in V1 (V3 adds PostgreSQL driver and HikariCP). Gson is configured with `serializeNulls()` and a custom `Instant` serializer that writes ISO-8601 strings
- **ISBN validation**: accepts 10-char (last char may be 'X') or 13-digit format
- **Duplicate ISBNs allowed** — users may own multiple copies; `findByIsbn` returns the oldest (first-created)
- **Rating**: Half-star scale 0.5–5.0 in 0.5 increments. Stored internally as Integer 0–10 (doubled). API accepts/returns decimal (e.g., `3.5`). Default `0` (not rated). User cannot explicitly set `0`; it's only the default. Whole-number ratings serialize as integers (`4`), half-stars as decimals (`4.5`)
- **`rating`/`pageCount`/`readingProgress` use `Integer`** (boxed) — nullable to distinguish "not provided" from 0 in partial updates
- **Start/finish dates** — `startedAt` and `finishedAt` are YYYY-MM-DD strings. Cannot be in the future. `finishedAt` cannot be before `startedAt`. When `readStatus` changes to `READING`, `startedAt` auto-fills to today if not set. When status changes to `FINISHED` or `DNF`, `finishedAt` auto-fills. When status changes to `WANT_TO_READ`, both dates are cleared
- **Reading goals** — keyed by year (unique). Progress is computed dynamically from finished books using `finishedAt` (preferred) or `createdAt` (fallback). `onPace` calculation differs for past, current, and future years
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

## MCP (Model Context Protocol) Integration

The server exposes an MCP endpoint at `POST /mcp` using the Streamable HTTP transport (stateless mode). This lets Claude Code query the bookshelf via natural language. Configured via `.mcp.json` in the project root.

**Tools:** `check_book`, `search_books`, `list_books`, `get_book_by_isbn`, `get_bookshelf_stats`

**Protocol:** JSON-RPC 2.0 over HTTP POST. Supports `initialize`, `notifications/initialized`, `tools/list`, `tools/call`, and `ping` methods. No SDK dependencies — implemented directly with Gson on the existing ServerSocket/Router.

**Requirement:** The bookshelf server must be running (`docker compose up --build -d`) for MCP tools to work.

## Environment Variables (V3+)

| Variable    | Default     | Description        |
|-------------|-------------|--------------------|
| `DB_HOST`   | `localhost` | PostgreSQL host    |
| `DB_PORT`   | `5432`      | PostgreSQL port    |
| `DB_NAME`   | `bookshelf` | Database name      |
| `DB_USER`   | `bookshelf` | Database user      |
| `DB_PASS`   | `bookshelf` | Database password  |
| `APP_PORT`  | `8080`      | Server listen port |
| `GOOGLE_BOOKS_API_KEY` | *(none)* | Optional — raises Google Books API quota |

## Testing

Tests use JUnit 5 with `java.net.HttpClient`. The server starts on a random port (`new ServerSocket(0)`) before each test class and shuts down after. Repository is cleaned between tests for isolation. Tests run against `InMemoryBookRepository` only (no DB required for `./gradlew test`).

Test classes:
- **`BookApiTest`** (89 tests) — full HTTP integration tests covering CRUD, filtering, search, sorting, reading progress, half-star ratings, start/finish dates, reviews, partial updates, validation, and cover endpoints
- **`BookMetadataTest`** (43 tests) — unit tests for `BookMetadata.deriveGenre()`, Google Books response parsing, and `mergeMetadata()`
- **`ShelfApiTest`** (57 tests) — shelf CRUD, book assignment, reordering, stats, validation, and edge cases
- **`McpTest`** (22 tests) — MCP endpoint tests covering JSON-RPC protocol (initialize, tools/list, tools/call, ping, errors) and all 5 tool implementations
- **`GoalApiTest`** (12 tests) — reading goal CRUD, progress computation, validation, duplicate handling, and edge cases
- **`OpenLibraryTest`** (11 tests) — live integration tests for Open Library enrichment service (excluded from default `./gradlew test` via `@Tag("integration")`; run with `./gradlew integrationTest`)

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
| `review`          | `review`           |
| `startedAt`       | `started_at`       |
| `finishedAt`      | `finished_at`      |

## DB Schema Migrations

Migrations run on startup via `DatabaseConfig.runMigrations()`. The schema uses `IF NOT EXISTS` / `ADD COLUMN IF NOT EXISTS` throughout so it is safe to re-run on an existing database. Current columns added as `ALTER TABLE` migrations (beyond the base `CREATE TABLE`):

1. `cover_data BYTEA` — cover image bytes
2. `reading_progress INTEGER DEFAULT NULL` — reading progress (0–100)
3. `review TEXT DEFAULT NULL` — book review/notes
4. `started_at DATE DEFAULT NULL` — reading start date
5. `finished_at DATE DEFAULT NULL` — reading finish date
6. Half-star migration: `UPDATE books SET rating = rating * 2 WHERE rating > 0` (guarded by max-rating check)
7. `reading_goals` table — `id UUID`, `year INTEGER UNIQUE`, `target INTEGER`, `created_at`, `updated_at`

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
| `ReadStatus.java` | Enum: `WANT_TO_READ`, `READING`, `FINISHED`, `DNF` |
| `BookRepository.java` | Repository interface |
| `InMemoryBookRepository.java` | `ConcurrentHashMap`-backed implementation (used in tests) |
| `JdbcBookRepository.java` | PostgreSQL JDBC implementation |
| `BookController.java` | HTTP handler methods; validation, JSON parsing, sorting |
| `ShelfController.java` | Shelf API endpoint handlers |
| `Shelf.java` | Shelf entity with computed transient fields |
| `ShelfRepository.java` | Shelf repository interface |
| `InMemoryShelfRepository.java` | In-memory shelf store (used in tests) |
| `JdbcShelfRepository.java` | PostgreSQL shelf implementation |
| `ReadingGoal.java` | Reading goal entity (year + target) |
| `GoalRepository.java` | Reading goal repository interface |
| `InMemoryGoalRepository.java` | In-memory goal store (used in tests) |
| `JdbcGoalRepository.java` | PostgreSQL goal implementation |
| `GoalController.java` | Goal API endpoint handlers with computed progress |
| `DuplicateGoalException.java` | Exception for duplicate year goals |
| `RequestTooLargeException.java` | Custom exception for oversized request bodies |
| `DatabaseConfig.java` | HikariCP pool setup + schema migrations |
| `BookEnrichmentService.java` | Async enrichment: Open Library + Google Books fallback |
| `BookMetadata.java` | DTO for Open Library response |
| `StaticFileHandler.java` | Serves `static/` files |
| `mcp/McpController.java` | MCP Streamable HTTP endpoint — JSON-RPC dispatch |
| `mcp/McpToolHandler.java` | MCP tool implementations (check_book, search_books, list_books, get_book_by_isbn, get_bookshelf_stats) |
