# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

A personal bookshelf REST API built from scratch in Java 17+ using only `java.net.ServerSocket` — no Spring, no Javalin, no frameworks. The HTTP layer, routing, request parsing, and response writing are all hand-built.

## Build & Run Commands

```bash
./gradlew build                  # Build
./gradlew run                    # Run server (default port 8080)
./gradlew test                   # Run all unit tests
./gradlew test --tests "com.bookshelf.BookApiTest"              # Single test class
./gradlew test --tests "com.bookshelf.BookApiTest.testMethod"   # Single test method
./gradlew integrationTest        # OpenLibraryTest only (live network)
./gradlew shadowJar              # Build fat JAR
docker compose up --build        # Build and run with PostgreSQL
docker compose down              # Stop containers (keeps data)
docker compose down -v           # Stop and remove data volumes
```

## Architecture

The project follows **hexagonal architecture** (ports & adapters):

```
com.bookshelf
├── App.java                          # Composition root — wires all layers
├── domain/                           # Pure domain — ZERO infrastructure imports
│   ├── model/                        # Book, ReadStatus, Shelf, ReadingGoal, User, BookMetadata
│   ├── port/out/                     # Outbound port interfaces (BookRepository, ShelfRepository,
│   │                                 #   GoalRepository, UserRepository, BookMetadataFetcher, TokenService)
│   └── exception/                    # DuplicateGoalException, DuplicateUserException
├── adapter/                          # Adapters — depend on domain ports + framework
│   ├── in/http/                      # Inbound HTTP (BookController, ShelfController,
│   │                                 #   GoalController, AuthController, GsonFactory)
│   ├── in/mcp/                       # Inbound MCP (McpController, McpToolHandler)
│   └── out/
│       ├── persistence/              # InMemory*Repository (tests), Jdbc*Repository (prod), DatabaseConfig
│       ├── enrichment/               # BookEnrichmentService, OpenLibraryClient
│       └── auth/                     # JwtUtil, PasswordUtil, GoogleTokenVerifier
└── framework/http/                   # HttpServer, HttpRequest, HttpResponse, RequestParser,
                                      #   ResponseWriter, Router, AuthMiddleware, StaticFileHandler,
                                      #   RequestLogger, RequestTooLargeException
```

### Dependency Rules
- **`domain`** depends on NOTHING — pure Java, no imports from adapter/framework
- **`adapter`** depends on `domain` (ports) and `framework` (HTTP types)
- **`framework`** depends on `domain` only for `AuthMiddleware` → `TokenService`
- **`App.java`** is the composition root — depends on all layers

### Key Ports (interfaces in `domain.port.out`)
- **`BookRepository`** / **`ShelfRepository`** / **`GoalRepository`** / **`UserRepository`** — persistence ports. `InMemory*` (tests) and `Jdbc*` (production)
- **`BookMetadataFetcher`** — `fetchByIsbn(isbn)`, `fetchCoverByIsbn(isbn)`, `fetchCoverByUrl(url)`. Implemented by `OpenLibraryClient`
- **`TokenService`** — `createToken(userId, username)`, `validateToken(token)`. Implemented by `JwtUtil`

## Data Model — Book

| Field            | Type                                        | Required | Notes |
|------------------|---------------------------------------------|----------|-------|
| `id`             | UUID                                        | auto     | Server-generated |
| `title`          | String                                      | yes*     | *Optional if `isbn` provided |
| `author`         | String                                      | yes*     | *Optional if `isbn` provided |
| `genre`          | String                                      | no       | Auto-derived from subjects during enrichment |
| `rating`         | Integer (0–10 internal, 0.5–5.0 display)    | no       | 0 = not rated. Internal doubled scale; API accepts 0.5–5.0 in 0.5 steps |
| `isbn`           | String                                      | no       | 10-char (last may be 'X') or 13-digit |
| `publisher`      | String                                      | no       | Auto-filled from Open Library |
| `publishDate`    | String                                      | no       | Auto-filled from Open Library |
| `pageCount`      | Integer                                     | no       | Auto-filled from Open Library |
| `subjects`       | List\<String\>                              | no       | Auto-filled; stored as JSON array string in DB |
| `readStatus`     | enum: `WANT_TO_READ`, `READING`, `FINISHED`, `DNF` | yes | |
| `readingProgress`| Integer (0–100)                             | no       | Only meaningful when READING |
| `review`         | String                                      | no       | User notes/review |
| `startedAt`      | String (YYYY-MM-DD)                         | no       | Cannot be in the future |
| `finishedAt`     | String (YYYY-MM-DD)                         | no       | Cannot be before `startedAt` or in the future |
| `coverData`      | byte[] (transient)                          | no       | BYTEA in DB. Not serialized to JSON |
| `coverUrl`       | String                                      | no       | Original Open Library URL |
| `createdAt`      | Instant                                     | auto     | ISO-8601 in JSON |
| `updatedAt`      | Instant (transient)                         | auto     | Not serialized to JSON |

**Other models** — `Shelf` (name, description, notes, color, sortField, sortDirection, position; computed: bookCount, coverBookIds, books, stats), `ReadingGoal` (year unique 2000–2100, target ≥ 1; computed: progress, percentage, onPace, paceDelta), `User` (username 3-50 chars unique, passwordHash/salt nullable for Google-only users, googleId, email).

## API Endpoints

| Method   | Path                  | Description                              | Code |
|----------|-----------------------|------------------------------------------|------|
| `GET`    | `/books`              | List books. Supports `?genre=`, `?readStatus=`, `?search=`, `?subject=`, `?sort=`, `?page=`, `?size=` | 200 |
| `GET`    | `/books/{id}`         | Get book by ID                           | 200 |
| `GET`    | `/books/isbn/{isbn}`  | Look up book by ISBN                     | 200 |
| `POST`   | `/books`              | Add a new book                           | 201 |
| `PUT`    | `/books/{id}`         | Partial update (only sent fields change) | 200 |
| `DELETE` | `/books/{id}`         | Delete a book                            | 204 |
| `POST`   | `/books/re-enrich`    | Re-enrich all ISBN books from Open Library | 202 |
| `GET`    | `/books/stats`        | Reading statistics                       | 200 |
| `GET`    | `/books/export`       | Export all books as CSV                  | 200 |
| `POST`   | `/books/import`       | Import CSV (MyBookShelf or Goodreads)    | 200 |
| `GET`    | `/books/{id}/cover`   | Serve cover image from DB                | 200 |
| `GET`    | `/shelves`            | List shelves (with book counts)          | 200 |
| `POST`   | `/shelves`            | Create shelf                             | 201 |
| `GET`    | `/shelves/{id}`       | Get shelf with books and stats           | 200 |
| `PUT`    | `/shelves/{id}`       | Update shelf                             | 200 |
| `DELETE` | `/shelves/{id}`       | Delete shelf                             | 204 |
| `PUT`    | `/shelves/reorder`    | Reorder shelves by position              | 200 |
| `POST`   | `/shelves/{id}/books` | Add book to shelf                        | 201 |
| `PUT`    | `/shelves/{id}/books/reorder` | Reorder books in shelf           | 200 |
| `DELETE` | `/shelves/{id}/books/{bookId}` | Remove book from shelf          | 204 |
| `GET`    | `/goals`              | List reading goals (with progress)       | 200 |
| `POST`   | `/goals`              | Create reading goal                      | 201 |
| `GET`    | `/goals/{year}`       | Get year's goal with progress            | 200 |
| `PUT`    | `/goals/{year}`       | Update goal target                       | 200 |
| `DELETE` | `/goals/{year}`       | Delete reading goal                      | 204 |
| `POST`   | `/mcp`                | MCP Streamable HTTP (JSON-RPC)           | 200 |
| `POST`   | `/auth/register`      | Register user (returns JWT)              | 201 |
| `POST`   | `/auth/login`         | Login (returns JWT)                      | 200 |
| `POST`   | `/auth/google`        | Google OAuth login                       | 200/201 |
| `GET`    | `/auth/config`        | Returns `{googleClientId}`               | 200 |

### `GET /books` Query Parameters

| Parameter   | Example                | Description |
|-------------|------------------------|-------------|
| `genre`     | `?genre=Fiction`       | Filter by genre (case-insensitive in SQL) |
| `readStatus`| `?readStatus=READING`  | Filter by read status enum |
| `search`    | `?search=dune`         | Case-insensitive substring on title and author |
| `subject`   | `?subject=science`     | Case-insensitive substring on subjects JSON |
| `sort`      | `?sort=title,asc`      | Fields: `title`, `author`, `rating`, `created` |
| `page`      | `?page=1`              | 1-based. Returns paginated wrapper object |
| `size`      | `?size=20`             | Default 20, max 100. Ignored without `?page=` |

Filter priority: `search` > `genre`/`subject` > base query. `readStatus` post-filters on top. Sorting after filtering. Pagination last. Without `?page=`, response is a raw JSON array (backward-compatible). With `?page=`: `{"books":[...], "page":1, "size":20, "totalItems":42, "totalPages":3}`.

### Error Responses

All errors return JSON: `{"error":"message"}`. Codes: `400` (validation), `401` (auth), `404` (not found), `405` (bad method), `409` (duplicate).

## Key Design Decisions

- **PUT = partial update** — only fields present are overwritten; missing fields unchanged; explicit `null` clears
- **Gson via `GsonFactory` singleton** — `serializeNulls()`, custom `Instant` serializer (ISO-8601). All controllers share this instance
- **Rating** — half-star scale 0.5–5.0 in 0.5 steps. Stored internally as Integer 0–10 (doubled). `0` = not rated (default, cannot be explicitly set). `rating`/`pageCount`/`readingProgress` use boxed `Integer` for nullable partial updates
- **Enrichment** — only fills `null` fields (never overwrites user data). On ISBN change via PUT, enriched fields are cleared before re-enrichment. `POST /books/re-enrich` queues all ISBN books with 3s rate-limit delays
- **Date auto-fill** — `READING` → auto-fills `startedAt`; `FINISHED`/`DNF` → auto-fills `finishedAt`; `WANT_TO_READ` → clears both
- **Reading goals** — progress computed from finished books using `finishedAt` (preferred) or `createdAt` (fallback). `onPace` differs for past/current/future years
- **Duplicate ISBNs allowed** — `findByIsbn` returns the oldest
- **Cover images** — stored as BYTEA in DB, not filesystem. Open Library returns 1x1 pixel placeholder for missing covers; detected via size < 1KB
- **Sorting/pagination** — in-memory in `BookController` (no SQL ORDER BY/LIMIT). Pagination uses in-memory slicing since sorting/post-filtering already happens in Java
- **Authentication** — hand-built JWT (HMAC-SHA256) with `iss`/`aud` claims and `alg` header validation (prevents `alg:none` bypass). PBKDF2 passwords (310k iterations, 16-byte salt). Constant-time comparison via `MessageDigest.isEqual()` for both signatures and password hashes. JWT payload built via Gson to prevent JSON injection. Public routes: `GET /health`, `GET /auth/config`, static files; all API data GETs require auth. `POST /auth/*`, `POST /mcp` are public. Tokens expire after 24h. Password: 8-128 chars
- **Google OAuth** — GIS credential flow. Backend verifies RS256 ID tokens using Google JWKS public keys (cached, volatile immutable Map for atomic refresh). Google-only users have null password/salt
- **Input validation** — string field length limits enforced (title/author/publisher 500, genre 100, publishDate 50, review 5000, coverUrl 2048, shelf description 2000, shelf notes 5000). Subjects: max 50, each max 200 chars. Whitespace-only title/author/shelf-name rejected via `isBlank()`. CSV export defends against formula injection (`=`, `@` prefixed with `'`)
- **HTTP hardening** — security headers (X-Content-Type-Options, X-Frame-Options, CSP, Referrer-Policy). Request parser rejects Transfer-Encoding (prevents smuggling), conflicting Content-Length, unsupported HTTP versions, URIs > 8KB. CRLF sanitization on status text and headers. SSRF allowlist for image downloads (covers.openlibrary.org, books.google.com, *.us.archive.org)
- **Logging** — SLF4J 2.0.17 + Logback 1.5.32. `RequestLogger`: INFO (2xx/3xx), WARN (4xx), ERROR (5xx). All controllers use SLF4J
- **DB schema** — `NULL` allowed for `title`/`author` to support ISBN-only creation. Migrations use `IF NOT EXISTS` / `ADD COLUMN IF NOT EXISTS` (safe to re-run). DB uses `snake_case`, Java uses `camelCase`

## MCP Integration

`POST /mcp` — Streamable HTTP transport (stateless), JSON-RPC 2.0. Tools: `check_book`, `search_books`, `list_books`, `get_book_by_isbn`, `get_bookshelf_stats`. Configured via `.mcp.json`. Server must be running.

## Environment Variables

| Variable    | Default     | Description        |
|-------------|-------------|--------------------|
| `DB_HOST`   | `localhost` | PostgreSQL host    |
| `DB_PORT`   | `5432`      | PostgreSQL port    |
| `DB_NAME`   | `bookshelf` | Database name      |
| `DB_USER`   | `bookshelf` | Database user      |
| `DB_PASS`   | `bookshelf` | Database password  |
| `APP_PORT`  | `8080`      | Server listen port |
| `GOOGLE_BOOKS_API_KEY` | *(none)* | Optional — raises Google Books API quota |
| `JWT_SECRET` | *(random)* | HMAC-SHA256 secret for JWT. Random if unset (tokens won't survive restarts) |
| `GOOGLE_CLIENT_ID` | *(none)* | Enables "Sign in with Google" if set |

## Testing

Tests use JUnit 5 with `java.net.HttpClient`. Server starts on random port (`new ServerSocket(0)`) per test class. In-memory repositories (no DB required). 327 tests total:

- **`BookApiTest`** (141) — CRUD, filtering, search, sorting, ratings, dates, reviews, validation, covers, pagination, stats, CSV, header limits, field length limits, formula injection, HTTP parsing edge cases
- **`BookMetadataTest`** (43) — `deriveGenre()`, Google Books parsing, `mergeMetadata()`
- **`ShelfApiTest`** (63) — shelf CRUD, book assignment, reordering, stats, validation, field length limits
- **`McpTest`** (21) — JSON-RPC protocol + all 5 tools
- **`GoalApiTest`** (17) — goal CRUD, progress, validation, year boundary tests
- **`AuthApiTest`** (19) — register, login, token validation, protected endpoints, input boundary tests
- **`GoogleAuthApiTest`** (11) — Google OAuth with test RSA keys
- **`JwtUtilTest`** (8) — JWT roundtrip, tampered tokens, special chars, alg:none bypass
- **`PasswordUtilTest`** (4) — hash roundtrip, salt uniqueness
- **`OpenLibraryTest`** (11) — live integration (excluded from default run; `./gradlew integrationTest`)
