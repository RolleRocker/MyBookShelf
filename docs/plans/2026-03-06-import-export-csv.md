# Import / Export (CSV)

**Status:** Not started
**Date:** 2026-03-06

## Research Findings

### How Other Apps Do It

| App | Export Formats | Import Sources | Duplicate Handling |
|-----|---------------|---------------|-------------------|
| **Goodreads** | CSV | N/A (export only) | N/A |
| **StoryGraph** | CSV | Goodreads CSV, Kindle, Bookly, LibraryThing | ISBN match, title/author fuzzy |
| **Hardcover** | JSON | Goodreads, StoryGraph, LibraryThing, Literal | ISBN match |
| **LibraryThing** | CSV, JSON, XML | Goodreads, Delicious Library, many others | ISBN match |

### Goodreads CSV Export Format (the de facto standard)

The Goodreads export is the most common import format across all apps. Column names:

```
Book Id, Title, Author, Author l-f, Additional Authors, ISBN, ISBN13,
My Rating, Average Rating, Publisher, Binding, Number of Pages, Year Published,
Original Publication Year, Date Read, Date Added, Bookshelves,
Bookshelves with positions, Exclusive Shelf, My Review, Spoiler, Private Notes,
Read Count, Owned Copies
```

**Key columns for MyBookShelf mapping:**

| Goodreads Column | MyBookShelf Field | Notes |
|-----------------|-------------------|-------|
| `Title` | `title` | Direct map |
| `Author` | `author` | Direct map |
| `ISBN` / `ISBN13` | `isbn` | Prefer ISBN13, fallback to ISBN. Goodreads wraps in `=""` quotes |
| `My Rating` | `rating` | 0-5 integer (0 = not rated). Direct map (or multiply by 2 if half-star feature is active) |
| `Number of Pages` | `pageCount` | Direct map |
| `Publisher` | `publisher` | Direct map |
| `Year Published` | `publishDate` | Map to string |
| `Exclusive Shelf` | `readStatus` | `read` → FINISHED, `currently-reading` → READING, `to-read` → WANT_TO_READ |
| `Date Read` | `finishedAt` | YYYY/MM/DD format → convert to YYYY-MM-DD |
| `Date Added` | `createdAt` | YYYY/MM/DD format |
| `My Review` | `review` | Direct map (if notes feature exists) |
| `Bookshelves` | *(ignored for now)* | Comma-separated shelf names |

**Quirks:**
- ISBN fields are wrapped in `="0441013593"` to prevent Excel from treating them as numbers. Must strip the `="` and `"` wrapper.
- `Date Read` can be empty for unread books.
- `Exclusive Shelf` values: `read`, `currently-reading`, `to-read`. Custom shelves go in `Bookshelves`.
- `My Rating` of `0` means not rated.

### Design Decisions

1. **Export as CSV** — universally compatible. Include all Book fields.
2. **Import Goodreads CSV** — this is the #1 requested import format. Map their columns to our fields.
3. **Import our own CSV** — allow re-importing our own export format.
4. **No JSON import/export for V1** — CSV is sufficient and more widely supported.
5. **Duplicate handling** — match by ISBN. If a book with the same ISBN exists, skip it (don't overwrite). Report skipped books in the response.
6. **Enrichment on import** — trigger Open Library enrichment for imported books that have ISBNs (fills in covers, subjects, etc.).
7. **Batch import** — process all rows in one request, return a summary.

## API Endpoints

| Method | Path | Description | Success Code |
|--------|------|-------------|--------------|
| `GET` | `/books/export` | Export all books as CSV | `200 OK` |
| `POST` | `/books/import` | Import books from CSV | `200 OK` |

### GET /books/export

Returns a CSV file with all books.

**Response headers:**
```
Content-Type: text/csv
Content-Disposition: attachment; filename="mybookshelf-export-2026-03-06.csv"
```

**CSV columns (our format):**

```
id,title,author,genre,rating,isbn,publisher,publishDate,pageCount,subjects,readStatus,readingProgress,review,startedAt,finishedAt,coverUrl,createdAt
```

- `id` — exported for reference but **ignored on import** (new UUIDs are always generated to avoid conflicts with existing books)
- `subjects` — JSON array string (e.g., `"[""Science fiction"",""Space""]"`)
- `rating` — decimal value (e.g., `3.5`) if half-star feature is active, else integer
- `coverUrl` — exported for reference; `coverData` (binary) is **excluded** from CSV
- Dates as `YYYY-MM-DD`
- Fields with commas or quotes are properly CSV-escaped (double-quote wrapping, double-double-quote escaping)
- Column list should include all Book fields that exist at implementation time. If some features (review, startedAt, finishedAt) aren't implemented yet, omit those columns.

### POST /books/import

Accepts a CSV file in the request body.

**Request headers:**
```
Content-Type: text/csv
```

OR multipart form upload:
```
Content-Type: multipart/form-data
```

**Chosen approach:** Plain `text/csv` body (simpler — no multipart parsing needed). The CSV content is the raw request body.

**Query parameter:** `?format=goodreads` or `?format=mybookshelf` (default: auto-detect based on header row)

**Response (200 OK):**

```json
{
  "imported": 42,
  "skipped": 3,
  "errors": 1,
  "details": [
    { "row": 5, "isbn": "9780441013593", "reason": "duplicate ISBN, skipped" },
    { "row": 12, "isbn": "", "title": "Unknown Book", "reason": "missing title and ISBN" },
    { "row": 18, "isbn": "invalid", "reason": "invalid ISBN format" }
  ]
}
```

**Import rules:**
- Each CSV row becomes a `POST /books` equivalent
- Skip rows where a book with the same ISBN already exists (report in `details`)
- Skip rows missing both `title` and `isbn` (can't identify the book)
- For rows with ISBN but no title: create the book and let enrichment fill it in
- `readStatus` mapping from Goodreads: `read` → `FINISHED`, `currently-reading` → `READING`, `to-read` → `WANT_TO_READ`. Default to `WANT_TO_READ` if missing or unrecognized.
- Trigger enrichment for imported books with ISBNs — **use `reEnrichAll()` on the list of imported books** after all saves complete, rather than calling `enrichBookAsync()` per row (which would flood the executor without rate limiting)
- `createdAt` from CSV is preserved on import (important for statistics and goals that count by year). If `createdAt` is missing, use current timestamp.

### Validation

| Code | When |
|------|------|
| `400` | Request body is empty, not valid CSV, or header row doesn't match any known format |
| `413` | CSV file too large (> 5MB). Note: `RequestParser` already enforces a 10MB limit. The 5MB CSV limit should be checked in the import handler before parsing. |

## Backend Changes

### New Files

**No new files** — the import/export handlers go in `BookController.java`.

### Modified Files

**`BookController.java`**:

`handleExportBooks(HttpRequest request)`:
1. Call `repository.findAll()`
2. Build CSV string with header row + one row per book
3. Return `HttpResponse` with `Content-Type: text/csv` and `Content-Disposition` header

`handleImportBooks(HttpRequest request)`:
1. Read CSV from request body
2. Detect format (Goodreads vs MyBookShelf) from header row
3. Parse each row into a `Book` object, mapping columns appropriately
4. For each book:
   a. Check for duplicate ISBN → skip if exists
   b. Validate minimum fields (title or isbn required)
   c. Save to repository
   d. Queue enrichment if ISBN present
5. Return JSON summary

**CSV parsing** — implement a simple CSV parser (handle quoted fields, escaped quotes, newlines in quotes). No external library needed for the simple cases. Alternatively, since Gson is already a dependency, there's no CSV library — hand-roll a basic parser.

**`Router` / `App.java`**:
- Register `GET /books/export` and `POST /books/import` routes
- Must be registered before `/books/{id}` (static segment priority handles this)

**`HttpResponse.java`**:
- May need a way to set custom headers (`Content-Disposition`). Check if this is already supported.

### Goodreads CSV Column Mapping (in code)

```java
Map<String, String> GOODREADS_MAP = Map.of(
    "Title", "title",
    "Author", "author",
    "ISBN13", "isbn",       // prefer ISBN13
    "ISBN", "isbn",          // fallback
    "My Rating", "rating",
    "Publisher", "publisher",
    "Number of Pages", "pageCount",
    "Year Published", "publishDate",
    "Exclusive Shelf", "readStatus",
    "Date Read", "finishedAt",
    "My Review", "review"
);
```

**Status mapping:**
```java
Map<String, ReadStatus> GOODREADS_STATUS = Map.of(
    "read", ReadStatus.FINISHED,
    "currently-reading", ReadStatus.READING,
    "to-read", ReadStatus.WANT_TO_READ
);
```

**ISBN cleanup:** Strip `="` prefix and `"` suffix from Goodreads ISBN fields.

## Frontend Changes

### index.html

Add export/import buttons in the header or settings area:

```html
<div class="import-export-actions">
  <button id="export-btn" title="Export library as CSV">Export CSV</button>
  <button id="import-btn" title="Import books from CSV">Import CSV</button>
  <input type="file" id="import-file" accept=".csv" hidden>
</div>
```

Import modal for showing results:

```html
<div id="import-modal" class="modal-overlay" hidden>
  <div class="modal import-modal">
    <div class="modal-header">
      <h2>Import Results</h2>
      <button class="modal-close" id="import-close">&times;</button>
    </div>
    <div class="modal-body">
      <div id="import-summary"></div>
      <div id="import-details"></div>
    </div>
  </div>
</div>
```

### app.js

- `exportBooks()` — `GET /books/export`, trigger file download via blob URL
- `importBooks(file)` — read file, `POST /books/import` with CSV body, show results modal
- File input change handler → read file → call import
- After successful import → reload books

### style.css

- `.import-export-actions` — button group in header area
- `.import-modal` — results modal showing imported/skipped/error counts
- `.import-details` — scrollable list of per-row details

## Tests

### BookApiTest.java (new tests)

**T_EXPORT_01 — Export empty library**
1. `GET /books/export`
2. Assert `200`, Content-Type is `text/csv`
3. Assert body contains header row only

**T_EXPORT_02 — Export books as CSV**
1. Create 3 books with various fields
2. `GET /books/export`
3. Assert CSV has 4 lines (header + 3 books)
4. Parse CSV and verify fields match created books

**T_EXPORT_03 — Export handles special characters**
1. Create book with title containing commas, quotes: `"The "Big" Book, Vol. 1"`
2. `GET /books/export`
3. Assert CSV properly escapes the field

**T_IMPORT_01 — Import MyBookShelf CSV**
1. Export books, then clear repository
2. `POST /books/import` with the exported CSV
3. Assert `imported: 3`, `skipped: 0`
4. `GET /books` — assert 3 books with correct data

**T_IMPORT_02 — Import Goodreads CSV**
1. Build a Goodreads-format CSV with header and 2 rows
2. `POST /books/import`
3. Assert `imported: 2`
4. Assert `readStatus` correctly mapped from `Exclusive Shelf`

**T_IMPORT_03 — Import skips duplicate ISBNs**
1. Create a book with ISBN X
2. Build CSV with a row having the same ISBN X
3. `POST /books/import`
4. Assert `skipped: 1`, details mention duplicate

**T_IMPORT_04 — Import handles missing fields gracefully**
1. Build CSV with a row missing title but having ISBN
2. `POST /books/import`
3. Assert `imported: 1` (enrichment will fill title later)

**T_IMPORT_05 — Import rejects empty body**
1. `POST /books/import` with empty body
2. Assert `400`

**T_IMPORT_06 — Goodreads ISBN cleanup**
1. Build CSV with ISBN field as `="9780441013593"` (Goodreads format)
2. `POST /books/import`
3. `GET /books` — assert `isbn` is `"9780441013593"` (cleaned)

**T_IMPORT_07 — Import with Goodreads status mapping**
1. Build CSV with `Exclusive Shelf` values: `read`, `currently-reading`, `to-read`
2. `POST /books/import`
3. Assert books have statuses: FINISHED, READING, WANT_TO_READ

**T_IMPORT_08 — Round-trip export then import**
1. Create 5 books with various fields
2. `GET /books/export` → save CSV
3. Clear repository
4. `POST /books/import` with saved CSV
5. `GET /books` — assert 5 books, all fields match originals

## Build Order

1. Implement CSV writer utility — handles escaping, quoting, header generation
2. Add `handleExportBooks()` to `BookController` — generate CSV from `findAll()`
3. Register `GET /books/export` route in `App.java`
4. Implement CSV parser utility — handle quoted fields, escaped quotes
5. Implement Goodreads format detection and column mapping
6. Add `handleImportBooks()` to `BookController` — parse CSV, create books, return summary
7. Register `POST /books/import` route in `App.java`
8. Write 8 export/import API tests
9. Run `./gradlew test` — verify all pass
10. Add export/import buttons and modal to `index.html`
11. Add export download and import upload logic to `app.js`
12. Style in `style.css`
13. Test with a real Goodreads export file in Docker

## Things to Watch Out For

- **CSV parsing edge cases** — fields containing commas, quotes, newlines. The CSV spec (RFC 4180) says: fields with special chars are wrapped in double quotes, and double-quotes inside are escaped as `""`. A simple parser must handle this.
- **Goodreads ISBN format** — `="0441013593"` wrapper must be stripped. Also handle ISBN-10 vs ISBN-13 (prefer ISBN13 if both present).
- **Enrichment rate limiting** — importing 100 books with ISBNs would trigger 100 enrichment requests. Queue them with delays (like `reEnrichAll()` does) to respect Open Library rate limits.
- **Large imports** — a 5000-book Goodreads export is ~1-2MB. Set a reasonable body size limit (5MB). Process synchronously for simplicity.
- **Character encoding** — Goodreads exports use UTF-8. Ensure the parser reads as UTF-8.
- **Header row detection** — auto-detect format by checking if the first row contains `"Title"` and `"Exclusive Shelf"` (Goodreads) or `"id"` and `"readStatus"` (MyBookShelf).
- **Content-Disposition header** — `HttpResponse` already supports custom headers via its `Map<String, String> headers` constructor parameter. Use: `Map<String, String> headers = new HashMap<>(); headers.put("Content-Type", "text/csv"); headers.put("Content-Disposition", "attachment; filename=\"mybookshelf-export.csv\""); return new HttpResponse(200, "OK", headers, csvString);` No changes to `HttpResponse` needed.
- **Route priority** — `/books/export` and `/books/import` are static segments. The router's static-over-param priority ensures they match before `/books/{id}`. Verify this.
- **Existing book data on import** — skip duplicates by ISBN. Books without ISBNs are always imported (no way to detect duplicates by title alone reliably).
- **BOM handling** — some Goodreads CSV exports include a UTF-8 BOM (`\uFEFF`) at the start of the file. The CSV parser should strip it before processing the header row.
- **Import `id` column** — always generate new UUIDs on import; ignore the `id` column from CSV. Using original UUIDs could conflict with existing books.
- **Shelves not in CSV** — book-to-shelf associations are not exported/imported in V1. Note this as a known limitation.
- **Enrichment strategy** — after saving all imported books, collect the ones with ISBNs into a list and call `reEnrichAll()` once. Do NOT call `enrichBookAsync()` per row — that bypasses rate limiting.
