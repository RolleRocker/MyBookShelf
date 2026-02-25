# 📚 Bookshelf REST API — From Scratch

## Overview
Build an HTTP server using only `java.net.ServerSocket` (no Spring, no Javalin, no external frameworks) that serves a JSON REST API for managing a personal bookshelf.

## Tech Stack
- **Java 17+**
- **Gradle** for builds
- **Gson** as the only external dependency for V1 (V2 and V3 add more — see those sections)
- No frameworks — you're building the HTTP layer yourself

### Design Decisions
| Decision | Choice | Notes |
|----------|--------|-------|
| Threading model | Fixed thread pool (10 threads) | `Executors.newFixedThreadPool(10)` in `HttpServer` |
| PUT semantics | Partial update | Only fields present in the request body are overwritten. Missing fields are left unchanged. Sending `null` explicitly for a field clears it. |
| Test framework | JUnit 5 | With `java.net.HttpClient` for HTTP calls |
| ISBN-10 check digit | Accept 'X' | Valid ISBN-10 format: 9 digits + digit or 'X' (e.g. `080442957X`) |
| Duplicate ISBNs | Allowed | Users may own multiple copies of the same book |
| ISBN lookup with duplicates | Return oldest | `GET /books/isbn/{isbn}` returns the first-created book when multiple share an ISBN |
| Rating default | `0` | Always present in JSON. `0` means "not rated". Valid user ratings are 1–5. |
| `rating` / `pageCount` Java type | `Integer` (boxed) | Nullable to distinguish "not provided" from 0; important for partial updates with Gson |
| Genre filtering | V3+ only | Not available in V1; added as a SQL query in V3 via `findByGenre()` |
| Read status filtering | API query param | `?readStatus=` added in V4, not client-side JS filtering |
| Subjects storage | JSON array string | Stored as `["Science fiction","Space"]` in a TEXT column |
| Repository pattern | Interface + impl | `BookRepository` interface in V1, `InMemoryBookRepository` and `JdbcBookRepository` as implementations |
| Frontend add default | `WANT_TO_READ` | New books added via ISBN always get this status initially |

---

## Data Model

### Book
| Field        | Type                                          | Required |
|--------------|-----------------------------------------------|----------|
| `id`         | UUID (server-generated)                       | auto     |
| `title`      | String                                        | yes*     |
| `author`     | String                                        | yes*     |
| `genre`      | String                                        | no       |
| `rating`     | Integer (1–5, default 0 = not rated)          | no       |
| `isbn`       | String (10 or 13 chars, ISBN-10 may end in X) | no       |
| `publisher`  | String                                        | no (auto-filled from Open Library) |
| `publishDate`| String                                        | no (auto-filled from Open Library) |
| `pageCount`  | Integer                                       | no (auto-filled from Open Library) |
| `subjects`   | List\<String\>                                | no (auto-filled from Open Library) |
| `readStatus` | enum: `WANT_TO_READ`, `READING`, `FINISHED`, `DNF`   | yes      |

\* `title` and `author` are required in V1–V3. In V4, they become optional if `isbn` is provided (enriched async from Open Library). The DB schema allows NULL for both from V1 onward to avoid a migration later.

---

## API Endpoints

| Method   | Path           | Description                          | Success Code |
|----------|----------------|--------------------------------------|--------------|
| `GET`    | `/books`       | List all books. V3 adds `?genre=` filter, V4 adds `?readStatus=` filter | `200 OK`     |
| `GET`    | `/books/{id}`  | Get a single book by ID              | `200 OK`     |
| `GET`    | `/books/isbn/{isbn}` | Look up a book by ISBN         | `200 OK`     |
| `POST`   | `/books`       | Add a new book                       | `201 Created`|
| `PUT`    | `/books/{id}`  | Partial update (only sent fields change) | `200 OK`     |
| `DELETE` | `/books/{id}`  | Delete a book                        | `204 No Content` |

### Error Responses
| Code  | When                                          |
|-------|-----------------------------------------------|
| `400` | Missing required fields, malformed JSON, invalid rating (must be 1–5 when provided), invalid ISBN format (must be 10 chars ending in digit/X or 13 digits) |
| `404` | Book ID not found                             |
| `405` | Unsupported HTTP method on a route            |

---

## Architecture / Key Components

Build these as separate classes with clear responsibilities:

### 1. `HttpServer`
- Listens on a configurable port using `ServerSocket`
- Accepts incoming connections in a loop
- Uses a fixed-size thread pool (`ExecutorService` with 10 threads) to handle connections

### 2. `RequestParser`
- Reads raw bytes from the socket `InputStream`
- Extracts: HTTP method, path, query parameters, headers, body
- Returns a structured `HttpRequest` object

### 3. `HttpRequest` (model)
- Fields: `method`, `path`, `queryParams` (Map), `headers` (Map), `body` (String)

### 4. `HttpResponse` (model)
- Fields: `statusCode`, `statusText`, `headers` (Map), `body` (String)

### 5. `Router`
- Registers routes as method + path pattern → handler function
- Matches incoming requests, extracting path parameters (e.g. `{id}`)
- Returns `405` if the path exists but the method doesn't match

### 6. `BookController`
- Handler methods for each endpoint
- Deserializes JSON request bodies into `Book` objects via Gson
- Validates input (required fields, rating range)
- Calls `BookRepository` and builds `HttpResponse` objects

### 7. `BookRepository` (interface)
- Defines the contract: `findAll()`, `findById(UUID)`, `findByIsbn(String)`, `save(Book)`, `update(UUID, Book)`, `delete(UUID)`, `updateFromOpenLibrary(UUID, BookMetadata, String coverData)` *(added in V2)*
- `findByIsbn()` returns the oldest (first-created) book when multiple books share the same ISBN
- V1 implementation: `InMemoryBookRepository` using `ConcurrentHashMap<UUID, Book>`
- V3 implementation: `JdbcBookRepository` using PostgreSQL + HikariCP

### 8. `ResponseWriter`
- Takes an `HttpResponse` and writes a properly formatted HTTP response to the socket `OutputStream`
- Includes `Content-Type: application/json`, `Content-Length`, etc.

---

## Build Order (Step by Step)

Each step is independently testable with `curl`.

### Step 1 — Hello World Server
- Create `HttpServer` that listens on port `8080`
- Return `"Hello World"` as plain text to every request
- **Test:** `curl http://localhost:8080/anything`

### Step 2 — Parse Requests
- Build `RequestParser` to extract method, path, headers, and body
- Log parsed request info to the console
- **Test:** `curl -X POST http://localhost:8080/test -d '{"hello":"world"}'` and check console output

### Step 3 — Router with Static Paths
- Build the `Router` to register and match `GET /books` and `POST /books`
- Return placeholder JSON responses
- **Test:** `curl http://localhost:8080/books` → `[]`

### Step 4 — Path Parameter Extraction
- Extend `Router` to handle `/books/{id}` patterns
- Also handle multi-segment patterns like `/books/isbn/{isbn}` — the router needs to distinguish static segments (`isbn`) from parameters (`{isbn}`)
- Extract named parameters from the path and make them available to handlers
- **Test:** `curl http://localhost:8080/books/abc123` → `{"id": "abc123"}`

### Step 5 — POST and GET /books
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

### Step 6 — GET, PUT, DELETE by ID
- `GET /books/{id}` — return single book or `404`
- `GET /books/isbn/{isbn}` — look up a book by ISBN or `404`
- `PUT /books/{id}` — partial update: only overwrite fields present in the body, leave others unchanged. Explicit `null` clears a field. Return updated book or `404`
- `DELETE /books/{id}` — remove book, return `204` or `404`
- **Test:**
  ```bash
  curl http://localhost:8080/books/{id-from-step-5}
  curl http://localhost:8080/books/isbn/9780441013593
  curl -X PUT http://localhost:8080/books/{id} \
    -H "Content-Type: application/json" \
    -d '{"title":"Dune","author":"Frank Herbert","isbn":"9780441013593","rating":5,"readStatus":"FINISHED"}'
  curl -X DELETE http://localhost:8080/books/{id}
  ```

### Step 7 — Validation and Error Handling
- Return `400` for: missing `title`/`author`/`readStatus`, malformed JSON, `rating` outside 1–5 (0 is allowed as default but cannot be explicitly set by user), `isbn` not valid format (must be 10 chars with last digit or X, or 13 digits)
- Return `405` for unsupported methods
- Include error message in JSON body: `{"error": "title is required"}`
- **Test:**
  ```bash
  curl -X POST http://localhost:8080/books \
    -H "Content-Type: application/json" \
    -d '{"author":"Someone"}'
  # → 400 {"error": "title is required"}
  ```

---

> **Note:** Genre filtering (`?genre=`) is not part of V1. It is added in V3 as a SQL query via `findByGenre()`. Read status filtering (`?readStatus=`) is added in V4.

## V1 — Integration Tests

Write these as automated tests using **JUnit 5** and `java.net.HttpClient` (Java 11+). Start the server on a random available port before each test class and shut it down after.

### Test Setup
```java
// JUnit 5 with @BeforeAll / @AfterAll to start/stop the server
// Start server on random port (new ServerSocket(0).getLocalPort()) before tests
// Use java.net.HttpClient to send requests
// Parse JSON responses with Gson
// Clean the repository between tests (@BeforeEach) to ensure isolation
```

### Gradle Test Dependencies
```groovy
dependencies {
    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
}

test {
    useJUnitPlatform()
}
```

### Happy Path Tests

**T1 — Create and retrieve a book**
1. `POST /books` with full valid body (title, author, isbn, genre, rating, readStatus)
2. Assert response is `201 Created`
3. Assert response body contains a server-generated `id` (valid UUID)
4. `GET /books/{id}` with the returned ID
5. Assert `200 OK` and all fields match what was sent

**T2 — List all books**
1. Create 3 books via `POST /books`
2. `GET /books`
3. Assert `200 OK` and response is a JSON array with 3 entries
4. Assert each book has an `id`, `title`, and `author`

**T3 — Partial update a book**
1. Create a book with `title: "Dune"`, `readStatus: "WANT_TO_READ"`, `rating` defaults to `0`
2. `PUT /books/{id}` with only `readStatus: "FINISHED"` and `rating: 5` (no other fields)
3. Assert `200 OK`
4. `GET /books/{id}` — assert `readStatus` and `rating` are updated, `title` is still `"Dune"`

**T4 — Delete a book**
1. Create a book
2. `DELETE /books/{id}`
3. Assert `204 No Content`
4. `GET /books/{id}` — assert `404 Not Found`
5. `GET /books` — assert the book is not in the list

**T5 — Look up by ISBN**
1. Create a book with `isbn: "9780441013593"`
2. `GET /books/isbn/9780441013593`
3. Assert `200 OK` and the correct book is returned

~~**T6 — Filter by genre** — Moved to V3 (genre filtering not in V1)~~

~~**T7 — Filter with no matches** — Moved to V3 (genre filtering not in V1)~~

### Validation / Error Tests

**T8 — Missing required field: title**
1. `POST /books` with body missing `title`
2. Assert `400 Bad Request`
3. Assert response body contains `{"error": "..."}` mentioning `title`

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
1. `POST /books` with `isbn: "123"` (not 10 or 13 digits)
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

### Edge Case Tests

**T18 — Create book with only required fields**
1. `POST /books` with only `title`, `author`, `readStatus` (no genre, rating, isbn)
2. Assert `201 Created`
3. Assert `genre` is `null`, `isbn` is `null`, `rating` is `0` in the response

**T19 — Update with no changes**
1. Create a book
2. `PUT /books/{id}` with the exact same data
3. Assert `200 OK` — nothing should break

**T20 — Delete already deleted book**
1. Create and delete a book
2. `DELETE /books/{id}` again
3. Assert `404 Not Found`

**T21 — Concurrent creates**
1. Send 10 `POST /books` requests in parallel using `CompletableFuture`
2. Assert all return `201`
3. `GET /books` — assert exactly 10 books exist

**T22 — Partial update clears field with explicit null**
1. Create a book with `genre: "sci-fi"`
2. `PUT /books/{id}` with `{"genre": null}`
3. Assert `200 OK`
4. `GET /books/{id}` — assert `genre` is `null`

**T23 — ISBN-10 with X check digit is accepted**
1. `POST /books` with `isbn: "080442957X"` (valid ISBN-10 ending in X)
2. Assert `201 Created`
3. `GET /books/isbn/080442957X` — assert the book is found

**T24 — Duplicate ISBNs are allowed**
1. Create two books with the same `isbn: "9780441013593"` but different titles
2. Assert both return `201 Created`
3. `GET /books` — assert both books exist

---

## Claude Code Tips

When working with Claude Code on this project, try prompts like:

- *"Set up the Gradle project with Gson as a dependency and create the main class with a ServerSocket listening on port 8080"*
- *"Build the RequestParser class — read from InputStream, extract method, path, query string, headers, and body into an HttpRequest record"*
- *"Implement the Router. It should support registering routes with path patterns like /books/{id} and match incoming requests to handlers"*
- *"Add input validation to BookController — return 400 with a JSON error if required fields are missing or rating is out of range"*

### Useful things to ask Claude Code along the way:
- *"Explain how you're parsing the HTTP request line"*
- *"Why did you use ConcurrentHashMap instead of HashMap?"*
- *"What would break if two requests came in at the same time?"*
- *"How does a real framework like Spring do this differently?"*

---

## V2 — Book Cover Downloads via Open Library

### Overview
Automatically fetch book cover images from the **Open Library Covers API** when a book with an ISBN is added or updated. Serve the covers through your API.

The Open Library Covers API is free, requires no API key, and supports lookup by ISBN:
```
https://covers.openlibrary.org/b/isbn/{isbn}-L.jpg
```
Sizes: `S` (small), `M` (medium), `L` (large).

### Data Model Changes
| Field         | Type           | Description                                     |
|---------------|----------------|-------------------------------------------------|
| `publisher`   | String         | Publisher name (auto-filled from Open Library)  |
| `publishDate` | String         | Publication date (auto-filled from Open Library)|
| `pageCount`   | int            | Number of pages (auto-filled from Open Library) |
| `subjects`    | List\<String\> | Topic tags (auto-filled from Open Library)      |
| `coverData`   | byte[] (transient) | Cover image bytes, stored as BYTEA in DB. Not serialized to JSON |
| `coverUrl`    | String         | Original Open Library URL (for reference)       |

**API URL:** `https://openlibrary.org/api/books?bibkeys=ISBN:{isbn}&jscmd=data&format=json`

Fields are only auto-filled when the user hasn't provided them manually.

### New Endpoint
| Method | Path                  | Description               | Success Code |
|--------|-----------------------|---------------------------|--------------|
| `GET`  | `/books/{id}/cover`   | Serve the cover image     | `200 OK`     |

Returns the image bytes with `Content-Type: image/jpeg`. Returns `404` if no cover is available.

### New Components

#### `OpenLibraryService`
- Uses a single-thread `ExecutorService` to fetch data from Open Library in the background
- **Two responsibilities:** download the cover image AND fetch book metadata
- API call: `https://openlibrary.org/api/books?bibkeys=ISBN:{isbn}&jscmd=data&format=json`
- From the response, extracts: `publishers`, `publish_date`, `number_of_pages`, `subjects`, and cover URLs
- Downloads the cover image and saves to `/covers` directory as `{bookId}.jpg`
- Updates the book in the repository with all fetched fields once complete
- Open Library returns a 1x1 pixel image when no cover exists — detect this by checking file size (< 1KB = no cover)

```java
// Rough shape of the async approach
private final ExecutorService executor = Executors.newSingleThreadExecutor();

public void enrichBookAsync(UUID bookId, String isbn) {
    executor.submit(() -> {
        try {
            // 1. Fetch metadata from Books API
            BookMetadata metadata = fetchMetadata(isbn);

            // 2. Download cover image
            String coverData = downloadCover(bookId, isbn);

            // 3. Update book with all enriched data
            bookRepository.updateFromOpenLibrary(bookId, metadata, coverData);
        } catch (Exception e) {
            // Log and move on — enrichment is best-effort
        }
    });
}
```

#### `BookMetadata` (model)
- Fields: `publisher` (String), `publishDate` (String), `pageCount` (int), `subjects` (List\<String\>), `coverUrl` (String)
- Parsed from the Open Library `jscmd=data` JSON response via Gson

#### `StaticFileHandler`
- Reads a file from disk and writes the raw bytes to the socket output stream
- Sets `Content-Type: image/jpeg` and `Content-Length` headers

### Integration Points
- **On `POST /books`** — if ISBN is present, fire off `enrichBookAsync()`. Response returns immediately with user-provided fields only. Open Library data fills in shortly after.
- **On `PUT /books/{id}`** — if ISBN changed, clear all previously-enriched metadata fields (`publisher`, `publishDate`, `pageCount`, `subjects`, `coverData`, `coverUrl`) and fire off a new async enrichment. This ensures the new ISBN's data replaces the old. User-provided fields in the same PUT request are preserved (enrichment still only fills `null` fields).
- **On `DELETE /books/{id}`** — delete the cover file from disk
- **On `GET /books/{id}`** — fields like `publisher`, `pageCount`, `subjects` will be `null` until the async enrichment finishes. Clients should handle this gracefully.
- **Shutdown** — call `executor.shutdown()` when the server stops to avoid orphaned threads

### Build Order
1. Create the `/covers` directory on startup if it doesn't exist
2. Build `OpenLibraryService` with a `fetchMetadata(String isbn)` method that calls the Books API (`jscmd=data`)
3. Parse the JSON response into a `BookMetadata` object (publisher, publishDate, pageCount, subjects)
4. Add `downloadCover(UUID bookId, String isbn)` method to the same service
5. Test both standalone — pass an ISBN, confirm metadata parses and image saves to disk
6. Add `enrichBookAsync()` that combines both and updates the repository
7. Wire it into `BookController` on POST and PUT
8. Build `StaticFileHandler` to serve image bytes from disk
9. Register `GET /books/{id}/cover` route
10. Handle edge cases: no ISBN, no cover found, API failures, missing fields in response

### Integration Tests

These tests require network access to Open Library. Consider also writing unit tests for `OpenLibraryService` with mocked HTTP responses for offline testing.

**T25 — Enrichment fills in metadata**
1. `POST /books` with `title`, `author`, `isbn: "9780441013593"`, `readStatus: "READING"`
2. Assert `201 Created`
3. Poll `GET /books/{id}` every 2 seconds, up to 30 seconds
4. Assert `publisher`, `publishDate`, `pageCount`, and `subjects` are eventually non-null

**T26 — Cover image is downloaded**
1. Create a book with a valid ISBN
2. Poll `GET /books/{id}` until `coverData` is non-null
3. `GET /books/{id}/cover`
4. Assert `200 OK` with `Content-Type: image/jpeg`
5. Assert response body is more than 1KB (not the 1x1 placeholder)

**T27 — Cover endpoint returns 404 when no cover yet**
1. Create a book with a valid ISBN
2. Immediately `GET /books/{id}/cover` (before enrichment completes)
3. Assert `404` (cover not yet available)

**T28 — Book without ISBN gets no enrichment**
1. `POST /books` with no `isbn` field
2. Wait 5 seconds
3. `GET /books/{id}`
4. Assert `publisher`, `pageCount`, `subjects`, `coverData` are all still `null`

**T29 — User-provided fields are not overwritten**
1. `POST /books` with `isbn: "9780441013593"` AND `publisher: "My Custom Publisher"`
2. Poll until enrichment completes (e.g. `pageCount` becomes non-null)
3. Assert `publisher` is still `"My Custom Publisher"` (not overwritten by Open Library)

**T30 — Invalid ISBN gets no enrichment but book still exists**
1. `POST /books` with `title`, `author`, `isbn: "0000000000000"`, `readStatus: "READING"`
2. Assert `201 Created`
3. Wait 10 seconds
4. `GET /books/{id}`
5. Assert book exists with `title` and `author` intact, `publisher`/`pageCount` still `null`

**T31 — Cover deleted when book is deleted**
1. Create a book with a valid ISBN
2. Poll until `coverData` is non-null
3. `DELETE /books/{id}`
4. Assert `204 No Content`
5. `GET /books/{id}/cover` — assert `404`

**T32 — Re-enrichment on ISBN change**
1. Create a book with `isbn: "9780441013593"` (Dune)
2. Wait for enrichment to complete
3. `PUT /books/{id}` with `isbn: "9780261102354"` (Lord of the Rings)
4. Poll until `publisher` changes from the Dune publisher
5. Assert the metadata now matches the new ISBN

### Manual Test Commands
```bash
# Add a book with ISBN — metadata and cover should auto-fill
curl -X POST http://localhost:8080/books \
  -H "Content-Type: application/json" \
  -d '{"title":"Dune","author":"Frank Herbert","isbn":"9780441013593","readStatus":"READING"}'

# Wait a few seconds, then check enriched data
curl http://localhost:8080/books/{id} | python3 -m json.tool

# Fetch the cover image
curl http://localhost:8080/books/{id}/cover --output dune-cover.jpg

# Open the image to verify
open dune-cover.jpg    # macOS
xdg-open dune-cover.jpg  # Linux
```

### Things to Watch Out For
- **Route conflicts** — `/books/{id}/cover` and `/books/isbn/{isbn}` need the router to match longer/more-specific paths before shorter ones. Make sure `/books/isbn/{isbn}` doesn't get matched as `/books/{id}` with `id = "isbn"`. Route matching should prioritize static segments over parameters.
- **Async timing** — if you `GET /books/{id}` immediately after `POST`, the enriched fields might still be `null`. This is expected — the client can poll or just accept eventual consistency.
- **Missing metadata** — not every book on Open Library has all fields. `pageCount` or `subjects` might be missing. Always null-check when parsing.
- **Subjects as a list** — subjects come back as a list of objects with `name` and `url`. You only need the `name` strings. Consider storing as a JSON array string or a comma-separated string for simplicity in V1/V2 (V3 could use a proper join table).
- **User overrides** — if the user manually provides a `publisher` in their POST, don't overwrite it with Open Library data. Only fill in `null` fields.
- **1x1 pixel response** — Open Library returns a tiny placeholder instead of a 404 when no cover exists. Check the file size after download.
- **Disk cleanup** — delete cover files when books are deleted
- **Executor shutdown** — make sure `OpenLibraryService.shutdown()` is called when the server stops, or the JVM may hang

---

## V3 — Containerization & SQL Database

### Overview
Replace the in-memory `ConcurrentHashMap` with a real **PostgreSQL** database and package everything in **Docker** containers using Docker Compose. This makes the app persistent across restarts and deployable anywhere.

### Tech Additions
- **PostgreSQL** — relational database for book storage
- **HikariCP** — lightweight JDBC connection pool (one new dependency)
- **Docker** — containerize the Java application
- **Docker Compose** — orchestrate the app + database together

### Database Schema

```sql
CREATE TABLE books (
    id            UUID PRIMARY KEY,
    title         VARCHAR(255),         -- NULL allowed: V4 ISBN-only flow enriches async
    author        VARCHAR(255),         -- NULL allowed: V4 ISBN-only flow enriches async
    genre         VARCHAR(100),
    rating        INTEGER DEFAULT 0 CHECK (rating BETWEEN 0 AND 5), -- 0 = not rated
    isbn          VARCHAR(13),          -- ISBN-10 (with possible X) or ISBN-13
    publisher     VARCHAR(255),
    publish_date  VARCHAR(50),
    page_count    INTEGER,
    subjects      TEXT,                  -- JSON array string: ["Sci-fi","Space"]
    read_status   VARCHAR(20) NOT NULL,
    cover_path    VARCHAR(500),
    cover_url     VARCHAR(500),
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_books_isbn ON books(isbn);
CREATE INDEX idx_books_genre ON books(genre);
```

### Architecture Changes

#### `DatabaseConfig`
- Reads DB connection details from environment variables
- Configures HikariCP connection pool
- Runs schema migration on startup (create table if not exists)

#### `JdbcBookRepository` (new implementation of `BookRepository` interface)
- Replace `InMemoryBookRepository` with this new implementation
- Uses JDBC queries against PostgreSQL via HikariCP connection pool
- Use `PreparedStatement` for all queries to prevent SQL injection
- Methods stay the same: `findAll()`, `findById()`, `findByIsbn()`, `save()`, `update()`, `delete()`
- Add `findByGenre(String)` as a SQL query — this is when genre filtering (`?genre=`) is first available in the API
- Swap is clean because the rest of the app depends on the `BookRepository` interface

#### Environment Variables
| Variable    | Default              | Description          |
|-------------|----------------------|----------------------|
| `DB_HOST`   | `localhost`          | PostgreSQL host      |
| `DB_PORT`   | `5432`               | PostgreSQL port      |
| `DB_NAME`   | `bookshelf`          | Database name        |
| `DB_USER`   | `bookshelf`          | Database user        |
| `DB_PASS`   | `bookshelf`          | Database password    |
| `APP_PORT`  | `8080`               | Server listen port   |

### Docker Setup

#### Dockerfile
```dockerfile
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY . .
RUN ./gradlew shadowJar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/*-all.jar app.jar
RUN mkdir -p /app/covers
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
```

Key points:
- **Multi-stage build** — build with JDK, run with lighter JRE
- **Shadow JAR** (via Gradle Shadow plugin) — packages all dependencies into one fat JAR
- `/app/covers` directory for book cover images

#### docker-compose.yml
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
    volumes:
      - covers:/app/covers
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
  covers:
```

Key points:
- **Health check** — app waits for Postgres to be ready before starting
- **Named volumes** — `pgdata` persists the database, `covers` persists cover images
- Covers survive container restarts

### Gradle Additions
```groovy
plugins {
    id 'com.github.johnrengelman.shadow' version '8.1.1'
}

dependencies {
    implementation 'org.postgresql:postgresql:42.7.3'
    implementation 'com.zaxxer:HikariCP:5.1.0'
}
```

### Build Order

1. **Add Gradle Shadow plugin** — configure it to produce a fat JAR
2. **Add PostgreSQL driver and HikariCP** to dependencies
3. **Create `DatabaseConfig`** — connection pool setup, read env vars with fallback defaults
4. **Write the schema migration** — `CREATE TABLE IF NOT EXISTS` on startup
5. **Refactor `BookRepository`** — swap HashMap operations for JDBC queries, one method at a time
6. **Test locally** — run Postgres in Docker, app on host: `docker run -p 5432:5432 -e POSTGRES_DB=bookshelf -e POSTGRES_USER=bookshelf -e POSTGRES_PASSWORD=bookshelf postgres:16-alpine`
7. **Verify all endpoints still work** with `curl`
8. **Write the Dockerfile** — multi-stage build
9. **Write `docker-compose.yml`** — wire up app + db
10. **Test the full stack** — `docker compose up --build`

### Integration Tests

These tests verify that the database layer works correctly and that data persists. Run them against the Docker Compose stack.

**T33 — All V1 tests pass on PostgreSQL**
1. Run every test from T1–T24 against the Dockerized app
2. All should pass with identical behavior — the storage layer change should be transparent

**T34 — Data survives server restart**
1. `POST /books` to create a book
2. Restart just the app container: `docker compose restart app`
3. `GET /books/{id}`
4. Assert the book is still there with all fields intact

**T35 — Data survives full stack restart**
1. Create 3 books
2. `docker compose down` then `docker compose up`
3. `GET /books`
4. Assert all 3 books are still present

**T36 — Data is lost on volume cleanup**
1. Create a book
2. `docker compose down -v` (removes volumes)
3. `docker compose up`
4. `GET /books`
5. Assert empty array `[]`

**T37 — Cover files persist across restarts**
1. Create a book with ISBN, wait for cover to download
2. `docker compose restart app`
3. `GET /books/{id}/cover`
4. Assert `200 OK` with image data

**T38 — Database can be queried directly**
1. Create a book via the API
2. `docker compose exec db psql -U bookshelf -c "SELECT id, title, author FROM books;"`
3. Assert the book appears in the SQL output

**T39 — Concurrent writes don't corrupt data**
1. Send 20 `POST /books` requests in parallel
2. Assert all return `201`
3. `GET /books` — assert exactly 20 books
4. `SELECT COUNT(*) FROM books` — assert 20

**T40 — Genre filter works as a SQL query**
1. Create 50 books across 5 genres
2. `GET /books?genre=sci-fi`
3. Assert only sci-fi books returned
4. Verify via SQL: `SELECT COUNT(*) FROM books WHERE genre = 'sci-fi'` matches the API count

**T6 — Filter by genre** *(moved from V1 — genre filtering added in V3)*
1. Create 2 books with `genre: "sci-fi"` and 1 with `genre: "fantasy"`
2. `GET /books?genre=sci-fi`
3. Assert only the 2 sci-fi books are returned
4. `GET /books?genre=Sci-Fi` (different case) — assert same result (case-insensitive)

**T7 — Filter with no matches** *(moved from V1)*
1. Create a book with `genre: "sci-fi"`
2. `GET /books?genre=romance`
3. Assert `200 OK` with empty array `[]`

**T41 — Updated_at timestamp changes on PUT**
1. Create a book
2. Note the `created_at` via direct SQL
3. Wait 2 seconds
4. `PUT /books/{id}` with a changed field
5. Query SQL: assert `updated_at > created_at`

**T42 — App handles database being temporarily unavailable**
1. Start the full stack
2. Pause the database: `docker compose pause db`
3. `POST /books` — assert a `500` or meaningful error (not a hang or crash)
4. `docker compose unpause db`
5. `POST /books` — assert `201` (app recovers)

### Manual Test Commands
```bash
# Start everything
docker compose up --build

# Test from host — same curl commands as before
curl http://localhost:8080/books

curl -X POST http://localhost:8080/books \
  -H "Content-Type: application/json" \
  -d '{"title":"Dune","author":"Frank Herbert","isbn":"9780441013593","readStatus":"READING"}'

# Verify persistence — restart and data should still be there
docker compose down
docker compose up
curl http://localhost:8080/books
# → Dune should still be there

# Check the database directly
docker compose exec db psql -U bookshelf -c "SELECT * FROM books;"

# Full cleanup (removes data too)
docker compose down -v
```

### Things to Watch Out For
- **Connection handling** — always close connections/statements/resultsets in `finally` blocks or use try-with-resources
- **Startup order** — the app will crash if it tries to connect before Postgres is ready. The health check in Compose handles this, but add retry logic in `DatabaseConfig` as a safety net
- **SQL vs Java naming** — database uses `snake_case` (`read_status`), Java uses `camelCase` (`readStatus`). Map them explicitly in your repository
- **Shadow JAR conflicts** — if you hit service loader issues with HikariCP or the Postgres driver, check the Shadow plugin merge strategy
- **Cover volume** — make sure the app writes covers to `/app/covers` inside the container so the volume mount works

---

## V4 — Simple HTML Frontend

### Overview
Add a basic HTML/CSS/JS frontend served by the same Java server. The main feature is a simple ISBN input — type an ISBN, hit enter, and the book gets added to your shelf with all metadata and cover auto-filled from Open Library. The page also displays your full bookshelf.

No build tools, no npm, no React — just plain HTML, CSS, and vanilla JavaScript served as static files.

### Pages / Views

#### Main View — Bookshelf
- Header with app title and the ISBN input bar
- Grid or list of book cards showing: cover image, title, author, genre, rating (as stars), read status badge, page count, publisher
- Each card is clickable to expand or show detail
- Empty state message when no books exist yet

#### Add Book Flow
1. User types an ISBN into the input field and presses Enter (or clicks "Add")
2. Frontend sends `POST /books` with `{"isbn": "{isbn}", "readStatus": "WANT_TO_READ"}`
3. Server returns `201` immediately with the book (title/author will be `null` initially if only ISBN was provided)
4. Frontend adds a placeholder card and polls `GET /books/{id}` every 2 seconds until `publisher` or `coverData` is populated (indicating Open Library enrichment is complete)
5. Card updates in place with the full book data and cover

#### Edit / Update
- Click a book card to open an inline edit view or modal
- Edit: `title`, `author`, `genre`, `rating` (1–5 stars), `readStatus` dropdown
- Sends `PUT /books/{id}` on save

#### Delete
- Delete button on each card (with confirmation)
- Sends `DELETE /books/{id}`

#### Filter / Search
- Genre dropdown filter (uses `GET /books?genre=...`)
- Read status tabs: All | Want to Read | Reading | Finished (uses `GET /books?readStatus=...`)

### Static File Serving

#### `StaticFileHandler` (extend from V2)
- Serves files from a `/static` directory in the project
- Maps URL paths: `/` → `index.html`, `/style.css` → `style.css`, `/app.js` → `app.js`
- Sets correct `Content-Type` headers: `text/html`, `text/css`, `application/javascript`, `image/jpeg`
- Returns `404` for unknown static files
- The router should check static files AFTER API routes, so `/books` still hits the API

### File Structure
```
/static
├── index.html      — Main page structure
├── style.css       — Styling
└── app.js          — All frontend logic (fetch calls, DOM manipulation, polling)
```

### Frontend Design Notes

#### index.html
```html
<!-- Key elements -->
<header>
  <h1>📚 Bookshelf</h1>
  <div class="add-bar">
    <input type="text" id="isbn-input" placeholder="Enter ISBN to add a book..." />
    <button id="add-btn">Add</button>
  </div>
</header>

<nav class="filters">
  <button data-status="all" class="active">All</button>
  <button data-status="WANT_TO_READ">Want to Read</button>
  <button data-status="READING">Reading</button>
  <button data-status="FINISHED">Finished</button>
</nav>

<main id="book-grid">
  <!-- Book cards rendered here by JS -->
</main>
```

#### app.js — Key Functions
```javascript
// Core functions to implement:
async function addBook(isbn) { /* POST /books */ }
async function loadBooks(statusFilter, genreFilter) { /* GET /books?... */ }
async function updateBook(id, data) { /* PUT /books/{id} */ }
async function deleteBook(id) { /* DELETE /books/{id} */ }
function renderBookCard(book) { /* Create DOM card element */ }
function pollForEnrichment(bookId) { /* Poll GET /books/{id} until enriched */ }
```

#### Book Card Layout
```
┌─────────────────────────────┐
│  ┌───────┐                  │
│  │ Cover │  Title           │
│  │ Image │  Author          │
│  │       │  Publisher, Year │
│  └───────┘  Pages: 412     │
│                             │
│  Genre: Sci-Fi              │
│  Rating: ★★★★☆             │
│  Status: [Reading ▼]       │
│                    [Delete] │
└─────────────────────────────┘
```

### API Requirements
All existing endpoints from V1 are used. One small addition:

| Method | Path       | Description                       | Note |
|--------|------------|-----------------------------------|------|
| `POST` | `/books`   | Now accepts ISBN-only requests    | `title` and `author` become optional if `isbn` is provided — they'll be filled by Open Library |
| `GET`  | `/books`   | Adds `?readStatus=` query param   | Filters by read status (e.g. `?readStatus=READING`). Can combine with `?genre=` from V3 |

This means updating the validation: if `isbn` is present, `title` and `author` are no longer required (they'll be enriched async). The DB schema already allows NULL for title/author from V1.

### Build Order
1. **Create `/static` directory** and add empty `index.html`, `style.css`, `app.js`
2. **Extend `StaticFileHandler`** to serve files from `/static` and handle content types
3. **Update Router** — serve `index.html` on `GET /` and other static files on matching paths
4. **Build the HTML skeleton** — header, ISBN input, filter nav, empty book grid
5. **Style it** — clean, simple CSS. Cards in a responsive grid. Status badges with colors.
6. **Implement `loadBooks()`** — fetch all books on page load and render cards
7. **Implement `addBook(isbn)`** — POST with just ISBN, add placeholder card, start polling
8. **Implement polling** — `pollForEnrichment()` that updates the card once data arrives
9. **Implement edit** — click card to toggle edit mode, PUT on save
10. **Implement delete** — delete button with confirm dialog
11. **Add filter tabs** — filter displayed books by read status
12. **Update validation** — make `title`/`author` optional when `isbn` is provided

### Test Flow
```bash
# Start the server
./gradlew run

# Open in browser
open http://localhost:8080

# Or test the ISBN-only POST directly:
curl -X POST http://localhost:8080/books \
  -H "Content-Type: application/json" \
  -d '{"isbn":"9780441013593","readStatus":"WANT_TO_READ"}'

# After a few seconds, the book should be enriched:
curl http://localhost:8080/books
# → title, author, publisher, pageCount, subjects should all be filled in
```

### Things to Watch Out For
- **CORS** — since the frontend is served by the same server, you won't need CORS headers. But if you test from a separate server, you will.
- **Validation change** — making `title`/`author` optional when ISBN is present is a V1 behavior change. Consider this carefully — what if the ISBN isn't found on Open Library? The book will have no title. You might want to prompt the user to fill in the title manually if enrichment fails.
- **Polling cleanup** — stop polling after a timeout (e.g. 30 seconds) to avoid infinite requests if Open Library is down
- **ISBN input validation** — validate the format on the frontend before sending (10 or 13 digits). Show an inline error for invalid formats.
- **Responsive layout** — CSS grid with `auto-fill` and `minmax()` makes the card grid work on any screen size
- **No JavaScript frameworks** — keep it vanilla. This is about learning how frontend-backend integration works, not React.

---

## Stretch Goals (if you want to keep going)
- Add **pagination** (`?page=1&size=10`) with prev/next buttons in the frontend
- Add **search** (`?search=dune` searches title and author) with a search bar in the UI
- Add **request logging middleware**
- Add **authentication** (basic API key or token-based)
- Add a **subjects filter** (`GET /books?subject=science+fiction`) with clickable subject tags
- Add **sorting** (by title, author, rating, date added) with clickable column headers
- ~~Add a **barcode scanner** using the browser camera~~ *(done — uses zbar-wasm with multi-pass image processing pipeline)*
- Add **reading progress** — a percentage field for books with status `READING`
