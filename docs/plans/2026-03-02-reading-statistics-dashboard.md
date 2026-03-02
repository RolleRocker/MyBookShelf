# Reading Statistics Dashboard

**Status:** Not started
**Date:** 2026-03-02

## Overview

Add a statistics endpoint (`GET /books/stats`) and a frontend stats dashboard that visualizes reading habits. Built from data already in the `books` table — no new tables or schema changes needed.

## API

### New Endpoint

| Method | Path | Description | Success Code |
|--------|------|-------------|--------------|
| `GET` | `/books/stats` | Return computed reading statistics | `200 OK` |

### Response Shape

```json
{
  "totalBooks": 42,
  "byStatus": {
    "WANT_TO_READ": 12,
    "READING": 3,
    "FINISHED": 25,
    "DNF": 2
  },
  "byGenre": {
    "Science Fiction": 10,
    "Fantasy": 8,
    "Non-Fiction": 6,
    "null": 18
  },
  "ratings": {
    "average": 3.8,
    "distribution": { "1": 2, "2": 3, "3": 8, "4": 15, "5": 7 },
    "unrated": 7
  },
  "pages": {
    "totalRead": 12450,
    "averagePerBook": 380
  },
  "finishedByMonth": {
    "2026-01": 3,
    "2026-02": 5,
    "2026-03": 2
  },
  "topAuthors": [
    { "author": "Frank Herbert", "count": 4 },
    { "author": "Brandon Sanderson", "count": 3 },
    { "author": "Ursula K. Le Guin", "count": 2 }
  ],
  "recentlyFinished": [
    { "id": "...", "title": "Dune", "author": "Frank Herbert", "rating": 5, "createdAt": "..." }
  ]
}
```

### Computation Rules

- `totalBooks` — count of all books
- `byStatus` — count per `ReadStatus` enum value
- `byGenre` — count per genre string; books with `null` genre grouped under key `"null"`
- `ratings.average` — mean of all books with `rating > 0`; exclude unrated (rating == 0)
- `ratings.distribution` — count of books at each rating 1-5
- `ratings.unrated` — count of books with `rating == 0`
- `pages.totalRead` — sum of `pageCount` for `FINISHED` books only (where `pageCount` is not null)
- `pages.averagePerBook` — `totalRead / count(FINISHED books with pageCount)`; 0 if none
- `finishedByMonth` — count of `FINISHED` books grouped by `YYYY-MM` of `createdAt`. Only include months that have at least 1 finished book. Sorted chronologically.
- `topAuthors` — top 5 authors by book count (all statuses). Exclude `null` authors. Sorted by count desc.
- `recentlyFinished` — last 5 books with `readStatus == FINISHED`, sorted by `createdAt` desc. Include `id`, `title`, `author`, `rating`, `createdAt`.

## Backend Changes

### BookController

Add `handleGetStats(HttpRequest request)`:

1. Call `repository.findAll()` to get all books
2. Compute all stats in-memory using streams (consistent with existing sorting approach — no SQL aggregation)
3. Build a `JsonObject` manually (no new model class needed)
4. Return `HttpResponse.ok(json)`

### Router (App.java)

Add route before the `{id}` catch-all:

```java
router.addRoute("GET", "/books/stats", controller::handleGetStats);
```

The router's static-segment priority ensures `/books/stats` matches before `/books/{id}`.

### BookRepository

No changes needed. `findAll()` already returns everything we need.

## Frontend Changes

### index.html

Add a stats toggle button in the header area (next to the filter bar):

```html
<button id="stats-toggle" class="stats-toggle" title="Reading Statistics">Stats</button>
```

Add a stats panel (hidden by default):

```html
<div id="stats-panel" class="stats-panel" hidden>
  <div class="stats-grid">
    <div class="stat-card" id="stat-total">...</div>
    <div class="stat-card" id="stat-status">...</div>
    <div class="stat-card" id="stat-genres">...</div>
    <div class="stat-card" id="stat-ratings">...</div>
    <div class="stat-card" id="stat-pages">...</div>
    <div class="stat-card" id="stat-monthly">...</div>
    <div class="stat-card" id="stat-authors">...</div>
    <div class="stat-card" id="stat-recent">...</div>
  </div>
</div>
```

### app.js

- `toggleStats()` — show/hide stats panel, fetch `/books/stats` when opening
- `renderStats(data)` — populate stat cards with data
- Charts rendered as **pure CSS bar charts** (horizontal bars with percentage widths). No chart library needed.
  - Genre breakdown: horizontal bars
  - Rating distribution: 5 horizontal bars (1-5 stars)
  - Monthly finished: horizontal bars per month
- Status counts shown as large numbers with labels
- Top authors as a simple ranked list
- Recently finished as mini book cards

### style.css

- `.stats-panel` — full-width panel that slides in below the filter bar, above the book grid
- `.stats-grid` — CSS grid, 2 columns on desktop, 1 on mobile
- `.stat-card` — same dark theme as book cards, with heading + content area
- CSS bar charts: `.bar-chart .bar { height: 1.2rem; background: var(--gold); border-radius: 2px; transition: width 0.4s; }`
- `prefers-reduced-motion` disables bar transitions (consistent with existing approach)

## Tests

### API Tests (BookApiTest.java)

**T_STATS_01 — Stats with no books**
1. `GET /books/stats`
2. Assert `200 OK`
3. Assert `totalBooks` is 0, `ratings.average` is 0, all counts are 0

**T_STATS_02 — Stats reflect book data**
1. Create 3 books: 2 FINISHED (rated 4 and 5, with pageCount), 1 READING
2. `GET /books/stats`
3. Assert `totalBooks` is 3
4. Assert `byStatus.FINISHED` is 2, `byStatus.READING` is 1
5. Assert `ratings.average` is 4.5
6. Assert `pages.totalRead` equals sum of the 2 finished books' pageCounts

**T_STATS_03 — Genre breakdown**
1. Create books with different genres including one with null genre
2. `GET /books/stats`
3. Assert `byGenre` has correct counts per genre
4. Assert null-genre books are counted under `"null"` key

**T_STATS_04 — Top authors**
1. Create 6 books by 3 different authors (3, 2, 1 books respectively)
2. `GET /books/stats`
3. Assert `topAuthors[0]` is the author with 3 books
4. Assert list is sorted by count descending

**T_STATS_05 — Finished by month**
1. Create 2 FINISHED books (they will share the current month's createdAt)
2. `GET /books/stats`
3. Assert `finishedByMonth` has an entry for the current `YYYY-MM` with count 2

## Build Order

1. Add `handleGetStats()` to `BookController` — compute stats from `findAll()`
2. Register `GET /books/stats` route in `App.java`
3. Write 5 API tests
4. Run `./gradlew test` — verify all pass (existing + new)
5. Add stats toggle button and panel HTML to `index.html`
6. Add `toggleStats()` and `renderStats()` to `app.js`
7. Style the stats panel and CSS bar charts in `style.css`
8. Test in Docker: `docker compose up --build`

## Things to Watch Out For

- Route must be registered before `/books/{id}` or rely on the router's static-segment priority (it already handles this — `stats` is static, `{id}` is a param)
- `finishedByMonth` should use `createdAt` (when the book was added), not a separate "finished date" field (we don't have one)
- `pages.averagePerBook` — guard against division by zero when no finished books have `pageCount`
- `ratings.average` — exclude unrated books (rating == 0) from the average calculation
- Stats are computed on every request (no caching). Fine for a personal shelf with < 1000 books
