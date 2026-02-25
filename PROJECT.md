# MyBookShelf — Complete Project Reference

This is the single combined reference for the entire project. It replaces and
supersedes `bookshelf-api-plan.md`, `isbn-scanner-plan.md`,
`docs/plans/2026-02-20-search-sorting-reading-progress.md`, `README.md`, and
`CLAUDE.md` as a content source — those files remain on disk but all
authoritative information lives here.

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Tech Stack](#2-tech-stack)
3. [Getting Started](#3-getting-started)
4. [Build & Run Commands](#4-build--run-commands)
5. [Architecture](#5-architecture)
6. [Source File Overview](#6-source-file-overview)
7. [Data Model](#7-data-model)
8. [API Endpoints](#8-api-endpoints)
9. [Key Design Decisions](#9-key-design-decisions)
10. [Build Order — Step by Step](#10-build-order--step-by-step)
11. [Integration Tests](#11-integration-tests)
12. [Feature Implementations](#12-feature-implementations)
13. [ISBN Barcode Scanner](#13-isbn-barcode-scanner)
14. [Environment Variables](#14-environment-variables)
15. [Database](#15-database)
16. [Docker](#16-docker)
17. [Testing](#17-testing)
18. [Things to Watch Out For](#18-things-to-watch-out-for)
19. [Claude Code Tips](#19-claude-code-tips)
20. [License](#20-license)

---

## 1. Project Overview

A personal bookshelf REST API built from scratch in Java 17 using only
`java.net.ServerSocket` — no Spring, no Javalin, no external frameworks. The
HTTP server, routing, request parsing, and response writing are all hand-built.
Includes a vanilla HTML/CSS/JS frontend and automatic book enrichment via the
Open Library API.

### Features

- **Framework-free HTTP server** — built on raw `ServerSocket` with a 10-thread pool
- **Full CRUD REST API** for managing books with filtering by genre, read status, and full-text search
- **Open Library integration** — add a book by ISBN and metadata (title, author, publisher, page count, subjects, cover image) is fetched automatically in the background
- **Cover image storage** — covers downloaded from Open Library and stored as binary data in PostgreSQL
- **ISBN barcode scanner** — scan a book's barcode with your device camera to add it instantly
- **Vanilla JS frontend** — dark antiquarian-library-themed UI with ISBN input, filter tabs, sort dropdown, inline editing, and star ratings
- **Reading progress** — track a percentage (0–100) for books with `READING` status
- **Dockerized** — single `docker compose up` to run the app and database together
- **38+ automated tests** covering the full API surface, validation, edge cases, and enrichment logic

---

## 2. Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 17 |
| HTTP Server | `java.net.ServerSocket` (hand-built) |
| JSON | Gson 2.11 |
| Database | PostgreSQL 16 |
| Connection Pool | HikariCP 5.1 |
| Build | Gradle 8 + Shadow plugin |
| Container | Docker + Docker Compose |
| Frontend | Vanilla HTML / CSS / JS |
| Barcode Scanning | zbar-wasm (WebAssembly) |
| Tests | JUnit 5 + `java.net.HttpClient` |

### Gradle Dependencies

```groovy
plugins {
    id 'com.github.johnrengelman.shadow' version '8.1.1'
}

dependencies {
    implementation 'com.google.code.gson:gson:2.11.0'
    implementation 'org.postgresql:postgresql:42.7.3'
    implementation 'com.zaxxer:HikariCP:5.1.0'
    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
}

test {
    useJUnitPlatform()
}
```

---

## 3. Getting Started

### Prerequisites

- **Java 17+** and **Gradle** (for building from source), or
- **Docker** and **Docker Compose** (recommended)

### Run with Docker (recommended)

```bash
git clone https://github.com/RolleRocker/MyBookShelf.git
cd MyBookShelf
docker compose up --build
```

The app will be available at **http://localhost:8080**.

### Run from source (development, in-memory store)

```bash
./gradlew run
```

No database or Docker needed — data lives in memory and is lost on restart.

### Run tests

```bash
./gradlew test
```

All tests use an in-memory repository — no database or Docker required.

---

## 4. Build & Run Commands

```bash
# Build
./gradlew build

# Run the server (default port 8080, in-memory store)
./gradlew run

# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.bookshelf.BookApiTest"

# Run a single test method
./gradlew test --tests "com.bookshelf.BookApiTest.testCreateAndRetrieveBook"

# Build fat JAR (includes all dependencies)
./gradlew shadowJar

# Docker
docker compose up --build       # start app + database
docker compose down             # stop containers
docker compose down -v          # stop and remove data volumes too
docker compose restart app      # restart just the app container
docker compose pause db         # pause database (for testing resilience)
docker compose unpause db
docker compose exec db psql -U bookshelf -c "SELECT * FROM books;"
```

---

## 5. Architecture

The project is built in four progressive versions, each adding a layer:

```
V1  Core HTTP server + REST API + in-memory storage
V2  Open Library integration (async enrichment + cover downloads)
V3  PostgreSQL persistence + Docker Compose
V4  Vanilla HTML/CSS/JS frontend + barcode scanner + search/sort/progress
```

### Core Components (V1)

- **`HttpServer`** — `ServerSocket` listener with a fixed thread pool (10 threads via `ExecutorService`). Accepts connections in a loop, dispatches each to a worker thread.
- **`RequestParser`** — Reads raw socket `InputStream`, produces `HttpRequest` (method, path, queryParams, headers, body).
- **`HttpRequest` / `HttpResponse`** — Simple model classes.
- **`Router`** — Maps method + path patterns (with `{param}` extraction) to handler functions. Static segments (`isbn`) take priority over parameters (`{id}`) to avoid route conflicts. Returns `405` if path matches but method doesn't.
- **`BookController`** — Endpoint handlers: deserializes JSON via Gson, validates input, calls repository, returns `HttpResponse`.
- **`BookRepository`** (interface) — `findAll()`, `findByGenre()`, `findByReadStatus()`, `findBySearch()`, `findById()`, `findByIsbn()`, `save()`, `update()`, `delete()`, `updateFromOpenLibrary()`, `clear()`. V1 uses `InMemoryBookRepository` (ConcurrentHashMap), V3 uses `JdbcBookRepository` (PostgreSQL + HikariCP).
- **`ResponseWriter`** — Writes formatted HTTP response to socket `OutputStream`. Auto-sets `Content-Type`, `Content-Length`, `Connection` headers.

### Open Library Integration (V2)

- **`OpenLibraryService`** — Single-thread `ExecutorService` that asynchronously fetches metadata and cover images from Open Library by ISBN. Enrichment is best-effort; POST returns immediately. Sends `User-Agent: MyBookShelf/1.0` header for better rate limits. Also provides `reEnrichAll()` for batch re-enrichment with 3-second rate-limit delays.
- **`BookMetadata`** — Model for parsed Open Library data (title, author, publisher, publishDate, pageCount, subjects, genre, coverUrl).
- **`StaticFileHandler`** — Serves static frontend files from `/static` directory, supports subdirectories, maps `/` to `index.html`, includes directory-traversal protection.

### Database Layer (V3)

- **`DatabaseConfig`** — HikariCP connection pool, reads config from env vars, runs schema migration on startup.
- **`JdbcBookRepository`** — JDBC implementation of `BookRepository` against PostgreSQL. Uses `PreparedStatement` for all queries to prevent SQL injection.

### Frontend (V4)

- Vanilla HTML/CSS/JS in `/static` directory, served by the same Java server.
- ISBN-only input flow: POST with just ISBN → placeholder card → polls until enrichment completes.
- **Barcode scanner**: zbar-wasm (WASM C decoder) in `static/lib/zbar-wasm.js` (inlined UMD, 326 KB). Multi-pass pipeline: raw grayscale → sharpen → global thresholds → adaptive threshold. Scans camera ROI via `getUserMedia`. See [§13](#13-isbn-barcode-scanner) for details.
- **Client-side search bar**: filters `allBooks` in memory by title/author; no extra API call.
- **Sort dropdown**: sorts by title, author, rating, or date added (asc/desc); applied client-side after filters.
- **Reading progress bar**: thin gold bar on `READING` cards; editable in the modal.

### Project File Structure

```
src/main/java/com/bookshelf/
├── App.java                    # Entry point
├── HttpServer.java             # ServerSocket listener + thread pool
├── RequestParser.java          # Raw HTTP request parsing
├── HttpRequest.java            # Request model
├── HttpResponse.java           # Response model + factory methods
├── ResponseWriter.java         # HTTP response writing
├── Router.java                 # Route matching with path params
├── BookController.java         # API endpoint handlers + sorting
├── Book.java                   # Book domain model (16 fields)
├── ReadStatus.java             # WANT_TO_READ / READING / FINISHED / DNF
├── BookRepository.java         # Repository interface
├── InMemoryBookRepository.java # ConcurrentHashMap implementation
├── JdbcBookRepository.java     # PostgreSQL implementation
├── DatabaseConfig.java         # HikariCP pool + schema migration
├── OpenLibraryService.java     # Async ISBN enrichment + covers
├── BookMetadata.java           # Enrichment data model
└── StaticFileHandler.java      # Static file serving

src/test/java/com/bookshelf/
├── BookApiTest.java            # 30+ tests — CRUD, filtering, validation, edge cases
└── OpenLibraryTest.java        # 8+ tests — enrichment, covers, re-enrichment

static/
├── index.html                  # Frontend page (dark antiquarian theme)
├── style.css                   # Styles (Libre Baskerville + DM Sans)
├── app.js                      # ISBN input, polling, search, sort, barcode scanner
└── lib/
    └── zbar-wasm.js            # Vendored barcode scanning library (WebAssembly, 326 KB)
```

---

## 6. Source File Overview

| File | Role |
|------|------|
| `App.java` | Entry point; wires repository, controller, router, and server; handles shutdown |
| `HttpServer.java` | `ServerSocket` + `ExecutorService` connection handler |
| `RequestParser.java` | Raw socket stream → `HttpRequest` |
| `ResponseWriter.java` | `HttpResponse` → raw socket stream |
| `Router.java` | Method + path pattern → handler dispatch; static segments beat `{param}` segments |
| `HttpRequest.java` | Request model (method, path, pathParams, queryParams, headers, body) |
| `HttpResponse.java` | Response model + factory methods (`ok`, `created`, `notFound`, `badRequest`, etc.) |
| `Book.java` | Book entity with all 16 fields and getters/setters |
| `ReadStatus.java` | Enum: `WANT_TO_READ`, `READING`, `FINISHED`, `DNF` |
| `BookRepository.java` | Repository interface |
| `InMemoryBookRepository.java` | `ConcurrentHashMap`-backed implementation (used in tests and `./gradlew run`) |
| `JdbcBookRepository.java` | PostgreSQL JDBC implementation |
| `BookController.java` | HTTP handler methods; validation, JSON parsing, in-memory sorting |
| `DatabaseConfig.java` | HikariCP pool setup + schema migrations on startup |
| `OpenLibraryService.java` | Async Open Library metadata + cover fetching; `reEnrichAll()` |
| `BookMetadata.java` | DTO for Open Library response |
| `StaticFileHandler.java` | Serves `static/` files with correct `Content-Type` headers |

---

## 7. Data Model

### Book Fields

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `id` | UUID | auto | Server-generated |
| `title` | String | yes* | *Optional if `isbn` provided (V4+) |
| `author` | String | yes* | *Optional if `isbn` provided (V4+) |
| `genre` | String | no | |
| `rating` | Integer (1–5, default 0) | no | 0 = not rated; user cannot explicitly set 0. Boxed type for nullable partial updates |
| `isbn` | String | no | 10-char (last may be 'X') or 13-digit |
| `publisher` | String | no | Auto-filled from Open Library |
| `publishDate` | String | no | Auto-filled from Open Library |
| `pageCount` | Integer | no | Auto-filled from Open Library. Boxed type for nullable |
| `subjects` | List\<String\> | no | Auto-filled; stored as JSON array string in DB |
| `readStatus` | enum | yes | `WANT_TO_READ`, `READING`, `FINISHED`, `DNF` |
| `readingProgress` | Integer (0–100) | no | Only meaningful when `readStatus == READING`. Nullable. |
| `coverData` | byte[] (transient) | no | Cover image bytes, stored as BYTEA in DB. Not serialized to JSON |
| `coverUrl` | String | no | Original Open Library URL |
| `createdAt` | Instant | auto | Serialized as ISO-8601 string in JSON responses |
| `updatedAt` | Instant (transient) | auto | Not serialized to JSON |

---

## 8. API Endpoints

### Endpoint Reference

| Method | Path | Description | Success Code |
|--------|------|-------------|--------------|
| `GET` | `/books` | List all books. Supports `?genre=`, `?readStatus=`, `?search=`, `?sort=` | `200 OK` |
| `GET` | `/books/{id}` | Get a single book by ID | `200 OK` |
| `GET` | `/books/isbn/{isbn}` | Look up a book by ISBN (returns oldest if duplicates) | `200 OK` |
| `POST` | `/books` | Add a new book | `201 Created` |
| `PUT` | `/books/{id}` | Partial update — only sent fields change | `200 OK` |
| `DELETE` | `/books/{id}` | Delete a book | `204 No Content` |
| `POST` | `/books/re-enrich` | Re-enrich all ISBN books from Open Library | `202 Accepted` |
| `GET` | `/books/{id}/cover` | Serve cover image from DB | `200 OK` |

### `GET /books` Query Parameters

| Parameter | Example | Description |
|-----------|---------|-------------|
| `genre` | `?genre=Fiction` | Filter by genre (case-insensitive in SQL, exact in memory) |
| `readStatus` | `?readStatus=READING` | Filter by read status enum value |
| `search` | `?search=dune` | Case-insensitive substring search on title and author |
| `sort` | `?sort=title,asc` | Sort results. Fields: `title`, `author`, `rating`, `created`. Directions: `asc`, `desc` |

Parameters can be combined: `?search=frank&readStatus=FINISHED&sort=rating,desc`

`search` takes priority over `genre` for the base query. `readStatus` is always applied as a post-filter on top of whatever the base result is. Sorting is applied last, in-memory.

### Error Responses

| Code | When |
|------|------|
| `400` | Missing required fields, malformed JSON, invalid rating (must be 1–5 when provided), invalid ISBN format, `readingProgress` out of 0–100 range |
| `404` | Book ID not found, ISBN not found, cover not available |
| `405` | Unsupported HTTP method on a route |

### Example Requests

```bash
# Create a book with all fields
curl -X POST http://localhost:8080/books \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Dune",
    "author": "Frank Herbert",
    "genre": "Science Fiction",
    "isbn": "9780441013593",
    "readStatus": "READING"
  }'

# Add by ISBN only (title/author filled by Open Library)
curl -X POST http://localhost:8080/books \
  -H "Content-Type: application/json" \
  -d '{"isbn": "9780441013593", "readStatus": "WANT_TO_READ"}'

# Partial update (only rating and status change)
curl -X PUT http://localhost:8080/books/{id} \
  -H "Content-Type: application/json" \
  -d '{"rating": 5, "readStatus": "FINISHED"}'

# Clear a field with explicit null
curl -X PUT http://localhost:8080/books/{id} \
  -H "Content-Type: application/json" \
  -d '{"genre": null}'

# Filter + search + sort combined
curl "http://localhost:8080/books?search=frank&readStatus=FINISHED&sort=rating,desc"

# Fetch cover image
curl http://localhost:8080/books/{id}/cover --output cover.jpg

# Re-enrich all books with ISBNs
curl -X POST http://localhost:8080/books/re-enrich

# Trigger re-enrichment via Docker
docker compose up --build
curl -X POST http://localhost:8080/books/re-enrich
```

---

## 9. Key Design Decisions

| Decision | Choice | Notes |
|----------|--------|-------|
| Threading model | Fixed thread pool (10 threads) | `Executors.newFixedThreadPool(10)` in `HttpServer` |
| PUT semantics | Partial update | Only fields present in the request body are overwritten. Missing fields unchanged. Sending `null` explicitly for a field clears it. |
| Test framework | JUnit 5 | With `java.net.HttpClient` for HTTP calls |
| ISBN-10 check digit | Accept 'X' | Valid ISBN-10 format: 9 digits + digit or 'X' (e.g. `080442957X`) |
| Duplicate ISBNs | Allowed | Users may own multiple copies of the same book |
| ISBN lookup with duplicates | Return oldest | `GET /books/isbn/{isbn}` returns the first-created book |
| Rating default | `0` | Always present in JSON. `0` means "not rated". Valid user ratings are 1–5; user cannot explicitly set 0. |
| `rating`/`pageCount`/`readingProgress` Java type | `Integer` (boxed) | Nullable to distinguish "not provided" from 0; essential for partial updates with Gson |
| Genre filtering | V3+ only | Not available in V1; added as a SQL query in V3 via `findByGenre()` |
| Read status filtering | `?readStatus=` query param | Added in V4; applied as a post-filter on top of search/genre results |
| Subjects storage | JSON array string | Stored as `["Science fiction","Space"]` in a TEXT column |
| Repository pattern | Interface + impl | `BookRepository` interface; `InMemoryBookRepository` for dev/tests, `JdbcBookRepository` for production |
| Frontend add default | `WANT_TO_READ` | New books added via ISBN always get this status initially |
| Gson configuration | `serializeNulls()` + custom `Instant` serializer | Ensures missing fields appear as `null` in JSON; Instant serialized as ISO-8601 string |
| Open Library enrichment | Async, best-effort, null-only | POST returns immediately. Enrichment only fills `null` fields — user-provided values are never overwritten. On ISBN change (PUT), previously-enriched fields are cleared before re-enrichment. |
| Genre auto-derivation | From subjects | Genre is auto-derived from Open Library subjects during enrichment |
| 1×1 pixel detection | File size < 1 KB | Open Library returns a tiny placeholder instead of 404 for missing covers |
| Cover storage | BYTEA in PostgreSQL | `Book.coverData` is `transient` (not serialized to JSON); served via `GET /books/{id}/cover` |
| DB schema nullability | NULL allowed for `title`/`author` | From V1 onward, to avoid a migration when V4 makes them optional |
| Search | Client-side in frontend, server-side via `?search=` | Frontend filters `allBooks` in memory; does not call `?search=`. API consumers use the query param. |
| Sorting | In-memory in `BookController` | A `Comparator` is applied to the result list after repository fetch. No `ORDER BY` in SQL. |
| `readingProgress` | Nullable Integer, 0–100 | Validated on create and update. `null` via PUT clears it. Only displayed in UI for `READING` books. |
| Re-enrichment rate limit | 3-second delays | `POST /books/re-enrich` queues null-title books first, then all others, with 3s between requests |
| Route priority | Static segments beat `{param}` | `/books/isbn/{isbn}` is matched before `/books/{id}` to prevent "isbn" being treated as an ID |

---

## 10. Build Order — Step by Step

Each step is independently testable with `curl`.

### V1 — Core HTTP Server & API

#### Step 1 — Hello World Server
- Create `HttpServer` that listens on port `8080`
- Return `"Hello World"` as plain text to every request
- **Test:** `curl http://localhost:8080/anything`

#### Step 2 — Parse Requests
- Build `RequestParser` to extract method, path, headers, and body
- Log parsed request info to the console
- **Test:** `curl -X POST http://localhost:8080/test -d '{"hello":"world"}'` and check console output

#### Step 3 — Router with Static Paths
- Build the `Router` to register and match `GET /books` and `POST /books`
- Return placeholder JSON responses
- **Test:** `curl http://localhost:8080/books` → `[]`

#### Step 4 — Path Parameter Extraction
- Extend `Router` to handle `/books/{id}` patterns
- Also handle multi-segment patterns like `/books/isbn/{isbn}` — static segments (`isbn`) take priority over parameters (`{isbn}`)
- Extract named parameters from the path and make them available to handlers
- **Test:** `curl http://localhost:8080/books/abc123` → `{"id": "abc123"}`

#### Step 5 — POST and GET /books
- Implement `BookRepository` with in-memory map
- `POST /books` — parse JSON body, validate, store, return `201`
- `GET /books` — return all books as JSON array
- **Test:**
  ```bash
  curl -X POST http://localhost:8080/books \
    -H "Content-Type: application/json" \
    -d '{"title":"Dune","author":"Frank Herbert","isbn":"9780441013593","readStatus":"READING"}'
  curl http://localhost:8080/books
  ```

#### Step 6 — GET, PUT, DELETE by ID
- `GET /books/{id}` — return single book or `404`
- `GET /books/isbn/{isbn}` — look up a book by ISBN or `404`
- `PUT /books/{id}` — partial update: only overwrite fields present in the body; explicit `null` clears a field
- `DELETE /books/{id}` — remove book, return `204` or `404`
- **Test:**
  ```bash
  curl http://localhost:8080/books/{id}
  curl http://localhost:8080/books/isbn/9780441013593
  curl -X PUT http://localhost:8080/books/{id} \
    -H "Content-Type: application/json" \
    -d '{"rating":5,"readStatus":"FINISHED"}'
  curl -X DELETE http://localhost:8080/books/{id}
  ```

#### Step 7 — Validation and Error Handling
- Return `400` for: missing `title`/`author`/`readStatus`, malformed JSON, `rating` outside 1–5, invalid `isbn` format
- Return `405` for unsupported methods
- Include error message in JSON body: `{"error": "title is required"}`
- **Test:**
  ```bash
  curl -X POST http://localhost:8080/books \
    -H "Content-Type: application/json" \
    -d '{"author":"Someone"}'
  # → 400 {"error": "title is required"}
  ```

> Genre filtering (`?genre=`) is not part of V1. Added in V3. Read status filtering (`?readStatus=`) is added in V4.

---

### V2 — Open Library Integration

#### Overview
Automatically fetch book metadata and cover images from Open Library when a book with an ISBN is added or updated.

API endpoints used:
```
https://openlibrary.org/api/books?bibkeys=ISBN:{isbn}&jscmd=data&format=json
https://covers.openlibrary.org/b/isbn/{isbn}-L.jpg
```

#### New Components

**`OpenLibraryService`**
```java
private final ExecutorService executor = Executors.newSingleThreadExecutor();

public void enrichBookAsync(UUID bookId, String isbn) {
    executor.submit(() -> {
        try {
            BookMetadata metadata = fetchMetadata(isbn);
            // download cover bytes, detect 1x1 placeholder (< 1 KB = skip)
            bookRepository.updateFromOpenLibrary(bookId, metadata, coverBytes);
        } catch (Exception e) {
            // Log and move on — enrichment is best-effort
        }
    });
}
```

**`BookMetadata`** (DTO): `publisher`, `publishDate`, `pageCount`, `subjects`, `genre`, `coverUrl`

#### Integration Points
- **On `POST /books`** — if ISBN is present, fire `enrichBookAsync()`. Response returns immediately.
- **On `PUT /books/{id}`** — if ISBN changed, clear all previously-enriched fields and fire new async enrichment. User-provided fields in the same PUT are preserved.
- **Shutdown** — call `executor.shutdown()` when the server stops.

#### Build Order (V2)
1. Create `OpenLibraryService` with `fetchMetadata(String isbn)` calling the Books API
2. Parse JSON response into `BookMetadata`
3. Add `downloadCover()` — fetch image bytes, detect 1×1 placeholder (< 1 KB = skip)
4. Add `enrichBookAsync()` combining both; update repository when done
5. Wire into `BookController` on POST and PUT
6. Add `GET /books/{id}/cover` route — serve `coverData` bytes with `Content-Type: image/jpeg`
7. Handle edge cases: no ISBN, no cover found, API failures, missing JSON fields

---

### V3 — PostgreSQL & Docker

#### Architecture Changes
- **`DatabaseConfig`** — reads env vars, configures HikariCP pool, runs schema migration on startup
- **`JdbcBookRepository`** — replaces `InMemoryBookRepository` in production. Adds `findByGenre(String)` enabling the `?genre=` filter for the first time.

#### Build Order (V3)
1. Add Shadow plugin, PostgreSQL driver, HikariCP to `build.gradle`
2. Create `DatabaseConfig` — connection pool, read env vars with fallback defaults
3. Write schema migration — `CREATE TABLE IF NOT EXISTS` on startup
4. Implement `JdbcBookRepository` one method at a time; test with local Postgres
5. Write `Dockerfile` (multi-stage: JDK build → JRE runtime)
6. Write `docker-compose.yml` with health check and named volumes
7. Test full stack: `docker compose up --build`

---

### V4 — Frontend

#### Overview
Vanilla HTML/CSS/JS served by the same Java server from `/static`. No build tools, no npm, no React.

#### Add Book Flow
1. User types an ISBN and presses Enter or clicks "Add"
2. Frontend sends `POST /books` with `{"isbn": "...", "readStatus": "WANT_TO_READ"}`
3. Server returns `201` immediately (title/author may be null)
4. Frontend adds a placeholder card and polls `GET /books/{id}` every 2 seconds until enrichment completes (30s timeout)
5. Card updates in place with full data and cover

#### Key Frontend Functions
```javascript
async function addBook(isbn)          // POST /books
async function loadBooks()            // GET /books → populates allBooks[]
async function updateBook(id, data)   // PUT /books/{id}
async function deleteBook(id)         // DELETE /books/{id}
function renderBookCard(book)         // Create DOM card element
function pollForEnrichment(bookId)    // Poll GET /books/{id} until enriched
function getFilteredBooks()           // Apply search + status filter + sort to allBooks[]
```

#### Validation Change (V4)
`title` and `author` become optional if `isbn` is provided. The DB schema already allows NULL for both from V1 onward.

#### Build Order (V4)
1. Create `/static` directory with empty `index.html`, `style.css`, `app.js`
2. Extend `StaticFileHandler` to serve all files from `/static` with correct `Content-Type`
3. Register `GET /` → serve `index.html`
4. Build HTML skeleton (header, ISBN input, filter nav, book grid)
5. Style with CSS (dark antiquarian theme)
6. Implement `loadBooks()` and `renderBookCard()`
7. Implement `addBook(isbn)` — POST with ISBN, placeholder card, start polling
8. Implement edit modal — PUT on save
9. Implement delete with confirm dialog
10. Add filter tabs and sort dropdown
11. Add client-side search bar
12. Add reading progress bar (Feature C — see §12)
13. Add barcode scanner (see §13)

---
## 11. Integration Tests

Tests use JUnit 5 with `java.net.HttpClient`. The server starts on a random port (`new ServerSocket(0)`) before each test class and shuts down after. Repository is cleaned between tests (`@BeforeEach`) for isolation.

### Test Setup Pattern
```java
// @BeforeAll: start server on random port, use InMemoryBookRepository
// @AfterAll: shut down server
// @BeforeEach: clear repository
// Use java.net.HttpClient to send requests
// Parse JSON responses with Gson
```

---

### V1 Tests — Happy Path

**T1 — Create and retrieve a book**
1. `POST /books` with full valid body (title, author, isbn, genre, rating, readStatus)
2. Assert `201 Created` and a server-generated UUID `id` in the response body
3. `GET /books/{id}` — assert `200 OK` and all fields match

**T2 — List all books**
1. Create 3 books via `POST /books`
2. `GET /books` — assert `200 OK` and JSON array has 3 entries, each with `id`, `title`, `author`

**T3 — Partial update a book**
1. Create a book with `title: "Dune"`, `readStatus: "WANT_TO_READ"`, rating defaults to `0`
2. `PUT /books/{id}` with only `readStatus: "FINISHED"` and `rating: 5`
3. Assert `200 OK`
4. `GET /books/{id}` — assert `readStatus` and `rating` updated, `title` still `"Dune"`

**T4 — Delete a book**
1. Create a book
2. `DELETE /books/{id}` — assert `204 No Content`
3. `GET /books/{id}` — assert `404 Not Found`
4. `GET /books` — assert the book is not in the list

**T5 — Look up by ISBN**
1. Create a book with `isbn: "9780441013593"`
2. `GET /books/isbn/9780441013593` — assert `200 OK` and the correct book is returned

---

### V1 Tests — Validation / Error

**T8 — Missing required field: title**
1. `POST /books` with body missing `title`
2. Assert `400 Bad Request` with `{"error": "...title..."}` in response body

**T9 — Missing required field: author**
1. `POST /books` with body missing `author`
2. Assert `400 Bad Request`

**T10 — Missing required field: readStatus**
1. `POST /books` with body missing `readStatus`
2. Assert `400 Bad Request`

**T11 — Invalid rating (too low)**
1. `POST /books` with `rating: 0`
2. Assert `400 Bad Request`

**T12 — Invalid rating (too high)**
1. `POST /books` with `rating: 6`
2. Assert `400 Bad Request`

**T13 — Invalid ISBN format**
1. `POST /books` with `isbn: "123"` (not 10 or 13 chars)
2. Assert `400 Bad Request`

**T14 — Malformed JSON**
1. `POST /books` with body `"not json at all"`
2. Assert `400 Bad Request`

**T15 — Book not found**
1. `GET /books/00000000-0000-0000-0000-000000000000` (non-existent UUID)
2. Assert `404 Not Found`

**T16 — ISBN not found**
1. `GET /books/isbn/0000000000`
2. Assert `404 Not Found`

**T17 — Method not allowed**
1. `PATCH /books` (unsupported method)
2. Assert `405 Method Not Allowed`

---

### V1 Tests — Edge Cases

**T18 — Create book with only required fields**
1. `POST /books` with only `title`, `author`, `readStatus` (no genre, rating, isbn)
2. Assert `201 Created`
3. Assert `genre` is `null`, `isbn` is `null`, `rating` is `0` in the response

**T19 — Update with no changes**
1. Create a book, then `PUT /books/{id}` with the exact same data
2. Assert `200 OK` — nothing should break

**T20 — Delete already-deleted book**
1. Create and delete a book
2. `DELETE /books/{id}` again — assert `404 Not Found`

**T21 — Concurrent creates**
1. Send 10 `POST /books` requests in parallel using `CompletableFuture`
2. Assert all return `201`
3. `GET /books` — assert exactly 10 books

**T22 — Partial update clears field with explicit null**
1. Create a book with `genre: "sci-fi"`
2. `PUT /books/{id}` with `{"genre": null}`
3. Assert `200 OK`
4. `GET /books/{id}` — assert `genre` is `null`

**T23 — ISBN-10 with X check digit is accepted**
1. `POST /books` with `isbn: "080442957X"` (valid ISBN-10)
2. Assert `201 Created`
3. `GET /books/isbn/080442957X` — assert the book is found

**T24 — Duplicate ISBNs are allowed**
1. Create two books with the same `isbn: "9780441013593"` but different titles
2. Assert both return `201 Created`
3. `GET /books` — assert both books exist

---

### V2 Tests — Open Library Enrichment

> These tests require network access to Open Library. Consider mocking HTTP responses for offline testing.

**T25 — Enrichment fills in metadata**
1. `POST /books` with `title`, `author`, `isbn: "9780441013593"`, `readStatus: "READING"`
2. Assert `201 Created`
3. Poll `GET /books/{id}` every 2 seconds, up to 30 seconds
4. Assert `publisher`, `publishDate`, `pageCount`, `subjects` eventually become non-null

**T26 — Cover image is downloaded**
1. Create a book with a valid ISBN
2. Poll until `coverUrl` is non-null
3. `GET /books/{id}/cover` — assert `200 OK`, `Content-Type: image/jpeg`, body > 1 KB

**T27 — Cover endpoint returns 404 before enrichment**
1. Create a book with a valid ISBN
2. Immediately `GET /books/{id}/cover` (before enrichment completes)
3. Assert `404`

**T28 — Book without ISBN gets no enrichment**
1. `POST /books` with no `isbn`
2. Wait 5 seconds
3. `GET /books/{id}` — assert `publisher`, `pageCount`, `subjects` all still `null`

**T29 — User-provided fields are not overwritten**
1. `POST /books` with `isbn: "9780441013593"` AND `publisher: "My Custom Publisher"`
2. Poll until enrichment completes (e.g. `pageCount` becomes non-null)
3. Assert `publisher` is still `"My Custom Publisher"`

**T30 — Invalid ISBN gets no enrichment but book still exists**
1. `POST /books` with `isbn: "0000000000000"` (valid format, not in Open Library)
2. Assert `201 Created`
3. Wait 10 seconds
4. `GET /books/{id}` — assert book exists, `publisher`/`pageCount` still `null`

**T31 — Cover deleted when book is deleted**
1. Create a book with a valid ISBN, poll until cover available
2. `DELETE /books/{id}` — assert `204`
3. `GET /books/{id}/cover` — assert `404`

**T32 — Re-enrichment on ISBN change**
1. Create a book with `isbn: "9780441013593"` (Dune), wait for enrichment
2. `PUT /books/{id}` with `isbn: "9780261102354"` (LotR)
3. Poll until `publisher` changes from the Dune publisher
4. Assert metadata now matches the new ISBN

---

### V3 Tests — Database Persistence

**T33 — All V1 tests pass on PostgreSQL**
Run every test from T1–T24 against the Dockerized app. All should pass with identical behavior — the storage swap is transparent.

**T34 — Data survives app restart**
1. `POST /books` to create a book
2. `docker compose restart app`
3. `GET /books/{id}` — assert the book is still there

**T35 — Data survives full stack restart**
1. Create 3 books
2. `docker compose down` then `docker compose up`
3. `GET /books` — assert all 3 books are still present

**T36 — Data is lost on volume cleanup**
1. Create a book
2. `docker compose down -v` then `docker compose up`
3. `GET /books` — assert empty array `[]`

**T37 — Cover files persist across restarts**
1. Create a book with ISBN, wait for cover to download
2. `docker compose restart app`
3. `GET /books/{id}/cover` — assert `200 OK` with image data

**T38 — Database can be queried directly**
1. Create a book via the API
2. `docker compose exec db psql -U bookshelf -c "SELECT id, title, author FROM books;"`
3. Assert the book appears in the SQL output

**T39 — Concurrent writes don't corrupt data**
1. Send 20 `POST /books` requests in parallel
2. Assert all return `201`
3. `GET /books` — assert exactly 20 books
4. `SELECT COUNT(*) FROM books` — assert 20

**T6 — Filter by genre** *(moved from V1 — genre filtering added in V3)*
1. Create 2 books with `genre: "sci-fi"` and 1 with `genre: "fantasy"`
2. `GET /books?genre=sci-fi` — assert only the 2 sci-fi books returned
3. `GET /books?genre=Sci-Fi` (different case) — assert same result (case-insensitive)

**T7 — Filter with no matches** *(moved from V1)*
1. Create a book with `genre: "sci-fi"`
2. `GET /books?genre=romance` — assert `200 OK` with empty array `[]`

**T40 — Genre filter works as a SQL query**
1. Create 50 books across 5 genres
2. `GET /books?genre=sci-fi` — assert only sci-fi books returned
3. Verify: `SELECT COUNT(*) FROM books WHERE genre = 'sci-fi'` matches the API count

**T41 — updated_at timestamp changes on PUT**
1. Create a book, note `created_at` via direct SQL
2. Wait 2 seconds
3. `PUT /books/{id}` with a changed field
4. Query SQL: assert `updated_at > created_at`

**T42 — App handles database being temporarily unavailable**
1. Start the full stack
2. `docker compose pause db`
3. `POST /books` — assert a `500` or meaningful error (not a hang or crash)
4. `docker compose unpause db`
5. `POST /books` — assert `201` (app recovers)

---

## 12. Feature Implementations

These three features were added after V4 as additive improvements with no breaking changes.
Suggested order: Search → Sorting → Reading Progress.

---

### Feature A — Search (`GET /books?search=dune`)

#### A1: Backend — add `findBySearch()` to repository

**`BookRepository.java`** — add after `findByReadStatus`:
```java
List<Book> findBySearch(String query);
```

**`InMemoryBookRepository.java`**:
```java
@Override
public List<Book> findBySearch(String query) {
    String q = query.toLowerCase();
    return books.values().stream()
        .filter(b -> (b.getTitle() != null && b.getTitle().toLowerCase().contains(q))
                  || (b.getAuthor() != null && b.getAuthor().toLowerCase().contains(q)))
        .sorted(Comparator.comparing(b -> b.getCreatedAt() != null ? b.getCreatedAt() : Instant.EPOCH))
        .toList();
}
```

**`JdbcBookRepository.java`**:
```java
@Override
public List<Book> findBySearch(String query) {
    String sql = "SELECT * FROM books WHERE LOWER(title) LIKE LOWER(?) OR LOWER(author) LIKE LOWER(?) ORDER BY created_at ASC";
    String pattern = "%" + query + "%";
    try (Connection conn = dataSource.getConnection();
         PreparedStatement stmt = conn.prepareStatement(sql)) {
        stmt.setString(1, pattern);
        stmt.setString(2, pattern);
        try (ResultSet rs = stmt.executeQuery()) {
            List<Book> books = new ArrayList<>();
            while (rs.next()) books.add(mapRow(rs));
            return books;
        }
    } catch (SQLException e) {
        throw new RuntimeException("Failed to search books", e);
    }
}
```

**`BookController.java`** — inside `handleGetBooks()`, replace the filtering block:
```java
String search = request.getQueryParams().get("search");

List<Book> books;
if (search != null && !search.isBlank()) {
    books = repository.findBySearch(search.trim());
} else if (genre != null && !genre.isEmpty()) {
    books = repository.findByGenre(genre);
} else if (readStatus != null) {
    books = repository.findByReadStatus(readStatus);
} else {
    books = repository.findAll();
}

// Apply readStatus as post-filter on top of search or genre results
if (readStatus != null && (search != null || (genre != null && !genre.isEmpty()))) {
    ReadStatus finalReadStatus = readStatus;
    books = books.stream().filter(b -> b.getReadStatus() == finalReadStatus).toList();
}
```

#### A2: Tests for search

```java
@Test
void testSearchByTitle() throws Exception {
    createBook("Dune", "Frank Herbert", "WANT_TO_READ");
    createBook("Neuromancer", "William Gibson", "READING");

    HttpResponse<String> resp = client.send(
        HttpRequest.newBuilder().uri(URI.create(base + "/books?search=dune")).GET().build(),
        HttpResponse.BodyHandlers.ofString());

    assertEquals(200, resp.statusCode());
    JsonArray books = JsonParser.parseString(resp.body()).getAsJsonArray();
    assertEquals(1, books.size());
    assertEquals("Dune", books.get(0).getAsJsonObject().get("title").getAsString());
}

@Test
void testSearchByAuthor() throws Exception {
    createBook("Dune", "Frank Herbert", "WANT_TO_READ");
    createBook("Neuromancer", "William Gibson", "READING");

    HttpResponse<String> resp = client.send(
        HttpRequest.newBuilder().uri(URI.create(base + "/books?search=gibson")).GET().build(),
        HttpResponse.BodyHandlers.ofString());

    assertEquals(200, resp.statusCode());
    JsonArray books = JsonParser.parseString(resp.body()).getAsJsonArray();
    assertEquals(1, books.size());
    assertEquals("Neuromancer", books.get(0).getAsJsonObject().get("title").getAsString());
}
```

#### A3: Frontend — client-side search bar

**`index.html`** — add inside `.catalog-search`, below `.search-terminal`:
```html
<div class="search-bar">
    <input type="search" id="search-input"
           placeholder="Search by title or author..."
           autocomplete="off" spellcheck="false">
</div>
```

**`app.js`**:
```javascript
const searchInput = document.getElementById('search-input');
let searchQuery = '';

searchInput.addEventListener('input', () => {
    searchQuery = searchInput.value.trim();
    renderBooks(getFilteredBooks());
});

function getFilteredBooks() {
    let books = allBooks;
    if (searchQuery) {
        const q = searchQuery.toLowerCase();
        books = books.filter(b =>
            (b.title && b.title.toLowerCase().includes(q)) ||
            (b.author && b.author.toLowerCase().includes(q))
        );
    }
    if (currentFilter !== 'all') {
        books = books.filter(b => b.readStatus === currentFilter);
    }
    return getSortedBooks(books);
}
```

> Search is done client-side on the already-loaded `allBooks` array — no extra API call. This is sufficient for a personal shelf.

**`style.css`**:
```css
.search-bar { margin-top: 0.5rem; }
.search-bar input {
    width: 100%;
    background: var(--bg-input);
    border: 1px solid var(--border);
    border-radius: var(--radius-md);
    color: var(--text-primary);
    font-family: 'DM Sans', sans-serif;
    font-size: 0.85rem;
    padding: 0.45rem 0.75rem;
    outline: none;
    transition: border-color 0.2s;
}
.search-bar input:focus { border-color: var(--gold-dim); }
```

---

### Feature B — Sorting (`?sort=title,asc`)

#### B1: Backend — sort parameter in `GET /books`

**`BookController.java`** — after the filtering block, before returning:
```java
String sortParam = request.getQueryParams().get("sort"); // e.g. "title,asc"
if (sortParam != null && !sortParam.isBlank()) {
    String[] parts = sortParam.split(",", 2);
    String field = parts[0].trim();
    boolean desc = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim());

    Comparator<Book> comparator = switch (field) {
        case "title"   -> Comparator.comparing(b -> b.getTitle() != null ? b.getTitle().toLowerCase() : "");
        case "author"  -> Comparator.comparing(b -> b.getAuthor() != null ? b.getAuthor().toLowerCase() : "");
        case "rating"  -> Comparator.comparing(b -> b.getRating() != null ? b.getRating() : 0);
        case "created" -> Comparator.comparing(b -> b.getCreatedAt() != null ? b.getCreatedAt() : Instant.EPOCH);
        default        -> null;
    };

    if (comparator != null) {
        if (desc) comparator = comparator.reversed();
        books = books.stream().sorted(comparator).toList();
    }
}
```

Add `import java.time.Instant;` if not already present.

#### B2: Tests for sorting

```java
@Test
void testSortByTitleAsc() throws Exception {
    createBook("Zorro", "Johnston McCulley", "WANT_TO_READ");
    createBook("Dune", "Frank Herbert", "WANT_TO_READ");

    HttpResponse<String> resp = client.send(
        HttpRequest.newBuilder().uri(URI.create(base + "/books?sort=title,asc")).GET().build(),
        HttpResponse.BodyHandlers.ofString());

    assertEquals(200, resp.statusCode());
    JsonArray books = JsonParser.parseString(resp.body()).getAsJsonArray();
    assertEquals("Dune",  books.get(0).getAsJsonObject().get("title").getAsString());
    assertEquals("Zorro", books.get(1).getAsJsonObject().get("title").getAsString());
}
```

#### B3: Frontend — sort dropdown

**`index.html`** — add inside `.filter-inner`:
```html
<select id="sort-select" class="sort-select">
    <option value="">Sort: Default</option>
    <option value="title,asc">Title A–Z</option>
    <option value="title,desc">Title Z–A</option>
    <option value="author,asc">Author A–Z</option>
    <option value="rating,desc">Highest Rated</option>
    <option value="created,desc">Recently Added</option>
</select>
```

**`app.js`**:
```javascript
const sortSelect = document.getElementById('sort-select');
let currentSort = '';

sortSelect.addEventListener('change', () => {
    currentSort = sortSelect.value;
    renderBooks(getFilteredBooks());
});

function getSortedBooks(books) {
    if (!currentSort) return books;
    const [field, dir] = currentSort.split(',');
    const asc = dir !== 'desc';
    return [...books].sort((a, b) => {
        let av, bv;
        if (field === 'title')   { av = (a.title  || '').toLowerCase(); bv = (b.title  || '').toLowerCase(); }
        if (field === 'author')  { av = (a.author || '').toLowerCase(); bv = (b.author || '').toLowerCase(); }
        if (field === 'rating')  { av = a.rating  || 0; bv = b.rating  || 0; }
        if (field === 'created') { av = a.createdAt || ''; bv = b.createdAt || ''; }
        if (av < bv) return asc ? -1 : 1;
        if (av > bv) return asc ? 1 : -1;
        return 0;
    });
}
```

**`style.css`**:
```css
.sort-select {
    background: var(--bg-input);
    border: 1px solid var(--border);
    border-radius: var(--radius-md);
    color: var(--text-secondary);
    font-family: 'DM Sans', sans-serif;
    font-size: 0.8rem;
    padding: 0.35rem 0.6rem;
    cursor: pointer;
    outline: none;
}
```

---

### Feature C — Reading Progress

A `readingProgress` field (Integer, 0–100) tracks how far through a book you are. Only meaningful for `READING` status. Validated on both create and update. Displayed as a thin gold progress bar on the card.

#### C1: DB schema migration

**`DatabaseConfig.java`** — add after the `CREATE TABLE` statement:
```java
conn.createStatement().execute(
    "ALTER TABLE books ADD COLUMN IF NOT EXISTS reading_progress INTEGER DEFAULT NULL"
);
```

Safe to re-run on an existing database.

#### C2: Add `readingProgress` to `Book` model

**`Book.java`**:
```java
private Integer readingProgress; // 0–100, only meaningful when readStatus == READING

public Integer getReadingProgress() { return readingProgress; }
public void setReadingProgress(Integer readingProgress) { this.readingProgress = readingProgress; }
```

#### C3: Persist in `JdbcBookRepository`

- **`save()` INSERT** — add `reading_progress` column and `stmt.setObject(N, book.getReadingProgress())` (use `setObject` for nullable int)
- **`update()` UPDATE** — add `reading_progress = ?` to SET clause
- **`mapRow()`**:
  ```java
  int rp = rs.getInt("reading_progress");
  book.setReadingProgress(rs.wasNull() ? null : rp);
  ```

#### C4: Handle in `BookController`

**`handleCreateBook()`** — after the `pageCount` block:
```java
if (json.has("readingProgress") && !json.get("readingProgress").isJsonNull()) {
    int progress = json.get("readingProgress").getAsInt();
    if (progress < 0 || progress > 100) {
        return HttpResponse.badRequest("readingProgress must be between 0 and 100");
    }
    book.setReadingProgress(progress);
}
```

**`handleUpdateBook()`**:
```java
if (json.has("readingProgress")) {
    if (json.get("readingProgress").isJsonNull()) {
        book.setReadingProgress(null);
    } else {
        int progress = json.get("readingProgress").getAsInt();
        if (progress < 0 || progress > 100) {
            return HttpResponse.badRequest("readingProgress must be between 0 and 100");
        }
        book.setReadingProgress(progress);
    }
}
```

#### C5: Frontend — progress bar and edit input

**`app.js`** — inside `createBookCard()`, after `.book-stars`:
```javascript
${book.readStatus === 'READING' && book.readingProgress != null
    ? `<div class="progress-bar-wrap" title="${book.readingProgress}% read">
         <div class="progress-bar-fill" style="width:${book.readingProgress}%"></div>
       </div>`
    : ''}
```

**`index.html`** — inside `#edit-form`:
```html
<div class="form-group" id="progress-group" hidden>
    <label for="edit-progress">Reading Progress (%)</label>
    <input type="number" id="edit-progress" min="0" max="100" placeholder="0–100">
</div>
```

Show/hide `#progress-group` when `edit-status` changes to/from `READING`. Include `readingProgress` in the PUT body when saving.

**`style.css`**:
```css
.progress-bar-wrap {
    height: 3px;
    background: var(--border);
    border-radius: 2px;
    margin-bottom: 0.5rem;
    overflow: hidden;
}
.progress-bar-fill {
    height: 100%;
    background: var(--gold);
    border-radius: 2px;
    transition: width 0.4s var(--ease-out);
}
```

---

## 13. ISBN Barcode Scanner

> **Status:** Implemented. Originally built with html5-qrcode, then replaced with zbar-wasm (WebAssembly) for unified cross-platform scanning. html5-qrcode ignores external image preprocessing on Windows Chrome (no native `BarcodeDetector` API). zbar-wasm accepts `ImageData` directly, enabling a multi-pass scan loop that works on all platforms.

### Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Library | **zbar-wasm** (`@undecaf/zbar-wasm@0.11.0`) | WASM-based C barcode decoder; accepts `ImageData` input directly; 2–10ms per decode; works on all platforms without `BarcodeDetector` API |
| Loading | **Local file** in `static/lib/` (inlined UMD build, 326 KB) | Self-contained, no CDN dependency, works offline |
| After scan | **Auto-submit if no duplicate** | If no copy of that ISBN exists on shelf, add immediately. If a copy exists, prompt "You already have this book. Add another copy?" |
| Multi-scan | **One-at-a-time** | Scanner closes after each successful scan. Click "Scan" again for the next book. |

### Why zbar-wasm instead of html5-qrcode?

- html5-qrcode's pure-JS ZXing port ignores external image preprocessing — it captures its own video frames
- On Windows Chrome (no native `BarcodeDetector` API), the entire preprocessing pipeline was dead code
- zbar-wasm accepts `ImageData` so our preprocessing integrates directly
- 2–10ms per decode vs 40–80ms for ZXing-js
- Better at damaged/low-quality/glossy barcodes

### Scan Pipeline (per frame, ~12 fps)

```
1. captureROI(video)                       ~2ms  downscale 1080p → 800px, crop center 80%×50%
2. grayToImageData → scanImageData          ~5ms  raw grayscale, fastest path
3. unsharpMask (in-place on gray)           ~2ms  3×3 box sharpen
4. globalThresholdGray(0.35) → scan         ~5ms  dark-bar recovery under glare
5. globalThresholdGray(0.50) → scan         ~5ms  balanced
6. globalThresholdGray(0.65) → scan         ~5ms  light-bar recovery in shadow
7. adaptiveThresholdGray(31, 10) → scan    ~15ms  handles uneven glare
   Total worst case: ~39ms — well within 80ms budget
```

Short-circuits on first successful decode; clean barcodes decode in ~7ms.

### UX Flow

```
1. User clicks "Scan" button
2. Browser prompts for camera permission (first time only)
3. Scanner modal opens with live camera feed
4. User points camera at book's ISBN barcode (back cover)
5. Library detects barcode → extracts EAN-13 digits
6. Modal closes, ISBN appears in the input field
7. System checks: GET /books/isbn/{isbn}
   ├── 404 (no copy) → auto-submit: POST /books → toast "Book added!"
   └── 200 (copy exists) → confirm: "You already have [title]. Add another copy?"
        ├── Yes → POST /books
        └── No → ISBN stays in input, user can edit or clear
8. Existing enrichment polling kicks in (placeholder card → enriched card)
```

### Implementation

#### HTML (`static/index.html`)
```html
<!-- In <head> -->
<script src="/lib/zbar-wasm.js"></script>

<!-- Scan button next to "Add to Shelf" -->
<button id="scan-btn" type="button" title="Scan ISBN barcode">
    <!-- camera/barcode SVG icon -->
</button>

<!-- Scanner modal -->
<div id="scanner-modal" class="modal-overlay" hidden>
    <div class="modal scanner-modal">
        <div class="modal-header">
            <h2>Scan ISBN Barcode</h2>
            <button class="modal-close" id="scanner-close" type="button"><!-- X --></button>
        </div>
        <div class="scanner-body">
            <div id="scanner-viewfinder"></div>
            <p class="scanner-hint">Point your camera at the barcode on the back of a book</p>
            <div id="scanner-error" class="scanner-error" hidden></div>
        </div>
    </div>
</div>
```

#### JavaScript (`static/app.js`) — key functions

```javascript
// State
let isScanning = false;
let activeStream = null;

// openScanner() — show modal, request camera, call startZbarScanner()
// startZbarScanner() — getUserMedia → optimizeCameraSettings → create <video>
//                      → requestAnimationFrame loop at ~12fps with multi-pass pipeline
// closeScanner() — set isScanning=false, stop stream tracks, hide modal
// handleScanResult(code) / onBarcodeScanned(code):
//   1. Stop scanner, close modal
//   2. Validate as ISBN via isValidIsbn(code)
//   3. Set isbnInput.value = code
//   4. GET /books/isbn/{code}
//      404 → addBook() directly
//      200 → confirm dialog → addBook() or leave in input

scanBtn.addEventListener('click', openScanner);
scannerClose.addEventListener('click', closeScanner);
scannerModal.addEventListener('click', e => { if (e.target === scannerModal) closeScanner(); });
// Escape key also closes scanner (extend existing handler)
```

#### Error Handling
- **Camera not available**: show in `#scanner-error`: "No camera found"
- **Permission denied**: show: "Camera permission denied. Please allow camera access in your browser settings."
- **Library not loaded**: if `typeof zbarWasm === 'undefined'` on page load, hide the scan button (graceful degradation)

#### CSS (`static/style.css`)
Add styles for `.scanner-modal` (wider than edit modal), `#scanner-viewfinder` (fixed 4:3 aspect ratio, gold border), `.scanner-hint`, `.scanner-error`, and a scanning animation (pulsing border or sweeping line).

### HTTPS Note

`navigator.mediaDevices.getUserMedia()` requires a **secure context** (HTTPS or `localhost`). Local development on `localhost:8080` works fine. Any non-localhost deployment requires HTTPS — this is a browser security requirement.

---

## 14. Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `bookshelf` | Database name |
| `DB_USER` | `bookshelf` | Database user |
| `DB_PASS` | `bookshelf` | Database password |
| `APP_PORT` | `8080` | Server listen port |

---

## 15. Database

### Schema

```sql
CREATE TABLE IF NOT EXISTS books (
    id               UUID PRIMARY KEY,
    title            VARCHAR(255),
    author           VARCHAR(255),
    genre            VARCHAR(100),
    rating           INTEGER DEFAULT 0 CHECK (rating BETWEEN 0 AND 5),
    isbn             VARCHAR(13),
    publisher        VARCHAR(255),
    publish_date     VARCHAR(50),
    page_count       INTEGER,
    subjects         TEXT,
    read_status      VARCHAR(20) NOT NULL,
    cover_url        VARCHAR(500),
    cover_data       BYTEA,
    reading_progress INTEGER DEFAULT NULL,
    created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_books_isbn  ON books(isbn);
CREATE INDEX IF NOT EXISTS idx_books_genre ON books(genre);
```

### Column Mapping (DB `snake_case` → Java `camelCase`)

| Java field | DB column |
|------------|-----------|
| `readStatus` | `read_status` |
| `coverData` | `cover_data` |
| `publishDate` | `publish_date` |
| `pageCount` | `page_count` |
| `coverUrl` | `cover_url` |
| `createdAt` | `created_at` |
| `updatedAt` | `updated_at` |
| `readingProgress` | `reading_progress` |

### Schema Migrations

Migrations run on startup via `DatabaseConfig.runMigrations()`. All statements use `IF NOT EXISTS` / `ADD COLUMN IF NOT EXISTS` so they are safe to re-run on an existing database.

Columns added as `ALTER TABLE` migrations beyond the base `CREATE TABLE`:

1. `cover_data BYTEA` — cover image bytes
2. `reading_progress INTEGER DEFAULT NULL` — reading progress (0–100)

---

## 16. Docker

### Dockerfile (multi-stage)

```dockerfile
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY . .
RUN ./gradlew shadowJar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/*-all.jar app.jar
COPY --from=build /app/static /app/static
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
```

### docker-compose.yml

```yaml
services:
  app:
    build: .
    ports:
      - "8080:8080"
    environment:
      DB_HOST: db
      DB_PORT: 5432
      DB_NAME: bookshelf
      DB_USER: bookshelf
      DB_PASS: bookshelf
    depends_on:
      db:
        condition: service_healthy

  db:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: bookshelf
      POSTGRES_USER: bookshelf
      POSTGRES_PASSWORD: bookshelf
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U bookshelf"]
      interval: 5s
      timeout: 3s
      retries: 5

volumes:
  pgdata:
```

Key points:
- **Health check** — app waits for Postgres to be ready before starting
- **Named volume `pgdata`** — persists the database across container restarts
- `docker compose down -v` removes the volume and wipes all data

---

## 17. Testing

### Test Infrastructure

- **Framework**: JUnit 5 with `@BeforeAll`/`@AfterAll` for server lifecycle
- **HTTP client**: `java.net.HttpClient`
- **Random port**: `new ServerSocket(0)` assigns a free port before each test class
- **Repository**: `InMemoryBookRepository` — no database or Docker required for `./gradlew test`
- **Isolation**: `@BeforeEach` calls `repository.clear()` between tests

### Test Classes

| Class | Coverage |
|-------|----------|
| `BookApiTest` | 30+ tests — CRUD, ISBN lookup, genre/status filtering, search, sorting, reading progress, partial updates, validation, error codes, edge cases (concurrent creates, null clearing, ISBN-10 with X) |
| `OpenLibraryTest` | 8+ tests — metadata fetching, cover download, 1×1 pixel detection, re-enrichment, user-data preservation, ISBN change triggering re-enrichment |

### Running Tests

```bash
# All tests
./gradlew test

# Single class
./gradlew test --tests "com.bookshelf.BookApiTest"

# Single method
./gradlew test --tests "com.bookshelf.BookApiTest.testT01_createAndRetrieveBook"

# Force re-run (skip Gradle cache)
./gradlew test --rerun-tasks
```

---

## 18. Things to Watch Out For

### HTTP Server
- **Async timing** — `GET /books/{id}` immediately after `POST` may return null enriched fields. This is expected — the client should poll or accept eventual consistency.
- **Executor shutdown** — call `OpenLibraryService.shutdown()` when the server stops, or the JVM may hang.

### Routing
- **Route conflicts** — `/books/isbn/{isbn}` must be matched before `/books/{id}`. The router prioritizes static segments (`isbn`) over path parameters (`{id}`). Without this, "isbn" would be treated as an ID.
- **Multi-segment paths** — `/books/{id}/cover` is a distinct route; ensure the router handles three-segment paths correctly.

### Open Library
- **Missing metadata** — not every book has all fields. Always null-check `pageCount`, `subjects`, etc. when parsing.
- **Subjects structure** — subjects come back as objects with `name` and `url`; extract only the `name` strings.
- **1×1 pixel placeholder** — Open Library returns a tiny image (< 1 KB) instead of 404 for missing covers. Check file size after download.
- **User overrides** — enrichment only fills `null` fields. Never overwrite user-provided values.
- **Rate limits** — `POST /books/re-enrich` uses 3-second delays between requests.

### Database
- **Connection handling** — always close connections, statements, and result sets with try-with-resources.
- **Startup order** — the app crashes if it connects before Postgres is ready. The Docker Compose health check handles this; add retry logic in `DatabaseConfig` as a safety net.
- **SQL vs Java naming** — DB uses `snake_case`, Java uses `camelCase`. Map explicitly in `JdbcBookRepository`.
- **Shadow JAR conflicts** — if service loader issues arise with HikariCP or the Postgres driver, check the Shadow plugin merge strategy.

### Frontend
- **CORS** — not needed when the frontend is served by the same Java server. Needed only if testing from a separate origin.
- **Polling cleanup** — stop polling after 30 seconds to avoid infinite requests if Open Library is down.
- **ISBN input validation** — validate format on the frontend before sending (10 or 13 chars). Show an inline error for invalid formats.
- **Responsive layout** — use CSS grid with `auto-fill` and `minmax()` for the card grid.
- **Barcode scanner HTTPS** — `getUserMedia()` requires HTTPS or localhost. Works in dev; any production deployment needs HTTPS.

### V4 Validation Change
- Making `title`/`author` optional when ISBN is provided is a breaking change from V1 behavior. If Open Library doesn't find the ISBN, the book will have no title. Consider prompting the user to fill it in manually if enrichment fails after a timeout.

---

## 19. Claude Code Tips

When working with Claude Code on this project, try prompts like:

- *"Set up the Gradle project with Gson as a dependency and create the main class with a ServerSocket listening on port 8080"*
- *"Build the RequestParser class — read from InputStream, extract method, path, query string, headers, and body into an HttpRequest record"*
- *"Implement the Router. It should support registering routes with path patterns like /books/{id} and match incoming requests to handlers"*
- *"Add input validation to BookController — return 400 with a JSON error if required fields are missing or rating is out of range"*
- *"Implement OpenLibraryService that fetches book metadata and cover images asynchronously when a book with an ISBN is created"*
- *"Write JdbcBookRepository that implements BookRepository against PostgreSQL using HikariCP and PreparedStatements"*
- *"Add the ?sort=field,asc|desc query parameter to GET /books using an in-memory Comparator in BookController"*

### Useful follow-up questions for Claude Code:
- *"Explain how you're parsing the HTTP request line"*
- *"Why did you use ConcurrentHashMap instead of HashMap?"*
- *"What would break if two requests came in at the same time?"*
- *"How does a real framework like Spring do this differently?"*
- *"What happens if Open Library is down when a book is created?"*

---

## 20. License

This project is for personal use and learning purposes.

