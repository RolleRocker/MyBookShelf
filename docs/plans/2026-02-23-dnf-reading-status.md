# DNF Reading Status Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a "Did Not Finish" (DNF) reading status — with its own filter tab, faded card styling, and reading progress bar preserved.

**Architecture:** Backend adds `DNF` to the `ReadStatus` enum (validation is automatic via `valueOf()`). Frontend adds the tab, badge, faded card class, and shows the progress bar for both `READING` and `DNF` statuses. No DB migration needed — `read_status` is already `VARCHAR(20)`.

**Tech Stack:** Java 17 enum, JUnit 5 (backend tests), Vanilla JS/HTML/CSS (frontend).

---

## Context

- `ReadStatus` enum: `src/main/java/com/bookshelf/ReadStatus.java` — currently `WANT_TO_READ`, `READING`, `FINISHED`
- Validation in `BookController` uses `ReadStatus.valueOf(str)` — adding `DNF` to the enum is all that's needed
- Tests: `src/test/java/com/bookshelf/BookApiTest.java` — uses `createBookJson(title, author, readStatus)` helper
- CSS variables for statuses defined at top of `static/style.css` (`:root` block, lines 27–32)
- Filter tabs in `static/index.html` lines 76–95; edit modal dropdown lines 168–172
- `statusLabel()` at `static/app.js:109`, `statusClass()` at `static/app.js:118`
- `createBookCard()` at `static/app.js:137` — card class set at line 139, progress bar condition at line 173
- `updateCounts()` at `static/app.js:325` — sets count-want/reading/finished span text
- Edit modal open: `progressGroup.hidden = book.readStatus !== 'READING'` at line 466
- Edit modal status-change handler: `progressGroup.hidden = editStatus.value !== 'READING'` at line 1177
- Updates save: `updates.readingProgress = editStatus.value === 'READING' && ...` at line 498

---

## Task 1: Backend — add DNF to enum and write tests

**Files:**
- Modify: `src/main/java/com/bookshelf/ReadStatus.java`
- Test: `src/test/java/com/bookshelf/BookApiTest.java`

**Step 1: Write 2 failing tests**

In `BookApiTest.java`, add after the existing invalid-readStatus test (search for `T10` or `testInvalidRating`):

```java
@Test
void testCreateBookWithDnfStatus() throws Exception {
    String body = createBookJson("Flowers for Algernon", "Daniel Keyes", "DNF");
    HttpResponse<String> resp = post("/books", body);
    assertEquals(201, resp.statusCode());
    JsonObject book = gson.fromJson(resp.body(), JsonObject.class);
    assertEquals("DNF", book.get("readStatus").getAsString());
}

@Test
void testUpdateBookToDnfStatus() throws Exception {
    HttpResponse<String> create = post("/books", createBookJson("Dune", "Frank Herbert", "READING"));
    String id = gson.fromJson(create.body(), JsonObject.class).get("id").getAsString();

    HttpResponse<String> update = put("/books/" + id, "{\"readStatus\":\"DNF\"}");
    assertEquals(200, update.statusCode());
    JsonObject book = gson.fromJson(update.body(), JsonObject.class);
    assertEquals("DNF", book.get("readStatus").getAsString());
}
```

**Step 2: Run tests to confirm they fail**

```bash
./gradlew test --tests "com.bookshelf.BookApiTest.testCreateBookWithDnfStatus" --tests "com.bookshelf.BookApiTest.testUpdateBookToDnfStatus"
```

Expected: FAIL — `IllegalArgumentException: No enum constant com.bookshelf.ReadStatus.DNF`

**Step 3: Add DNF to the enum**

Replace `src/main/java/com/bookshelf/ReadStatus.java` with:

```java
package com.bookshelf;

public enum ReadStatus {
    WANT_TO_READ,
    READING,
    FINISHED,
    DNF
}
```

**Step 4: Run tests to confirm they pass**

```bash
./gradlew test --tests "com.bookshelf.BookApiTest.testCreateBookWithDnfStatus" --tests "com.bookshelf.BookApiTest.testUpdateBookToDnfStatus"
```

Expected: PASS

**Step 5: Run full test suite**

```bash
./gradlew test
```

Expected: All tests pass (47 total).

**Step 6: Commit**

```bash
git add src/main/java/com/bookshelf/ReadStatus.java src/test/java/com/bookshelf/BookApiTest.java
git commit -m "feat: add DNF to ReadStatus enum and tests"
```

---

## Task 2: Frontend CSS — DNF colour variables, dot, badge, faded card

**Files:**
- Modify: `static/style.css`

**Step 1: Add CSS variables for DNF colour**

The `:root` block ends around line 32 with `--status-finished-bg`. After `--status-finished-bg`, add:

```css
    --status-dnf:      #a06060;
    --status-dnf-bg:   rgba(160, 96, 96, 0.12);
```

**Step 2: Add `.dot-dnf` tab dot colour**

After `.dot-finished` (around line 273), add:

```css
.dot-dnf       { background: var(--status-dnf); }
```

**Step 3: Add `.badge-status.dnf` badge styles**

After the `.badge-status.finished` block (around line 650), add:

```css
.badge-status.dnf {
    background: var(--status-dnf-bg);
    color: var(--status-dnf);
}
```

**Step 4: Add `.book-card.dnf` faded card styles**

After the `.book-card:hover` block (around line 400), add:

```css
.book-card.dnf {
    opacity: 0.6;
    filter: saturate(0.7);
}

.book-card.dnf:hover {
    opacity: 0.85;
    filter: saturate(0.9);
}
```

**Step 5: Verify with grep**

```bash
grep -n "status-dnf\|dot-dnf\|badge-status.dnf\|book-card.dnf" static/style.css
```

Expected: 6+ hits across the four additions.

---

## Task 3: Frontend HTML — DNF filter tab and edit modal option

**Files:**
- Modify: `static/index.html`

**Step 1: Add DNF filter tab**

After the Finished tab (lines 91–95):
```html
            <button class="filter-tab" data-status="FINISHED">
                <span class="tab-dot dot-finished"></span>
                Finished
                <span class="tab-count" id="count-finished">0</span>
            </button>
```

Add immediately after:
```html
            <button class="filter-tab" data-status="DNF">
                <span class="tab-dot dot-dnf"></span>
                Did Not Finish
                <span class="tab-count" id="count-dnf">0</span>
            </button>
```

**Step 2: Add DNF option to edit modal dropdown**

After `<option value="FINISHED">Finished</option>` (line 171), add:

```html
                        <option value="DNF">Did Not Finish</option>
```

**Step 3: Verify with grep**

```bash
grep -n "DNF\|count-dnf\|dot-dnf" static/index.html
```

Expected: 3 hits.

---

## Task 4: Frontend JS — status helpers, card class, counts, progress bar

**Files:**
- Modify: `static/app.js`

### Step 1: Add DNF to `statusLabel()`

Find (line 109):
```js
function statusLabel(status) {
    switch (status) {
        case 'WANT_TO_READ': return 'Want to Read';
        case 'READING':      return 'Reading';
        case 'FINISHED':     return 'Finished';
        default:             return status;
    }
}
```

Replace with:
```js
function statusLabel(status) {
    switch (status) {
        case 'WANT_TO_READ': return 'Want to Read';
        case 'READING':      return 'Reading';
        case 'FINISHED':     return 'Finished';
        case 'DNF':          return 'Did Not Finish';
        default:             return status;
    }
}
```

### Step 2: Add DNF to `statusClass()`

Find (line 118):
```js
function statusClass(status) {
    switch (status) {
        case 'WANT_TO_READ': return 'want';
        case 'READING':      return 'reading';
        case 'FINISHED':     return 'finished';
        default:             return '';
    }
}
```

Replace with:
```js
function statusClass(status) {
    switch (status) {
        case 'WANT_TO_READ': return 'want';
        case 'READING':      return 'reading';
        case 'FINISHED':     return 'finished';
        case 'DNF':          return 'dnf';
        default:             return '';
    }
}
```

### Step 3: Add `dnf` class to book card and extend progress bar condition

Find (line 137–139):
```js
function createBookCard(book) {
    const card = document.createElement('div');
    card.className = 'book-card';
```

Replace with:
```js
function createBookCard(book) {
    const card = document.createElement('div');
    card.className = `book-card${book.readStatus === 'DNF' ? ' dnf' : ''}`;
```

Then find the progress bar condition (line 173):
```js
            ${book.readStatus === 'READING' && book.readingProgress != null
                ? `<div class="progress-bar-wrap" title="${book.readingProgress}% read">
                     <div class="progress-bar-fill" style="width:${book.readingProgress}%"></div>
                   </div>`
                : ''}
```

Replace with:
```js
            ${(book.readStatus === 'READING' || book.readStatus === 'DNF') && book.readingProgress != null
                ? `<div class="progress-bar-wrap" title="${book.readingProgress}% read">
                     <div class="progress-bar-fill" style="width:${book.readingProgress}%"></div>
                   </div>`
                : ''}
```

### Step 4: Add DNF count to `updateCounts()`

Find (lines 325–327):
```js
    document.getElementById('count-want').textContent = books.filter(b => b.readStatus === 'WANT_TO_READ').length;
    document.getElementById('count-reading').textContent = books.filter(b => b.readStatus === 'READING').length;
    document.getElementById('count-finished').textContent = books.filter(b => b.readStatus === 'FINISHED').length;
```

Replace with:
```js
    document.getElementById('count-want').textContent = books.filter(b => b.readStatus === 'WANT_TO_READ').length;
    document.getElementById('count-reading').textContent = books.filter(b => b.readStatus === 'READING').length;
    document.getElementById('count-finished').textContent = books.filter(b => b.readStatus === 'FINISHED').length;
    document.getElementById('count-dnf').textContent = books.filter(b => b.readStatus === 'DNF').length;
```

### Step 5: Show progress input for DNF when opening edit modal

Find (line 466):
```js
    progressGroup.hidden = book.readStatus !== 'READING';
```

Replace with:
```js
    progressGroup.hidden = book.readStatus !== 'READING' && book.readStatus !== 'DNF';
```

### Step 6: Show progress input for DNF on status change in edit modal

Find (line 1177):
```js
    progressGroup.hidden = editStatus.value !== 'READING';
```

Replace with:
```js
    progressGroup.hidden = editStatus.value !== 'READING' && editStatus.value !== 'DNF';
```

### Step 7: Send readingProgress on save for DNF too

Find (line 498):
```js
    updates.readingProgress = editStatus.value === 'READING' && editProgress.value !== ''
```

Replace with:
```js
    updates.readingProgress = (editStatus.value === 'READING' || editStatus.value === 'DNF') && editProgress.value !== ''
```

### Step 8: Run full test suite

```bash
./gradlew test
```

Expected: All tests pass.

### Step 9: Commit

```bash
git add static/app.js static/style.css static/index.html
git commit -m "feat: add DNF status to frontend — tab, badge, faded card, progress bar"
```

---

## Task 5: Rebuild Docker and push

**Step 1: Rebuild Docker**

```bash
docker compose up --build -d
```

Expected: Image rebuilt, container restarted.

**Step 2: Smoke test**

Open `http://localhost:8080`. Edit any book and set its status to "Did Not Finish". Confirm:
- Card appears faded (opacity ~0.6)
- Badge reads "Did Not Finish" in dusty red
- "Did Not Finish" tab appears and count increments
- Progress bar shows if `readingProgress` was set

**Step 3: Push**

```bash
git push origin main
```
