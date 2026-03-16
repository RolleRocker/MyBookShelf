# Half-Star Ratings

**Status:** Completed (commit d50f4cb)
**Date:** 2026-03-06

## Research Findings

### How Other Apps Do It

| App | Scale | Granularity | UI Interaction | Storage |
|-----|-------|-------------|---------------|---------|
| **Goodreads** | 1-5 | Integer only | Click full star | Integer |
| **StoryGraph** | 0.25-5 | Quarter-star | Tap star quadrant | Decimal |
| **Letterboxd** | 0.5-5 | Half-star | Click left/right half of star | Decimal |
| **Hardcover** | 0.5-5 | Half-star | Click half-star | Decimal |
| **IMDb** | 1-10 | Integer | Click number | Integer |

**Key insights:**
- Goodreads' lack of half-stars is one of the most common user complaints. StoryGraph and Letterboxd both gained users partly because of finer-grained ratings.
- **Half-star (0.5 increments)** is the sweet spot — quarter-stars (StoryGraph) add complexity without proportional value. Letterboxd proved half-stars work perfectly.
- **Letterboxd UX** is the gold standard: hovering over the left half of a star highlights it as a half-star, hovering over the right half highlights the full star. On mobile, tapping cycles through half → full → clear.
- Storage as a decimal or multiplied integer (e.g., store 7 for 3.5, divide by 2 for display) avoids floating-point issues.

### Design Decisions for MyBookShelf

1. **0.5 increments** — 0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0, 4.5, 5.0 (10 possible values)
2. **Store as integer x2** — store `7` in DB for a 3.5 rating. Avoids floating-point issues entirely. Display = `value / 2.0`.
3. **Backward compatible** — existing ratings (1-5) become 2, 4, 6, 8, 10 internally. `0` still means "not rated".
4. **API accepts both formats** — accept `3.5` (decimal) in JSON, store as `7` internally. Also accept `3` → stored as `6`.
5. **Letterboxd-style hover UX** — left half of star = half-star, right half = full star.

## Data Model Changes

### Book

| Field | Old Type | New Type | Notes |
|-------|----------|----------|-------|
| `rating` | `Integer` (0-5) | `Integer` (0-10) | Internal representation. 0 = not rated, 2 = 1 star, 3 = 1.5 stars, ..., 10 = 5 stars |

**JSON representation**: The API continues to accept and return decimal values (e.g., `3.5`), not the internal integer. Gson serialization custom-handles this.

### Migration

```sql
-- Double existing ratings to convert to new scale
UPDATE books SET rating = rating * 2 WHERE rating > 0;
-- Update CHECK constraint
ALTER TABLE books DROP CONSTRAINT IF EXISTS books_rating_check;
ALTER TABLE books ADD CONSTRAINT books_rating_check CHECK (rating BETWEEN 0 AND 10);
```

Add to `DatabaseConfig.runMigrations()` — must be idempotent. Guard with a check: only run if max rating in table is <= 5.

## API Changes

### Request Format

Rating in JSON is a **decimal number** representing stars:

```json
{ "rating": 3.5 }
```

Valid values: `0.5, 1, 1.5, 2, 2.5, 3, 3.5, 4, 4.5, 5` (and `null` to clear)

### Validation (BookController)

- Must be a number (integer or decimal)
- Must be one of: 0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 3.5, 4.0, 4.5, 5.0
- Internally: multiply by 2, cast to int, validate 1-10
- `0` is still not user-settable (it's the "not rated" default)
- Return `400` for invalid values like `0.3`, `2.7`, `6`, `-1`

### Response Format

Rating returned as a decimal:

```json
{ "rating": 3.5 }
```

### Serialization

Custom Gson `TypeAdapter<Integer>` for the `rating` field that:
- **Serializes**: `7` → `3.5` (divide by 2, output as number)
- **Deserializes**: `3.5` → `7` (multiply by 2, cast to int)

Alternative: handle conversion in `BookController` during request parsing and response building, keeping `Book.rating` as a plain integer internally.

**Chosen approach**: Convert in `BookController` — simpler, no custom Gson adapter needed. Controller reads the JSON `rating` as a `Number`, multiplies by 2. Response builder divides by 2 before serializing.

### Unrated books in responses

Unrated books have internal `rating == 0`. In JSON responses, `0 / 2.0 = 0.0` — but we should output `0` (integer zero) to preserve the existing "0 means not rated" semantics and avoid confusing `0.0` with a valid half-star value.

## Backend Changes

### Modified Files

**`BookController.java`**:
- **Rating parsing** — `safeGetInt()` cannot be used for half-star ratings because `getAsInt()` truncates `3.5` → `3`. Replace with a new helper:
  ```java
  private Integer safeGetRating(JsonObject json) {
      if (!json.has("rating") || json.get("rating").isJsonNull()) return null;
      double val = json.get("rating").getAsNumber().doubleValue();
      int internal = (int) Math.round(val * 2);
      if (internal < 1 || internal > 10 || Math.abs(val * 2 - internal) > 0.01) {
          throw new IllegalArgumentException("Rating must be between 0.5 and 5 in 0.5 increments");
      }
      return internal;
  }
  ```
- **Both validation blocks must be rewritten together** — the rating validation at create (lines 212-226) and update (lines 338-352) both use `getAsInt()` which truncates decimals. Replace both with the `safeGetRating()` helper above.
- **`enrichBookJson()` must transform rating** — currently `gson.toJson(book)` serializes `book.getRating()` as-is (the internal 0-10 value). After the `gson.toJson()` call, add:
  ```java
  if (book.getRating() != null && book.getRating() > 0) {
      obj.addProperty("rating", book.getRating() / 2.0);
  } else {
      obj.addProperty("rating", 0);
  }
  ```
- Update all validation error messages: "rating must be between 0.5 and 5"

**`Book.java`**:
- No changes — `rating` stays as `Integer`, just now stores 0-10 instead of 0-5

**`DatabaseConfig.java`**:
- Add migration to double existing ratings and update CHECK constraint

**`JdbcBookRepository.java`**:
- No changes — stores whatever integer the controller provides

**`InMemoryBookRepository.java`**:
- No changes

**`BookController.java` sorting**:
- Rating sort already works (higher internal value = higher rating) — no change needed

### MCP Changes

**`mcp/McpToolHandler.java`**:
- `get_bookshelf_stats` computes `averageRating` — update to divide by 2.0 for display
- **All 5 MCP tools** that return book data (`check_book`, `search_books`, `list_books`, `get_book_by_isbn`, `get_bookshelf_stats`) must divide rating by 2 when building response JSON. Add a shared helper to avoid duplication.

## Frontend Changes

### style.css

```css
/* Half-star support */
.star-rating .star {
  position: relative;
  cursor: pointer;
  font-size: 1.4rem;
}
.star-rating .star .star-half {
  position: absolute;
  left: 0;
  top: 0;
  width: 50%;
  overflow: hidden;
}
```

### app.js

- `renderStars(rating)`: now accepts decimals. For `3.5`: render 3 full stars + 1 half-star + 1 empty star.
- Star click handler: clicking left half of star N sets rating to `N - 0.5`, clicking right half sets to `N`. Use `event.offsetX / target.offsetWidth < 0.5` to detect half.
- `updateBook()` sends decimal rating in PUT request.
- Display: use CSS `clip-path` or `width: 50%` overflow technique for half-star rendering.

### index.html

- Star display elements remain the same — rendering logic is in JS.

## Tests

### BookApiTest.java (new tests)

**T_HALFSTAR_01 — Create book with half-star rating**
1. `POST /books` with `{"title": "Test", "author": "Test", "readStatus": "READING", "rating": 3.5}`
2. Assert `201`, response `rating` is `3.5`
3. `GET /books/{id}` — assert `rating` is `3.5`

**T_HALFSTAR_02 — Create book with integer rating still works**
1. `POST /books` with `"rating": 4`
2. Assert `201`, response `rating` is `4` (or `4.0`)

**T_HALFSTAR_03 — Update to half-star rating**
1. Create book with `rating: 3`
2. `PUT /books/{id}` with `{"rating": 4.5}`
3. Assert `200`, `rating` is `4.5`

**T_HALFSTAR_04 — Invalid half-star values rejected**
1. `POST /books` with `"rating": 2.7` → assert `400`
2. `POST /books` with `"rating": 0.3` → assert `400`
3. `POST /books` with `"rating": 5.5` → assert `400`

**T_HALFSTAR_05 — Rating 0.5 is valid (minimum)**
1. `POST /books` with `"rating": 0.5`
2. Assert `201`, `rating` is `0.5`

**T_HALFSTAR_06 — Sort by rating still works**
1. Create books with ratings 2.5, 4.0, 3.5
2. `GET /books?sort=rating,desc`
3. Assert order: 4.0, 3.5, 2.5

**T_HALFSTAR_07 — Stats average with half-stars**
1. Create books with ratings 3.5 and 4.5
2. `GET /books/stats` (if stats endpoint exists)
3. Assert average is 4.0

## Build Order

1. Add DB migration to `DatabaseConfig.runMigrations()` — double existing ratings, update CHECK constraint
2. Update `BookController` — parse rating as `Number`, multiply by 2 for storage, divide by 2 for response
3. Update validation — accept 0.5 increments (0.5-5.0), reject other decimals
4. Update MCP tool handler — divide ratings by 2 in responses
5. Write 7 API tests in `BookApiTest.java`
6. Run `./gradlew test` — verify all existing + new tests pass
7. Update frontend star rendering in `app.js` — half-star display
8. Update star click interaction — left/right half detection
9. Style half-stars in `style.css`
10. Test in Docker: `docker compose up --build`

## Things to Watch Out For

- **Migration idempotency** — the "double existing ratings" migration must only run once. Guard with: `SELECT MAX(rating) FROM books WHERE rating > 0` — if result is `NULL` (empty table) skip the migration, if max > 5 migration already ran, if max <= 5 run it. Must handle the empty-table case (`MAX()` returns `NULL`).
- **Existing API consumers** — clients sending `"rating": 4` (integer) must still work. The controller should accept both `4` and `4.0`.
- **Gson parsing** — Gson may parse `4` as `Integer` and `4.0` as `Double`. Handle both types when reading the rating from JSON.
- **MCP bookshelf stats** — the `get_bookshelf_stats` tool computes average rating. Must divide internal values by 2.
- **Sorting** — internal integer comparison still works correctly for sorting (7 > 6 means 3.5 > 3.0).
- **Zero-clearing** — `"rating": 0` in a PUT should still be rejected (0 is not a valid user rating). Only `null` clears a rating.
- **Frontend precision** — JavaScript `offsetX` for half-star detection needs careful handling on touch devices. Consider a tap-to-cycle approach for mobile: tap star → half → full → clear.
- **Existing tests** — all existing tests that send `"rating": 4` and assert `"rating": 4` should continue to pass without changes, since the controller round-trips correctly (×2 on input, ÷2 on output). However, Gson may serialize `4.0` as `4.0` (double) instead of `4` (int) — test assertions comparing JSON numbers must handle both `4` and `4.0`. Consider always serializing whole-number ratings as integers in `enrichBookJson()` (e.g., `rating % 2 == 0 ? rating/2 : rating/2.0`).
