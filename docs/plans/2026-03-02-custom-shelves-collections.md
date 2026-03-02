# Custom Shelves / Collections

**Status:** Not started
**Date:** 2026-03-02

## Overview

Let users group books into named collections beyond the built-in read-status filter. Examples: "Favorites", "Sci-Fi Classics", "Lent to friends", "2026 reads". A book can belong to multiple shelves. Each shelf has a user-chosen color, configurable sort order, and optional notes. Books are added to shelves via drag-and-drop from the book grid onto a persistent sidebar.

### Feature Summary

- **Color-coded shelves** — 10 preset theme colors + custom hex picker
- **Persistent sidebar** — collapsible left sidebar showing shelf cards with cover collages
- **Drag and drop** — drag book cards onto shelf cards to add; drag to reorder shelves and books within shelves
- **Per-shelf sorting** — each shelf can have its own sort (title, author, rating, date) or a manual custom order
- **Shelf stats** — book count, average rating, total pages, genre breakdown shown when viewing a shelf
- **Cover collage** — auto-generated 2x2 thumbnail grid from first 4 books as the shelf's visual identity
- **Notes** — optional longer notes/description per shelf
- **Shelf tags on book cards** — colored pills showing which shelves a book belongs to
- **Mobile responsive** — sidebar collapses to horizontal color dots on small screens

---

## Data Model

### Shelf

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `id` | UUID | auto | Server-generated |
| `name` | String | yes | Max 100 chars, case-insensitive unique |
| `description` | String | no | Short tagline |
| `notes` | String | no | Longer free-form notes |
| `color` | String | yes | Hex color string, e.g. `#C4975A`. Defaults to first preset if not provided |
| `sortField` | String | no | `title`, `author`, `rating`, `created`, or `custom`. Default: `custom` |
| `sortDirection` | String | no | `asc` or `desc`. Default: `asc`. Ignored when `sortField` is `custom` |
| `position` | Integer | yes | Display order in sidebar. 0-based. Auto-assigned on create. |
| `createdAt` | Instant | auto | |
| `updatedAt` | Instant | auto | |

### ShelfBook (join table)

| Field | Type | Notes |
|-------|------|-------|
| `shelf_id` | UUID | FK -> shelves.id, ON DELETE CASCADE |
| `book_id` | UUID | FK -> books.id, ON DELETE CASCADE |
| `position` | Integer | Manual sort order within this shelf. 0-based. |
| `added_at` | TIMESTAMP | When the book was added to the shelf |
| Primary key | (`shelf_id`, `book_id`) | Prevents duplicates |

### Color Presets

```
#C4975A  Antique Gold       (default)
#8B4513  Saddle Brown
#6B2D2D  Deep Crimson
#2D5016  Forest Green
#1B3A5C  Navy Blue
#5C3D6E  Royal Purple
#7A6652  Weathered Bronze
#4A6741  Sage Green
#8B6914  Dark Amber
#4A4A6A  Slate Blue
```

---

## Database Schema

```sql
CREATE TABLE IF NOT EXISTS shelves (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    notes TEXT,
    color VARCHAR(7) NOT NULL DEFAULT '#C4975A',
    sort_field VARCHAR(20) NOT NULL DEFAULT 'custom',
    sort_direction VARCHAR(4) NOT NULL DEFAULT 'asc',
    position INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_shelves_name_lower ON shelves(LOWER(name));

CREATE TABLE IF NOT EXISTS shelf_books (
    shelf_id UUID NOT NULL REFERENCES shelves(id) ON DELETE CASCADE,
    book_id UUID NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    position INTEGER NOT NULL DEFAULT 0,
    added_at TIMESTAMP NOT NULL DEFAULT NOW(),
    PRIMARY KEY (shelf_id, book_id)
);
```

Add to `DatabaseConfig.runMigrations()` as a new migration block.

Key points:
- Case-insensitive uniqueness via `LOWER(name)` index instead of a column-level `UNIQUE` constraint
- `ON DELETE CASCADE` on both foreign keys — deleting a shelf removes join rows, deleting a book removes join rows
- `position` on both tables enables drag-to-reorder for shelves and books within shelves

---

## API Endpoints

### Shelf CRUD

| Method | Path | Description | Success Code |
|--------|------|-------------|--------------|
| `GET` | `/shelves` | List all shelves (with book count + cover IDs), sorted by position | `200 OK` |
| `POST` | `/shelves` | Create a new shelf | `201 Created` |
| `GET` | `/shelves/{id}` | Get shelf details + sorted books + stats | `200 OK` |
| `PUT` | `/shelves/{id}` | Partial update (name/description/notes/color/sort) | `200 OK` |
| `DELETE` | `/shelves/{id}` | Delete shelf (books are NOT deleted) | `204 No Content` |

### Shelf Membership

| Method | Path | Description | Success Code |
|--------|------|-------------|--------------|
| `POST` | `/shelves/{id}/books` | Add a book to a shelf (appended at end) | `201 Created` |
| `DELETE` | `/shelves/{id}/books/{bookId}` | Remove a book from a shelf | `204 No Content` |

### Ordering

| Method | Path | Description | Success Code |
|--------|------|-------------|--------------|
| `PUT` | `/shelves/reorder` | Reorder shelves in sidebar | `200 OK` |
| `PUT` | `/shelves/{id}/books/reorder` | Reorder books within a shelf (sets sortField to custom) | `200 OK` |

### Response Shapes

**POST /shelves**

Request:
```json
{ "name": "Favorites", "description": "Books I loved", "color": "#6B2D2D" }
```

Response `201`:
```json
{
  "id": "...",
  "name": "Favorites",
  "description": "Books I loved",
  "notes": null,
  "color": "#6B2D2D",
  "sortField": "custom",
  "sortDirection": "asc",
  "position": 0,
  "bookCount": 0,
  "coverBookIds": [],
  "createdAt": "...",
  "updatedAt": "..."
}
```

**GET /shelves**
```json
[
  {
    "id": "...",
    "name": "Favorites",
    "description": "Books I loved",
    "color": "#6B2D2D",
    "position": 0,
    "sortField": "custom",
    "sortDirection": "asc",
    "bookCount": 5,
    "coverBookIds": ["uuid1", "uuid2", "uuid3", "uuid4"],
    "createdAt": "..."
  }
]
```

Sorted by `position` ascending. `coverBookIds` contains the first 4 book IDs on the shelf (by position), used by the frontend to build cover collages from existing `/books/{id}/cover` endpoints.

**GET /shelves/{id}**
```json
{
  "id": "...",
  "name": "Favorites",
  "description": "Books I loved",
  "notes": "My all-time best reads, curated over the years.",
  "color": "#6B2D2D",
  "position": 0,
  "sortField": "custom",
  "sortDirection": "asc",
  "createdAt": "...",
  "updatedAt": "...",
  "books": [ { "id": "...", "title": "Dune", "author": "Frank Herbert", ... } ],
  "stats": {
    "bookCount": 5,
    "averageRating": 4.2,
    "totalPages": 1850,
    "genreBreakdown": { "Science Fiction": 3, "Fantasy": 2 }
  }
}
```

Books sorted by `sortField`/`sortDirection`, or by `shelf_books.position` when `sortField == custom`. Stats computed on the fly from the shelf's books:
- `averageRating` — mean of books with `rating > 0`; 0 if all unrated
- `totalPages` — sum of `pageCount` for books where `pageCount` is not null
- `genreBreakdown` — count per genre string; null-genre books excluded

**PUT /shelves/{id}** (partial update)

Request (any subset of fields):
```json
{ "notes": "Updated notes", "sortField": "rating", "sortDirection": "desc" }
```

Response `200`: full shelf object with updated fields.

**PUT /shelves/reorder**
```json
{ "order": ["uuid-shelf-1", "uuid-shelf-3", "uuid-shelf-2"] }
```

Array of all shelf IDs in desired order. Server sets `position = index`. Must include all existing shelf IDs.

**PUT /shelves/{id}/books/reorder**
```json
{ "order": ["uuid-book-3", "uuid-book-1", "uuid-book-2"] }
```

Array of all book IDs on this shelf in desired order. Server sets `shelf_books.position = index`. Also sets the shelf's `sortField` to `custom` automatically. Must include all books currently on the shelf.

**POST /shelves/{id}/books**

Request:
```json
{ "bookId": "uuid-of-book" }
```

Response `201`:
```json
{ "shelfId": "...", "bookId": "...", "position": 5, "addedAt": "..." }
```

New book appended at end (position = current max + 1).

### Book Response Enhancement

All book JSON responses (`GET /books`, `GET /books/{id}`, etc.) include a `shelves` array:

```json
{
  "id": "...",
  "title": "Dune",
  "shelves": [
    { "id": "...", "name": "Favorites", "color": "#6B2D2D" },
    { "id": "...", "name": "Sci-Fi Classics", "color": "#1B3A5C" }
  ],
  ...
}
```

### Error Responses

| Code | When |
|------|------|
| `400` | Missing `name`, blank/whitespace-only name, name exceeds 100 chars, invalid color hex (must be `#` + 6 hex digits), missing `bookId`, invalid `bookId` UUID, invalid `sortField`/`sortDirection`, empty `order` array, `order` array doesn't match actual shelf/book IDs, malformed JSON |
| `404` | Shelf not found, book not found, non-UUID shelf path |
| `409` | Shelf name already exists (case-insensitive), book already on shelf |

---

## Backend Changes

### New Files

**`Shelf.java`** — Model class:
- Fields: `id` (UUID), `name`, `description`, `notes`, `color`, `sortField`, `sortDirection`, `position` (int), `createdAt` (Instant), `updatedAt` (Instant)
- Transient fields (not stored, computed at read time): `bookCount` (int), `coverBookIds` (List\<UUID\>), `books` (List\<Book\>), `stats` (Map or nested object)

**`ShelfRepository.java`** — Interface:
```java
public interface ShelfRepository {
    List<Shelf> findAll();                              // with bookCount + coverBookIds, sorted by position
    Optional<Shelf> findById(UUID id);                  // with books list + stats
    Shelf save(Shelf shelf);
    Optional<Shelf> update(UUID id, Shelf updates);
    boolean delete(UUID id);
    void addBook(UUID shelfId, UUID bookId);
    void removeBook(UUID shelfId, UUID bookId);
    boolean hasBook(UUID shelfId, UUID bookId);
    List<Shelf> findShelvesForBook(UUID bookId);        // for book response enrichment (id, name, color only)
    void reorderShelves(List<UUID> shelfIds);
    void reorderBooks(UUID shelfId, List<UUID> bookIds);
    int getMaxShelfPosition();                          // for auto-assigning position on create
    int getMaxBookPosition(UUID shelfId);               // for auto-assigning position on add
    void renumberShelfPositions();                       // close gaps after delete
}
```

**`InMemoryShelfRepository.java`** — For tests. Uses `ConcurrentHashMap<UUID, Shelf>` for shelves and a `ConcurrentHashMap<UUID, List<ShelfBookEntry>>` keyed by shelfId for the join table. `ShelfBookEntry` is a small inner record: `record ShelfBookEntry(UUID bookId, int position, Instant addedAt)`.

**`JdbcShelfRepository.java`** — JDBC implementation against PostgreSQL.

**`ShelfController.java`** — HTTP handlers for all 9 endpoints (5 CRUD + 2 membership + 2 reorder).

### Modified Files

**`App.java`** — Wire `ShelfRepository`, `ShelfController`, register 9 new routes. Pass `ShelfRepository` to `BookController` constructor.

**`BookController`** — Add `ShelfRepository` as a constructor parameter. In `handleGetBook` and `handleGetBooks`, enrich book JSON with `shelves` array by calling `shelfRepository.findShelvesForBook(bookId)`. Since `BookController` builds JSON manually via Gson, add the `shelves` array to the `JsonObject` before serializing.

**`DatabaseConfig`** — Add `shelves` and `shelf_books` table creation + index to `runMigrations()`.

### Routes to Register (App.java)

```java
router.addRoute("GET", "/shelves", shelfController::handleGetShelves);
router.addRoute("POST", "/shelves", shelfController::handleCreateShelf);
router.addRoute("PUT", "/shelves/reorder", shelfController::handleReorderShelves);
router.addRoute("GET", "/shelves/{id}", shelfController::handleGetShelf);
router.addRoute("PUT", "/shelves/{id}", shelfController::handleUpdateShelf);
router.addRoute("DELETE", "/shelves/{id}", shelfController::handleDeleteShelf);
router.addRoute("POST", "/shelves/{id}/books", shelfController::handleAddBook);
router.addRoute("PUT", "/shelves/{id}/books/reorder", shelfController::handleReorderBooks);
router.addRoute("DELETE", "/shelves/{id}/books/{bookId}", shelfController::handleRemoveBook);
```

Note: `PUT /shelves/reorder` must be registered before `PUT /shelves/{id}` so the router's static-segment priority matches `reorder` as a literal before treating it as an `{id}` param. Same for `PUT /shelves/{id}/books/reorder` vs `DELETE /shelves/{id}/books/{bookId}`.

### Validation (ShelfController)

- **name**: required, non-blank after trim, max 100 chars, case-insensitive unique (409 on duplicate)
- **color**: if provided, must match `^#[0-9a-fA-F]{6}$`. If not provided on create, defaults to `#C4975A`
- **sortField**: if provided, must be one of `custom`, `title`, `author`, `rating`, `created`
- **sortDirection**: if provided, must be `asc` or `desc`
- **bookId** on add: required, must be a valid UUID format, book must exist (404 if not)
- **order arrays**: must be non-empty, must contain exactly the IDs currently in the shelf/system (no extras, no missing)
- Shelf must exist for all shelf-specific operations (404 if not)
- Non-UUID shelf path params return 404

### Sorting Logic (ShelfController or ShelfRepository)

When `GET /shelves/{id}` returns books:
- If `sortField == custom`: order by `shelf_books.position ASC`
- If `sortField == title`: order by `book.title` (nulls last) in `sortDirection`
- If `sortField == author`: order by `book.author` (nulls last) in `sortDirection`
- If `sortField == rating`: order by `book.rating` in `sortDirection` (unrated/0 last when desc)
- If `sortField == created`: order by `book.createdAt` in `sortDirection`

Sorting is done in-memory after fetching the shelf's books, consistent with the existing `BookController` sorting approach.

---

## Frontend Changes

### Layout Restructure

The page layout changes from single-column to sidebar + main content:

```
┌──────────────────────────────────────────────────────────┐
│  Header  (ISBN input, Add button, Scan button)           │
├─────────────┬────────────────────────────────────────────┤
│             │  Filter bar  (All | Want to Read | ...)    │
│  SHELVES    │  Search bar + Sort dropdown                │
│  SIDEBAR    ├────────────────────────────────────────────┤
│             │                                            │
│  [shelf 1]  │   Book grid                                │
│  [shelf 2]  │                                            │
│  [shelf 3]  │   ┌─────┐  ┌─────┐  ┌─────┐              │
│             │   │Book1│  │Book2│  │Book3│              │
│  [+ New]    │   └─────┘  └─────┘  └─────┘              │
│             │                                            │
│ [Collapse]  │                                            │
├─────────────┴────────────────────────────────────────────┤
```

### Sidebar (`#shelf-sidebar`)

- Fixed-width left sidebar (220px), scrollable if many shelves
- Background matches the existing dark theme
- **"All Books" link** at the top — clears shelf filter, returns to normal view
- **Shelf cards** — one per shelf, vertically stacked, draggable for reordering
- **"+ New Shelf" button** at the bottom — opens create shelf modal
- **Collapse button** — toggles sidebar to a thin strip (40px) showing only shelf color dots. State saved in `localStorage`.

### Shelf Card in Sidebar

```
┌──────────────────┐
│ ┌──┬──┐          │
│ │c1│c2│ Favorites│
│ ├──┼──┤   5 bks  │
│ │c3│c4│          │
│ └──┴──┘          │
└──────────────────┘
```

- **3px left border** in the shelf's color
- **Cover collage** — 2x2 grid of tiny thumbnails (32x32px each) from the first 4 books via `/books/{id}/cover`. Empty slots filled with the shelf's color. Empty shelf shows solid color square.
- **Shelf name** — truncated with ellipsis if too long
- **Book count** — small muted text
- **Click** — filters book grid to that shelf's books
- **Active state** — highlighted background, brighter border when selected
- **Drop target state** — glowing border in shelf color when a book is being dragged over it
- **Already-has-book state** — muted glow + checkmark when the dragged book is already on this shelf
- **Right-click** — context menu: Edit, Delete
- **Draggable** — drag up/down to reorder shelves

### Shelf Stats Bar

Shown between filter bar and book grid when viewing a single shelf:

```
┌──────────────────────────────────────────────────┐
│  5 books  │  ★ 4.2 avg  │  1,850 pages          │
│──────────────────────────────────────────────────│
│  Sci-Fi ████████ 3    Fantasy ████ 2             │
│──────────────────────────────────────────────────│
│  My all-time best reads, curated over the years. │
└──────────────────────────────────────────────────┘
```

- Top row: book count, average rating, total pages
- Middle row: genre breakdown as inline horizontal bars
- Bottom row: shelf notes in italic (only if notes exist)
- Dismissable via small "x" button (preference saved in `localStorage`)

### Book Card Shelf Tags

When viewing "All Books", each book card shows colored shelf pills:

```
┌──────────┐ ┌─────────────┐
│● Favorites│ │● Sci-Fi Class│
└──────────┘ └─────────────┘
```

- Small colored dot + shelf name
- Max 3 visible; overflow shows "+N more" that expands on click
- Clicking a pill filters to that shelf

### Create Shelf Modal

```
┌─────────────────────────────────────┐
│  Create Shelf                    x  │
├─────────────────────────────────────┤
│                                     │
│  Name                               │
│  [________________________]         │
│                                     │
│  Description (optional)             │
│  [________________________]         │
│                                     │
│  Color                              │
│  ● ● ● ● ● ● ● ● ● ●  [Custom]   │
│                                     │
│           [Create Shelf]            │
└─────────────────────────────────────┘
```

- 10 preset color circles. Selected one gets a checkmark + ring.
- "Custom" button opens native `<input type="color">`. Custom color appears as 11th circle.
- Default selection: Antique Gold (#C4975A).

### Edit Shelf Modal

Same as create but pre-populated, with additional fields:

```
┌─────────────────────────────────────┐
│  Edit Shelf                      x  │
├─────────────────────────────────────┤
│                                     │
│  Name                               │
│  [Favorites___________________]     │
│                                     │
│  Description (optional)             │
│  [Books I loved_______________]     │
│                                     │
│  Notes (optional)                   │
│  [My all-time best reads,     ]     │
│  [curated over the years.     ]     │
│                                     │
│  Color                              │
│  ● ● ● ● ● ● ● ● ● ●  [Custom]   │
│                                     │
│  Sort Order                         │
│  [Custom Order ▼]                   │
│  Options: Custom Order, Title A-Z,  │
│  Title Z-A, Author A-Z, Author Z-A,│
│  Highest Rated, Recently Added      │
│                                     │
│    [Save]              [Delete]     │
└─────────────────────────────────────┘
```

- **Notes** textarea only in edit modal (not create, to keep creation quick)
- **Sort Order** dropdown maps to `sortField` + `sortDirection`
- **Delete** button with confirmation: "Delete shelf '[name]'? Books won't be deleted."

### Book Edit Modal Enhancement

New "Shelves" section in the existing book edit modal:

```
  Shelves
  [x] ● Favorites
  [x] ● Sci-Fi Classics
  [ ] ● 2026 Reads
  [ ] ● Lent to Friends
  [+ New shelf...]
```

- Checkboxes with shelf color dots
- Checking/unchecking calls `POST` or `DELETE` on shelf membership immediately
- "New shelf..." link opens create shelf modal

### Drag and Drop

**Dragging books to shelves (HTML5 Drag and Drop API, no libraries):**

1. `dragstart` on book card — set `dataTransfer` with book ID, create semi-transparent ghost clone on cursor, dim original card, show tooltip "Drop on a shelf"
2. `dragover` on shelf cards — `preventDefault()`, add glow highlight in shelf color. If book already on shelf, show muted glow + checkmark instead.
3. `dragleave` on shelf cards — remove highlight
4. `drop` on shelf card — call `POST /shelves/{id}/books`. Success: toast "Added [title] to [shelf]", update shelf card (count, collage). Already-on-shelf (409): toast "Already on [shelf]". Ghost animates toward shelf card before disappearing.
5. `dragend` — cleanup ghost, restore card opacity
6. Escape key cancels drag

**Reordering shelves in sidebar:**

1. Drag shelf card up/down
2. Other cards shift with animated gap
3. On drop: `PUT /shelves/reorder` with new order
4. Dragged card has slight rotation + drop shadow

**Reordering books within a shelf:**

1. Only when viewing a single shelf and `sortField == custom`
2. Drag book card to new position in grid
3. Other cards shift to make room
4. On drop: `PUT /shelves/{id}/books/reorder` with new order
5. If shelf uses a named sort (e.g. title), dragging switches to custom with toast: "Switched to custom order"

**`prefers-reduced-motion`:** skip fly-to animation and ghost clone — instant add with toast only.

**Mobile (< 768px):**

- Sidebar collapses to horizontal scrollable row of shelf color dots above filter bar
- Tap a dot to select that shelf
- Long-press a dot for edit/delete options
- Drag-and-drop disabled — instead, a "+" button on book cards opens a shelf picker dropdown

### Key JavaScript Functions (app.js)

```javascript
// Shelf data
let allShelves = [];
let activeShelfId = null;

// Shelf CRUD
async function loadShelves()                    // GET /shelves → populate sidebar + allShelves
async function createShelf(data)                // POST /shelves
async function updateShelf(id, data)            // PUT /shelves/{id}
async function deleteShelf(id)                  // DELETE /shelves/{id} with confirm
async function loadShelfBooks(shelfId)          // GET /shelves/{id} → render books + stats

// Shelf membership
async function addBookToShelf(shelfId, bookId)  // POST /shelves/{id}/books
async function removeBookFromShelf(shelfId, bookId)  // DELETE /shelves/{id}/books/{bookId}

// Ordering
async function reorderShelves(shelfIds)         // PUT /shelves/reorder
async function reorderShelfBooks(shelfId, bookIds)  // PUT /shelves/{id}/books/reorder

// UI
function renderSidebar(shelves)                 // Render shelf cards in sidebar
function renderShelfCard(shelf)                 // Single shelf card with collage
function renderShelfStats(stats, notes)         // Stats bar above book grid
function renderShelfTags(bookElement, shelves)  // Colored pills on book cards
function openCreateShelfModal()
function openEditShelfModal(shelf)
function renderColorPicker(selectedColor)       // Preset circles + custom button

// Drag and drop
function initBookDrag(bookCardElement)          // Setup dragstart on book cards
function initShelfDropTarget(shelfCardElement)  // Setup dragover/drop on shelf cards
function initShelfReorder()                     // Setup drag reorder in sidebar
function initBookReorder()                      // Setup drag reorder in book grid

// State
function selectShelf(shelfId)                   // Set active shelf, fetch + render
function clearShelfFilter()                     // Return to "All Books"
function toggleSidebar()                        // Collapse/expand, save to localStorage
```

On page load: call `loadShelves()` alongside `loadBooks()`.
After any book add/update/delete: refresh sidebar (counts, collages) and re-render shelf tags.

---

## Tests

### ShelfApiTest.java (new test class — 56 tests)

#### Core CRUD (T_SHELF_01 — T_SHELF_12)

**T_SHELF_01 — Create and retrieve a shelf**
1. `POST /shelves` with `{"name": "Favorites"}`
2. Assert `201`, response has `id`, `name`, `bookCount: 0`, `coverBookIds: []`
3. `GET /shelves/{id}` — assert shelf returned with empty books array and zeroed stats

**T_SHELF_02 — List all shelves**
1. Create 3 shelves
2. `GET /shelves` — assert array of 3, each with `bookCount` and `coverBookIds`

**T_SHELF_03 — Update shelf**
1. Create shelf with name "Old Name"
2. `PUT /shelves/{id}` with `{"name": "New Name"}`
3. Assert `200`, name changed

**T_SHELF_04 — Delete shelf**
1. Create shelf, add a book to it
2. `DELETE /shelves/{id}` — assert `204`
3. `GET /shelves/{id}` — assert `404`
4. `GET /books/{bookId}` — assert book still exists

**T_SHELF_05 — Add book to shelf**
1. Create a shelf and a book
2. `POST /shelves/{id}/books` with `{"bookId": "..."}`
3. Assert `201`
4. `GET /shelves/{id}` — assert books array contains the book

**T_SHELF_06 — Remove book from shelf**
1. Create shelf, add book, then `DELETE /shelves/{shelfId}/books/{bookId}`
2. Assert `204`
3. `GET /shelves/{id}` — assert books array is empty

**T_SHELF_07 — Duplicate shelf name returns 409**
1. Create shelf "Favorites"
2. `POST /shelves` with `{"name": "Favorites"}` again
3. Assert `409 Conflict`

**T_SHELF_08 — Add same book twice returns 409**
1. Create shelf, add book
2. Add same book again
3. Assert `409 Conflict`

**T_SHELF_09 — Delete book cascades to shelf_books**
1. Create shelf, add book
2. `DELETE /books/{bookId}`
3. `GET /shelves/{shelfId}` — assert books array is empty (join row cascaded)

**T_SHELF_10 — Book response includes shelves array**
1. Create 2 shelves, add same book to both
2. `GET /books/{bookId}`
3. Assert response has `shelves` array with 2 entries, each with `id`, `name`, `color`

**T_SHELF_11 — Missing name returns 400**
1. `POST /shelves` with `{}`
2. Assert `400`

**T_SHELF_12 — Shelf not found returns 404**
1. `GET /shelves/00000000-0000-0000-0000-000000000000`
2. Assert `404`

#### Color & Sorting (T_SHELF_13 — T_SHELF_18)

**T_SHELF_13 — Create shelf with custom color**
1. `POST /shelves` with `{"name": "Test", "color": "#FF5733"}`
2. Assert `201`, response has `color: "#FF5733"`

**T_SHELF_14 — Default color when none provided**
1. `POST /shelves` with `{"name": "Test"}` (no color)
2. Assert `201`, response has `color: "#C4975A"`

**T_SHELF_15 — Invalid color hex returns 400**
1. `POST /shelves` with `{"name": "Test", "color": "not-a-color"}`
2. Assert `400`

**T_SHELF_16 — Set shelf sort preference**
1. Create shelf, add 3 books with titles "Zorro", "Alpha", "Middle"
2. `PUT /shelves/{id}` with `{"sortField": "title", "sortDirection": "asc"}`
3. `GET /shelves/{id}` — assert books returned in order Alpha, Middle, Zorro

**T_SHELF_17 — Custom sort order preserved**
1. Create shelf with `sortField: "custom"`, add 3 books (A, B, C)
2. `PUT /shelves/{id}/books/reorder` with order [C, A, B]
3. `GET /shelves/{id}` — assert books in order C, A, B

**T_SHELF_18 — Invalid sortField returns 400**
1. `PUT /shelves/{id}` with `{"sortField": "invalid"}`
2. Assert `400`

#### Reorder (T_SHELF_19 — T_SHELF_22)

**T_SHELF_19 — Reorder shelves**
1. Create 3 shelves (positions 0, 1, 2)
2. `PUT /shelves/reorder` with reversed order
3. `GET /shelves` — assert positions are now reversed

**T_SHELF_20 — Reorder books within shelf**
1. Create shelf, add 3 books
2. `PUT /shelves/{id}/books/reorder` with new order
3. `GET /shelves/{id}` — assert books in new order

**T_SHELF_21 — Reorder with missing book ID returns 400**
1. Create shelf, add 2 books
2. `PUT /shelves/{id}/books/reorder` with only 1 book ID
3. Assert `400`

**T_SHELF_22 — Reorder with invalid shelf ID returns 400**
1. `PUT /shelves/reorder` with a non-existent shelf UUID in the array
2. Assert `400`

#### Stats (T_SHELF_23 — T_SHELF_25)

**T_SHELF_23 — Shelf stats computed correctly**
1. Create shelf, add 3 books: rated 3, 4, 5 with pageCount 200, 300, 400. Genres: "Sci-Fi", "Sci-Fi", "Fantasy"
2. `GET /shelves/{id}`
3. Assert `stats.bookCount: 3`, `stats.averageRating: 4.0`, `stats.totalPages: 900`
4. Assert `stats.genreBreakdown: {"Sci-Fi": 2, "Fantasy": 1}`

**T_SHELF_24 — Stats exclude unrated from average**
1. Create shelf, add 2 books: one rated 4, one unrated (rating 0)
2. `GET /shelves/{id}`
3. Assert `stats.averageRating: 4.0` (not 2.0)

**T_SHELF_25 — Stats with empty shelf**
1. Create shelf with no books
2. `GET /shelves/{id}`
3. Assert `stats.bookCount: 0`, `stats.averageRating: 0`, `stats.totalPages: 0`, `stats.genreBreakdown: {}`

#### Cover Collage (T_SHELF_26 — T_SHELF_28)

**T_SHELF_26 — coverBookIds returns first 4 books**
1. Create shelf, add 5 books
2. `GET /shelves` — assert the shelf's `coverBookIds` has exactly 4 entries matching first 4 by position

**T_SHELF_27 — coverBookIds with fewer than 4 books**
1. Create shelf, add 2 books
2. `GET /shelves` — assert `coverBookIds` has 2 entries

**T_SHELF_28 — coverBookIds empty for empty shelf**
1. Create empty shelf
2. `GET /shelves` — assert `coverBookIds: []`

#### Position Edge Cases (T_SHELF_29 — T_SHELF_33)

**T_SHELF_29 — Position auto-assigned on shelf create**
1. Create 3 shelves in sequence
2. `GET /shelves` — assert positions are 0, 1, 2

**T_SHELF_30 — Position auto-assigned on book add**
1. Create shelf, add 3 books in sequence
2. `GET /shelves/{id}` with `sortField: "custom"`
3. Assert books in insertion order (positions 0, 1, 2)

**T_SHELF_31 — Deleting a shelf renumbers remaining positions**
1. Create 3 shelves (positions 0, 1, 2)
2. Delete the middle shelf (position 1)
3. `GET /shelves` — assert remaining shelves have positions 0, 1 (not 0, 2)

**T_SHELF_32 — Shelf description and notes are optional**
1. `POST /shelves` with only `{"name": "Test"}`
2. Assert `201`, `description: null`, `notes: null`

**T_SHELF_33 — Update only notes without changing other fields**
1. Create shelf with name "Test" and color "#C4975A"
2. `PUT /shelves/{id}` with `{"notes": "Some notes"}`
3. Assert `200`, name and color unchanged, notes updated

#### Interaction with Existing Features (T_SHELF_34 — T_SHELF_43)

**T_SHELF_34 — Shelf filter combined with readStatus filter**
1. Create shelf, add 3 books: 2 FINISHED, 1 READING
2. `GET /shelves/{id}` — assert 3 books returned
3. Verify the `readStatus` field is present on each book so frontend can filter client-side

**T_SHELF_35 — Shelf filter combined with search**
1. Create shelf, add 3 books: "Dune", "Neuromancer", "Foundation"
2. `GET /shelves/{id}` — assert 3 books returned with titles for frontend search filtering

**T_SHELF_36 — Book enrichment updates reflected in shelf view**
1. Create shelf, add a book by ISBN only (no title/author yet)
2. `GET /shelves/{id}` — book appears with null title
3. Poll `GET /books/{bookId}` until title is non-null (enrichment complete)
4. `GET /shelves/{id}` — assert the book now has enriched title/author

**T_SHELF_37 — Shelf sort by rating with mixed rated/unrated books**
1. Create shelf with `sortField: "rating"`, `sortDirection: "desc"`
2. Add books: rated 5, rated 3, unrated (0), rated 1
3. `GET /shelves/{id}` — assert order: 5, 3, 1, 0 (unrated sorted last)

**T_SHELF_38 — Shelf sort by title with null titles**
1. Create shelf with `sortField: "title"`, `sortDirection: "asc"`
2. Add 2 books with titles and 1 ISBN-only book (null title, pending enrichment)
3. `GET /shelves/{id}` — assert null-title book sorts last

**T_SHELF_39 — Book appears in multiple shelves' stats independently**
1. Create 2 shelves, add the same book (rated 5, 300 pages, genre "Sci-Fi") to both
2. `GET /shelves/{shelf1Id}` — assert `stats.bookCount: 1`, `stats.averageRating: 5.0`, `stats.totalPages: 300`
3. `GET /shelves/{shelf2Id}` — assert identical stats

**T_SHELF_40 — Deleting a book updates shelf stats**
1. Create shelf, add 2 books rated 4 and 5
2. `GET /shelves/{id}` — assert `stats.averageRating: 4.5`, `stats.bookCount: 2`
3. `DELETE /books/{bookId}` (the 5-rated book)
4. `GET /shelves/{id}` — assert `stats.averageRating: 4.0`, `stats.bookCount: 1`

**T_SHELF_41 — Updating a book's rating updates shelf stats**
1. Create shelf, add a book rated 3
2. `GET /shelves/{id}` — assert `stats.averageRating: 3.0`
3. `PUT /books/{bookId}` with `{"rating": 5}`
4. `GET /shelves/{id}` — assert `stats.averageRating: 5.0`

**T_SHELF_42 — coverBookIds updates when book is removed from shelf**
1. Create shelf, add 5 books
2. `GET /shelves` — note `coverBookIds` (first 4 by position)
3. Remove the first book from shelf
4. `GET /shelves` — assert `coverBookIds` now includes the 5th book

**T_SHELF_43 — Re-enrich all doesn't break shelf memberships**
1. Create shelf, add 3 books with ISBNs
2. `POST /books/re-enrich`
3. Wait for enrichment to complete
4. `GET /shelves/{id}` — assert still 3 books, memberships intact

#### Validation Edge Cases (T_SHELF_44 — T_SHELF_56)

**T_SHELF_44 — Empty string name returns 400**
1. `POST /shelves` with `{"name": ""}`
2. Assert `400`

**T_SHELF_45 — Whitespace-only name returns 400**
1. `POST /shelves` with `{"name": "   "}`
2. Assert `400`

**T_SHELF_46 — Name at exactly 100 chars is accepted**
1. `POST /shelves` with name of exactly 100 characters
2. Assert `201`

**T_SHELF_47 — Name at 101 chars returns 400**
1. `POST /shelves` with name of 101 characters
2. Assert `400`

**T_SHELF_48 — Duplicate name with different casing returns 409**
1. Create shelf "Favorites"
2. `POST /shelves` with `{"name": "favorites"}`
3. Assert `409`

**T_SHELF_49 — Special characters in shelf name are accepted**
1. `POST /shelves` with `{"name": "Sci-Fi & Fantasy (Best!)"}`
2. Assert `201`
3. `GET /shelves/{id}` — assert name preserved exactly

**T_SHELF_50 — Color hex validation**
1. `POST /shelves` with `{"name": "T1", "color": "#FFF"}` — assert `400` (too short)
2. `POST /shelves` with `{"name": "T2", "color": "#GGGGGG"}` — assert `400` (invalid hex)
3. `POST /shelves` with `{"name": "T3", "color": "C4975A"}` — assert `400` (missing #)
4. `POST /shelves` with `{"name": "T4", "color": "#c4975a"}` — assert `201` (lowercase valid)

**T_SHELF_51 — Reorder with empty array returns 400**
1. `PUT /shelves/reorder` with `{"order": []}`
2. Assert `400`

**T_SHELF_52 — Reorder books with book ID not on this shelf returns 400**
1. Create shelf, add 2 books. Create a 3rd book not on the shelf.
2. `PUT /shelves/{id}/books/reorder` with order including the 3rd book's ID
3. Assert `400`

**T_SHELF_53 — Add book with invalid UUID returns 400**
1. `POST /shelves/{id}/books` with `{"bookId": "not-a-uuid"}`
2. Assert `400`

**T_SHELF_54 — Add book with non-existent UUID returns 404**
1. `POST /shelves/{id}/books` with `{"bookId": "00000000-0000-0000-0000-000000000000"}`
2. Assert `404`

**T_SHELF_55 — Malformed JSON on shelf create returns 400**
1. `POST /shelves` with body `"not json"`
2. Assert `400`

**T_SHELF_56 — Non-UUID shelf path returns 404**
1. `GET /shelves/not-a-uuid`
2. Assert `404`

---

## Build Order

### Phase 1: Backend — Schema & Models
1. Add `shelves` and `shelf_books` tables + `LOWER(name)` unique index to `DatabaseConfig.runMigrations()`
2. Create `Shelf.java` model class (all fields including transient computed ones)

### Phase 2: Backend — Repositories
3. Create `ShelfRepository.java` interface
4. Create `InMemoryShelfRepository.java` (with `ShelfBookEntry` record for join table simulation)
5. Create `JdbcShelfRepository.java`

### Phase 3: Backend — Controller & Wiring
6. Create `ShelfController.java` with all 9 handlers (CRUD + membership + reorder) including validation and stats computation
7. Wire into `App.java` — create repositories, controller, register 9 routes, pass `ShelfRepository` to `BookController`
8. Modify `BookController` to include `shelves` array in book JSON responses

### Phase 4: Tests
9. Write 56 API tests in `ShelfApiTest.java`
10. Run `./gradlew test` — verify all pass (existing 99 + new 56)

### Phase 5: Frontend — Sidebar & Shelves
11. Restructure `index.html` layout to sidebar + main content
12. Add sidebar HTML: shelf cards container, "All Books" link, "+ New Shelf" button, collapse toggle
13. Add create shelf modal and edit shelf modal HTML (with color picker and sort dropdown)
14. Add shelf stats bar HTML (shown when viewing a shelf)
15. Add "Shelves" checkbox section to existing book edit modal

### Phase 6: Frontend — JavaScript
16. Implement `loadShelves()`, `renderSidebar()`, `renderShelfCard()` with cover collages
17. Implement `selectShelf()`, `clearShelfFilter()`, `toggleSidebar()` with localStorage persistence
18. Implement `createShelf()`, `updateShelf()`, `deleteShelf()` with modals and color picker
19. Implement `addBookToShelf()`, `removeBookFromShelf()` and book edit modal shelf checkboxes
20. Implement `renderShelfStats()` and `renderShelfTags()` on book cards
21. Implement drag-and-drop: book-to-shelf, shelf reorder, book reorder within shelf
22. Add mobile responsive behavior: collapsed sidebar as horizontal dots, shelf picker dropdown instead of drag

### Phase 7: Frontend — Styling
23. Style sidebar layout, shelf cards, collapse states in `style.css`
24. Style create/edit shelf modals, color picker presets, sort dropdown
25. Style shelf stats bar with inline genre bars
26. Style shelf tags (colored pills) on book cards
27. Style drag-and-drop states: ghost, glow highlights, shift animations
28. Style mobile responsive breakpoints
29. Add `prefers-reduced-motion` overrides for drag animations

### Phase 8: Integration Test
30. Test in Docker: `docker compose up --build` — verify full flow end-to-end

---

## Things to Watch Out For

### Backend
- **Case-insensitive uniqueness** — the `LOWER(name)` unique index on PostgreSQL handles this at the DB level. `InMemoryShelfRepository` must replicate this check manually (compare lowercase names).
- **CASCADE behavior** — deleting a shelf removes `shelf_books` rows. Deleting a book removes `shelf_books` rows. Neither deletion affects the other entity. `InMemoryShelfRepository` must manually clean up join rows when `BookRepository.delete()` is called — either via a cleanup method called from `BookController.handleDeleteBook`, or by accepting that cascade tests only work in JDBC.
- **Position gaps after delete** — when a shelf or book-on-shelf is deleted, renumber remaining positions to close gaps. Otherwise position 0, 2 after deleting position 1 would cause visual ordering bugs.
- **Reorder validation** — the `order` array must contain exactly the IDs currently in the shelf/system. Extra or missing IDs should return 400. This prevents accidental data loss.
- **Book response enrichment** — adding `shelves` to every book in `GET /books` means calling `findShelvesForBook()` per book. For a personal shelf (< 1000 books) this is fine. Optimization if needed later: batch query joining `shelf_books` + `shelves` for all book IDs in one SQL call.
- **Route registration order** — `PUT /shelves/reorder` must be registered before `PUT /shelves/{id}` so the router matches `reorder` as a static segment. Same for `PUT /shelves/{id}/books/reorder`.
- **UNIQUE violation handling** — `JdbcShelfRepository.save()` and `addBook()` should catch `SQLException` with unique constraint violations and let the controller return 409.
- **Sorting nulls** — when sorting by title or author, null values sort last (not first). When sorting by rating descending, unrated (0) books sort last.

### Frontend
- **Sidebar layout shift** — the sidebar changes the book grid width. Use CSS grid or flexbox so the grid reflows smoothly. The collapse/expand should animate the width transition.
- **Cover collage loading** — the 2x2 thumbnails load from `/books/{id}/cover`. Use small `<img>` elements with lazy loading. If a book has no cover, show the shelf's color as a fallback.
- **Drag and drop browser support** — HTML5 Drag and Drop works in all modern browsers but has quirks: `dragover` must call `preventDefault()` to allow drops, `dataTransfer` has limited data in `dragover` (only in `drop`). Touch devices don't support it — hence the mobile fallback with a shelf picker.
- **Ghost positioning** — the drag ghost needs to follow the cursor. Use `dragstart` to set a custom drag image via `dataTransfer.setDragImage()`.
- **Sidebar scroll** — if many shelves, the sidebar should scroll independently of the main content. Use `overflow-y: auto` with a max-height.
- **Context menu** — right-click on shelf cards should show a custom context menu (Edit, Delete), not the browser default. Use `contextmenu` event with `preventDefault()`.
- **localStorage keys** — use namespaced keys: `bookshelf_sidebar_collapsed`, `bookshelf_stats_dismissed` to avoid conflicts.
- **Refresh after changes** — after adding/removing a book from a shelf, refresh: sidebar shelf counts, cover collages, book card shelf tags. Don't reload the entire page — update the relevant DOM elements.

### CLAUDE.md Updates Needed After Implementation
- Add `Shelf.java`, `ShelfRepository.java`, `InMemoryShelfRepository.java`, `JdbcShelfRepository.java`, `ShelfController.java` to source file overview
- Add `/shelves` endpoints to API endpoints table
- Add `shelves` and `shelf_books` tables to DB schema migrations section
- Add `ShelfApiTest.java` to test classes
- Update test count
- Document the `shelves` field on book responses in data model
