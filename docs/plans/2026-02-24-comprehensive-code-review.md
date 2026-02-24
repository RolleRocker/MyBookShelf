# Comprehensive Code Review — Fix Plan

**Date:** 2026-02-24
**Scope:** Full codebase review across Java backend, frontend (JS/CSS/HTML), tests, infrastructure, and documentation
**Status:** Pending implementation

---

## Critical Issues

### C1: PUT allows `readStatus: null` — crashes JDBC or corrupts in-memory store

**Severity:** Critical
**File:** `src/main/java/com/bookshelf/BookController.java`, lines 315-317
**Problem:** The PUT handler permits `{"readStatus": null}`, which sets `readStatus` to `null` on the Book object. On `JdbcBookRepository.update()`, this violates the `NOT NULL` DB constraint and throws a `RuntimeException` caught as a generic 500. On `InMemoryBookRepository`, the null persists silently and causes `NullPointerException` later when filtering by readStatus.

**Current code:**
```java
if (json.has("readStatus")) {
    book.setReadStatus(json.get("readStatus").isJsonNull() ? null : ReadStatus.valueOf(json.get("readStatus").getAsString()));
}
```

**Fix:** Add a null guard before setting readStatus on PUT, returning 400:
```java
if (json.has("readStatus")) {
    if (json.get("readStatus").isJsonNull()) {
        return HttpResponse.badRequest("readStatus cannot be null");
    }
    try {
        book.setReadStatus(ReadStatus.valueOf(json.get("readStatus").getAsString()));
    } catch (IllegalArgumentException e) {
        return HttpResponse.badRequest("Invalid readStatus");
    }
}
```

**Test to add:** `testUpdateReadStatusToNullReturns400` — PUT with `{"readStatus": null}` expects 400.

---

### C2: ISBN change on PUT clears user-provided fields from the same request

**Severity:** Critical
**File:** `src/main/java/com/bookshelf/BookController.java`, lines 347-360
**Problem:** When ISBN changes on PUT, enrichable fields (publisher, publishDate, pageCount, subjects, coverUrl, coverData) are cleared *after* all JSON fields have been applied. So if a user sends `{"isbn": "newisbn", "publisher": "My Custom Publisher"}`, the publisher is set on ~line 304, then immediately cleared to null on ~line 354. The plan says user-provided fields in the same PUT should be preserved.

**Current code:**
```java
if (isbnChanged && newIsbn != null) {
    book.setPublisher(null);
    book.setPublishDate(null);
    book.setPageCount(null);
    book.setSubjects(null);
    book.setCoverUrl(null);
    book.setCoverData(null);
}
```

**Fix:** Only clear fields that were NOT explicitly provided in the request JSON:
```java
if (isbnChanged && newIsbn != null) {
    if (!json.has("publisher")) book.setPublisher(null);
    if (!json.has("publishDate")) book.setPublishDate(null);
    if (!json.has("pageCount")) book.setPageCount(null);
    if (!json.has("subjects")) book.setSubjects(null);
    if (!json.has("coverUrl")) book.setCoverUrl(null);
    book.setCoverData(null); // always clear binary data — re-enrichment will re-fetch
}
```

**Test to add:** `testIsbnChangePreservesUserProvidedFields` — PUT with `{"isbn": "newisbn", "publisher": "Custom"}` expects publisher to remain "Custom" in response.

---

### C3: `setFilter()` calls `loadBooks()` (API fetch) on every tab switch

**Severity:** Critical (performance/UX)
**File:** `static/app.js`, lines 539-545
**Problem:** `setFilter()` calls `loadBooks()` which does a `GET /books` API fetch every time the user clicks a filter tab. Since filtering is client-side from `allBooks`, this is an unnecessary network round-trip. Search input and sort dropdown already use `renderBooks(getFilteredBooks())` directly.

**Current code:**
```javascript
function setFilter(status) {
    currentFilter = status;
    filterTabs.forEach(tab => {
        tab.classList.toggle('active', tab.dataset.status === status);
    });
    loadBooks();
}
```

**Fix:**
```javascript
function setFilter(status) {
    currentFilter = status;
    filterTabs.forEach(tab => {
        tab.classList.toggle('active', tab.dataset.status === status);
    });
    renderBooks(getFilteredBooks());
}
```

**Verification:** Switch filter tabs with browser DevTools Network tab open — no `GET /books` request should fire.

---

## Important Issues

### I1: `InMemoryBookRepository.update()` ignores parameter semantics

**Severity:** Important
**File:** `src/main/java/com/bookshelf/InMemoryBookRepository.java`, lines 64-69
**Problem:** The method accepts `(UUID id, Book updates)` but does `store.put(id, updates)` — it replaces the stored book with the parameter object wholesale. This works today only because `BookController` mutates the same object reference fetched from the store, so by the time `update()` is called, the store already has the mutations. However, this is a lost-update race condition: two concurrent PUTs on the same book can silently lose one writer's changes. Also masks bugs if anyone ever calls `update()` with a different Book object.

**Fix:** Add a comment documenting the known limitation, or implement field-by-field merge:
```java
@Override
public Optional<Book> update(UUID id, Book updates) {
    // NOTE: This works because BookController mutates the existing object in-place
    // before calling update(). The `updates` param IS the same reference as store.get(id).
    // A proper implementation would merge fields, but this is test-only code.
    Book existing = store.get(id);
    if (existing == null) return Optional.empty();
    store.put(id, updates);
    return Optional.of(updates);
}
```

---

### I2: Subjects validation missing on POST

**Severity:** Important
**File:** `src/main/java/com/bookshelf/BookController.java`, `handleCreateBook` method
**Problem:** The PUT handler validates that `subjects` is a JSON array and returns 400 if not (lines 318-330). The POST handler has no such validation — if a client sends `{"subjects": "not-an-array", ...}` on POST, the field is silently ignored. This is inconsistent.

**Fix:** Add subjects parsing to `handleCreateBook`, matching the PUT logic:
```java
if (json.has("subjects") && !json.get("subjects").isJsonNull()) {
    if (!json.get("subjects").isJsonArray()) {
        return HttpResponse.badRequest("subjects must be an array");
    }
    List<String> subjects = new ArrayList<>();
    for (var el : json.getAsJsonArray("subjects")) {
        subjects.add(el.getAsString());
    }
    book.setSubjects(subjects);
}
```

**Test to add:** `testCreateBookWithNonArraySubjectsReturns400` — POST with `{"subjects": "string", ...}` expects 400.

---

### I3: Shutdown hook has no per-step try-catch

**Severity:** Important
**File:** `src/main/java/com/bookshelf/App.java`, lines 36-39
**Problem:** If `openLibraryService.shutdown()` throws, `server.stop()` and `dbConfig.close()` never execute, leaking resources.

**Current code:**
```java
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    openLibraryService.shutdown();
    server.stop();
    dbConfig.close();
}));
```

**Fix:**
```java
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    try { openLibraryService.shutdown(); } catch (Exception e) { System.err.println("Shutdown error: " + e.getMessage()); }
    try { server.stop(); } catch (Exception e) { System.err.println("Shutdown error: " + e.getMessage()); }
    try { dbConfig.close(); } catch (Exception e) { System.err.println("Shutdown error: " + e.getMessage()); }
}));
```

---

### I4: Dead `cover_path` column in DB migration

**Severity:** Important
**File:** `src/main/java/com/bookshelf/DatabaseConfig.java`, line 49
**Problem:** The `cover_path VARCHAR(512)` column is created in the schema but never read or written. Cover data is stored as `cover_data BYTEA`. This dead column confuses developers.

**Fix:** Remove `cover_path VARCHAR(512),` from the CREATE TABLE statement. Existing databases keep the column (harmless), new databases don't get it.

---

### I5: RuntimeException catch blocks swallow stack traces

**Severity:** Important
**File:** `src/main/java/com/bookshelf/BookController.java`, all handler methods
**Problem:** Every handler catches `RuntimeException` and returns a generic 500 with no logging. Stack traces are completely lost, making production debugging impossible.

**Fix:** Add `System.err.println` + `e.printStackTrace()` to each catch block. Example:
```java
} catch (RuntimeException e) {
    System.err.println("Error in handleGetBooks: " + e.getMessage());
    e.printStackTrace();
    return HttpResponse.internalServerError("Internal server error");
}
```

Apply to all handler methods: `handleGetBooks`, `handleGetBook`, `handleGetBookByIsbn`, `handleCreateBook`, `handleUpdateBook`, `handleDeleteBook`, `handleReEnrich`, `handleGetCover`.

---

### I6: `RequestParser.readLine()` reads one byte at a time (unbuffered)

**Severity:** Important
**File:** `src/main/java/com/bookshelf/RequestParser.java`, lines 84-109
**Problem:** Reading one byte at a time from an unbuffered socket `InputStream` incurs a system call per byte. For headers, this means hundreds of syscalls per request.

**Fix:** In `HttpServer.handleConnection()`, wrap the socket input stream in a `BufferedInputStream`:
```java
InputStream buffered = new BufferedInputStream(socket.getInputStream());
HttpRequest request = RequestParser.parse(buffered);
```

**Note:** The `ResponseWriter` uses the socket's `OutputStream` separately, so this change is safe.

---

### I7: Content-Length pre-allocation DoS vector

**Severity:** Important
**File:** `src/main/java/com/bookshelf/RequestParser.java`, lines 69-77
**Problem:** A client declaring `Content-Length: 10485760` (10MB, the MAX_BODY_SIZE) but sending no body forces a 10MB allocation that sits in memory until the 30-second socket timeout. Across 10 threads = 100MB wasted.

**Fix:** Read the body in chunks (e.g., 8KB) into a `ByteArrayOutputStream` instead of pre-allocating the full Content-Length array:
```java
byte[] buffer = new byte[8192];
ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.min(contentLength, 8192));
int totalRead = 0;
while (totalRead < contentLength) {
    int toRead = Math.min(buffer.length, contentLength - totalRead);
    int read = input.read(buffer, 0, toRead);
    if (read == -1) break;
    baos.write(buffer, 0, read);
    totalRead += read;
}
body = baos.toString(StandardCharsets.UTF_8);
```

---

### I8: Canvas created per frame in `captureROI`

**Severity:** Important
**File:** `static/app.js`, lines 630-642
**Problem:** `captureROI()` is called at ~12fps during scanning. Each call creates a new `<canvas>` element and 2D context, causing GC pressure and potential frame drops on low-end devices.

**Fix:** Cache the canvas and context, reuse across frames:
```javascript
let roiCanvas = null;
let roiCtx = null;

function captureROI(video) {
    // ... compute w, h, roiX, roiY, scale as before ...
    if (!roiCanvas) {
        roiCanvas = document.createElement('canvas');
        roiCtx = roiCanvas.getContext('2d', { willReadFrequently: true });
    }
    roiCanvas.width = w;
    roiCanvas.height = h;
    roiCtx.drawImage(video, roiX, roiY, roiW, roiH, 0, 0, w, h);
    // ... rest of function uses roiCtx instead of ctx ...
}
```

Reset on scanner close: add `roiCanvas = null; roiCtx = null;` inside `closeScanner()`.

---

### I9: Toast elements never removed if `animationend` doesn't fire

**Severity:** Important
**File:** `static/app.js`, lines 95-105
**Problem:** If CSS animations are disabled (`prefers-reduced-motion`) or interrupted, `animationend` never fires and toast DOM elements accumulate forever. During bulk scanning with many toasts, this is a memory leak.

**Fix:** Add a fallback timeout after the animation:
```javascript
setTimeout(() => {
    toast.classList.add('toast-out');
    toast.addEventListener('animationend', () => toast.remove());
    setTimeout(() => { if (toast.parentNode) toast.remove(); }, 500);
}, 4500);
```

---

### I10: `getSortedBooks` uses chained `if` instead of `if/else`

**Severity:** Important
**File:** `static/app.js`, lines 218-232
**Problem:** All four `if` branches execute on every comparator call. The logic works accidentally because only the matching branch sets `av`/`bv`, but an unknown field leaves them `undefined` and sorting silently does nothing.

**Fix:** Change to `else if` chain:
```javascript
if (field === 'title')        { av = (a.title  || '').toLowerCase(); bv = (b.title  || '').toLowerCase(); }
else if (field === 'author')  { av = (a.author || '').toLowerCase(); bv = (b.author || '').toLowerCase(); }
else if (field === 'rating')  { av = a.rating  || 0; bv = b.rating  || 0; }
else if (field === 'created') { av = a.createdAt || ''; bv = b.createdAt || ''; }
else return 0;
```

---

### I11: Toast container lacks `aria-live` for screen readers

**Severity:** Important
**File:** `static/index.html`, line 220
**Problem:** Toast notifications are invisible to assistive technology.

**Fix:**
```html
<div id="toast-container" class="toast-container" aria-live="polite" role="status"></div>
```

---

### I12: No `prefers-reduced-motion` media query

**Severity:** Important
**File:** `static/style.css`
**Problem:** Users who prefer reduced motion still see card entrance animations, pulse effects, and scan pulse.

**Fix:** Add at the end of `style.css`:
```css
@media (prefers-reduced-motion: reduce) {
    *, *::before, *::after {
        animation-duration: 0.01ms !important;
        animation-iteration-count: 1 !important;
        transition-duration: 0.01ms !important;
    }
}
```

---

### I13: `OpenLibraryTest` makes live network calls with no way to skip in CI

**Severity:** Important
**File:** `src/test/java/com/bookshelf/OpenLibraryTest.java`
**Problem:** Tests T25, T26, T29, T31, T32, T33 make real HTTP calls to `openlibrary.org` with polling loops up to 30s. Flaky under rate limiting.

**Fix:** Add `@Tag("integration")` to `OpenLibraryTest` class. Configure `build.gradle`:
```groovy
test {
    useJUnitPlatform {
        excludeTags 'integration'
    }
}

tasks.register('integrationTest', Test) {
    useJUnitPlatform {
        includeTags 'integration'
    }
}
```

---

### I14: Docker container runs as root

**Severity:** Important (security)
**File:** `Dockerfile`
**Problem:** No `USER` directive — the Java process runs as root inside the container.

**Fix:** Add non-root user to the runtime stage:
```dockerfile
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN groupadd -r appuser && useradd -r -g appuser appuser
COPY --from=build /app/build/libs/*-all.jar app.jar
COPY static/ static/
RUN chown -R appuser:appuser /app
USER appuser
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
```

---

### I15: Database credentials hardcoded in plain text

**Severity:** Important (security)
**File:** `docker-compose.yml`, lines 8-12 and 19-21
**Problem:** `DB_PASS: bookshelf` and `POSTGRES_PASSWORD: bookshelf` are committed to a public GitHub repo.

**Fix:** Move credentials to a `.env` file excluded from git:
1. Create `.env`:
   ```
   DB_PASS=bookshelf
   POSTGRES_PASSWORD=bookshelf
   ```
2. Update `docker-compose.yml` to use `${DB_PASS}` and `${POSTGRES_PASSWORD}`.
3. Add `.env` to `.gitignore`.
4. Add `.env.example` with placeholder values for documentation.

---

### I16: PostgreSQL port exposed to host unnecessarily

**Severity:** Important
**File:** `docker-compose.yml`, lines 25-26
**Problem:** `ports: - "5432:5432"` exposes PostgreSQL to the host network. Only needed for debugging.

**Fix:** Either remove the `ports` section entirely, or bind to localhost only:
```yaml
ports:
  - "127.0.0.1:5432:5432"
```

---

### I17: Plan file missing `DNF` from `ReadStatus` enum

**Severity:** Important (documentation)
**File:** `bookshelf-api-plan.md`, line 46, and `CLAUDE.md` data model table
**Problem:** The `ReadStatus` enum now has 4 values (`WANT_TO_READ`, `READING`, `FINISHED`, `DNF`) but the plan file and CLAUDE.md data model table only list 3.

**Fix:** Update both files to include `DNF` in the enum definition.

---

## Suggestions

### S1: `OpenLibraryService` import name collision risk

**File:** `src/main/java/com/bookshelf/OpenLibraryService.java`, lines 11-12
**Problem:** Imports `java.net.http.HttpRequest` and `java.net.http.HttpResponse` which collide with project's `com.bookshelf.HttpRequest/HttpResponse`. Works today because no wildcard import, but a refactoring could break it.
**Fix:** Use fully-qualified names for the JDK HTTP types inside this file.

---

### S2: Thread pool (10) == DB pool (10) — saturation risk

**Files:** `HttpServer.java` line 25, `DatabaseConfig.java` line 27
**Problem:** Handlers like `handleUpdateBook` make multiple DB calls (`findById` + `update`). If all 10 threads hit the DB simultaneously, they may block waiting for connections.
**Fix:** Consider DB pool = 15 (1.5x server threads) or document the current sizing rationale.

---

### S3: `escapeHtml()` creates throwaway DOM element per call

**File:** `static/app.js`, lines 210-214
**Fix:** Replace with string-based escaping:
```javascript
function escapeHtml(str) {
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
              .replace(/"/g, '&quot;').replace(/'/g, '&#039;');
}
```

---

### S4: 300-char single-line template for subject tags

**File:** `static/app.js`, line 181
**Fix:** Extract a `renderSubjectTags(book)` helper function for readability.

---

### S5: 15+ global mutable variables

**File:** `static/app.js`, lines 1-12, 548-563
**Fix:** Group related state into a `const state = { filter, books, searchQuery, sort, ... }` object.

---

### S6: No `<noscript>` fallback

**File:** `static/index.html`
**Fix:** Add `<noscript><p>This app requires JavaScript to run.</p></noscript>` inside `<body>`.

---

### S7: Filter tabs lack ARIA tab semantics

**File:** `static/index.html`
**Fix:** Add `role="tablist"` to the filter bar container, `role="tab"` and `aria-selected` to each filter button.

---

### S8: No way to clear rating in edit modal

**File:** `static/app.js`, lines 501-504
**Fix:** Add a "Clear rating" link/button next to the star picker that sets `editRating.dataset.currentValue = 0` and visually empties all stars.

---

### S9: Missing edge-case tests

**File:** `src/test/java/com/bookshelf/BookApiTest.java`
**Tests to add:**
- `testGetBookWithNonUuidReturns404` — `GET /books/not-a-uuid` expects 404
- `testNegativeReadingProgressReturns400` — POST/PUT with `readingProgress: -1` expects 400
- `testEmptyPutBodyReturns400` — `PUT /books/{id}` with empty body expects 400
- `testSortWithoutDirectionDefaultsAsc` — `?sort=title` (no `,asc`) expects ascending order

---

### S10: Unit test mixed into integration test class

**File:** `src/test/java/com/bookshelf/BookApiTest.java`, lines 798-812
**Problem:** `testBadRequestBodyWithControlCharsIsValidJson` directly calls `HttpResponse.badRequest()` without HTTP.
**Fix:** Move to a separate `HttpResponseTest` class.

---

### S11: `Thread.sleep(100)` for server startup — flaky

**File:** `src/test/java/com/bookshelf/BookApiTest.java`, line 46
**Fix:** Poll the server port until it accepts a connection, with a timeout.

---

### S12: Inconsistent test naming

**File:** `src/test/java/com/bookshelf/BookApiTest.java`
**Problem:** Mix of `testTXX_camelCase` and `testCamelCase` conventions.
**Fix:** Standardize on one convention (recommend `testTXX_` for plan-mapped tests, `testDescriptiveName` for additions).

---

### S13: Dependency versions slightly dated

**File:** `build.gradle`
**Current → Recommended:** gson 2.11→2.12, postgresql 42.7.3→42.7.5+, HikariCP 5.1→6.x, JUnit 5.10.2→5.11.x, shadow 8.1.1→8.3.x, Gradle wrapper 8.5→8.12+
**Fix:** Run `./gradlew wrapper --gradle-version=8.12` and update dependency versions in `build.gradle`.

---

### S14: No test logging configuration

**File:** `build.gradle`
**Fix:** Add to `test` block:
```groovy
testLogging {
    exceptionFormat = 'full'
    events 'passed', 'skipped', 'failed'
}
```

---

### S15: Missing `.dockerignore`

**File:** (new) `.dockerignore`
**Fix:** Create with:
```
.git
build
.gradle
*.md
docs
.env
```

---

### S16: Plan V2 references obsolete file-based cover storage

**File:** `bookshelf-api-plan.md`, ~line 363
**Problem:** Says `coverPath: String — Local file path to the saved cover image`. Implementation uses `cover_data BYTEA`.
**Fix:** Update plan to reflect DB-based cover storage.
