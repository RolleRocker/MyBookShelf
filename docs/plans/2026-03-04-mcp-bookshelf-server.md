# MCP Bookshelf Server

**Status:** Not started
**Date:** 2026-03-04

## Overview

Add an [MCP (Model Context Protocol)](https://modelcontextprotocol.io) server so Claude can answer natural-language questions about your bookshelf — "Do I have Dune?", "Show me everything I'm reading", "Have I read any Frank Herbert?". The MCP server is a Java process (same project, new main class) that uses the official MCP Java SDK to expose tools. Each tool calls the existing bookshelf REST API over HTTP. Claude Code discovers and launches it automatically via `.mcp.json`.

No new language, no new toolchain — just Java, Gson, and the MCP SDK added to `build.gradle`.

---

## Architecture

```
User asks Claude ──► Claude picks an MCP tool ──► BookshelfMcpServer (Java, stdio)
                                                         │
                                              java.net.http.HttpClient
                                                         │
                                                         ▼
                                              GET /books?search=...
                                              GET /books/isbn/{isbn}
                                              GET /books?readStatus=...
                                                         │
                                                         ▼
                                              Java Bookshelf REST API
                                                  (localhost:8080)
```

**Transport:** The MCP Java SDK communicates over **stdio** (stdin/stdout). Claude Code launches the MCP server as a subprocess and speaks the MCP JSON-RPC protocol over those streams. No extra port needed.

**HTTP client:** `java.net.http.HttpClient` (built into Java 17 — no new dependency).

**JSON:** Gson (already in `build.gradle`).

---

## MCP Tools

| Tool | Description | Key Inputs |
|------|-------------|------------|
| `check_book` | Check if a specific book is on the shelf | `title` (required), `author` (optional), `isbn` (optional) |
| `search_books` | Full-text search across title and author | `query` |
| `list_books` | List books with optional status/genre filter | `readStatus` (optional), `genre` (optional) |
| `get_book_by_isbn` | Look up a book by ISBN | `isbn` |
| `get_bookshelf_stats` | Summary counts by read status + average rating | — |

### Tool Behaviour

#### `check_book`
- If `isbn` provided: call `GET /books/isbn/{isbn}`. Return book or "not found."
- Otherwise: call `GET /books?search={title}`. Filter results by author substring if `author` provided.
- Returns full book details (title, author, readStatus, rating, genre, ISBN) or a clear not-found message.

#### `search_books`
- Calls `GET /books?search={query}`.
- Returns a bulleted list: `• {title} by {author} — {readStatus}, rated {rating}/5`.
- If empty: "No books found matching '{query}'."

#### `list_books`
- Calls `GET /books` with `readStatus` and/or `genre` query params.
- If both provided: applies `readStatus` as a post-filter client-side (matches server behaviour).
- Returns a numbered list. If no filter: groups by status.

#### `get_book_by_isbn`
- Calls `GET /books/isbn/{isbn}`.
- Returns book details or a not-found message.

#### `get_bookshelf_stats`
- Calls `GET /books` (all books).
- Computes counts per `readStatus`, total, and average rating across rated books.
- Returns a human-readable summary paragraph.

---

## File Structure

New files only — no existing files changed except `build.gradle`:

```
build.gradle                                  ← add mcp dependency + mcpJar task
src/main/java/com/bookshelf/mcp/
├── BookshelfMcpServer.java                   ← main class; wires MCP server + registers tools
├── BookshelfClient.java                      ← thin HTTP client wrapping java.net.http.HttpClient
└── BookshelfToolHandler.java                 ← one method per MCP tool; formats responses
```

---

## Dependencies

Add to `build.gradle`:

```groovy
// MCP Java SDK (stdio transport)
implementation 'io.modelcontextprotocol.sdk:mcp:0.10.0'

// Spring Reactor (required by MCP SDK's async model) — no Spring Boot, just reactor-core
implementation 'io.projectreactor:reactor-core:3.6.6'
```

Add a second Shadow JAR task so the MCP server can be launched independently:

```groovy
tasks.register('mcpJar', com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar) {
    archiveClassifier = 'mcp'
    from sourceSets.main.output
    configurations = [project.configurations.runtimeClasspath]
    manifest {
        attributes 'Main-Class': 'com.bookshelf.mcp.BookshelfMcpServer'
    }
}
```

Build with: `./gradlew mcpJar` → produces `build/libs/MyBookShelf-*-mcp.jar`.

---

## Implementation Plan

### Step 1 — Add dependencies and build task
- Add `mcp` and `reactor-core` to `build.gradle` `dependencies` block.
- Add the `mcpJar` Shadow JAR task (separate from the existing `shadowJar` task, which keeps its `App` main class).
- Verify: `./gradlew mcpJar` builds without error.

### Step 2 — `BookshelfClient.java`
- Holds a single `java.net.http.HttpClient` instance.
- Configurable base URL via `BOOKSHELF_URL` env var (default: `http://localhost:8080`).
- Methods:
  - `List<JsonObject> searchBooks(String query)` → `GET /books?search={query}`
  - `List<JsonObject> listBooks(String readStatus, String genre)` → `GET /books` with optional params
  - `Optional<JsonObject> getByIsbn(String isbn)` → `GET /books/isbn/{isbn}` (returns empty on 404)
- All methods return Gson `JsonObject`s parsed from the response body.
- On connection refused: throw a `BookshelfUnavailableException` with a user-friendly message.

### Step 3 — `BookshelfToolHandler.java`
One static method per tool; each takes a `Map<String, Object>` of arguments (from the MCP SDK) and returns a `String` result.

- `checkBook(Map args, BookshelfClient client)` — isbn branch or search branch; formats result.
- `searchBooks(Map args, BookshelfClient client)` — bulleted list or no-results message.
- `listBooks(Map args, BookshelfClient client)` — numbered list with optional grouping.
- `getBookByIsbn(Map args, BookshelfClient client)` — single book or not-found.
- `getBookshelfStats(BookshelfClient client)` — counts + average rating summary.

All methods catch `BookshelfUnavailableException` and return: `"The bookshelf server is not running. Start it with ./gradlew run"`.

### Step 4 — `BookshelfMcpServer.java`
- Entry point (`public static void main`).
- Instantiates `BookshelfClient`.
- Uses the MCP Java SDK to build a stdio server:
  ```java
  McpServer.using(new StdioServerTransport())
      .serverInfo("bookshelf", "1.0.0")
      .tool(checkBookTool, (exchange, args) -> BookshelfToolHandler.checkBook(args, client))
      .tool(searchBooksTool, ...)
      .tool(listBooksTool, ...)
      .tool(getBookByIsbnTool, ...)
      .tool(getBookshelfStatsTool, ...)
      .serve();
  ```
- Each `Tool` is defined with a name, description, and JSON Schema for its input parameters.
- `serve()` blocks until the parent process (Claude Code) closes the stream.

### Step 5 — Tool input schemas
Define JSON Schema for each tool's inputs as a `Map` or a schema string. Key schemas:

- `check_book`: `{ title: string (required), author: string, isbn: string }`
- `search_books`: `{ query: string (required) }`
- `list_books`: `{ readStatus: string (enum: WANT_TO_READ, READING, FINISHED, DNF), genre: string }`
- `get_book_by_isbn`: `{ isbn: string (required) }`
- `get_bookshelf_stats`: `{}` (no inputs)

### Step 6 — Claude Code integration
Create `.mcp.json` in the project root (this file is read automatically by Claude Code):

```json
{
  "mcpServers": {
    "bookshelf": {
      "command": "java",
      "args": ["-jar", "build/libs/MyBookShelf-1.0-mcp.jar"],
      "env": {
        "BOOKSHELF_URL": "http://localhost:8080"
      }
    }
  }
}
```

Claude Code will launch this subprocess on startup and restart it if it crashes.

### Step 7 — Verify end-to-end
- Start the bookshelf server: `./gradlew run`
- Build the MCP jar: `./gradlew mcpJar`
- Restart Claude Code (picks up `.mcp.json`)
- Ask: "Do I have Dune?" → Claude calls `check_book`, returns result.

---

## Example Interactions

```
User:  Do I have Dune on my shelf?
Claude: [calls check_book(title="Dune")]
        Yes! You have Dune by Frank Herbert — FINISHED, rated 5/5. Genre: Science Fiction.

User:  What am I currently reading?
Claude: [calls list_books(readStatus="READING")]
        You're currently reading:
        1. The Name of the Wind by Patrick Rothfuss
        2. Project Hail Mary by Andy Weir (progress: 52%)

User:  How many books do I have?
Claude: [calls get_bookshelf_stats()]
        Your shelf has 47 books total:
        • 12 finished (avg rating 4.1/5)
        • 3 reading
        • 28 want to read
        • 4 DNF
```

---

## Constraints & Notes

- **Read-only** — No tools for create, update, or delete. Keeps the conversational interface safe and simple.
- **Java server must be running** — The MCP server calls the REST API; it doesn't connect to the DB directly.
- **Java 17+** — Required (matches the existing project).
- **`reactor-core` is a transitive requirement of the MCP SDK** — it pulls in Project Reactor for async handling, but no reactive programming patterns are needed in the tool implementations; tool methods are plain synchronous Java.
- **No changes to existing source files** — `App.java`, `BookController.java`, etc. are untouched.
- **`.mcp.json` is gitignored by default in Claude Code** — consider committing it so the project is ready to use out of the box.
