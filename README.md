# MyBookShelf

A personal bookshelf REST API built from scratch in Java 17 using only `java.net.ServerSocket` — no Spring, no Javalin, no frameworks. The HTTP server, routing, request parsing, and response writing are all hand-built. Includes a vanilla HTML/CSS/JS frontend and automatic book enrichment via the Open Library API.

## Features

- **Framework-free HTTP server** — built on raw `ServerSocket` with a 10-thread pool
- **Full CRUD REST API** for managing books with filtering by genre and read status
- **Open Library integration** — add a book by ISBN and metadata (title, author, publisher, page count, subjects, cover image) is fetched automatically in the background
- **Cover image storage** — covers downloaded from Open Library and stored as binary data in PostgreSQL
- **ISBN barcode scanner** — scan a book's barcode with your device camera to add it instantly
- **Vanilla JS frontend** — dark antiquarian-library-themed UI with ISBN input, filter tabs, inline editing, and star ratings
- **Dockerized** — single `docker compose up` to run the app and database together
- **38 automated tests** covering the full API surface, validation, edge cases, and enrichment logic

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
| Barcode Scanning | html5-qrcode |
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

All 38 tests use an in-memory repository — no database or Docker required.

## API Reference

### Endpoints

| Method | Path | Description | Status |
|--------|------|-------------|--------|
| `GET` | `/books` | List all books | `200` |
| `GET` | `/books?genre=fiction` | Filter by genre (case-insensitive) | `200` |
| `GET` | `/books?readStatus=READING` | Filter by read status | `200` |
| `POST` | `/books` | Add a new book | `201` |
| `GET` | `/books/{id}` | Get a book by ID | `200` |
| `PUT` | `/books/{id}` | Partial update (only sent fields change) | `200` |
| `DELETE` | `/books/{id}` | Delete a book | `204` |
| `GET` | `/books/isbn/{isbn}` | Look up a book by ISBN | `200` |
| `GET` | `/books/{id}/cover` | Serve cover image | `200` |

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
| `400` | Missing required fields, invalid JSON, rating not 1-5, bad ISBN format |
| `404` | Book or cover not found |
| `405` | HTTP method not supported on this route |

## Data Model

| Field | Type | Notes |
|-------|------|-------|
| `id` | UUID | Auto-generated |
| `title` | String | Required (optional if ISBN provided) |
| `author` | String | Required (optional if ISBN provided) |
| `genre` | String | Optional |
| `rating` | Integer | 1-5 (0 = not rated) |
| `isbn` | String | 10 or 13 digits |
| `readStatus` | Enum | `WANT_TO_READ`, `READING`, `FINISHED` |
| `publisher` | String | Auto-filled from Open Library |
| `publishDate` | String | Auto-filled from Open Library |
| `pageCount` | Integer | Auto-filled from Open Library |
| `subjects` | List | Auto-filled from Open Library |
| `coverUrl` | String | Open Library cover URL |

## Architecture

The project is built in four progressive versions, each adding a layer:

```
V1  Core HTTP server + REST API + in-memory storage
V2  Open Library integration (async enrichment + cover downloads)
V3  PostgreSQL persistence + Docker Compose
V4  Vanilla HTML/CSS/JS frontend
```

### Project Structure

```
src/main/java/com/bookshelf/
├── App.java                    # Entry point
├── HttpServer.java             # ServerSocket listener + thread pool
├── RequestParser.java          # Raw HTTP request parsing
├── HttpRequest.java            # Request model
├── HttpResponse.java           # Response model
├── ResponseWriter.java         # HTTP response writing
├── Router.java                 # Route matching with path params
├── BookController.java         # API endpoint handlers
├── Book.java                   # Book model
├── ReadStatus.java             # WANT_TO_READ / READING / FINISHED
├── BookRepository.java         # Repository interface
├── InMemoryBookRepository.java # ConcurrentHashMap implementation
├── JdbcBookRepository.java     # PostgreSQL implementation
├── DatabaseConfig.java         # HikariCP pool + schema migration
├── OpenLibraryService.java     # Async ISBN enrichment
├── BookMetadata.java           # Enrichment data model
└── StaticFileHandler.java      # Static file serving

src/test/java/com/bookshelf/
├── BookApiTest.java            # 30 tests — CRUD, filtering, validation, edge cases
└── OpenLibraryTest.java        # 8 tests — enrichment, covers, re-enrichment

static/
├── index.html                  # Frontend page
├── style.css                   # Dark antiquarian theme
├── app.js                      # ISBN input, polling, inline editing, barcode scanner
└── lib/
    └── html5-qrcode.min.js     # Vendored barcode scanning library
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

## Testing

```bash
# Run all 38 tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.bookshelf.BookApiTest"

# Run a single test method
./gradlew test --tests "com.bookshelf.BookApiTest.testT01_createAndRetrieveBook"
```

Tests start the server on a random port using `new ServerSocket(0)` and use `InMemoryBookRepository` — no database or Docker required.

**Test coverage includes:**
- CRUD operations (create, read, update, delete)
- ISBN lookup and duplicate handling
- Genre and read status filtering
- Input validation (missing fields, bad JSON, invalid rating/ISBN)
- HTTP method restrictions (405)
- Edge cases (concurrent creates, null field clearing, ISBN-10 with trailing X)
- Open Library enrichment (metadata, covers, re-enrichment on ISBN change)
- User-provided fields preserved during enrichment

## License

This project is for personal use and learning purposes.
