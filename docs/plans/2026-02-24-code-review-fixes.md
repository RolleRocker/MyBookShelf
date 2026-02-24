# Code Review Fixes Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** Fix eight concrete bugs and code-quality issues identified by the parallel codebase review: two BookController bugs, one HttpResponse escape bug, one StaticFileHandler MIME type gap, one RequestParser DoS vector, and three frontend issues in app.js.

**Architecture:** Five backend fixes (Java, tested with JUnit 5 + HttpClient or raw Socket), three frontend fixes (app.js, verified manually). All backend tasks follow TDD. Frontend tasks have no automated tests but include exact manual verification steps.

**Tech Stack:** Java 17, Gson, JUnit 5, vanilla JS (no framework), Gradle

**Run tests with:** `./gradlew test` (uses InMemoryBookRepository — no Docker needed)
**Run server:** `docker compose up --build -d` (after all tasks complete)

---

## Context for Every Task

- Project root: `C:\Users\rolan\MyBookShelf`
- Main source: `src/main/java/com/bookshelf/`
- Test source: `src/test/java/com/bookshelf/`
- Frontend: `static/app.js`
- All existing tests (99) must remain green after every commit

---

## Task 1: Fix genre filter whitespace bug in BookController

**Problem:** `BookController.handleGetBooks()` line 56 uses `!genre.isEmpty()` to guard the genre filter. A request like `GET /books?genre=%20%20` (URL-encoded spaces) passes the `isEmpty()` check, so `repository.findByGenre("  ")` is called and returns zero results instead of all books. `search` already uses `isBlank()` correctly (line 54). This is an inconsistency that is a real user-facing bug.

**Files:**
- Modify: `src/main/java/com/bookshelf/BookController.java` (line 56)
- Test: `src/test/java/com/bookshelf/BookApiTest.java`

---

**Step 1: Write the failing test**

Add at the end of `BookApiTest.java`, before the closing `}`:

```java
@Test
void testGenreFilterWhitespaceOnlyReturnsAllBooks() throws Exception {
    post("/books", createBookJson("Dune", "Frank Herbert", "READING", "sci-fi", null, null));
    post("/books", createBookJson("1984", "George Orwell", "WANT_TO_READ", "dystopia", null, null));

    // ?genre=%20%20 decodes to "  " (two spaces); should be treated as "no genre filter"
    HttpResponse<String> resp = get("/books?genre=%20%20");
    assertEquals(200, resp.statusCode());
    JsonArray books = JsonParser.parseString(resp.body()).getAsJsonArray();
    assertEquals(2, books.size(), "Whitespace-only genre param should not filter out books");
}
```

**Step 2: Run the test to confirm it fails**

```bash
./gradlew test --tests "com.bookshelf.BookApiTest.testGenreFilterWhitespaceOnlyReturnsAllBooks"
```

Expected: FAIL — returns 0 books instead of 2.

**Step 3: Apply the fix**

In `BookController.java`, find line 56 (the `else if (genre != null ...` branch):

```java
// BEFORE (line 56):
} else if (genre != null && !genre.isEmpty()) {

// AFTER:
} else if (genre != null && !genre.isBlank()) {
```

**Step 4: Run the test to confirm it passes**

```bash
./gradlew test --tests "com.bookshelf.BookApiTest.testGenreFilterWhitespaceOnlyReturnsAllBooks"
```

Expected: PASS

**Step 5: Run all tests**

```bash
./gradlew test
```

Expected: All 100 tests pass.

**Step 6: Commit**

```bash
git add src/main/java/com/bookshelf/BookController.java src/test/java/com/bookshelf/BookApiTest.java
git commit -m "fix: use isBlank() for genre filter to treat whitespace-only param as no filter"
```

---

## Task 2: Fix subjects non-array crash → 400 in BookController

**Problem:** `BookController.handleUpdateBook()` around line 318–326, when the client sends `{"subjects": "not-an-array"}` (a string instead of an array), `getAsJsonArray()` throws `IllegalStateException`. The outer `catch (RuntimeException e)` catches it and returns 500. Should return 400.

**Files:**
- Modify: `src/main/java/com/bookshelf/BookController.java` (lines 318–326)
- Test: `src/test/java/com/bookshelf/BookApiTest.java`

---

**Step 1: Write the failing test**

Add to `BookApiTest.java`:

```java
@Test
void testSubjectsNotArrayReturns400() throws Exception {
    HttpResponse<String> create = post("/books", createBookJson("Dune", "Frank Herbert", "READING"));
    String id = getIdFromResponse(create);

    // "subjects" is a string, not an array — should be 400, not 500
    HttpResponse<String> update = put("/books/" + id, "{\"subjects\": \"not-an-array\"}");
    assertEquals(400, update.statusCode());
}
```

**Step 2: Run to confirm it fails**

```bash
./gradlew test --tests "com.bookshelf.BookApiTest.testSubjectsNotArrayReturns400"
```

Expected: FAIL — returns 500 instead of 400.

**Step 3: Apply the fix**

In `BookController.java`, locate the subjects block inside `handleUpdateBook()`. It looks like this:

```java
if (json.has("subjects")) {
    if (json.get("subjects").isJsonNull()) {
        book.setSubjects(null);
    } else {
        List<String> subjects = new ArrayList<>();
        json.get("subjects").getAsJsonArray().forEach(e -> subjects.add(e.getAsString()));
        book.setSubjects(subjects);
    }
}
```

Replace the inner `else` block to wrap the parsing in a try-catch:

```java
if (json.has("subjects")) {
    if (json.get("subjects").isJsonNull()) {
        book.setSubjects(null);
    } else {
        List<String> subjects = new ArrayList<>();
        try {
            json.get("subjects").getAsJsonArray().forEach(e -> subjects.add(e.getAsString()));
        } catch (IllegalStateException | UnsupportedOperationException e) {
            return HttpResponse.badRequest("'subjects' must be an array of strings");
        }
        book.setSubjects(subjects);
    }
}
```

- `IllegalStateException`: thrown when `subjects` is a JSON primitive or object (not an array)
- `UnsupportedOperationException`: thrown when an element inside the array is a nested object

**Step 4: Run the test to confirm it passes**

```bash
./gradlew test --tests "com.bookshelf.BookApiTest.testSubjectsNotArrayReturns400"
```

Expected: PASS

**Step 5: Run all tests**

```bash
./gradlew test
```

Expected: All 101 tests pass.

**Step 6: Commit**

```bash
git add src/main/java/com/bookshelf/BookController.java src/test/java/com/bookshelf/BookApiTest.java
git commit -m "fix: return 400 when subjects field is not a JSON array instead of 500"
```

---

## Task 3: Fix HttpResponse.escapeJson() missing control characters

**Problem:** `HttpResponse.escapeJson()` (line 73–75) only escapes `\` and `"`. If an error message ever contains `\n`, `\r`, or `\t`, the resulting JSON string is invalid (raw control characters are not allowed inside JSON strings). The fix adds escaping for the three most common control characters.

**Files:**
- Modify: `src/main/java/com/bookshelf/HttpResponse.java` (lines 73–75)
- Test: `src/test/java/com/bookshelf/BookApiTest.java`

---

**Step 1: Write the failing test**

Add to `BookApiTest.java` (this is a pure unit test — no network call needed):

```java
@Test
void testBadRequestBodyWithControlCharsIsValidJson() {
    // Directly test HttpResponse.badRequest() with control characters in the message
    com.bookshelf.HttpResponse resp = com.bookshelf.HttpResponse.badRequest("line1\nline2\ttab\r");
    // Gson must be able to parse the body without throwing
    JsonObject json = JsonParser.parseString(resp.getBody()).getAsJsonObject();
    // The error field must round-trip correctly
    assertEquals("line1\nline2\ttab\r", json.get("error").getAsString());
}
```

**Step 2: Run to confirm it fails**

```bash
./gradlew test --tests "com.bookshelf.BookApiTest.testBadRequestBodyWithControlCharsIsValidJson"
```

Expected: FAIL — `JsonParser.parseString()` throws because the raw newline makes the body invalid JSON.

**Step 3: Apply the fix**

In `HttpResponse.java`, replace lines 73–75:

```java
// BEFORE:
private static String escapeJson(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
}

// AFTER:
private static String escapeJson(String s) {
    return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
}
```

Order matters: `\\` must be replaced first to avoid double-escaping.

**Step 4: Run the test to confirm it passes**

```bash
./gradlew test --tests "com.bookshelf.BookApiTest.testBadRequestBodyWithControlCharsIsValidJson"
```

Expected: PASS

**Step 5: Run all tests**

```bash
./gradlew test
```

Expected: All 102 tests pass.

**Step 6: Commit**

```bash
git add src/main/java/com/bookshelf/HttpResponse.java src/test/java/com/bookshelf/BookApiTest.java
git commit -m "fix: escape newline, carriage return, and tab in JSON error messages"
```

---

## Task 4: Add .wasm MIME type to StaticFileHandler

**Problem:** `StaticFileHandler.CONTENT_TYPES` has 9 entries and is missing `.wasm → application/wasm`. The barcode scanner currently uses an inlined `.js` file so the impact is low, but serving `.wasm` as `application/octet-stream` can cause browsers to refuse it as a WebAssembly module.

There is no automated test for this change (would require a `.wasm` fixture file). The fix is a one-line addition.

**Files:**
- Modify: `src/main/java/com/bookshelf/StaticFileHandler.java` (line 10–20)

---

**Step 1: Apply the fix**

`Map.of()` supports up to 10 entries. We currently have 9; adding `.wasm` hits the limit exactly.

In `StaticFileHandler.java`, replace the `CONTENT_TYPES` map:

```java
// BEFORE:
private static final Map<String, String> CONTENT_TYPES = Map.of(
        ".html", "text/html",
        ".css", "text/css",
        ".js", "application/javascript",
        ".json", "application/json",
        ".png", "image/png",
        ".jpg", "image/jpeg",
        ".jpeg", "image/jpeg",
        ".svg", "image/svg+xml",
        ".ico", "image/x-icon"
);

// AFTER:
private static final Map<String, String> CONTENT_TYPES = Map.of(
        ".html", "text/html",
        ".css", "text/css",
        ".js", "application/javascript",
        ".json", "application/json",
        ".wasm", "application/wasm",
        ".png", "image/png",
        ".jpg", "image/jpeg",
        ".jpeg", "image/jpeg",
        ".svg", "image/svg+xml",
        ".ico", "image/x-icon"
);
```

**Step 2: Run all tests to confirm nothing broke**

```bash
./gradlew test
```

Expected: All 102 tests pass.

**Step 3: Commit**

```bash
git add src/main/java/com/bookshelf/StaticFileHandler.java
git commit -m "fix: add .wasm -> application/wasm MIME type to StaticFileHandler"
```

---

## Task 5: Add header line length cap to RequestParser

**Problem:** `RequestParser.readLine()` has no limit on how long a single header line can be. An attacker (or misconfigured client) sending an 8 KB+ header line causes the server to allocate unbounded memory per connection. The body size is already capped at 10 MB (`MAX_BODY_SIZE`), but headers are unguarded. Fix: add an 8 KB cap per line; throw `IOException` if exceeded (server returns 400).

**Files:**
- Modify: `src/main/java/com/bookshelf/RequestParser.java` (lines 12 and 83–102)
- Test: `src/test/java/com/bookshelf/BookApiTest.java`

---

**Step 1: Write the failing test**

This test uses a raw `java.net.Socket` because Java's `HttpClient` itself rejects malformed requests before sending them.

Add the following imports to `BookApiTest.java` if not already present (they likely aren't):

```java
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
```

Add the test:

```java
@Test
void testOversizedHeaderReturns400() throws Exception {
    // Send a raw HTTP request with a header line > 8 KB, bypassing HttpClient
    try (Socket socket = new Socket("localhost", port)) {
        socket.setSoTimeout(5000);
        OutputStream out = socket.getOutputStream();
        // "X-Test: " (8 chars) + 8193 'a' chars = 8201-char line, exceeds the 8 KB cap
        String bigHeader = "X-Test: " + "a".repeat(8193);
        String request = "GET /books HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                bigHeader + "\r\n" +
                "\r\n";
        out.write(request.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.flush();

        // Read the first chunk of the response
        byte[] buf = new byte[256];
        int n = socket.getInputStream().read(buf);
        assertTrue(n > 0, "Server must respond");
        String responseStart = new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(responseStart.startsWith("HTTP/1.1 400"),
                "Expected 400 for oversized header, got: " + responseStart);
    }
}
```

**Step 2: Run to confirm it fails**

```bash
./gradlew test --tests "com.bookshelf.BookApiTest.testOversizedHeaderReturns400"
```

Expected: FAIL — the server hangs or eventually reads the full header (no cap exists yet).

**Step 3: Apply the fix**

In `RequestParser.java`:

Add the constant after `MAX_BODY_SIZE` (line 12):

```java
private static final int MAX_BODY_SIZE = 10 * 1024 * 1024; // 10 MB
private static final int MAX_HEADER_LINE_SIZE = 8 * 1024;  // 8 KB per header line
```

Replace the `readLine()` method (lines 83–102) entirely:

```java
private static String readLine(InputStream input) throws IOException {
    StringBuilder sb = new StringBuilder();
    int c;
    while ((c = input.read()) != -1) {
        if (c == '\r') {
            int next = input.read(); // consume \n
            if (next != '\n' && next != -1) {
                sb.append((char) c);
                sb.append((char) next);
                if (sb.length() > MAX_HEADER_LINE_SIZE) {
                    throw new IOException("Header line too long");
                }
                continue;
            }
            break;
        }
        if (c == '\n') {
            break;
        }
        sb.append((char) c);
        if (sb.length() > MAX_HEADER_LINE_SIZE) {
            throw new IOException("Header line too long");
        }
    }
    return sb.length() == 0 && c == -1 ? null : sb.toString();
}
```

The length check fires after each character is appended. This caps both the request line and every header line at 8 KB.

**Step 4: Run the test to confirm it passes**

```bash
./gradlew test --tests "com.bookshelf.BookApiTest.testOversizedHeaderReturns400"
```

Expected: PASS

**Step 5: Run all tests**

```bash
./gradlew test
```

Expected: All 103 tests pass.

**Step 6: Commit**

```bash
git add src/main/java/com/bookshelf/RequestParser.java src/test/java/com/bookshelf/BookApiTest.java
git commit -m "fix: cap header line length at 8 KB in RequestParser to prevent memory DoS"
```

---

## Task 6: Add readingProgress bounds validation to the edit modal

**Problem:** `submitEdit()` in `app.js` (line 501–503) calls `parseInt(editProgress.value, 10)` and sends the result directly. If the user types `150`, the server rejects it with a 400 error toast, but the modal stays open with no indication of what was wrong. The fix validates bounds client-side and shows a clear toast before the API call.

**Files:**
- Modify: `static/app.js` (lines 501–503, inside `submitEdit()`)

---

**Step 1: Apply the fix**

Locate this block in `submitEdit()`:

```javascript
updates.readingProgress = (editStatus.value === 'READING' || editStatus.value === 'DNF') && editProgress.value !== ''
    ? parseInt(editProgress.value, 10)
    : null;
```

Replace it with:

```javascript
if ((editStatus.value === 'READING' || editStatus.value === 'DNF') && editProgress.value !== '') {
    const progressVal = parseInt(editProgress.value, 10);
    if (isNaN(progressVal) || progressVal < 0 || progressVal > 100) {
        showToast('Reading progress must be between 0 and 100', 'error');
        return;
    }
    updates.readingProgress = progressVal;
} else {
    updates.readingProgress = null;
}
```

**Step 2: Manual verification**

1. Open the app in the browser
2. Add a book (any ISBN), set status to "Reading"
3. Open the edit modal, type `150` in the progress field, click "Save Changes"
4. **Expected:** Red toast "Reading progress must be between 0 and 100" appears; modal stays open; no API call is made
5. Change progress to `75`, click "Save Changes"
6. **Expected:** Modal closes, success toast, card shows 75% progress bar

**Step 3: Commit**

```bash
git add static/app.js
git commit -m "fix: validate readingProgress bounds (0-100) in edit modal before API call"
```

---

## Task 7: Debounce the search input

**Problem:** `searchInput.addEventListener('input', ...)` in `app.js` (lines 1049–1052) re-renders the entire book grid on every keystroke. Typing "dune" (4 chars) triggers 4 full re-renders. With a large library, this can cause visible lag. Fix: add a 300 ms debounce.

**Files:**
- Modify: `static/app.js` (lines 1049–1052)

---

**Step 1: Apply the fix**

Locate this block near the end of `app.js` in the event listeners section:

```javascript
// Search bar
searchInput.addEventListener('input', () => {
    searchQuery = searchInput.value.trim();
    renderBooks(getFilteredBooks());
});
```

Replace it with:

```javascript
// Search bar — debounced to avoid re-rendering on every keystroke
let searchDebounceTimer;
searchInput.addEventListener('input', () => {
    clearTimeout(searchDebounceTimer);
    searchDebounceTimer = setTimeout(() => {
        searchQuery = searchInput.value.trim();
        renderBooks(getFilteredBooks());
    }, 300);
});
```

**Step 2: Manual verification**

1. Open the app
2. Type "dune" quickly in the search box
3. **Expected:** The grid does NOT flicker on each letter — it updates once ~300 ms after you stop typing
4. Clear the search box — **Expected:** All books reappear after 300 ms

**Step 3: Commit**

```bash
git add static/app.js
git commit -m "fix: debounce search input at 300ms to prevent per-keystroke re-renders"
```

---

## Task 8: Fix loadBooks() dual API call race condition

**Problem:** `loadBooks()` (lines 335–355) makes two API calls when a status filter tab is active:
1. `GET /books?readStatus=READING` → sets `allBooks` (filtered)
2. `GET /books` → updates the tab count badges

If the user switches filter tabs between these two calls, the second response updates counts for the wrong state. Also, `allBooks` contains only the filtered subset, so searching within a filtered view searches a smaller dataset than expected.

**Fix:** Always fetch `GET /books` (all books), then let `getFilteredBooks()` (which already has the status-filter logic at line 247–249) filter in memory. One API call, no race, always-correct counts.

**Files:**
- Modify: `static/app.js` (lines 335–355)

---

**Step 1: Apply the fix**

Replace the entire `loadBooks()` function:

```javascript
// BEFORE:
async function loadBooks() {
    try {
        let path = '/books';
        if (currentFilter !== 'all') {
            path += `?readStatus=${currentFilter}`;
        }
        const books = await apiGet(path);
        allBooks = books;
        renderBooks(getFilteredBooks());

        // Fetch all books for counts (if filtering, we need full list for counts)
        if (currentFilter !== 'all') {
            const allList = await apiGet('/books');
            updateCounts(allList);
        } else {
            updateCounts(books);
        }
    } catch (e) {
        showToast('Failed to load books', 'error');
    }
}

// AFTER:
async function loadBooks() {
    try {
        const books = await apiGet('/books');
        allBooks = books;
        renderBooks(getFilteredBooks());
        updateCounts(books);
    } catch (e) {
        showToast('Failed to load books', 'error');
    }
}
```

`getFilteredBooks()` (line 234–251) already applies `currentFilter` at line 247–249, so the rendered grid is still correctly filtered. `updateCounts()` always receives the full book list, so all tab badges are accurate.

**Step 2: Manual verification**

1. Open the app with books in different statuses (READING, FINISHED, WANT_TO_READ, DNF)
2. Click "Reading" tab — **Expected:** only READING books shown; all count badges (including Finished, Want to Read) display correct totals
3. Search for a title while on the "Reading" tab — **Expected:** shows only books matching search AND with READING status
4. Click "Refresh Library" — **Expected:** completes with a single batch of toasts, no duplicate count flicker
5. Rapidly click through filter tabs — **Expected:** counts remain correct throughout (no stale data)

**Step 3: Commit**

```bash
git add static/app.js
git commit -m "fix: loadBooks() always fetches all books once; getFilteredBooks() handles in-memory filtering"
```

---

## Final step: Rebuild Docker and verify end-to-end

```bash
./gradlew test   # all 103 tests must pass
docker compose up --build -d
```

Then verify in browser:
- Tab counts are correct when switching filters
- Search debounces properly
- Edit modal rejects progress >100 with a clear message
- Genre filter with whitespace returns all books (can verify via `curl "http://localhost:8080/books?genre=%20"`)
