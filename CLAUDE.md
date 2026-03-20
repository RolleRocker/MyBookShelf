# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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

The project follows **hexagonal architecture** (ports & adapters) with three layers:

### Package Structure

```
com.bookshelf
├── App.java                          # Composition root — wires all layers
├── domain/                           # Pure domain — ZERO infrastructure imports
│   ├── model/                        # Book, ReadStatus, Shelf, ReadingGoal, User, BookMetadata
│   ├── port/out/                     # Outbound port interfaces (BookRepository, ShelfRepository,
│   │                                 #   GoalRepository, UserRepository, BookMetadataFetcher, TokenService)
│   └── exception/                    # DuplicateGoalException, DuplicateUserException
├── adapter/                          # Adapters — depend on domain ports + framework
│   ├── in/http/                      # Inbound HTTP adapters (BookController, ShelfController,
│   │                                 #   GoalController, AuthController)
│   ├── in/mcp/                       # Inbound MCP adapter (McpController, McpToolHandler)
│   └── out/
│       ├── persistence/              # Outbound persistence (InMemory*Repository, Jdbc*Repository, DatabaseConfig)
│       ├── enrichment/               # Outbound enrichment (BookEnrichmentService, OpenLibraryClient)
│       └── auth/                     # Outbound auth (JwtUtil, PasswordUtil, GoogleTokenVerifier)
└── framework/http/                   # Hand-built HTTP framework (HttpServer, HttpRequest, HttpResponse,
                                      #   RequestParser, ResponseWriter, Router, AuthMiddleware,
                                      #   StaticFileHandler, RequestLogger, RequestTooLargeException)
```

### Dependency Rules
- **`domain`** depends on NOTHING — pure Java, no imports from adapter/framework
- **`adapter`** depends on `domain` (ports) and `framework` (HTTP types)
- **`framework`** depends on `domain` only for `AuthMiddleware` → `TokenService`
- **`App.java`** sits at the top as the composition root — depends on all layers

### Key Ports (interfaces in `domain.port.out`)
- **`BookRepository`** / **`ShelfRepository`** / **`GoalRepository`** / **`UserRepository`** — persistence ports. Implemented by `InMemory*` (tests) and `Jdbc*` (production)
- **`BookMetadataFetcher`** — outbound port for fetching book metadata. Methods: `fetchByIsbn(isbn)`, `fetchCoverByIsbn(isbn)`, `fetchCoverByUrl(url)`. Implemented by `OpenLibraryClient`
- **`TokenService`** — outbound port for JWT token creation/validation. Methods: `createToken(userId, username)`, `validateToken(token)`. Implemented by `JwtUtil`

### Framework Layer
- **`HttpServer`** — `ServerSocket` listener with a fixed thread pool (10 threads via `ExecutorService`)
- **`RequestParser`** — Reads raw socket `InputStream`, produces `HttpRequest` (method, path, queryParams, headers, body)
- **`HttpRequest` / `HttpResponse`** — Simple model classes
- **`Router`** — Maps method + path patterns (with `{param}` extraction) to handler functions. Static segments (`isbn`) take priority over parameters (`{id}`) to avoid route conflicts
- **`ResponseWriter`** — Writes formatted HTTP response to socket `OutputStream`
- **`AuthMiddleware`** — JWT validation via `TokenService` port + public route checking
- **`StaticFileHandler`** — Serves static frontend files from `/static` directory

### Adapter Layer
- **`BookController`** — Inbound HTTP adapter: endpoint handlers for books; deserializes JSON via Gson, validates input, calls repository, returns `HttpResponse`
- **`BookEnrichmentService`** — Async orchestration: queues enrichment jobs via `ExecutorService`, delegates HTTP fetching to `BookMetadataFetcher` port, manages rate limiting for batch re-enrichment
- **`OpenLibraryClient`** — Implements `BookMetadataFetcher`: fetches metadata from Open Library API, falls back to Google Books API, merges results, downloads cover images. Sends `User-Agent: MyBookShelf/1.0` header
- **`BookMetadata`** (domain model) — DTO for parsed API data (title, author, publisher, publishDate, pageCount, subjects, genre, coverUrl)
- **`DatabaseConfig`** — HikariCP connection pool, reads config from env vars, runs schema migration on startup

### Frontend
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

## Data Model — User

| Field            | Type           | Required | Notes |
|------------------|----------------|----------|-------|
| `id`             | UUID           | auto     | Server-generated |
| `username`       | String         | yes      | 3-50 chars, unique |
| `passwordHash`   | String         | no*      | PBKDF2WithHmacSHA256 hash. *Nullable for Google-only users |
| `salt`           | String         | no*      | 16-byte random, Base64-encoded. *Nullable for Google-only users |
| `googleId`       | String         | no       | Google account `sub` claim. Unique index (partial, non-null) |
| `email`          | String         | no       | Email from Google ID token |
| `createdAt`      | Instant        | auto     | |

## API Endpoints

| Method   | Path                  | Description                              | Success Code     |
|----------|-----------------------|------------------------------------------|------------------|
| `GET`    | `/books`              | List all books. Supports `?genre=`, `?readStatus=`, `?search=`, `?subject=`, `?sort=`, `?page=`, `?size=` | `200 OK`         |
| `GET`    | `/books/{id}`         | Get a single book by ID                  | `200 OK`         |
| `GET`    | `/books/isbn/{isbn}`  | Look up a book by ISBN                   | `200 OK`         |
| `POST`   | `/books`              | Add a new book                           | `201 Created`    |
| `PUT`    | `/books/{id}`         | Partial update (only sent fields change) | `200 OK`         |
| `DELETE` | `/books/{id}`         | Delete a book                            | `204 No Content` |
| `POST`   | `/books/re-enrich`    | Re-enrich all books with ISBNs from Open Library | `202 Accepted` |
| `GET`    | `/books/stats`        | Return computed reading statistics        | `200 OK`         |
| `GET`    | `/books/export`       | Export all books as CSV                   | `200 OK`         |
| `POST`   | `/books/import`       | Import books from CSV (MyBookShelf or Goodreads format) | `200 OK` |
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
| `POST`   | `/auth/register`      | Register a new user (returns JWT token)  | `201 Created`    |
| `POST`   | `/auth/login`         | Login with credentials (returns JWT token)| `200 OK`        |
| `POST`   | `/auth/google`        | Login with Google ID token (creates user if new) | `200 OK` / `201 Created` |
| `GET`    | `/auth/config`        | Returns `{googleClientId}` for frontend  | `200 OK`         |

### `GET /books` Query Parameters

| Parameter   | Example                | Description |
|-------------|------------------------|-------------|
| `genre`     | `?genre=Fiction`       | Filter by genre (case-insensitive in SQL, exact in memory) |
| `readStatus`| `?readStatus=READING`  | Filter by read status enum value |
| `search`    | `?search=dune`         | Case-insensitive substring search on title and author |
| `subject`   | `?subject=science`     | Filter by subject (case-insensitive substring match on subjects JSON array) |
| `sort`      | `?sort=title,asc`      | Sort results. Fields: `title`, `author`, `rating`, `created`. Directions: `asc`, `desc` |
| `page`      | `?page=1`              | Page number (1-based). When present, response is a paginated wrapper object |
| `size`      | `?size=20`             | Page size (default 20, max 100). Ignored without `?page=` |

Parameters can be combined: `?search=frank&readStatus=FINISHED&sort=rating,desc&page=1&size=10`

Filter priority: `search` > `genre`/`subject` (mutually exclusive with search) > base query. `readStatus` is applied as a post-filter on top of search/genre/subject results. Sorting is applied after filtering. Pagination is applied last.

When `?page=` is present, the response is a wrapper object: `{"books":[...], "page":1, "size":20, "totalItems":42, "totalPages":3}`. Without `?page=`, the response is a raw JSON array (backward-compatible).

### Error Responses
| Code  | When |
|-------|------|
| `400` | Missing required fields, malformed JSON, invalid rating (must be 0.5–5.0 in 0.5 increments when provided), invalid ISBN format, `readingProgress` out of 0–100 range, invalid date format, future dates, `finishedAt` before `startedAt` |
| `404` | Book ID not found, ISBN not found, cover not available, goal not found |
| `405` | Unsupported HTTP method on a route |
| `401` | Missing or invalid JWT token on protected endpoint |
| `409` | Duplicate reading goal for a year, duplicate username |

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
- **Authentication** — Hand-built JWT (HMAC-SHA256 via `javax.crypto.Mac`), PBKDF2WithHmacSHA256 password hashing (310k iterations, 16-byte salt). No external auth libraries. Public routes: all GET requests, `POST /auth/register`, `POST /auth/login`, `POST /auth/google`, `POST /mcp`. All other POST/PUT/DELETE require `Authorization: Bearer <token>` header. JWT secret from `JWT_SECRET` env var (random generated if not set). Tokens expire after 24 hours. Username: 3-50 chars. Password: 8-128 chars
- **Google OAuth** — Uses Google Identity Services (GIS) credential flow. Frontend loads `accounts.google.com/gsi/client`, gets an ID token from Google, POSTs it to `/auth/google`. Backend verifies the RS256-signed ID token using Google's JWKS public keys (`java.security.Signature`, no libraries). Keys are cached and refreshed on rotation. `GET /auth/config` returns the client ID so the frontend can conditionally show the Google button. Google-only users have null `passwordHash`/`salt`. Username is derived from email prefix with uniqueness suffix
- **Request logging** — SLF4J 2.0.12 + Logback 1.5.6. `RequestLogger` routes to INFO (2xx/3xx), WARN (4xx), ERROR (5xx). Format: `METHOD /path STATUS TIMEms CLIENT_IP`. HikariCP logs suppressed to WARN level
- **Subject filter** — `?subject=` query param with case-insensitive substring match on the JSON array TEXT column. Mutually exclusive with `?search=` (search takes priority). Uses `LOWER(subjects) LIKE LOWER(?)` in SQL with `escapeLike()` for safe matching
- **Pagination** — Backward-compatible: without `?page=`, returns raw JSON array. With `?page=`, returns wrapper object with `books`, `page`, `size`, `totalItems`, `totalPages`. In-memory slicing (not SQL LIMIT/OFFSET) because sorting/post-filtering already happens in Java. Default page size 20, max 100

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
| `JWT_SECRET` | *(random)* | HMAC-SHA256 secret for JWT tokens. If not set, a random secret is generated (tokens won't survive restarts) |
| `GOOGLE_CLIENT_ID` | *(none)* | Google OAuth client ID. If set, enables "Sign in with Google" on login page |

## Testing

Tests use JUnit 5 with `java.net.HttpClient`. The server starts on a random port (`new ServerSocket(0)`) before each test class and shuts down after. Repository is cleaned between tests for isolation. Tests run against `InMemoryBookRepository` only (no DB required for `./gradlew test`).

Test classes:
- **`BookApiTest`** (117 tests) — full HTTP integration tests covering CRUD, filtering, search, sorting, reading progress, half-star ratings, start/finish dates, reviews, partial updates, validation, cover endpoints, subject filtering, pagination, statistics, and CSV import/export
- **`BookMetadataTest`** (43 tests) — unit tests for `BookMetadata.deriveGenre()`, Google Books response parsing, and `mergeMetadata()`
- **`ShelfApiTest`** (56 tests) — shelf CRUD, book assignment, reordering, stats, validation, and edge cases
- **`McpTest`** (21 tests) — MCP endpoint tests covering JSON-RPC protocol (initialize, tools/list, tools/call, ping, errors) and all 5 tool implementations
- **`GoalApiTest`** (11 tests) — reading goal CRUD, progress computation, validation, duplicate handling, and edge cases
- **`AuthApiTest`** (12 tests) — auth endpoint tests covering register, login, duplicate username, wrong password, protected endpoints (no/valid/invalid/tampered token), public GET access, and validation
- **`GoogleAuthApiTest`** (11 tests) — Google OAuth tests using test RSA key pair: new user creation, existing user login, JWT works on protected endpoints, invalid/expired/tampered tokens, username derivation, auth config endpoint
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
8. `users` table — `id UUID`, `username VARCHAR(50) UNIQUE`, `password_hash VARCHAR(255)`, `salt VARCHAR(255)`, `created_at`
9. Google OAuth: `google_id VARCHAR(255)`, `email VARCHAR(255)` on users; `password_hash`/`salt` made nullable; unique partial index on `google_id`

## Source File Overview

### `com.bookshelf` (Composition Root)
| File | Role |
|------|------|
| `App.java` | Entry point; wires all layers together |

### `com.bookshelf.domain.model`
| File | Role |
|------|------|
| `Book.java` | Book entity with all fields and getters/setters |
| `ReadStatus.java` | Enum: `WANT_TO_READ`, `READING`, `FINISHED`, `DNF` |
| `Shelf.java` | Shelf entity with computed transient fields |
| `ReadingGoal.java` | Reading goal entity (year + target) |
| `User.java` | User entity (id, username, passwordHash, salt, googleId, email, createdAt) |
| `BookMetadata.java` | DTO for enrichment response data |

### `com.bookshelf.domain.port.out`
| File | Role |
|------|------|
| `BookRepository.java` | Book repository interface |
| `ShelfRepository.java` | Shelf repository interface |
| `GoalRepository.java` | Reading goal repository interface |
| `UserRepository.java` | User repository interface |
| `BookMetadataFetcher.java` | Outbound port for fetching book metadata by ISBN |
| `TokenService.java` | Outbound port for JWT token creation/validation |

### `com.bookshelf.domain.exception`
| File | Role |
|------|------|
| `DuplicateGoalException.java` | Exception for duplicate year goals |
| `DuplicateUserException.java` | Exception for duplicate username |

### `com.bookshelf.adapter.in.http`
| File | Role |
|------|------|
| `BookController.java` | Book API endpoint handlers; validation, JSON parsing, sorting |
| `ShelfController.java` | Shelf API endpoint handlers |
| `GoalController.java` | Goal API endpoint handlers with computed progress |
| `AuthController.java` | Register + login + Google OAuth endpoint handlers |

### `com.bookshelf.adapter.in.mcp`
| File | Role |
|------|------|
| `McpController.java` | MCP Streamable HTTP endpoint — JSON-RPC dispatch |
| `McpToolHandler.java` | MCP tool implementations (check_book, search_books, list_books, get_book_by_isbn, get_bookshelf_stats) |

### `com.bookshelf.adapter.out.persistence`
| File | Role |
|------|------|
| `InMemoryBookRepository.java` | `ConcurrentHashMap`-backed implementation (used in tests) |
| `JdbcBookRepository.java` | PostgreSQL JDBC implementation |
| `InMemoryShelfRepository.java` | In-memory shelf store (used in tests) |
| `JdbcShelfRepository.java` | PostgreSQL shelf implementation |
| `InMemoryGoalRepository.java` | In-memory goal store (used in tests) |
| `JdbcGoalRepository.java` | PostgreSQL goal implementation |
| `InMemoryUserRepository.java` | In-memory user store (used in tests) |
| `JdbcUserRepository.java` | PostgreSQL user implementation |
| `DatabaseConfig.java` | HikariCP pool setup + schema migrations |

### `com.bookshelf.adapter.out.enrichment`
| File | Role |
|------|------|
| `OpenLibraryClient.java` | Implements `BookMetadataFetcher`; HTTP calls to Open Library + Google Books fallback |
| `BookEnrichmentService.java` | Async orchestration: queues ISBN lookups, delegates to `BookMetadataFetcher` |

### `com.bookshelf.adapter.out.auth`
| File | Role |
|------|------|
| `JwtUtil.java` | Implements `TokenService`; hand-built JWT creation/validation (HMAC-SHA256) |
| `PasswordUtil.java` | PBKDF2 password hashing and verification |
| `GoogleTokenVerifier.java` | Verifies Google ID tokens (RS256) using JWKS public keys |

### `com.bookshelf.framework.http`
| File | Role |
|------|------|
| `HttpServer.java` | `ServerSocket` + `ExecutorService` connection handler |
| `RequestParser.java` | Raw socket stream → `HttpRequest` |
| `ResponseWriter.java` | `HttpResponse` → raw socket stream |
| `Router.java` | Method + path pattern → handler dispatch |
| `HttpRequest.java` | Request model (method, path, pathParams, queryParams, headers, body) |
| `HttpResponse.java` | Response model + factory methods (`ok`, `created`, `notFound`, etc.) |
| `StaticFileHandler.java` | Serves `static/` files |
| `RequestLogger.java` | SLF4J structured request logging (INFO/WARN/ERROR by status code) |
| `AuthMiddleware.java` | JWT validation + public route checking (depends on `TokenService`) |
| `RequestTooLargeException.java` | Custom exception for oversized request bodies |
