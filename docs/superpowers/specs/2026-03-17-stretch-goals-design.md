# Stretch Goals Design Spec

**Date:** 2026-03-17
**Scope:** Request logging, subjects filter, pagination, authentication

## Implementation Order

1. Request Logging Middleware
2. Subjects Filter
3. Pagination
4. Authentication (token-based with users)

---

## 1. Request Logging Middleware

### Dependencies

Add to `build.gradle`:

```groovy
implementation 'org.slf4j:slf4j-api:2.0.12'
implementation 'ch.qos.logback:logback-classic:1.5.6'
```

### New Class: `RequestLogger`

A utility invoked from `HttpServer.handleConnection()` to log each request after the response is determined.

**Logged fields:** timestamp, HTTP method, path (with query string), response status code, response time in ms, client IP address.

**Log levels:**
- `INFO` — 2xx and 3xx responses
- `WARN` — 4xx responses (client errors)
- `ERROR` — 5xx responses (server errors)

**Format example:**
```
14:32:05.123 INFO  RequestLogger      - GET /books?genre=Fiction 200 12ms 127.0.0.1
```

### Logback Configuration

File: `src/main/resources/logback.xml`

```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} %-5level %logger{20} - %msg%n</pattern>
        </encoder>
    </appender>
    <logger name="com.zaxxer.hikari" level="WARN" />
    <root level="INFO">
        <appender-ref ref="STDOUT" />
    </root>
</configuration>
```

### Integration Point

In `HttpServer.handleConnection()`:
1. Capture `System.nanoTime()` before `router.route()`
2. After response is determined, call `RequestLogger.log(request, response, elapsedMs, clientIp)`
3. No changes needed to Router, controllers, or ResponseWriter

### Existing Code Impact

- Replace any existing `System.out.println` debug logging with SLF4J calls
- HikariCP will automatically pick up SLF4J (it already uses it internally)

### Tests

No dedicated logging test class. Logging is verified visually via `docker compose logs` and indirectly by all existing tests (every request generates a log line). Adding a custom Logback test appender is over-engineering for this feature.

---

## 2. Subjects Filter

### API Change

New query parameter on `GET /books`:

| Parameter | Example | Description |
|-----------|---------|-------------|
| `subject` | `?subject=science` | Case-insensitive substring match against subjects JSON array |

### Filter Priority Chain (updated)

```
search > subject > genre > readStatus (as base query)
```

The base query filters are mutually exclusive (if/else if chain). Only one of `search`, `subject`, `genre`, or `readStatus` is used as the base query, in priority order. `?subject=science&genre=Fiction` uses `subject` as base and ignores `genre`.

`readStatus` is applied as a **post-filter** on top of `search`, `subject`, or `genre` results when combined. Example: `?subject=fiction&readStatus=FINISHED` fetches by subject first, then filters by status in Java.

### Repository Changes

**New method on `BookRepository` interface:**
```java
List<Book> findBySubject(String subject);
```

**`InMemoryBookRepository`:**
```java
public List<Book> findBySubject(String subject) {
    String q = subject.toLowerCase();
    return store.values().stream()
        .filter(b -> b.getSubjects() != null && b.getSubjects().stream()
            .anyMatch(s -> s.toLowerCase().contains(q)))
        .sorted(Comparator.comparing(b -> b.getCreatedAt() != null ? b.getCreatedAt() : Instant.EPOCH))
        .toList();
}
```

**`JdbcBookRepository`:**
```sql
SELECT * FROM books WHERE LOWER(subjects) LIKE LOWER(?) ESCAPE '\' ORDER BY created_at ASC
```

Since subjects are stored as a JSON array string (e.g., `["Science fiction","Space"]`), a LIKE query on the TEXT column works for substring matching. The search term must be escaped via the existing `escapeLike()` method (escapes `%`, `_`, `\`) before wrapping with `%...%`, same as `findBySearch()`.

### Controller Change

In `BookController.handleGetBooks()`, add `subject` parameter handling between `search` and `genre`:

```java
String subject = request.getQueryParams().get("subject");
// ...
if (search != null && !search.isBlank()) {
    books = repository.findBySearch(search);
} else if (subject != null && !subject.isBlank()) {
    books = repository.findBySubject(subject);
} else if (genre != null && !genre.isBlank()) {
    books = repository.findByGenre(genre);
} else if (...) { ... }
```

### Frontend

- Render subjects as clickable pill/tag elements on each book card (below the genre line)
- Clicking a subject pill:
  - Sets a subject filter variable
  - Filters `allBooks` client-side (same pattern as existing search bar)
  - Shows a "clear filter" indicator
- Style: small rounded pills, muted background color, hover effect

### Tests (~5 new)

1. Filter by subject returns matching books
2. Case-insensitive subject match
3. Subject filter with no matches returns empty array
4. Subject + readStatus combination
5. Subject + sort combination

---

## 3. Pagination

### API Change

New query parameters on `GET /books`:

| Parameter | Default | Max | Description |
|-----------|---------|-----|-------------|
| `page` | *(absent)* | - | 1-indexed page number |
| `size` | `20` | `100` | Items per page |

### Backward Compatibility (Option C)

- **`?page=` absent:** returns raw JSON array `[...]` (current behavior, no change)
- **`?page=` present:** returns wrapper object with pagination metadata

### Response Format (paginated)

```json
{
  "books": [...],
  "page": 1,
  "size": 20,
  "totalItems": 87,
  "totalPages": 5
}
```

### Validation

- `page` < 1 or non-numeric → `400 Bad Request`
- `size` non-numeric → `400 Bad Request`
- `size` < 1 → clamped to 20 (default)
- `size` > 100 → clamped to 100
- `size` present without `page` → ignored (returns raw array, same as no pagination params)

### Implementation

Pagination is applied **after** all filters and sorting in `BookController.handleGetBooks()`:

```java
List<Book> allResults = /* filtered + sorted list */;

if (pageParam != null) {
    int page = parseAndValidate(pageParam);
    int size = clamp(sizeParam, 1, 100, 20);
    int totalItems = allResults.size();
    int totalPages = (int) Math.ceil((double) totalItems / size);
    int offset = (page - 1) * size;
    int end = Math.min(offset + size, totalItems);
    List<Book> pageItems = offset < totalItems ? allResults.subList(offset, end) : List.of();
    // Return wrapper JSON with books, page, size, totalItems, totalPages
} else {
    // Return raw array (current behavior)
}
```

**Why in-memory slicing:** Sorting and post-filtering already happen in Java. Pushing pagination to SQL would require refactoring all filter logic into queries — not worth it for a personal bookshelf app with hundreds of books.

### Frontend

- Add prev/next navigation below the book grid
- Display "Page X of Y" between buttons
- Disable prev on page 1, disable next on last page
- Default to `?page=1&size=20` — UI always uses pagination
- Filter, sort, or search changes reset to page 1

### Tests (~6 new)

1. Basic pagination returns wrapper with correct metadata
2. Default size is 20
3. Size > 100 clamped to 100
4. Invalid page returns 400
5. Page beyond range returns empty books array with correct totalItems
6. No `?page=` param returns raw array (backward compatibility)

---

## 4. Authentication

### Data Model — User

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `id` | UUID | auto | Server-generated |
| `username` | String | yes | Unique, 3-50 chars |
| `passwordHash` | String | auto | PBKDF2WithHmacSHA256 |
| `salt` | String | auto | Random per-user, base64-encoded |
| `createdAt` | Instant | auto | |

### DB Schema

```sql
CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    salt VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

Migration runs in `DatabaseConfig.runMigrations()` alongside existing book/shelf/goal migrations.

### New Endpoints

| Method | Path | Description | Success Code |
|--------|------|-------------|-------------|
| `POST` | `/auth/register` | Create new account | `201 Created` |
| `POST` | `/auth/login` | Authenticate, get JWT | `200 OK` |

**Request body (both):**
```json
{
  "username": "roland",
  "password": "mysecretpassword"
}
```

**Success response (both):**
```json
{
  "token": "eyJhbGciOi...",
  "user": {
    "id": "uuid-here",
    "username": "roland"
  }
}
```

**Error responses:**

| Code | When |
|------|------|
| `400` | Missing username/password, username < 3 or > 50 chars, password < 8 or > 128 chars |
| `401` | Login with wrong username or password |
| `409` | Register with duplicate username |

### JWT Implementation — `JwtUtil`

- **Algorithm:** HMAC-SHA256 via `javax.crypto.Mac` (built-in)
- **Secret:** `JWT_SECRET` env var (required for production — must be set in `docker-compose.yml`). If absent, auto-generates a 256-bit random secret on startup and logs a warning. Note: auto-generated secrets do not survive container restarts, invalidating all tokens
- **Token payload:** `{"sub": "<userId>", "username": "<username>", "iat": <epoch>, "exp": <epoch>}`
- **Expiry:** 24 hours from issuance
- **Encoding:** Manual base64url encode/decode of `header.payload.signature`
- No external JWT library

### Password Hashing — `PasswordUtil`

- **Algorithm:** `PBKDF2WithHmacSHA256` via `javax.crypto.SecretKeyFactory` (built-in)
- **Salt:** 128-bit random, generated per user via `SecureRandom`
- **Iterations:** 310,000 (OWASP 2023 recommendation)
- **Key length:** 256 bits
- Salt stored base64-encoded in `users.salt` column

### Auth Enforcement — `AuthMiddleware`

**Method:** `authenticate(HttpRequest request) → Optional<UUID>`
- Extracts `Authorization: Bearer <token>` header
- Validates JWT signature, expiry
- Returns user ID if valid, empty if invalid/missing

**Integration point:** In `HttpServer.handleConnection()`, after parsing, before routing:

```java
HttpRequest request = RequestParser.parse(inputStream);
boolean isPublic = AuthMiddleware.isPublicRoute(request.getMethod(), request.getPath());
if (!isPublic) {
    Optional<UUID> userId = authMiddleware.authenticate(request);
    if (userId.isEmpty()) {
        // Return 401 Unauthorized
        return;
    }
    request.setUserId(userId.get());
}
HttpResponse response = router.route(request);
```

### Public vs Protected Routes

**Public (no auth required):**
- `POST /auth/register`
- `POST /auth/login`
- All `GET` requests (books, shelves, goals, covers, static files)
- `POST /mcp` — MCP must remain public because Claude Code's MCP client cannot send JWT tokens
- Static file fallback handler

**Protected (valid JWT required):**
- `POST`, `PUT`, `DELETE` on `/books/**` (except MCP)
- `POST /books/re-enrich`
- `POST`, `PUT`, `DELETE` on `/shelves/**`
- `POST`, `PUT`, `DELETE` on `/goals/**`

### HttpRequest Change

Add a `userId` field to `HttpRequest`:
```java
private UUID userId; // Set by AuthMiddleware for authenticated requests
```

### Frontend

**New file: `login.html`**
- Tab-based form: Login / Register
- Username + password fields
- Error display area
- On success: store JWT in `localStorage`, redirect to `index.html`

**Changes to `app.js`:**
- On page load: check `localStorage` for token. If absent, redirect to `login.html`
- All fetch calls to write endpoints include `Authorization: Bearer <token>` header
- Add logout button in header — clears token from `localStorage`, redirects to `login.html`
- If any API call returns `401`, clear token and redirect to login
- If no token present: hide add/edit/delete buttons (read-only visitor mode)

**Changes to `index.html`:**
- Add logout button to header (shown when authenticated)

### New Source Files

| File | Role |
|------|------|
| `User.java` | User entity (id, username, passwordHash, salt, createdAt) |
| `UserRepository.java` | Interface: `findById(UUID)`, `findByUsername(String)`, `save(User)` |
| `InMemoryUserRepository.java` | ConcurrentHashMap implementation (for tests) |
| `JdbcUserRepository.java` | PostgreSQL implementation |
| `AuthController.java` | Register + login handlers |
| `AuthMiddleware.java` | JWT validation, public route checking |
| `JwtUtil.java` | Token creation and verification |
| `PasswordUtil.java` | Password hashing and verification |
| `static/login.html` | Login/register page |

### Environment Variables (new)

| Variable | Default | Description |
|----------|---------|-------------|
| `JWT_SECRET` | *(auto-generated)* | HMAC-SHA256 signing key. Auto-generates if absent (warning logged). **Must be set in `docker-compose.yml` for token persistence across restarts** |

### Existing Test Migration

Auth will break all 223 existing tests that call write endpoints without tokens. Migration strategy:

- Tests use `InMemoryBookRepository` and start their own `HttpServer` — auth middleware is wired in `App.java` / test setup
- Add a test helper method (e.g., `registerAndGetToken()`) that calls `POST /auth/register` and returns the JWT
- In each test class's `@BeforeAll`, register a test user and store the token
- Update all `POST`, `PUT`, `DELETE` test requests to include `Authorization: Bearer <token>` header
- Add a helper method like `authenticatedRequest(HttpRequest.Builder)` to reduce boilerplate
- `GET` requests remain unchanged (public access)

### Design Note: Auto-Login on Register

`POST /auth/register` returns a token immediately (auto-login). This is a deliberate UX choice — the user doesn't need to register then log in separately. No rate limiting on registration; acceptable for a personal app.

### Tests (~12 new in `AuthApiTest`)

1. Register with valid credentials returns 201 + token
2. Login with valid credentials returns 200 + token
3. Register duplicate username returns 409
4. Login with wrong password returns 401
5. Login with non-existent username returns 401
6. Protected endpoint without token returns 401
7. Protected endpoint with valid token succeeds
8. Protected endpoint with expired token returns 401
9. Protected endpoint with invalid/tampered token returns 401
10. GET endpoints work without auth (public access)
11. Register with weak password (< 8 chars) returns 400
12. Register with short username (< 3 chars) returns 400

---

## Summary of All Changes

### New Dependencies
- `org.slf4j:slf4j-api:2.0.12`
- `ch.qos.logback:logback-classic:1.5.6`

### New Source Files (12)
- `RequestLogger.java`
- `User.java`
- `UserRepository.java`
- `InMemoryUserRepository.java`
- `JdbcUserRepository.java`
- `AuthController.java`
- `AuthMiddleware.java`
- `JwtUtil.java`
- `PasswordUtil.java`
- `src/main/resources/logback.xml`
- `static/login.html`
- `AuthApiTest.java`

### Modified Files
- `build.gradle` — new dependencies
- `BookRepository.java` — add `findBySubject()`
- `InMemoryBookRepository.java` — implement `findBySubject()`
- `JdbcBookRepository.java` — implement `findBySubject()`
- `BookController.java` — subject filter + pagination logic
- `HttpServer.java` — request logging + auth middleware integration
- `HttpRequest.java` — add `userId` field
- `App.java` — wire new controllers, repositories, middleware
- `DatabaseConfig.java` — add `users` table migration
- `BookApiTest.java` — new pagination + subject filter tests
- `static/index.html` — subject tags, pagination nav, logout button
- `static/style.css` — subject pills, pagination, login page styles
- `static/app.js` — subject filtering, pagination, auth token handling

### New DB Table
- `users` (id, username, password_hash, salt, created_at)

### New Test Class
- `AuthApiTest` (~12 tests)

### Test Count Impact
- Current: 223 tests
- Added: ~23 new tests (5 subjects + 6 pagination + 12 auth)
- Updated: all existing write-endpoint tests updated with auth tokens
- Expected total: ~246 tests
