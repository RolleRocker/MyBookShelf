# MyBookShelf

A personal bookshelf REST API built from scratch in Java 17 using only `java.net.ServerSocket` — no Spring, no Javalin, no frameworks. The HTTP server, routing, request parsing, and response writing are all hand-built. Includes a vanilla HTML/CSS/JS frontend and automatic book enrichment via the Open Library API.

## Features

- **Framework-free HTTP server** — built on raw `ServerSocket` with a 10-thread pool
- **Full CRUD REST API** for managing books with filtering by genre, read status, and full-text search
- **Open Library + Google Books integration** — add a book by ISBN and metadata (title, author, publisher, page count, subjects, cover image) is fetched automatically in the background. Falls back to Google Books API if Open Library returns no data
- **Cover image storage** — covers downloaded from Open Library and stored as binary data in PostgreSQL
- **ISBN barcode scanner** — scan a book's barcode with your device camera to add it instantly
- **Custom shelves / collections** — create named shelves with colors, descriptions, and notes; drag-and-drop books between shelves; per-shelf sorting and stats
- **Vanilla JS frontend** — dark antiquarian-library-themed UI with ISBN input, filter tabs, sort dropdown, search bar, inline editing, star ratings, and a sidebar for shelf management
- **Half-star ratings** — rate books from 0.5 to 5.0 in 0.5 increments with Letterboxd-style hover interaction
- **Reading progress** — track a percentage (0-100) for books currently being read
- **Start/finish dates** — track when you started and finished reading each book (YYYY-MM-DD)
- **Yearly reading goals** — set a target number of books per year with live progress tracking, pace calculations, and on-track indicators
- **Book reviews/notes** — add personal notes or reviews to any book
- **MCP integration** — query your bookshelf from Claude Code via natural language ("Do I have Dune?", "What am I reading?")
- **Dockerized** — single `docker compose up` to run the app and database together
- **Authentication** — hand-built JWT (HMAC-SHA256) with `iss`/`aud` claims and `alg` header validation + PBKDF2 password hashing; optional Google OAuth (Sign in with Google). All API data routes require auth; only health, auth config, and static files are public
- **Reading statistics** — total books, by-status/by-genre breakdowns, average rating, pages read, finished-by-month, top authors
- **CSV import/export** — export your library as CSV, import from MyBookShelf or Goodreads format
- **Subject filtering & pagination** — `?subject=` query param, backward-compatible paginated responses
- **Security hardening** — security headers (CSP, X-Frame-Options, nosniff), SSRF allowlist for image downloads, Transfer-Encoding rejection, CRLF sanitization, input length limits, CSV formula injection defense
- **Hexagonal architecture** — domain layer has zero infrastructure dependencies; outbound ports for metadata fetching and token management
- **327 automated tests** covering the full API surface, shelves, goals, MCP protocol, auth, validation, edge cases, corner cases, and enrichment logic

## Tech Stack

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
| Book Enrichment | Open Library API + Google Books API (fallback) |
| Tests | JUnit 5 + `java.net.HttpClient` |

## Getting Started

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

### Run from source (development)

This runs the server with an in-memory store (no database needed):

```bash
./gradlew run
```

### Run tests

```bash
./gradlew test
```

All 327 unit tests use an in-memory repository — no database or Docker required. 11 integration tests (Open Library) are excluded from the default run.

## API Reference

### Endpoints

| Method | Path | Description | Status |
|--------|------|-------------|--------|
| `GET` | `/books` | List all books. Supports `?genre=`, `?readStatus=`, `?search=`, `?subject=`, `?sort=`, `?page=`, `?size=` | `200` |
| `POST` | `/books` | Add a new book | `201` |
| `GET` | `/books/{id}` | Get a book by ID | `200` |
| `PUT` | `/books/{id}` | Partial update (only sent fields change) | `200` |
| `DELETE` | `/books/{id}` | Delete a book | `204` |
| `GET` | `/books/isbn/{isbn}` | Look up a book by ISBN | `200` |
| `POST` | `/books/re-enrich` | Re-enrich all ISBN books from Open Library | `202` |
| `GET` | `/books/stats` | Reading statistics (totals, averages, distributions) | `200` |
| `GET` | `/books/export` | Export all books as CSV | `200` |
| `POST` | `/books/import` | Import books from CSV (MyBookShelf or Goodreads format) | `200` |
| `GET` | `/books/{id}/cover` | Serve cover image | `200` |
| `GET` | `/shelves` | List all shelves (with book counts and cover IDs) | `200` |
| `POST` | `/shelves` | Create a new shelf | `201` |
| `GET` | `/shelves/{id}` | Get a shelf with its books and stats | `200` |
| `PUT` | `/shelves/{id}` | Update a shelf | `200` |
| `DELETE` | `/shelves/{id}` | Delete a shelf | `204` |
| `PUT` | `/shelves/reorder` | Reorder shelves by position | `200` |
| `POST` | `/shelves/{id}/books` | Add a book to a shelf | `201` |
| `PUT` | `/shelves/{id}/books/reorder` | Reorder books within a shelf | `200` |
| `DELETE` | `/shelves/{id}/books/{bookId}` | Remove a book from a shelf | `204` |
| `GET` | `/goals` | List all reading goals (with progress) | `200` |
| `POST` | `/goals` | Create a new reading goal | `201` |
| `GET` | `/goals/{year}` | Get a specific year's goal with progress | `200` |
| `PUT` | `/goals/{year}` | Update goal target | `200` |
| `DELETE` | `/goals/{year}` | Delete a reading goal | `204` |
| `POST` | `/auth/register` | Register a new user (returns JWT) | `201` |
| `POST` | `/auth/login` | Login with credentials (returns JWT) | `200` |
| `POST` | `/auth/google` | Login with Google ID token | `200`/`201` |
| `GET` | `/auth/config` | Returns `{googleClientId}` for frontend | `200` |
| `POST` | `/mcp` | MCP Streamable HTTP endpoint (JSON-RPC) | `200` |

### Create a book

```bash
curl -X POST http://localhost:8080/books \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Dune",
    "author": "Frank Herbert",
    "genre": "Science Fiction",
    "isbn": "9780441013593",
    "readStatus": "READING"
  }'
```

If an ISBN is provided, Open Library enrichment runs in the background — publisher, page count, subjects, and cover image are filled in automatically.

### Add by ISBN only

```bash
curl -X POST http://localhost:8080/books \
  -H "Content-Type: application/json" \
  -d '{
    "isbn": "9780441013593",
    "readStatus": "WANT_TO_READ"
  }'
```

Title and author are optional when ISBN is provided. They will be fetched from Open Library.

### Partial update

```bash
curl -X PUT http://localhost:8080/books/{id} \
  -H "Content-Type: application/json" \
  -d '{"rating": 5, "readStatus": "FINISHED"}'
```

Only fields present in the request body are updated. Send `null` to clear a field.

### Error responses

| Code | Meaning |
|------|---------|
| `400` | Missing required fields, invalid JSON, rating not 0.5-5.0, bad ISBN format, invalid dates |
| `401` | Missing or invalid JWT token on protected endpoint |
| `404` | Book, cover, or goal not found |
| `405` | HTTP method not supported on this route |
| `409` | Duplicate reading goal for a year, duplicate username |

## Data Model

| Field | Type | Notes |
|-------|------|-------|
| `id` | UUID | Auto-generated |
| `title` | String | Required (optional if ISBN provided) |
| `author` | String | Required (optional if ISBN provided) |
| `genre` | String | Optional |
| `rating` | Number | 0.5-5.0 in 0.5 increments (0 = not rated) |
| `isbn` | String | 10 or 13 digits |
| `readStatus` | Enum | `WANT_TO_READ`, `READING`, `FINISHED`, `DNF` |
| `readingProgress` | Integer | 0-100, only for `READING` status |
| `publisher` | String | Auto-filled from Open Library |
| `publishDate` | String | Auto-filled from Open Library |
| `pageCount` | Integer | Auto-filled from Open Library |
| `subjects` | List | Auto-filled from Open Library |
| `review` | String | Personal notes/review |
| `startedAt` | String | Start date (YYYY-MM-DD) |
| `finishedAt` | String | Finish date (YYYY-MM-DD) |
| `coverUrl` | String | Open Library cover URL |

## Architecture

The project follows **hexagonal architecture** (ports & adapters). The domain layer has zero dependencies on infrastructure — all external concerns are behind interfaces.

```
domain/          Pure business logic, entities, and port interfaces (zero framework imports)
adapter/in/      Inbound adapters: HTTP controllers, MCP endpoint
adapter/out/     Outbound adapters: persistence, enrichment, auth
framework/       Hand-built HTTP infrastructure (ServerSocket, Router, middleware)
```

### Project Structure

```
src/main/java/com/bookshelf/
├── App.java                              # Composition root; wires all layers
├── domain/
│   ├── model/                            # Entities
│   │   ├── Book.java, ReadStatus.java, Shelf.java
│   │   ├── ReadingGoal.java, User.java, BookMetadata.java
│   ├── port/out/                         # Outbound port interfaces
│   │   ├── BookRepository.java, ShelfRepository.java
│   │   ├── GoalRepository.java, UserRepository.java
│   │   ├── BookMetadataFetcher.java      # ISBN → metadata/cover
│   │   └── TokenService.java            # JWT creation/validation
│   └── exception/
│       ├── DuplicateGoalException.java
│       └── DuplicateUserException.java
├── adapter/
│   ├── in/http/                          # Inbound HTTP adapters
│   │   ├── BookController.java, ShelfController.java
│   │   ├── GoalController.java, AuthController.java
│   ├── in/mcp/                           # Inbound MCP adapter
│   │   ├── McpController.java, McpToolHandler.java
│   ├── out/persistence/                  # Repository implementations
│   │   ├── InMemory*Repository.java      # In-memory (tests)
│   │   ├── Jdbc*Repository.java          # PostgreSQL (production)
│   │   └── DatabaseConfig.java           # HikariCP + migrations
│   ├── out/enrichment/                   # Book metadata fetching
│   │   ├── OpenLibraryClient.java        # Implements BookMetadataFetcher
│   │   └── BookEnrichmentService.java    # Async orchestration
│   └── out/auth/                         # Auth implementations
│       ├── JwtUtil.java                  # Implements TokenService
│       ├── PasswordUtil.java, GoogleTokenVerifier.java
└── framework/http/                       # Hand-built HTTP layer
    ├── HttpServer.java, Router.java
    ├── HttpRequest.java, HttpResponse.java
    ├── RequestParser.java, ResponseWriter.java
    ├── StaticFileHandler.java, RequestLogger.java
    ├── AuthMiddleware.java               # Depends on TokenService port
    └── RequestTooLargeException.java

src/test/java/com/bookshelf/
├── BookApiTest.java            # 141 tests — CRUD, filtering, search, sorting, ratings, dates, pagination, stats, CSV, field limits, HTTP parsing
├── BookMetadataTest.java       # 43 tests — metadata parsing, Google Books, enrichment logic
├── ShelfApiTest.java           # 63 tests — shelf CRUD, book assignment, reordering, stats, validation
├── McpTest.java                # 21 tests — MCP JSON-RPC protocol and tool implementations
├── GoalApiTest.java            # 17 tests — reading goal CRUD, progress, validation, year boundaries
├── AuthApiTest.java            # 19 tests — register, login, JWT auth, protected endpoints, input boundaries
├── GoogleAuthApiTest.java      # 11 tests — Google OAuth with test RSA key pair
├── JwtUtilTest.java            # 8 tests — JWT roundtrip, tampered tokens, alg:none bypass
├── PasswordUtilTest.java       # 4 tests — hash roundtrip, salt uniqueness
└── OpenLibraryTest.java        # 11 integration tests — live Open Library API (excluded from default run)

static/
├── index.html                  # Frontend page
├── style.css                   # Dark antiquarian theme
├── app.js                      # ISBN input, polling, inline editing, barcode scanner, shelves
└── lib/
    └── zbar-wasm.js            # Vendored barcode scanning library (WebAssembly)
```

### Key Design Decisions

- **No frameworks** — the HTTP layer is built from `ServerSocket` up, as a learning exercise
- **PUT = partial update** — only fields in the request body are changed; missing fields are left alone
- **Enrichment is best-effort** — POST returns immediately; metadata arrives asynchronously
- **User data wins** — Open Library enrichment only fills `null` fields, never overwrites user-provided values
- **Covers in the database** — stored as `BYTEA` in PostgreSQL, not on the filesystem
- **Duplicate ISBNs allowed** — a user may own multiple copies; `findByIsbn` returns the oldest

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_NAME` | `bookshelf` | Database name |
| `DB_USER` | `bookshelf` | Database user |
| `DB_PASS` | `bookshelf` | Database password |
| `APP_PORT` | `8080` | Server listen port |
| `GOOGLE_BOOKS_API_KEY` | *(none)* | Optional — raises Google Books API quota |
| `JWT_SECRET` | *(random)* | HMAC-SHA256 secret for JWT tokens. Random if not set (tokens won't survive restarts) |
| `GOOGLE_CLIENT_ID` | *(none)* | Google OAuth client ID. Enables "Sign in with Google" on login page |

## Testing

```bash
# Run all unit tests (327 tests)
./gradlew test

# Run integration tests (11 tests, requires network)
./gradlew integrationTest

# Run a single test class
./gradlew test --tests "com.bookshelf.BookApiTest"

# Run a single test method
./gradlew test --tests "com.bookshelf.BookApiTest.testT01_createAndRetrieveBook"
```

Tests start the server on a random port using `new ServerSocket(0)` and use `InMemoryBookRepository` — no database or Docker required.

**Test coverage includes:**
- CRUD operations (create, read, update, delete) for books and shelves
- ISBN lookup and duplicate handling
- Genre and read status filtering, search, subject filtering, sorting, pagination
- Half-star ratings (0.5–5.0 in 0.5 increments)
- Start/finish date tracking and auto-fill on status change
- Reading statistics and CSV import/export
- Reading goals — CRUD, progress computation, pace tracking
- Custom shelves — CRUD, book assignment, reordering, stats, edge cases
- Authentication — register, login, JWT validation, protected endpoints, Google OAuth
- MCP JSON-RPC protocol and all 5 tool implementations
- Input validation (missing fields, bad JSON, invalid rating/ISBN, field length limits, whitespace-only rejection)
- HTTP method restrictions (405)
- HTTP parsing edge cases (Transfer-Encoding rejection, unsupported HTTP versions)
- Edge cases (concurrent creates, null field clearing, ISBN-10 with trailing X, empty subjects, pagination boundaries, CSV formula injection)
- Open Library enrichment (metadata, covers, re-enrichment on ISBN change)
- Google Books fallback enrichment and response parsing
- User-provided fields preserved during enrichment

## Claude MCP Integration

MyBookShelf exposes an MCP (Model Context Protocol) endpoint at `POST /mcp` using the Streamable HTTP transport. This lets Claude Code query your bookshelf via natural language — "Do I have Dune?", "What am I currently reading?", "Show me all my sci-fi books."

### Prerequisites

- The bookshelf server must be running (`./gradlew run` or `docker compose up --build -d`)

### Configuration

The project includes a `.mcp.json` that Claude Code reads automatically:

```json
{
  "mcpServers": {
    "bookshelf": {
      "type": "http",
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

If your server runs on a different port or host, update the URL accordingly.

### Available Tools

| Tool | What you can ask |
|------|-----------------|
| `check_book` | "Do I have Dune?" / "Do I own anything by Le Guin?" |
| `search_books` | "Which Herbert books are on my shelf?" |
| `list_books` | "What am I currently reading?" / "Show me my fantasy books" |
| `get_book_by_isbn` | "What's the book with ISBN 9780441013593?" |
| `get_bookshelf_stats` | "How many books do I have?" / "Give me a summary of my shelf" |

## License

This project is for personal use and learning purposes.
