# Stretch Goals Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add request logging (SLF4J+Logback), subjects filter, pagination, and token-based authentication to the bookshelf API.

**Architecture:** Four independent features built sequentially. Logging adds SLF4J+Logback in `HttpServer`. Subjects filter adds a new query param + repository method following existing filter patterns. Pagination wraps `GET /books` results when `?page=` is present (backward-compatible). Authentication adds a `users` table, JWT via built-in `javax.crypto`, and an `AuthMiddleware` that gates write endpoints in `HttpServer.handleConnection()`.

**Tech Stack:** Java 17+, SLF4J 2.0.12, Logback 1.5.6, PBKDF2WithHmacSHA256, HMAC-SHA256 JWT (all built-in crypto), PostgreSQL, Gson, JUnit 5

**Spec:** `docs/superpowers/specs/2026-03-17-stretch-goals-design.md`

---

## File Structure

### New Files

| File | Responsibility |
|------|---------------|
| `src/main/java/com/bookshelf/RequestLogger.java` | SLF4J request logging utility |
| `src/main/resources/logback.xml` | Logback console appender config |
| `src/main/java/com/bookshelf/User.java` | User entity (id, username, passwordHash, salt, createdAt) |
| `src/main/java/com/bookshelf/UserRepository.java` | Interface: findById, findByUsername, save |
| `src/main/java/com/bookshelf/InMemoryUserRepository.java` | ConcurrentHashMap user store (tests) |
| `src/main/java/com/bookshelf/JdbcUserRepository.java` | PostgreSQL user store |
| `src/main/java/com/bookshelf/DuplicateUserException.java` | Exception for duplicate username |
| `src/main/java/com/bookshelf/AuthController.java` | Register + login endpoint handlers |
| `src/main/java/com/bookshelf/AuthMiddleware.java` | JWT validation + public route checking |
| `src/main/java/com/bookshelf/JwtUtil.java` | Token creation and verification (HMAC-SHA256) |
| `src/main/java/com/bookshelf/PasswordUtil.java` | PBKDF2 hashing and verification |
| `src/test/java/com/bookshelf/AuthApiTest.java` | Auth endpoint + middleware integration tests |
| `static/login.html` | Login/register page |

### Modified Files

| File | Changes |
|------|---------|
| `build.gradle:23-28` | Add SLF4J + Logback dependencies |
| `HttpServer.java:48-70` | Add timing, request logging, auth middleware check |
| `HttpRequest.java:6-12` | Add `userId` field + getter/setter |
| `BookRepository.java:8-18` | Add `findBySubject(String)` method |
| `InMemoryBookRepository.java` | Implement `findBySubject()` |
| `JdbcBookRepository.java` | Implement `findBySubject()` |
| `BookController.java:56-137` | Add subject filter + pagination logic to `handleGetBooks()` |
| `HttpResponse.java` | Add `unauthorized()` factory method |
| `App.java:9-166` | Wire auth components, update `createRouter()` signature |
| `DatabaseConfig.java:36-161` | Add `users` table migration |
| `BookApiTest.java:39-74` | Add auth helper, update write-endpoint tests with tokens |
| `ShelfApiTest.java:31-60` | Add auth helper, update write-endpoint tests with tokens |
| `GoalApiTest.java:28-43` | Add auth helper, update write-endpoint tests with tokens |
| `McpTest.java:25-39` | Update setup to wire auth components |
| `static/index.html` | Add subject pills, pagination nav, logout button |
| `static/style.css` | Add subject pill, pagination, login page styles |
| `static/app.js:99-135` | Add auth headers to fetch, subject filtering, pagination |
| `docker-compose.yml` | Add `JWT_SECRET` env var |

---

## Task 1: Add SLF4J + Logback Dependencies

**Files:**
- Modify: `build.gradle:23-28`

- [ ] **Step 1: Add dependencies to build.gradle**

In the `dependencies` block (line 23), add SLF4J and Logback:

```groovy
dependencies {
    implementation 'com.google.code.gson:gson:2.11.0'
    implementation 'org.postgresql:postgresql:42.7.3'
    implementation 'com.zaxxer:HikariCP:5.1.0'
    implementation 'org.slf4j:slf4j-api:2.0.12'
    implementation 'ch.qos.logback:logback-classic:1.5.6'
    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
}
```

- [ ] **Step 2: Create logback.xml**

Create `src/main/resources/logback.xml`:

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

- [ ] **Step 3: Verify build compiles**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add build.gradle src/main/resources/logback.xml
git commit -m "feat: add SLF4J and Logback dependencies"
```

---

## Task 2: Implement RequestLogger

**Files:**
- Create: `src/main/java/com/bookshelf/RequestLogger.java`

- [ ] **Step 1: Create RequestLogger class**

```java
package com.bookshelf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RequestLogger {

    private static final Logger logger = LoggerFactory.getLogger(RequestLogger.class);

    public static void log(HttpRequest request, HttpResponse response, long elapsedMs, String clientIp) {
        String method = request != null ? request.getMethod() : "?";
        String path = request != null ? request.getPath() : "?";
        String queryString = "";
        if (request != null && request.getQueryParams() != null && !request.getQueryParams().isEmpty()) {
            StringBuilder sb = new StringBuilder("?");
            request.getQueryParams().forEach((k, v) -> {
                if (sb.length() > 1) sb.append("&");
                sb.append(k).append("=").append(v);
            });
            queryString = sb.toString();
        }
        int status = response != null ? response.getStatusCode() : 0;
        String message = String.format("%s %s%s %d %dms %s", method, path, queryString, status, elapsedMs, clientIp);

        if (status >= 500) {
            logger.error(message);
        } else if (status >= 400) {
            logger.warn(message);
        } else {
            logger.info(message);
        }
    }
}
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/bookshelf/RequestLogger.java
git commit -m "feat: add RequestLogger utility class"
```

---

## Task 3: Integrate RequestLogger into HttpServer

**Files:**
- Modify: `src/main/java/com/bookshelf/HttpServer.java:48-70`

- [ ] **Step 1: Update handleConnection to log requests**

Replace the `handleConnection` method (lines 48-70). Add timing around the route call and log after response. Replace `System.err.println` calls with logger calls.

```java
private void handleConnection(Socket socket) {
    HttpRequest request = null;
    HttpResponse response = null;
    long startTime = System.nanoTime();
    String clientIp = socket.getInetAddress().getHostAddress();
    try {
        socket.setSoTimeout(READ_TIMEOUT_MS);
        InputStream buffered = new BufferedInputStream(socket.getInputStream());
        request = RequestParser.parse(buffered);
        response = router.route(request);
        ResponseWriter.write(socket.getOutputStream(), response);
        socket.shutdownOutput();
    } catch (RequestTooLargeException e) {
        response = HttpResponse.payloadTooLarge("Request body too large");
        try {
            ResponseWriter.write(socket.getOutputStream(), response);
        } catch (IOException ignored) {}
    } catch (IOException e) {
        response = HttpResponse.badRequest("Bad request");
        try {
            ResponseWriter.write(socket.getOutputStream(), response);
        } catch (IOException ignored) {}
    } catch (Exception e) {
        response = HttpResponse.internalServerError("Internal server error");
        try {
            ResponseWriter.write(socket.getOutputStream(), response);
        } catch (IOException ignored) {}
    } finally {
        long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
        RequestLogger.log(request, response, elapsedMs, clientIp);
        try { socket.close(); } catch (IOException ignored) {}
    }
}
```

Check that `BufferedInputStream` and `InputStream` imports already exist at the top of the file.

- [ ] **Step 2: Replace remaining System.out/err with SLF4J in HttpServer and App**

In `HttpServer.java`, replace any remaining `System.err.println` calls (e.g., in `stop()`) with logger calls. Add a logger field:
```java
private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(HttpServer.class);
```

In `App.java`, replace `System.out.println("Bookshelf server started on port " + port)` and `System.err.println` calls with SLF4J logger calls.

- [ ] **Step 3: Run all tests to verify nothing is broken**

Run: `./gradlew test`
Expected: all 223 tests pass. Log output should appear in console.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/bookshelf/HttpServer.java src/main/java/com/bookshelf/App.java
git commit -m "feat: integrate request logging into HttpServer and replace System.out with SLF4J"
```

---

## Task 4: Add findBySubject to Repository Interface and Both Implementations

**Files:**
- Modify: `src/main/java/com/bookshelf/BookRepository.java:8-18`
- Modify: `src/main/java/com/bookshelf/InMemoryBookRepository.java`
- Modify: `src/main/java/com/bookshelf/JdbcBookRepository.java`

- [ ] **Step 1: Add findBySubject method to interface**

In `BookRepository.java`, add after `findBySearch` (line 11):

```java
List<Book> findBySubject(String subject);
```

- [ ] **Step 2: Implement in InMemoryBookRepository**

Add after `findBySearch` method (after line 42):

```java
@Override
public List<Book> findBySubject(String subject) {
    String q = subject.toLowerCase();
    return store.values().stream()
        .filter(b -> b.getSubjects() != null && b.getSubjects().stream()
            .anyMatch(s -> s.toLowerCase().contains(q)))
        .sorted(Comparator.comparing(b -> b.getCreatedAt() != null ? b.getCreatedAt() : Instant.EPOCH))
        .toList();
}
```

- [ ] **Step 3: Implement in JdbcBookRepository**

Add after `findBySearch` method (after line 95). Use the existing `escapeLike()` helper (line 75) for input sanitization:

```java
@Override
public List<Book> findBySubject(String subject) {
    String sql = "SELECT * FROM books WHERE LOWER(subjects) LIKE LOWER(?) ESCAPE '\\' ORDER BY created_at ASC";
    String pattern = "%" + escapeLike(subject) + "%";
    try (Connection conn = dataSource.getConnection();
         PreparedStatement stmt = conn.prepareStatement(sql)) {
        stmt.setString(1, pattern);
        try (ResultSet rs = stmt.executeQuery()) {
            List<Book> books = new ArrayList<>();
            while (rs.next()) books.add(mapRow(rs));
            return books;
        }
    } catch (SQLException e) {
        throw new RuntimeException("Failed to find books by subject", e);
    }
}
```

- [ ] **Step 4: Verify build compiles**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/bookshelf/BookRepository.java src/main/java/com/bookshelf/InMemoryBookRepository.java src/main/java/com/bookshelf/JdbcBookRepository.java
git commit -m "feat: add findBySubject to BookRepository and both implementations"
```

---

## Task 5: Write Subject Filter Tests

**Files:**
- Modify: `src/test/java/com/bookshelf/BookApiTest.java`

- [ ] **Step 1: Write 5 subject filter tests and a helper**

Add a helper method to create books with subjects (subjects are set via `PUT` since Open Library enrichment is null in tests):

```java
private String createBookWithSubjects(String title, java.util.List<String> subjects) throws Exception {
    return createBookWithSubjects(title, subjects, "WANT_TO_READ");
}

private String createBookWithSubjects(String title, java.util.List<String> subjects, String readStatus) throws Exception {
    var createResp = post("/books", gson.toJson(java.util.Map.of(
        "title", title, "author", "Test Author", "readStatus", readStatus)));
    String id = gson.fromJson(createResp.body(), JsonObject.class).get("id").getAsString();
    JsonObject update = new JsonObject();
    update.add("subjects", gson.toJsonTree(subjects));
    put("/books/" + id, gson.toJson(update));
    return id;
}
```

Add 5 test methods:

```java
@Test
void testFilterBySubject() throws Exception {
    createBookWithSubjects("Book A", java.util.List.of("Science fiction", "Space opera"));
    createBookWithSubjects("Book B", java.util.List.of("Fantasy", "Magic"));
    createBookWithSubjects("Book C", java.util.List.of("Science", "Biology"));

    var response = get("/books?subject=science");
    assertEquals(200, response.statusCode());
    var arr = gson.fromJson(response.body(), JsonArray.class);
    assertEquals(2, arr.size());
}

@Test
void testFilterBySubjectCaseInsensitive() throws Exception {
    createBookWithSubjects("Book A", java.util.List.of("Science Fiction"));

    var response = get("/books?subject=SCIENCE");
    assertEquals(200, response.statusCode());
    var arr = gson.fromJson(response.body(), JsonArray.class);
    assertEquals(1, arr.size());
}

@Test
void testFilterBySubjectNoMatches() throws Exception {
    createBookWithSubjects("Book A", java.util.List.of("Science Fiction"));

    var response = get("/books?subject=romance");
    assertEquals(200, response.statusCode());
    var arr = gson.fromJson(response.body(), JsonArray.class);
    assertEquals(0, arr.size());
}

@Test
void testFilterBySubjectWithReadStatus() throws Exception {
    createBookWithSubjects("Book A", java.util.List.of("Science Fiction"), "READING");
    createBookWithSubjects("Book B", java.util.List.of("Science Fiction"), "FINISHED");

    var response = get("/books?subject=science&readStatus=FINISHED");
    assertEquals(200, response.statusCode());
    var arr = gson.fromJson(response.body(), JsonArray.class);
    assertEquals(1, arr.size());
}

@Test
void testFilterBySubjectWithSort() throws Exception {
    createBookWithSubjects("Zebra Book", java.util.List.of("Science Fiction"));
    createBookWithSubjects("Alpha Book", java.util.List.of("Science Fiction"));

    var response = get("/books?subject=science&sort=title,asc");
    assertEquals(200, response.statusCode());
    var arr = gson.fromJson(response.body(), JsonArray.class);
    assertEquals(2, arr.size());
    assertEquals("Alpha Book", arr.get(0).getAsJsonObject().get("title").getAsString());
}
```

- [ ] **Step 2: Run subject filter tests — they should fail**

Run: `./gradlew test --tests "com.bookshelf.BookApiTest.testFilterBySubject"`
Expected: FAIL — `handleGetBooks` doesn't handle `subject` param yet.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/bookshelf/BookApiTest.java
git commit -m "test: add subject filter tests"
```

---

## Task 6: Implement Subject Filter in BookController

**Files:**
- Modify: `src/main/java/com/bookshelf/BookController.java:56-93`

- [ ] **Step 1: Add subject parameter to handleGetBooks**

In `handleGetBooks` (line 56), make these changes:

1. Add subject extraction alongside existing query params (around line 63):
```java
String subject = request.getQueryParams().get("subject");
```

2. Insert the `subject` branch between `search` and `genre` in the if-else chain (lines 71-80):
```java
List<Book> books;
if (search != null && !search.isBlank()) {
    books = repository.findBySearch(search.trim());
} else if (subject != null && !subject.isBlank()) {
    books = repository.findBySubject(subject.trim());
} else if (genre != null && !genre.isBlank()) {
    books = repository.findByGenre(genre);
} else if (readStatus != null) {
    books = repository.findByReadStatus(readStatus);
} else {
    books = repository.findAll();
}
```

3. Update the readStatus post-filter condition (lines 82-93) to include `subject`:
```java
if (readStatus != null && ((search != null && !search.isBlank()) || (genre != null && !genre.isBlank()) || (subject != null && !subject.isBlank()))) {
```

- [ ] **Step 2: Run subject filter tests — they should pass**

Run: `./gradlew test --tests "com.bookshelf.BookApiTest.testFilterBySubject*"`
Expected: All 5 subject filter tests PASS

- [ ] **Step 3: Run all tests to verify nothing is broken**

Run: `./gradlew test`
Expected: All tests pass (223 + 5 = 228)

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/bookshelf/BookController.java
git commit -m "feat: implement subject filter in BookController"
```

---

## Task 7: Add Subject Tags to Frontend

**Files:**
- Modify: `static/style.css`
- Modify: `static/app.js`

- [ ] **Step 1: Add subject pills to book card rendering in app.js**

In `createBookCard` function (around line 222 in the card's template), add subject pills after the genre section. Build the subject pills using DOM methods:

```javascript
// After creating the card and setting innerHTML, before return:
if (book.subjects && book.subjects.length > 0) {
    const subjectsDiv = document.createElement('div');
    subjectsDiv.className = 'book-subjects';
    book.subjects.forEach(s => {
        const pill = document.createElement('span');
        pill.className = 'subject-pill';
        pill.dataset.subject = s;
        pill.textContent = s;
        subjectsDiv.appendChild(pill);
    });
    // Insert into card's info section
    const infoSection = card.querySelector('.book-info') || card;
    infoSection.appendChild(subjectsDiv);
}
```

- [ ] **Step 2: Add subject pill click handler in app.js**

Add a delegated click listener and global state:

```javascript
// Near other global state (around line 6):
let activeSubjectFilter = null;

// In DOMContentLoaded event handler:
document.getElementById('book-grid').addEventListener('click', (e) => {
    const pill = e.target.closest('.subject-pill');
    if (pill) {
        e.stopPropagation();
        activeSubjectFilter = pill.dataset.subject;
        renderBooks(getFilteredBooks());
        showSubjectFilterIndicator(pill.dataset.subject);
    }
});
```

Update `getFilteredBooks()` to include subject filtering:
```javascript
if (activeSubjectFilter) {
    filtered = filtered.filter(b => b.subjects && b.subjects.some(
        s => s.toLowerCase().includes(activeSubjectFilter.toLowerCase())
    ));
}
```

Add show/clear functions:
```javascript
function showSubjectFilterIndicator(subject) {
    let indicator = document.getElementById('subject-filter-indicator');
    if (!indicator) {
        indicator = document.createElement('div');
        indicator.id = 'subject-filter-indicator';
        document.querySelector('.filters').appendChild(indicator);
    }
    indicator.textContent = '';
    const text = document.createTextNode('Filtered by: ');
    const strong = document.createElement('strong');
    strong.textContent = subject;
    const btn = document.createElement('button');
    btn.textContent = '\u2715';
    btn.addEventListener('click', clearSubjectFilter);
    indicator.appendChild(text);
    indicator.appendChild(strong);
    indicator.appendChild(document.createTextNode(' '));
    indicator.appendChild(btn);
    indicator.style.display = 'flex';
}

function clearSubjectFilter() {
    activeSubjectFilter = null;
    const indicator = document.getElementById('subject-filter-indicator');
    if (indicator) indicator.style.display = 'none';
    renderBooks(getFilteredBooks());
}
```

- [ ] **Step 3: Add subject pill styles to style.css**

Append before the `@media (prefers-reduced-motion)` block (before line 2173):

```css
/* Subject pills */
.book-subjects {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
    margin-top: 4px;
}

.subject-pill {
    display: inline-block;
    padding: 2px 8px;
    font-size: 0.7rem;
    border-radius: 12px;
    background: var(--surface-hover, #e8e8e8);
    color: var(--text-secondary, #666);
    cursor: pointer;
    transition: background 0.15s, color 0.15s;
    white-space: nowrap;
}

.subject-pill:hover {
    background: var(--accent, #4a90d9);
    color: #fff;
}

#subject-filter-indicator {
    display: none;
    align-items: center;
    gap: 8px;
    padding: 6px 12px;
    background: var(--surface-hover, #e8e8e8);
    border-radius: 8px;
    font-size: 0.85rem;
    margin-left: 12px;
}

#subject-filter-indicator button {
    background: none;
    border: none;
    cursor: pointer;
    font-size: 1rem;
    padding: 0 4px;
    color: var(--text-secondary, #666);
}
```

- [ ] **Step 4: Commit**

```bash
git add static/style.css static/app.js
git commit -m "feat: add clickable subject tags to frontend"
```

---

## Task 8: Write Pagination Tests

**Files:**
- Modify: `src/test/java/com/bookshelf/BookApiTest.java`

- [ ] **Step 1: Write 7 pagination tests**

```java
@Test
void testPaginationBasic() throws Exception {
    for (int i = 1; i <= 25; i++) {
        post("/books", gson.toJson(java.util.Map.of(
            "title", "Book " + String.format("%02d", i),
            "author", "Author", "readStatus", "WANT_TO_READ")));
    }
    var response = get("/books?page=1&size=10");
    assertEquals(200, response.statusCode());
    var obj = gson.fromJson(response.body(), JsonObject.class);
    assertEquals(10, obj.getAsJsonArray("books").size());
    assertEquals(1, obj.get("page").getAsInt());
    assertEquals(10, obj.get("size").getAsInt());
    assertEquals(25, obj.get("totalItems").getAsInt());
    assertEquals(3, obj.get("totalPages").getAsInt());
}

@Test
void testPaginationDefaultSize() throws Exception {
    for (int i = 1; i <= 25; i++) {
        post("/books", gson.toJson(java.util.Map.of(
            "title", "Book " + i, "author", "Author", "readStatus", "WANT_TO_READ")));
    }
    var response = get("/books?page=1");
    assertEquals(200, response.statusCode());
    var obj = gson.fromJson(response.body(), JsonObject.class);
    assertEquals(20, obj.getAsJsonArray("books").size());
    assertEquals(20, obj.get("size").getAsInt());
}

@Test
void testPaginationSizeClampedTo100() throws Exception {
    for (int i = 1; i <= 5; i++) {
        post("/books", gson.toJson(java.util.Map.of(
            "title", "Book " + i, "author", "Author", "readStatus", "WANT_TO_READ")));
    }
    var response = get("/books?page=1&size=999");
    assertEquals(200, response.statusCode());
    var obj = gson.fromJson(response.body(), JsonObject.class);
    assertEquals(100, obj.get("size").getAsInt());
}

@Test
void testPaginationInvalidPage() throws Exception {
    var resp1 = get("/books?page=0");
    assertEquals(400, resp1.statusCode());
    var resp2 = get("/books?page=-1");
    assertEquals(400, resp2.statusCode());
    var resp3 = get("/books?page=abc");
    assertEquals(400, resp3.statusCode());
}

@Test
void testPaginationBeyondRange() throws Exception {
    for (int i = 1; i <= 5; i++) {
        post("/books", gson.toJson(java.util.Map.of(
            "title", "Book " + i, "author", "Author", "readStatus", "WANT_TO_READ")));
    }
    var response = get("/books?page=99&size=10");
    assertEquals(200, response.statusCode());
    var obj = gson.fromJson(response.body(), JsonObject.class);
    assertEquals(0, obj.getAsJsonArray("books").size());
    assertEquals(5, obj.get("totalItems").getAsInt());
}

@Test
void testNoPaginationParamReturnsArray() throws Exception {
    post("/books", gson.toJson(java.util.Map.of(
        "title", "Book 1", "author", "Author", "readStatus", "WANT_TO_READ")));
    var response = get("/books");
    assertEquals(200, response.statusCode());
    var arr = gson.fromJson(response.body(), JsonArray.class);
    assertNotNull(arr);
    assertTrue(arr.size() > 0);
}

@Test
void testPaginationInvalidSizeReturns400() throws Exception {
    var response = get("/books?page=1&size=abc");
    assertEquals(400, response.statusCode());
}
```

- [ ] **Step 2: Run pagination tests — they should fail**

Run: `./gradlew test --tests "com.bookshelf.BookApiTest.testPaginationBasic"`
Expected: FAIL — pagination not implemented yet.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/bookshelf/BookApiTest.java
git commit -m "test: add pagination tests"
```

---

## Task 9: Implement Pagination in BookController

**Files:**
- Modify: `src/main/java/com/bookshelf/BookController.java:56-137`

- [ ] **Step 1: Add pagination logic to handleGetBooks**

At the end of `handleGetBooks`, after sorting is applied (around line 125) and before the JSON response is built (around line 127), add pagination logic:

```java
// Pagination
String pageParam = request.getQueryParams().get("page");
String sizeParam = request.getQueryParams().get("size");

if (pageParam != null) {
    int page;
    try {
        page = Integer.parseInt(pageParam);
    } catch (NumberFormatException e) {
        return HttpResponse.badRequest("{\"error\": \"invalid page parameter\"}");
    }
    if (page < 1) {
        return HttpResponse.badRequest("{\"error\": \"page must be >= 1\"}");
    }

    int size = 20;
    if (sizeParam != null) {
        try {
            size = Integer.parseInt(sizeParam);
        } catch (NumberFormatException e) {
            return HttpResponse.badRequest("{\"error\": \"invalid size parameter\"}");
        }
    }
    if (size < 1) size = 20;
    if (size > 100) size = 100;

    int totalItems = books.size();
    int totalPages = (int) Math.ceil((double) totalItems / size);
    int offset = (page - 1) * size;
    int end = Math.min(offset + size, totalItems);
    List<Book> pageItems = offset < totalItems ? books.subList(offset, end) : List.of();

    JsonObject wrapper = new JsonObject();
    wrapper.add("books", gson.toJsonTree(pageItems));
    wrapper.addProperty("page", page);
    wrapper.addProperty("size", size);
    wrapper.addProperty("totalItems", totalItems);
    wrapper.addProperty("totalPages", totalPages);
    return HttpResponse.ok(gson.toJson(wrapper));
}
```

The existing `return HttpResponse.ok(gson.toJson(books))` remains for the non-paginated case.

Add the `JsonObject` import if not already present:
```java
import com.google.gson.JsonObject;
```

- [ ] **Step 2: Run pagination tests — they should pass**

Run: `./gradlew test --tests "com.bookshelf.BookApiTest.testPagination*"`
Expected: All 7 pagination tests PASS

- [ ] **Step 3: Run all tests to verify backward compatibility**

Run: `./gradlew test`
Expected: All tests pass (existing tests use `GET /books` without `?page=` and still get raw array)

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/bookshelf/BookController.java
git commit -m "feat: implement pagination in BookController"
```

---

## Task 10: Add Pagination to Frontend

**Files:**
- Modify: `static/index.html`
- Modify: `static/style.css`
- Modify: `static/app.js`

- [ ] **Step 1: Add pagination nav to index.html**

Add a pagination container after the book grid section (around line 341):

```html
<div id="pagination-nav" class="pagination-nav" style="display:none;">
    <button id="prev-page" class="pagination-btn">&larr; Previous</button>
    <span id="page-info">Page 1 of 1</span>
    <button id="next-page" class="pagination-btn">Next &rarr;</button>
</div>
```

- [ ] **Step 2: Update app.js to use paginated view**

Add pagination state variables near other globals (around line 6):
```javascript
let currentPage = 1;
let pageSize = 20;
let totalPages = 1;
```

Update `loadBooks()` (line 454) to support client-side pagination. After loading all books and applying filters, slice for the current page:

```javascript
// Inside loadBooks, for the non-shelf branch:
const filtered = getFilteredBooks();
const startIdx = (currentPage - 1) * pageSize;
const pageItems = filtered.slice(startIdx, startIdx + pageSize);
totalPages = Math.max(1, Math.ceil(filtered.length / pageSize));
renderBooks(pageItems);
updatePaginationNav();
```

Add pagination nav update function:
```javascript
function updatePaginationNav() {
    const nav = document.getElementById('pagination-nav');
    const info = document.getElementById('page-info');
    const prev = document.getElementById('prev-page');
    const next = document.getElementById('next-page');
    if (!nav) return;

    const filtered = getFilteredBooks();
    totalPages = Math.max(1, Math.ceil(filtered.length / pageSize));
    if (currentPage > totalPages) currentPage = totalPages;

    nav.style.display = filtered.length > pageSize ? 'flex' : 'none';
    info.textContent = 'Page ' + currentPage + ' of ' + totalPages;
    prev.disabled = currentPage <= 1;
    next.disabled = currentPage >= totalPages;
}
```

Wire up pagination buttons in DOMContentLoaded:
```javascript
document.getElementById('prev-page')?.addEventListener('click', () => {
    if (currentPage > 1) { currentPage--; loadBooks(); }
});
document.getElementById('next-page')?.addEventListener('click', () => {
    if (currentPage < totalPages) { currentPage++; loadBooks(); }
});
```

Reset `currentPage = 1` in any filter/sort change handlers before calling `loadBooks()`.

- [ ] **Step 3: Add pagination styles to style.css**

Append before the `@media (prefers-reduced-motion)` block:

```css
/* Pagination */
.pagination-nav {
    display: flex;
    justify-content: center;
    align-items: center;
    gap: 16px;
    padding: 20px 0;
    margin-top: 8px;
}

.pagination-btn {
    padding: 8px 16px;
    border: 1px solid var(--border, #ddd);
    border-radius: 8px;
    background: var(--surface, #fff);
    color: var(--text, #333);
    cursor: pointer;
    font-size: 0.9rem;
    transition: background 0.15s;
}

.pagination-btn:hover:not(:disabled) {
    background: var(--surface-hover, #e8e8e8);
}

.pagination-btn:disabled {
    opacity: 0.4;
    cursor: not-allowed;
}

#page-info {
    font-size: 0.9rem;
    color: var(--text-secondary, #666);
}
```

- [ ] **Step 4: Commit**

```bash
git add static/index.html static/style.css static/app.js
git commit -m "feat: add pagination navigation to frontend"
```

---

## Task 11: Add `unauthorized` Factory Method to HttpResponse

**Files:**
- Modify: `src/main/java/com/bookshelf/HttpResponse.java`

- [ ] **Step 1: Add unauthorized method**

Add after the `badRequest` method (after line 80):

```java
public static HttpResponse unauthorized(String error) {
    return new HttpResponse(401, "Unauthorized", new HashMap<>(),
        "{\"error\": \"" + escapeJson(error) + "\"}");
}
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/bookshelf/HttpResponse.java
git commit -m "feat: add unauthorized factory method to HttpResponse"
```

---

## Task 12: Implement PasswordUtil

**Files:**
- Create: `src/main/java/com/bookshelf/PasswordUtil.java`

- [ ] **Step 1: Create PasswordUtil class**

```java
package com.bookshelf;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;

public class PasswordUtil {

    private static final int ITERATIONS = 310_000;
    private static final int KEY_LENGTH = 256;
    private static final int SALT_LENGTH = 16;

    public static String generateSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        new SecureRandom().nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    public static String hashPassword(String password, String salt) {
        try {
            byte[] saltBytes = Base64.getDecoder().decode(salt);
            KeySpec spec = new PBEKeySpec(password.toCharArray(), saltBytes, ITERATIONS, KEY_LENGTH);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hash = factory.generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash password", e);
        }
    }

    public static boolean verifyPassword(String password, String salt, String expectedHash) {
        String actualHash = hashPassword(password, salt);
        return actualHash.equals(expectedHash);
    }
}
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/bookshelf/PasswordUtil.java
git commit -m "feat: add PasswordUtil with PBKDF2 hashing"
```

---

## Task 13: Implement JwtUtil

**Files:**
- Create: `src/main/java/com/bookshelf/JwtUtil.java`

- [ ] **Step 1: Create JwtUtil class**

```java
package com.bookshelf;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JwtUtil {

    private static final Logger logger = LoggerFactory.getLogger(JwtUtil.class);
    private static final long EXPIRY_MS = 24 * 60 * 60 * 1000;
    private final byte[] secret;

    public JwtUtil() {
        String envSecret = System.getenv("JWT_SECRET");
        if (envSecret != null && !envSecret.isEmpty()) {
            this.secret = envSecret.getBytes(StandardCharsets.UTF_8);
        } else {
            logger.warn("JWT_SECRET not set - generating random secret. Tokens will not survive restarts.");
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.secret = random;
        }
    }

    public JwtUtil(String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String createToken(UUID userId, String username) {
        String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        long now = System.currentTimeMillis() / 1000;
        long exp = now + (EXPIRY_MS / 1000);
        String payloadJson = String.format(
            "{\"sub\":\"%s\",\"username\":\"%s\",\"iat\":%d,\"exp\":%d}",
            userId.toString(), username, now, exp);
        String payload = base64Url(payloadJson);
        String signature = sign(header + "." + payload);
        return header + "." + payload + "." + signature;
    }

    public UUID validateToken(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return null;

            String expectedSig = sign(parts[0] + "." + parts[1]);
            if (!expectedSig.equals(parts[2])) return null;

            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);

            long exp = extractLong(payloadJson, "exp");
            if (System.currentTimeMillis() / 1000 > exp) return null;

            String sub = extractString(payloadJson, "sub");
            if (sub == null) return null;

            return UUID.fromString(sub);
        } catch (Exception e) {
            return null;
        }
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] sig = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign JWT", e);
        }
    }

    private String base64Url(String input) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(input.getBytes(StandardCharsets.UTF_8));
    }

    private long extractLong(String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx == -1) return 0;
        int start = idx + search.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        return Long.parseLong(json.substring(start, end));
    }

    private String extractString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int idx = json.indexOf(search);
        if (idx == -1) return null;
        int start = idx + search.length();
        int end = json.indexOf("\"", start);
        if (end == -1) return null;
        return json.substring(start, end);
    }
}
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/bookshelf/JwtUtil.java
git commit -m "feat: add JwtUtil with HMAC-SHA256 JWT implementation"
```

---

## Task 14: Implement User Entity and UserRepository

**Files:**
- Create: `src/main/java/com/bookshelf/User.java`
- Create: `src/main/java/com/bookshelf/UserRepository.java`
- Create: `src/main/java/com/bookshelf/InMemoryUserRepository.java`

- [ ] **Step 1: Create User entity**

```java
package com.bookshelf;

import java.time.Instant;
import java.util.UUID;

public class User {
    private UUID id;
    private String username;
    private String passwordHash;
    private String salt;
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getSalt() { return salt; }
    public void setSalt(String salt) { this.salt = salt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
```

- [ ] **Step 2: Create UserRepository interface**

```java
package com.bookshelf;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository {
    Optional<User> findById(UUID id);
    Optional<User> findByUsername(String username);
    User save(User user);
}
```

- [ ] **Step 3: Create InMemoryUserRepository**

```java
package com.bookshelf;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryUserRepository implements UserRepository {

    private final ConcurrentHashMap<UUID, User> store = new ConcurrentHashMap<>();

    @Override
    public Optional<User> findById(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return store.values().stream()
            .filter(u -> u.getUsername().equals(username))
            .findFirst();
    }

    @Override
    public User save(User user) {
        store.put(user.getId(), user);
        return user;
    }
}
```

- [ ] **Step 4: Verify build compiles**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/bookshelf/User.java src/main/java/com/bookshelf/UserRepository.java src/main/java/com/bookshelf/InMemoryUserRepository.java
git commit -m "feat: add User entity and UserRepository with in-memory implementation"
```

---

## Task 15: Implement JdbcUserRepository and DB Migration

**Files:**
- Create: `src/main/java/com/bookshelf/JdbcUserRepository.java`
- Create: `src/main/java/com/bookshelf/DuplicateUserException.java`
- Modify: `src/main/java/com/bookshelf/DatabaseConfig.java:36-161`

- [ ] **Step 1: Add users table migration to DatabaseConfig**

In `DatabaseConfig.runMigrations()`, after the reading_goals table migration (around line 160), add:

```java
// Users table
stmt.executeUpdate(
    "CREATE TABLE IF NOT EXISTS users (" +
    "id UUID PRIMARY KEY, " +
    "username VARCHAR(50) UNIQUE NOT NULL, " +
    "password_hash VARCHAR(255) NOT NULL, " +
    "salt VARCHAR(255) NOT NULL, " +
    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)"
);
```

- [ ] **Step 2: Create DuplicateUserException**

```java
package com.bookshelf;

public class DuplicateUserException extends RuntimeException {
    public DuplicateUserException(String message) {
        super(message);
    }
}
```

- [ ] **Step 3: Create JdbcUserRepository**

```java
package com.bookshelf;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public class JdbcUserRepository implements UserRepository {

    private final DataSource dataSource;

    public JdbcUserRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<User> findById(UUID id) {
        String sql = "SELECT * FROM users WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find user by id", e);
        }
    }

    @Override
    public Optional<User> findByUsername(String username) {
        String sql = "SELECT * FROM users WHERE username = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find user by username", e);
        }
    }

    @Override
    public User save(User user) {
        String sql = "INSERT INTO users (id, username, password_hash, salt, created_at) VALUES (?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, user.getId());
            stmt.setString(2, user.getUsername());
            stmt.setString(3, user.getPasswordHash());
            stmt.setString(4, user.getSalt());
            stmt.setTimestamp(5, Timestamp.from(user.getCreatedAt()));
            stmt.executeUpdate();
            return user;
        } catch (SQLException e) {
            if (e.getSQLState() != null && e.getSQLState().startsWith("23")) {
                throw new DuplicateUserException("Username already exists: " + user.getUsername());
            }
            throw new RuntimeException("Failed to save user", e);
        }
    }

    private User mapRow(ResultSet rs) throws SQLException {
        User user = new User();
        user.setId(rs.getObject("id", UUID.class));
        user.setUsername(rs.getString("username"));
        user.setPasswordHash(rs.getString("password_hash"));
        user.setSalt(rs.getString("salt"));
        Timestamp ts = rs.getTimestamp("created_at");
        if (ts != null) user.setCreatedAt(ts.toInstant());
        return user;
    }
}
```

- [ ] **Step 4: Verify build compiles**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/bookshelf/JdbcUserRepository.java src/main/java/com/bookshelf/DuplicateUserException.java src/main/java/com/bookshelf/DatabaseConfig.java
git commit -m "feat: add JdbcUserRepository and users table migration"
```

---

## Task 16: Implement AuthController

**Files:**
- Create: `src/main/java/com/bookshelf/AuthController.java`

- [ ] **Step 1: Create AuthController**

```java
package com.bookshelf;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.UUID;

public class AuthController {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final Gson gson = new Gson();

    public AuthController(UserRepository userRepository, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
    }

    public HttpResponse handleRegister(HttpRequest request) {
        JsonObject body;
        try {
            body = gson.fromJson(request.getBody(), JsonObject.class);
        } catch (Exception e) {
            return HttpResponse.badRequest("{\"error\": \"invalid JSON\"}");
        }
        if (body == null) {
            return HttpResponse.badRequest("{\"error\": \"request body is required\"}");
        }

        String username = body.has("username") && !body.get("username").isJsonNull()
            ? body.get("username").getAsString().trim() : null;
        String password = body.has("password") && !body.get("password").isJsonNull()
            ? body.get("password").getAsString() : null;

        if (username == null || username.isEmpty()) {
            return HttpResponse.badRequest("{\"error\": \"username is required\"}");
        }
        if (username.length() < 3 || username.length() > 50) {
            return HttpResponse.badRequest("{\"error\": \"username must be 3-50 characters\"}");
        }
        if (password == null || password.isEmpty()) {
            return HttpResponse.badRequest("{\"error\": \"password is required\"}");
        }
        if (password.length() < 8) {
            return HttpResponse.badRequest("{\"error\": \"password must be at least 8 characters\"}");
        }
        if (password.length() > 128) {
            return HttpResponse.badRequest("{\"error\": \"password must be at most 128 characters\"}");
        }

        if (userRepository.findByUsername(username).isPresent()) {
            return HttpResponse.conflict("{\"error\": \"username already exists\"}");
        }

        String salt = PasswordUtil.generateSalt();
        String hash = PasswordUtil.hashPassword(password, salt);

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(hash);
        user.setSalt(salt);
        user.setCreatedAt(Instant.now());

        try {
            userRepository.save(user);
        } catch (DuplicateUserException e) {
            return HttpResponse.conflict("{\"error\": \"username already exists\"}");
        }

        String token = jwtUtil.createToken(user.getId(), user.getUsername());
        return HttpResponse.created(buildAuthResponse(token, user));
    }

    public HttpResponse handleLogin(HttpRequest request) {
        JsonObject body;
        try {
            body = gson.fromJson(request.getBody(), JsonObject.class);
        } catch (Exception e) {
            return HttpResponse.badRequest("{\"error\": \"invalid JSON\"}");
        }
        if (body == null) {
            return HttpResponse.badRequest("{\"error\": \"request body is required\"}");
        }

        String username = body.has("username") && !body.get("username").isJsonNull()
            ? body.get("username").getAsString().trim() : null;
        String password = body.has("password") && !body.get("password").isJsonNull()
            ? body.get("password").getAsString() : null;

        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            return HttpResponse.badRequest("{\"error\": \"username and password are required\"}");
        }

        var userOpt = userRepository.findByUsername(username);
        if (userOpt.isEmpty()) {
            return HttpResponse.unauthorized("invalid username or password");
        }

        User user = userOpt.get();
        if (!PasswordUtil.verifyPassword(password, user.getSalt(), user.getPasswordHash())) {
            return HttpResponse.unauthorized("invalid username or password");
        }

        String token = jwtUtil.createToken(user.getId(), user.getUsername());
        return HttpResponse.ok(buildAuthResponse(token, user));
    }

    private String buildAuthResponse(String token, User user) {
        JsonObject resp = new JsonObject();
        resp.addProperty("token", token);
        JsonObject userObj = new JsonObject();
        userObj.addProperty("id", user.getId().toString());
        userObj.addProperty("username", user.getUsername());
        resp.add("user", userObj);
        return gson.toJson(resp);
    }
}
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/bookshelf/AuthController.java
git commit -m "feat: add AuthController with register and login handlers"
```

---

## Task 17: Implement AuthMiddleware and Update HttpRequest

**Files:**
- Create: `src/main/java/com/bookshelf/AuthMiddleware.java`
- Modify: `src/main/java/com/bookshelf/HttpRequest.java:6-12`

- [ ] **Step 1: Add userId field to HttpRequest**

In `HttpRequest.java`, add after existing fields (line 11):
```java
private UUID userId;
```

Add getter/setter after existing getters (after line 32):
```java
public UUID getUserId() { return userId; }
public void setUserId(UUID userId) { this.userId = userId; }
```

Add import at top: `import java.util.UUID;`

- [ ] **Step 2: Create AuthMiddleware class**

```java
package com.bookshelf;

import java.util.Optional;
import java.util.UUID;

public class AuthMiddleware {

    private final JwtUtil jwtUtil;

    public AuthMiddleware(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    public Optional<UUID> authenticate(HttpRequest request) {
        String authHeader = request.getHeaders().get("authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String token = authHeader.substring(7);
        UUID userId = jwtUtil.validateToken(token);
        return Optional.ofNullable(userId);
    }

    public static boolean isPublicRoute(String method, String path) {
        if ("GET".equalsIgnoreCase(method)) {
            return true;
        }
        if ("POST".equalsIgnoreCase(method)) {
            if ("/auth/register".equals(path) || "/auth/login".equals(path) || "/mcp".equals(path)) {
                return true;
            }
        }
        return false;
    }
}
```

- [ ] **Step 3: Verify build compiles**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/bookshelf/AuthMiddleware.java src/main/java/com/bookshelf/HttpRequest.java
git commit -m "feat: add AuthMiddleware with JWT validation and public route checking"
```

---

## Task 18: Wire Auth into App.java and HttpServer

**Files:**
- Modify: `src/main/java/com/bookshelf/App.java:9-166`
- Modify: `src/main/java/com/bookshelf/HttpServer.java`

- [ ] **Step 1: Add AuthMiddleware field to HttpServer**

In `HttpServer.java`, add field and update constructors:

```java
private final AuthMiddleware authMiddleware;

public HttpServer(int port, Router router) {
    this(port, router, null);
}

public HttpServer(int port, Router router, AuthMiddleware authMiddleware) {
    this.port = port;
    this.router = router;
    this.authMiddleware = authMiddleware;
}
```

- [ ] **Step 2: Add auth check to handleConnection**

In `handleConnection()`, after `request = RequestParser.parse(buffered)` and before `response = router.route(request)`, add:

```java
// Auth check
if (authMiddleware != null) {
    boolean isPublic = AuthMiddleware.isPublicRoute(request.getMethod(), request.getPath());
    if (!isPublic) {
        java.util.Optional<java.util.UUID> userId = authMiddleware.authenticate(request);
        if (userId.isEmpty()) {
            response = HttpResponse.unauthorized("authentication required");
            ResponseWriter.write(socket.getOutputStream(), response);
            socket.shutdownOutput();
            return;
        }
        request.setUserId(userId.get());
    }
}
```

- [ ] **Step 3: Wire auth components in App.java**

In `App.main()`, after goal controller creation and before router creation, add:

```java
JwtUtil jwtUtil = new JwtUtil();
UserRepository userRepository = new JdbcUserRepository(dbConfig.getDataSource());
AuthController authController = new AuthController(userRepository, jwtUtil);
AuthMiddleware authMiddleware = new AuthMiddleware(jwtUtil);
```

Update `createRouter` call:
```java
Router router = createRouter(controller, shelfController, mcpController, goalController, authController);
```

Update `HttpServer` construction:
```java
HttpServer server = new HttpServer(port, router, authMiddleware);
```

Update `createRouter` method signature and add auth routes:
```java
public static Router createRouter(BookController controller, ShelfController shelfController,
                                   McpController mcpController, GoalController goalController,
                                   AuthController authController) {
    // ... existing routes ...
    router.addRoute("POST", "/auth/register", authController::handleRegister);
    router.addRoute("POST", "/auth/login", authController::handleLogin);
    return router;
}
```

- [ ] **Step 4: Verify build compiles**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/bookshelf/App.java src/main/java/com/bookshelf/HttpServer.java
git commit -m "feat: wire auth components into App and HttpServer"
```

---

## Task 19: Update Existing Tests for Auth

**Files:**
- Modify: `src/test/java/com/bookshelf/BookApiTest.java:39-100`
- Modify: `src/test/java/com/bookshelf/ShelfApiTest.java:31-60`
- Modify: `src/test/java/com/bookshelf/GoalApiTest.java:28-43`
- Modify: `src/test/java/com/bookshelf/McpTest.java:25-39`

All 4 test classes need the same pattern of changes.

- [ ] **Step 1: Update BookApiTest setup**

In `BookApiTest.@BeforeAll` (line 39):

1. Add auth component creation before router:
```java
JwtUtil jwtUtil = new JwtUtil("test-secret");
InMemoryUserRepository userRepository = new InMemoryUserRepository();
AuthController authController = new AuthController(userRepository, jwtUtil);
AuthMiddleware authMiddleware = new AuthMiddleware(jwtUtil);
```

2. Update `App.createRouter` call to include `authController`
3. Update `HttpServer` constructor to pass `authMiddleware`
4. Add static `authToken` field
5. After `server.start()` and `Thread.sleep(100)`, register a test user:
```java
String regBody = "{\"username\":\"testuser\",\"password\":\"testpassword123\"}";
var regResp = client.send(
    java.net.http.HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:" + port + "/auth/register"))
        .header("Content-Type", "application/json")
        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(regBody))
        .build(),
    java.net.http.HttpResponse.BodyHandlers.ofString());
authToken = new Gson().fromJson(regResp.body(), JsonObject.class).get("token").getAsString();
```

6. Update `post()`, `put()`, `delete()` helpers to add `.header("Authorization", "Bearer " + authToken)`

- [ ] **Step 2: Update ShelfApiTest setup**

Same pattern as BookApiTest: add auth components, register test user, add auth header to write helpers.

- [ ] **Step 3: Update GoalApiTest setup**

Same pattern: add auth components, register test user, auth headers on POST/PUT/DELETE helpers.

- [ ] **Step 4: Update McpTest setup**

Add auth components to `@BeforeAll`. Wire into router and server constructor. MCP POST is public — no auth headers needed on `mcpPost()`.

- [ ] **Step 5: Run all existing tests**

Run: `./gradlew test`
Expected: All 223+ tests pass

- [ ] **Step 6: Commit**

```bash
git add src/test/java/com/bookshelf/BookApiTest.java src/test/java/com/bookshelf/ShelfApiTest.java src/test/java/com/bookshelf/GoalApiTest.java src/test/java/com/bookshelf/McpTest.java
git commit -m "test: update all test classes with auth token for write endpoints"
```

---

## Task 20: Write Auth Tests

**Files:**
- Create: `src/test/java/com/bookshelf/AuthApiTest.java`

- [ ] **Step 1: Create AuthApiTest with 12 tests**

Create `src/test/java/com/bookshelf/AuthApiTest.java` with test class setup (same pattern as other test classes: `@BeforeAll` starts server with auth components, `@BeforeEach` clears book repo) and these 12 test methods:

1. `testRegisterReturnsTokenAndUser` — POST `/auth/register` returns 201 with token and user object
2. `testLoginReturnsToken` — Register then POST `/auth/login` returns 200 with token
3. `testRegisterDuplicateUsername` — Second register with same username returns 409
4. `testLoginWrongPassword` — Login with wrong password returns 401
5. `testLoginNonExistentUser` — Login with unknown username returns 401
6. `testProtectedEndpointWithoutToken` — POST `/books` without auth returns 401
7. `testProtectedEndpointWithValidToken` — POST `/books` with valid token returns 201
8. `testProtectedEndpointWithInvalidToken` — POST `/books` with tampered token returns 401
9. `testProtectedEndpointWithTamperedToken` — Modify token signature, verify 401
10. `testGetEndpointsWorkWithoutAuth` — GET `/books` without token returns 200
11. `testRegisterWeakPassword` — Password < 8 chars returns 400
12. `testRegisterShortUsername` — Username < 3 chars returns 400

Each test should use unique usernames to avoid conflicts (e.g., `"user1"`, `"user2"`, etc.).

Helper methods needed: `post(path, body)`, `postWithAuth(path, body, token)`, `get(path)`, `registerAndGetToken(username)`.

- [ ] **Step 2: Run auth tests**

Run: `./gradlew test --tests "com.bookshelf.AuthApiTest"`
Expected: All 12 tests PASS

- [ ] **Step 3: Run ALL tests**

Run: `./gradlew test`
Expected: All tests pass (~246 total)

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/bookshelf/AuthApiTest.java
git commit -m "test: add AuthApiTest with 12 auth integration tests"
```

---

## Task 21: Create Login Page

**Files:**
- Create: `static/login.html`

- [ ] **Step 1: Create login.html**

Create `static/login.html` with:
- Centered auth card with app title
- Tab-based form: Login / Register
- Username + password input fields
- Error display area
- Submit button (text changes based on active tab)
- JavaScript that:
  - Handles tab switching between login/register modes
  - On submit: fetches `POST /auth/{mode}` with username+password
  - On success: stores token and username in `localStorage`, redirects to `/`
  - On error: displays error message from API response
  - On page load: if token exists in `localStorage`, redirects to `/` immediately
- Inline styles (within the page) for the auth card, tabs, form, and error display
- Links to `/style.css` for base variables (colors, fonts)

Build all dynamic content using DOM methods (`createElement`, `textContent`) — do not use string interpolation for user-provided values.

- [ ] **Step 2: Commit**

```bash
git add static/login.html
git commit -m "feat: add login/register page"
```

---

## Task 22: Add Auth to Frontend (app.js + index.html)

**Files:**
- Modify: `static/app.js:99-135`
- Modify: `static/index.html`
- Modify: `static/style.css`

- [ ] **Step 1: Add auth helpers to app.js**

At the top of `app.js`, add:
```javascript
function getToken() { return localStorage.getItem('token'); }
function isAuthenticated() { return !!getToken(); }
function handleAuthExpired() {
    localStorage.removeItem('token');
    localStorage.removeItem('username');
    window.location.href = '/login.html';
}
```

- [ ] **Step 2: Update apiPost, apiPut, apiDelete with auth header**

Update `apiPost` (line 107), `apiPut` (line 119), `apiDelete` (line 131) to:
- Add `Authorization: Bearer <token>` header when token exists
- Check for 401 response and call `handleAuthExpired()`

- [ ] **Step 3: Add logout button to index.html header**

Add a logout button element in the header section.

- [ ] **Step 4: Add auth gate and logout handler to app.js**

In DOMContentLoaded handler:
- If not authenticated, hide add/edit/delete controls and logout button
- Wire logout button click to clear `localStorage` and redirect to `/login.html`

- [ ] **Step 5: Add logout button styles to style.css**

```css
.logout-btn {
    padding: 6px 14px;
    border: 1px solid var(--border, #ddd);
    border-radius: 8px;
    background: transparent;
    color: var(--text-secondary, #666);
    cursor: pointer;
    font-size: 0.85rem;
    transition: background 0.15s, color 0.15s;
}
.logout-btn:hover {
    background: #e74c3c;
    color: #fff;
    border-color: #e74c3c;
}
```

- [ ] **Step 6: Commit**

```bash
git add static/app.js static/index.html static/style.css
git commit -m "feat: add auth token handling and logout to frontend"
```

---

## Task 23: Update docker-compose.yml with JWT_SECRET

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: Add JWT_SECRET to app environment**

In `docker-compose.yml`, add to the app service's environment:
```yaml
JWT_SECRET: change-me-to-a-secure-random-string
```

- [ ] **Step 2: Commit**

```bash
git add docker-compose.yml
git commit -m "feat: add JWT_SECRET to docker-compose environment"
```

---

## Task 24: Update Documentation

**Files:**
- Modify: `CLAUDE.md`
- Modify: `bookshelf-api-plan.md`

- [ ] **Step 1: Update CLAUDE.md**

Add to the relevant sections:
- **Data Model:** Add User model table
- **API Endpoints:** Add `/auth/register` and `/auth/login`; update `GET /books` query params with `subject`, `page`, `size`
- **Error Responses:** Add `401 Unauthorized`
- **Key Design Decisions:** Add auth, pagination, subjects, logging decisions
- **Environment Variables:** Add `JWT_SECRET`
- **Source File Overview:** Add all new files
- **Testing:** Update test count, add `AuthApiTest`
- **DB Schema Migrations:** Add users table

- [ ] **Step 2: Update bookshelf-api-plan.md**

Mark the 4 stretch goals as completed with strikethrough (matching existing pattern).

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md bookshelf-api-plan.md
git commit -m "docs: update documentation for auth, pagination, subjects, and logging"
```

---

## Task 25: Final Verification

- [ ] **Step 1: Run all tests**

Run: `./gradlew test`
Expected: All ~246 tests pass

- [ ] **Step 2: Build and test Docker deployment**

```bash
docker compose down
docker compose up --build -d
```

Wait for containers to start, then test:
```bash
curl http://localhost:8080/books
curl -X POST http://localhost:8080/auth/register -H "Content-Type: application/json" -d '{"username":"testuser","password":"password123"}'
# Use returned token for protected endpoint
curl -X POST http://localhost:8080/books -H "Content-Type: application/json" -H "Authorization: Bearer <token>" -d '{"title":"Dune","author":"Frank Herbert","readStatus":"READING"}'
curl "http://localhost:8080/books?page=1&size=10"
curl http://localhost:8080/login.html
```

- [ ] **Step 3: Verify log output**

Run: `docker compose logs app | head -20`
Expected: Structured log lines like `14:32:05.123 INFO  RequestLogger - GET /books 200 12ms 172.18.0.1`

- [ ] **Step 4: Commit any fixes**

If any fixes were needed during verification, commit them.
