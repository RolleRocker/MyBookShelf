# Search, Sorting & Reading Progress — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add three user-facing features: full-text search by title/author, sort controls, and a reading-progress percentage for books currently being read.

**Architecture:** All three features are additive — no breaking changes. Search and sorting are pure query-parameter extensions on `GET /books`. Reading progress adds one nullable column (`reading_progress INTEGER`) to the DB schema and one new field throughout the stack. Frontend adds a search bar in the header, a sort dropdown in the filter bar, and a progress input in the edit modal.

**Tech Stack:** Java 17, PostgreSQL (SQL `ILIKE`, `ORDER BY`), Vanilla JS/CSS, Gradle, Docker Compose.

---

## Feature A — Search (`GET /books?search=dune`)

### Task A1: Backend — add `findBySearch()` to repository

**Files:**
- Modify: `src/main/java/com/bookshelf/BookRepository.java`
- Modify: `src/main/java/com/bookshelf/InMemoryBookRepository.java`
- Modify: `src/main/java/com/bookshelf/JdbcBookRepository.java`

**Step 1: Add method to interface**

In `BookRepository.java` after `findByReadStatus`:
```java
List<Book> findBySearch(String query);
```

**Step 2: Implement in `InMemoryBookRepository`**

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

**Step 3: Implement in `JdbcBookRepository`**

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

**Step 4: Wire into `BookController.handleGetBooks()`**

In `BookController.java`, inside `handleGetBooks()`, after the existing `genre`/`readStatus` params:

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

// Apply readStatus filter on top of search or genre result
if (readStatus != null && (search != null || (genre != null && !genre.isEmpty()))) {
    ReadStatus finalReadStatus = readStatus;
    books = books.stream().filter(b -> b.getReadStatus() == finalReadStatus).toList();
}
```

**Step 5: Run tests — all 38 should still pass**
```
./gradlew test --rerun-tasks
```
Expected: `BUILD SUCCESSFUL`

**Step 6: Commit**
```bash
git add src/main/java/com/bookshelf/BookRepository.java \
        src/main/java/com/bookshelf/InMemoryBookRepository.java \
        src/main/java/com/bookshelf/JdbcBookRepository.java \
        src/main/java/com/bookshelf/BookController.java
git commit -m "feat: add GET /books?search= for title/author search"
```

---

### Task A2: Write a test for search

**Files:**
- Modify: `src/test/java/com/bookshelf/BookApiTest.java`

**Step 1: Add test after existing filter tests**

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

**Step 2: Run tests**
```
./gradlew test --rerun-tasks
```
Expected: `BUILD SUCCESSFUL` (40 tests now)

**Step 3: Commit**
```bash
git add src/test/java/com/bookshelf/BookApiTest.java
git commit -m "test: add search tests for title and author"
```

---

### Task A3: Frontend — search bar in header

**Files:**
- Modify: `static/index.html`
- Modify: `static/app.js`
- Modify: `static/style.css`

**Step 1: Add search input to `index.html`**

Add a second row in `.catalog-search`, below `.search-terminal`:
```html
<div class="search-bar">
    <input
        type="search"
        id="search-input"
        placeholder="Search by title or author..."
        autocomplete="off"
        spellcheck="false"
    >
</div>
```

**Step 2: Add JS logic in `app.js`**

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
    return books;
}
```

Update all calls to `renderBooks(allBooks)` → `renderBooks(getFilteredBooks())`, and update filter tab click handlers to also call `renderBooks(getFilteredBooks())`.

**Note:** Search is done client-side on the already-loaded `allBooks` array — no extra API call needed. This is sufficient for a personal shelf (hundreds of books, not millions).

**Step 3: Add CSS for `.search-bar`**

```css
.search-bar {
    margin-top: 0.5rem;
}

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

.search-bar input:focus {
    border-color: var(--gold-dim);
}
```

**Step 4: Run tests**
```
./gradlew test --rerun-tasks
```
Expected: `BUILD SUCCESSFUL`

**Step 5: Commit**
```bash
git add static/index.html static/app.js static/style.css
git commit -m "feat: add client-side search bar to frontend"
```

---

## Feature B — Sorting (`?sort=title,asc`)

### Task B1: Backend — add sort parameter to `GET /books`

**Files:**
- Modify: `src/main/java/com/bookshelf/BookController.java`

The backend already loads all books into a `List<Book>`. Rather than adding an `ORDER BY` to every repository method, sorting is applied in `BookController` after fetching, using a `Comparator`. This keeps the repository lean.

**Step 1: Add sort logic in `handleGetBooks()`**

After the filtering block, before `return HttpResponse.ok(...)`:

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

**Step 2: Run tests**
```
./gradlew test --rerun-tasks
```
Expected: `BUILD SUCCESSFUL`

**Step 3: Commit**
```bash
git add src/main/java/com/bookshelf/BookController.java
git commit -m "feat: add ?sort=field,asc|desc to GET /books"
```

---

### Task B2: Write tests for sorting

**Files:**
- Modify: `src/test/java/com/bookshelf/BookApiTest.java`

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
    assertEquals("Dune", books.get(0).getAsJsonObject().get("title").getAsString());
    assertEquals("Zorro", books.get(1).getAsJsonObject().get("title").getAsString());
}
```

**Run and commit as with Task A2.**

---

### Task B3: Frontend — sort dropdown in filter bar

**Files:**
- Modify: `static/index.html`
- Modify: `static/app.js`
- Modify: `static/style.css`

**Step 1: Add sort select to `index.html` filter bar**

Add before the closing `</div>` of `.filter-inner`:
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

**Step 2: Add sort logic in `app.js`**

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

Update `getFilteredBooks()` to pipe through `getSortedBooks()` at the end:
```javascript
function getFilteredBooks() {
    let books = allBooks;
    // ... existing filter logic ...
    return getSortedBooks(books);
}
```

**Step 3: Add CSS**

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

**Step 4: Run tests and commit**

---

## Feature C — Reading Progress

A `readingProgress` field (0–100 integer) tracks how far through a book you are. Only meaningful for `READING` status. Displayed as a thin gold progress bar on the card.

### Task C1: DB schema migration

**Files:**
- Modify: `src/main/java/com/bookshelf/DatabaseConfig.java`

**Step 1: Find the schema migration SQL** in `DatabaseConfig.java` (look for the `CREATE TABLE books` block).

**Step 2: Add column to migration**

The migration must use `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` so it's safe to re-run on an existing DB:

```java
// After the CREATE TABLE statement, add:
conn.createStatement().execute(
    "ALTER TABLE books ADD COLUMN IF NOT EXISTS reading_progress INTEGER DEFAULT NULL"
);
```

**Step 3: Run tests**
```
./gradlew test --rerun-tasks
```
Expected: `BUILD SUCCESSFUL`

**Step 4: Commit**
```bash
git add src/main/java/com/bookshelf/DatabaseConfig.java
git commit -m "feat: add reading_progress column to books schema"
```

---

### Task C2: Add `readingProgress` field to `Book` model

**Files:**
- Modify: `src/main/java/com/bookshelf/Book.java`

**Step 1: Add field and accessor**

```java
private Integer readingProgress; // 0–100, only meaningful when readStatus == READING

public Integer getReadingProgress() { return readingProgress; }
public void setReadingProgress(Integer readingProgress) { this.readingProgress = readingProgress; }
```

**Step 2: Run tests** — should still pass (new nullable field, no existing code touches it yet).

**Step 3: Commit**

---

### Task C3: Persist `readingProgress` in `JdbcBookRepository`

**Files:**
- Modify: `src/main/java/com/bookshelf/JdbcBookRepository.java`

**Step 1: Add to `save()` INSERT**

Extend the INSERT column list to include `reading_progress` and add `setNullableInt(stmt, N, book.getReadingProgress())` at the appropriate position.

**Step 2: Add to `update()` UPDATE**

Add `reading_progress = ?` to the SET clause and bind `setNullableInt(stmt, N, book.getReadingProgress())`.

**Step 3: Add to `mapRow()`**

```java
int readingProgress = rs.getInt("reading_progress");
book.setReadingProgress(rs.wasNull() ? null : readingProgress);
```

**Step 4: Run tests**
```
./gradlew test --rerun-tasks
```
Expected: `BUILD SUCCESSFUL`

**Step 5: Commit**
```bash
git add src/main/java/com/bookshelf/JdbcBookRepository.java \
        src/main/java/com/bookshelf/Book.java
git commit -m "feat: persist readingProgress field in DB"
```

---

### Task C4: Handle `readingProgress` in `BookController`

**Files:**
- Modify: `src/main/java/com/bookshelf/BookController.java`

**Step 1: Accept field in `handleCreateBook()`**

After the existing `pageCount` block:
```java
if (json.has("readingProgress") && !json.get("readingProgress").isJsonNull()) {
    int progress = json.get("readingProgress").getAsInt();
    if (progress < 0 || progress > 100) {
        return HttpResponse.badRequest("readingProgress must be between 0 and 100");
    }
    book.setReadingProgress(progress);
}
```

**Step 2: Accept field in `handleUpdateBook()`**

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

**Step 3: Write a test**

```java
@Test
void testReadingProgress() throws Exception {
    // Create a READING book
    JsonObject body = new JsonObject();
    body.addProperty("title", "Dune");
    body.addProperty("author", "Frank Herbert");
    body.addProperty("readStatus", "READING");
    body.addProperty("readingProgress", 42);
    // POST and verify progress is stored and returned
    // PUT with new progress value and verify update
}
```

**Step 4: Run tests and commit**

---

### Task C5: Show progress bar in frontend

**Files:**
- Modify: `static/app.js`
- Modify: `static/style.css`
- Modify: `static/index.html` (edit modal)

**Step 1: Add progress bar to `createBookCard()` in `app.js`**

Inside the card HTML, after `.book-stars`, add:
```javascript
${book.readStatus === 'READING' && book.readingProgress != null
    ? `<div class="progress-bar-wrap" title="${book.readingProgress}% read">
         <div class="progress-bar-fill" style="width:${book.readingProgress}%"></div>
       </div>`
    : ''}
```

**Step 2: Add progress input to edit modal in `index.html`**

Inside `#edit-form`, add a group that shows/hides based on read status:
```html
<div class="form-group" id="progress-group" hidden>
    <label for="edit-progress">Reading Progress (%)</label>
    <input type="number" id="edit-progress" min="0" max="100" placeholder="0–100">
</div>
```

In `app.js`, show/hide `#progress-group` when `edit-status` changes and when opening the modal.

**Step 3: Include `readingProgress` in the PUT body when saving edits.**

**Step 4: Add CSS**

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

**Step 5: Run tests**
```
./gradlew test --rerun-tasks
```
Expected: `BUILD SUCCESSFUL`

**Step 6: Final commit**
```bash
git add static/index.html static/app.js static/style.css
git commit -m "feat: show reading progress bar on READING book cards"
```

---

## Suggested Implementation Order

1. Feature A (Search) — highest user value, simplest backend change
2. Feature B (Sorting) — no DB changes, pure in-memory sort
3. Feature C (Reading Progress) — requires DB migration, most steps

Each feature can be merged independently. Start a new branch per feature: `git checkout -b feat/search`.
