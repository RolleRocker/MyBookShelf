# Hexagonal Architecture Restructuring

## Context

The MyBookShelf project has 42 Java files in a flat `com.bookshelf` package (plus 2 in `com.bookshelf.mcp`). As the project grew through V1-V4 with auth, shelves, goals, MCP, and enrichment, the single-package structure became hard to navigate. This restructuring moves to a true hexagonal (ports & adapters) architecture where the domain has zero dependencies on infrastructure.

## Target Package Structure

```
com.bookshelf
├── App.java                              # Composition root
│
├── domain/
│   ├── model/
│   │   ├── Book.java
│   │   ├── ReadStatus.java
│   │   ├── Shelf.java
│   │   ├── ReadingGoal.java
│   │   ├── User.java
│   │   └── BookMetadata.java
│   ├── port/
│   │   └── out/
│   │       ├── BookRepository.java
│   │       ├── ShelfRepository.java
│   │       ├── GoalRepository.java
│   │       ├── UserRepository.java
│   │       ├── BookMetadataFetcher.java    # NEW
│   │       └── TokenService.java           # NEW
│   └── exception/
│       ├── DuplicateGoalException.java
│       └── DuplicateUserException.java
│
├── adapter/
│   ├── in/
│   │   ├── http/
│   │   │   ├── BookController.java
│   │   │   ├── ShelfController.java
│   │   │   ├── GoalController.java
│   │   │   └── AuthController.java
│   │   └── mcp/
│   │       ├── McpController.java
│   │       └── McpToolHandler.java
│   └── out/
│       ├── persistence/
│       │   ├── InMemoryBookRepository.java
│       │   ├── InMemoryShelfRepository.java
│       │   ├── InMemoryGoalRepository.java
│       │   ├── InMemoryUserRepository.java
│       │   ├── JdbcBookRepository.java
│       │   ├── JdbcShelfRepository.java
│       │   ├── JdbcGoalRepository.java
│       │   ├── JdbcUserRepository.java
│       │   └── DatabaseConfig.java
│       ├── enrichment/
│       │   ├── BookEnrichmentService.java
│       │   └── OpenLibraryClient.java      # NEW
│       └── auth/
│           ├── JwtUtil.java
│           ├── PasswordUtil.java
│           └── GoogleTokenVerifier.java
│
└── framework/
    └── http/
        ├── HttpServer.java
        ├── HttpRequest.java
        ├── HttpResponse.java
        ├── RequestParser.java
        ├── ResponseWriter.java
        ├── Router.java
        ├── AuthMiddleware.java
        ├── StaticFileHandler.java
        ├── RequestLogger.java
        └── RequestTooLargeException.java
```

## Dependency Rules

- **`domain`** depends on NOTHING — pure Java, no imports from adapter/framework
- **`adapter`** depends on `domain` (ports) and `framework` (HTTP types)
- **`framework`** depends on `domain` only for `AuthMiddleware` → `TokenService`
- **`App.java`** sits at the top and wires everything — depends on all layers

## New Interfaces

### `BookMetadataFetcher` — `domain.port.out`

```java
package com.bookshelf.domain.port.out;

import com.bookshelf.domain.model.BookMetadata;

public interface BookMetadataFetcher {
    /** Fetch metadata from Open Library, falling back to Google Books. Merges results. */
    BookMetadata fetchByIsbn(String isbn);

    /** Download cover image by ISBN from Open Library. Returns null if unavailable. */
    byte[] fetchCoverByIsbn(String isbn);

    /** Download cover image from an arbitrary URL (e.g. Google Books thumbnail). */
    byte[] fetchCoverByUrl(String url);
}
```

The third method (`fetchCoverByUrl`) is needed because the cover fallback path in `enrichBookAsync` first tries Open Library covers, then falls back to the `coverUrl` from metadata (often a Google Books thumbnail). Without this method, `BookEnrichmentService` would need its own `HttpClient` just for that one download.

### `TokenService` — `domain.port.out`

```java
package com.bookshelf.domain.port.out;

import java.util.UUID;

public interface TokenService {
    String createToken(UUID userId, String username);
    UUID validateToken(String token);
}
```

## New Class: `OpenLibraryClient`

Extracted from `BookEnrichmentService`. Lives in `adapter.out.enrichment`, implements `BookMetadataFetcher`.

Contains:
- `fetchByIsbn(isbn)` — calls Open Library API, falls back to Google Books, merges results
- `fetchCoverByIsbn(isbn)` — downloads cover from Open Library, falls back to coverUrl from metadata
- `fetchMetadata(isbn)` — Open Library API call + JSON parsing (private)
- `fetchGoogleBooksMetadata(isbn)` — Google Books API call + JSON parsing (private)
- `parseGoogleBooksResponse(jsonBody)` — JSON → BookMetadata (**public**, tested by `BookMetadataTest`)
- `downloadCover(isbn)` — cover image download with 1x1 pixel detection (private)
- `downloadImageBytes(url)` — generic image download (private)
- `mergeMetadata(primary, fallback)` — merge two BookMetadata objects (static, public)
- Owns the `HttpClient`, `userAgent`, and `googleBooksApiKey` fields

## Refactored: `BookEnrichmentService`

After extraction, becomes thinner:
- Constructor takes `BookRepository` + `BookMetadataFetcher` (instead of just `BookRepository`)
- `enrichBookAsync(bookId, isbn)` — delegates fetching to `BookMetadataFetcher`, handles async execution
- `reEnrichAll(books)` — async orchestration with sorting and 3-second rate limiting between books
- `shutdown()` — executor cleanup
- Owns the `ExecutorService` for async execution

## Refactored: `JwtUtil`

```java
public class JwtUtil implements TokenService {
    // All existing code unchanged — createToken() and validateToken() already match the interface
}
```

## Refactored: `AuthMiddleware`

```java
// Before:
private final JwtUtil jwtUtil;
public AuthMiddleware(JwtUtil jwtUtil) { ... }

// After:
private final TokenService tokenService;
public AuthMiddleware(TokenService tokenService) { ... }
// authenticate() calls tokenService.validateToken() instead of jwtUtil.validateToken()
```

## Refactored: `AuthController`

```java
// Before:
private final JwtUtil jwtUtil;
public AuthController(UserRepository userRepository, JwtUtil jwtUtil, ...) { ... }

// After:
private final TokenService tokenService;
public AuthController(UserRepository userRepository, TokenService tokenService, ...) { ... }
// handleRegister/handleLogin/handleGoogleLogin call tokenService.createToken() instead of jwtUtil.createToken()
```

## Complete File Move Map

| # | File | From Package | To Package | Logic Changes |
|---|------|-------------|-----------|---------------|
| 1 | `Book.java` | `com.bookshelf` | `domain.model` | None |
| 2 | `ReadStatus.java` | `com.bookshelf` | `domain.model` | None |
| 3 | `Shelf.java` | `com.bookshelf` | `domain.model` | None |
| 4 | `ReadingGoal.java` | `com.bookshelf` | `domain.model` | None |
| 5 | `User.java` | `com.bookshelf` | `domain.model` | None |
| 6 | `BookMetadata.java` | `com.bookshelf` | `domain.model` | None |
| 7 | `BookRepository.java` | `com.bookshelf` | `domain.port.out` | None |
| 8 | `ShelfRepository.java` | `com.bookshelf` | `domain.port.out` | None |
| 9 | `GoalRepository.java` | `com.bookshelf` | `domain.port.out` | None |
| 10 | `UserRepository.java` | `com.bookshelf` | `domain.port.out` | None |
| 11 | `DuplicateGoalException.java` | `com.bookshelf` | `domain.exception` | None |
| 12 | `DuplicateUserException.java` | `com.bookshelf` | `domain.exception` | None |
| 13 | `BookController.java` | `com.bookshelf` | `adapter.in.http` | Imports only |
| 14 | `ShelfController.java` | `com.bookshelf` | `adapter.in.http` | Imports only |
| 15 | `GoalController.java` | `com.bookshelf` | `adapter.in.http` | Imports only |
| 16 | `AuthController.java` | `com.bookshelf` | `adapter.in.http` | `JwtUtil` → `TokenService` |
| 17 | `McpController.java` | `com.bookshelf.mcp` | `adapter.in.mcp` | Imports only |
| 18 | `McpToolHandler.java` | `com.bookshelf.mcp` | `adapter.in.mcp` | Imports only |
| 19 | `InMemoryBookRepository.java` | `com.bookshelf` | `adapter.out.persistence` | Imports only |
| 20 | `InMemoryShelfRepository.java` | `com.bookshelf` | `adapter.out.persistence` | Imports only |
| 21 | `InMemoryGoalRepository.java` | `com.bookshelf` | `adapter.out.persistence` | Imports only |
| 22 | `InMemoryUserRepository.java` | `com.bookshelf` | `adapter.out.persistence` | Imports only |
| 23 | `JdbcBookRepository.java` | `com.bookshelf` | `adapter.out.persistence` | Imports only |
| 24 | `JdbcShelfRepository.java` | `com.bookshelf` | `adapter.out.persistence` | Imports only |
| 25 | `JdbcGoalRepository.java` | `com.bookshelf` | `adapter.out.persistence` | Imports only |
| 26 | `JdbcUserRepository.java` | `com.bookshelf` | `adapter.out.persistence` | Imports only |
| 27 | `DatabaseConfig.java` | `com.bookshelf` | `adapter.out.persistence` | Imports only |
| 28 | `BookEnrichmentService.java` | `com.bookshelf` | `adapter.out.enrichment` | HTTP methods extracted to OpenLibraryClient; depends on BookMetadataFetcher port |
| 29 | `JwtUtil.java` | `com.bookshelf` | `adapter.out.auth` | Adds `implements TokenService` |
| 30 | `PasswordUtil.java` | `com.bookshelf` | `adapter.out.auth` | None |
| 31 | `GoogleTokenVerifier.java` | `com.bookshelf` | `adapter.out.auth` | Imports only |
| 32 | `HttpServer.java` | `com.bookshelf` | `framework.http` | Imports only |
| 33 | `HttpRequest.java` | `com.bookshelf` | `framework.http` | None |
| 34 | `HttpResponse.java` | `com.bookshelf` | `framework.http` | None |
| 35 | `RequestParser.java` | `com.bookshelf` | `framework.http` | Imports only |
| 36 | `ResponseWriter.java` | `com.bookshelf` | `framework.http` | Imports only |
| 37 | `Router.java` | `com.bookshelf` | `framework.http` | Imports only |
| 38 | `AuthMiddleware.java` | `com.bookshelf` | `framework.http` | `JwtUtil` → `TokenService` |
| 39 | `StaticFileHandler.java` | `com.bookshelf` | `framework.http` | Imports only |
| 40 | `RequestLogger.java` | `com.bookshelf` | `framework.http` | Imports only |
| 41 | `RequestTooLargeException.java` | `com.bookshelf` | `framework.http` | None |
| 42 | `App.java` | `com.bookshelf` | `com.bookshelf` (stays) | All imports updated |
| NEW | `BookMetadataFetcher.java` | — | `domain.port.out` | New interface |
| NEW | `TokenService.java` | — | `domain.port.out` | New interface |
| NEW | `OpenLibraryClient.java` | — | `adapter.out.enrichment` | New class (extracted from BookEnrichmentService) |

## Test Impact

All 8 test files stay in `src/test/java/com/bookshelf/`. Most changes are import-only, but two tests have minor logic changes:

**`BookMetadataTest.java`** — has logic changes beyond imports:
- Lines that create `new BookEnrichmentService(new InMemoryBookRepository())` to call `parseGoogleBooksResponse()` → change to `new OpenLibraryClient()` (4 occurrences)
- `BookEnrichmentService.mergeMetadata(...)` → `OpenLibraryClient.mergeMetadata(...)` (4 occurrences)
- `parseGoogleBooksResponse()` becomes **public** on `OpenLibraryClient` so it's accessible across packages

**`OpenLibraryTest.java`** — constructor change:
- `new BookEnrichmentService(repository)` → `new BookEnrichmentService(repository, new OpenLibraryClient())`
- Rest is import-only changes

**All other test files** (BookApiTest, ShelfApiTest, McpTest, GoalApiTest, AuthApiTest, GoogleAuthApiTest) — import-only changes, no logic changes.

All 271 tests must pass after restructuring.

## Build Notes

- No `build.gradle` changes needed — this is a package-level restructuring only, all files remain under `src/main/java/com/bookshelf/`
- Main class path `com.bookshelf.App` is unchanged — no Docker/Gradle config updates needed
- Note: `java.net.http.HttpRequest` (used by `GoogleTokenVerifier`, `OpenLibraryClient`) is a different type from `com.bookshelf.framework.http.HttpRequest` (used by controllers). No collision, but be aware during implementation

## App.java Wiring Changes

`App.java` composition root updates to:
```java
OpenLibraryClient openLibraryClient = new OpenLibraryClient();
BookEnrichmentService enrichmentService = new BookEnrichmentService(repository, openLibraryClient);
// ... rest uses enrichmentService as before
JwtUtil jwtUtil = new JwtUtil();  // still concrete here — App is the composition root
AuthMiddleware authMiddleware = new AuthMiddleware(jwtUtil);  // accepts TokenService
```

## Implementation Order

Move files in this order to minimize broken-compilation chaos:

**Phase 1: Create directories + new interfaces (no moves yet)**
1. Create all target package directories under `src/main/java/com/bookshelf/`
2. Create `BookMetadataFetcher.java` and `TokenService.java` in `domain.port.out`
3. Create `OpenLibraryClient.java` in `adapter.out.enrichment` (extracted from `BookEnrichmentService`)
4. Compile — new files should have no errors

**Phase 2: Move domain layer (everything else depends on this)**
5. Move domain models: `Book`, `ReadStatus`, `Shelf`, `ReadingGoal`, `User`, `BookMetadata` → `domain.model`
6. Move repository interfaces: `BookRepository`, `ShelfRepository`, `GoalRepository`, `UserRepository` → `domain.port.out`
7. Move exceptions: `DuplicateGoalException`, `DuplicateUserException` → `domain.exception`
8. Fix all compilation errors (update imports across entire codebase)
9. Compile — should pass

**Phase 3: Move framework layer**
10. Move HTTP types: `HttpRequest`, `HttpResponse`, `RequestParser`, `ResponseWriter`, `Router`, `StaticFileHandler`, `RequestLogger`, `RequestTooLargeException` → `framework.http`
11. Move `AuthMiddleware` → `framework.http` (change `JwtUtil` → `TokenService` at the same time)
12. Move `HttpServer` → `framework.http`
13. Fix all compilation errors
14. Compile — should pass

**Phase 4: Move adapter layer**
15. Move controllers: `BookController`, `ShelfController`, `GoalController`, `AuthController` → `adapter.in.http` (change `JwtUtil` → `TokenService` in AuthController)
16. Move MCP: `McpController`, `McpToolHandler` → `adapter.in.mcp`
17. Move persistence: all `InMemory*Repository`, `Jdbc*Repository`, `DatabaseConfig` → `adapter.out.persistence`
18. Refactor `BookEnrichmentService` (remove extracted methods, add `BookMetadataFetcher` dependency) → `adapter.out.enrichment`
19. Move auth: `JwtUtil` (add `implements TokenService`), `PasswordUtil`, `GoogleTokenVerifier` → `adapter.out.auth`
20. Fix all compilation errors

**Phase 5: Update composition root + tests**
21. Update `App.java` imports and wiring
22. Update all 8 test files (imports + logic changes for `BookMetadataTest` and `OpenLibraryTest`)
23. Delete the now-empty `com/bookshelf/mcp/` directory
24. `./gradlew test` — all 271 tests must pass

## Verification

1. `./gradlew test` — all 271 tests pass
2. `./gradlew build` — compiles cleanly with no circular dependencies
3. Verify domain package has zero imports from adapter/framework packages
4. `docker compose up --build -d` — server starts and responds to requests
5. MCP tools still work via `.mcp.json` endpoint
