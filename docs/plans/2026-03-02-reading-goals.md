# Reading Goals

**Status:** Not started
**Date:** 2026-03-02

## Overview

Let users set yearly reading goals (e.g. "Read 24 books in 2026") and track progress against them. A goal tracks a target count of finished books for a given year. The frontend shows a progress widget. Lightweight feature — one new table, one new controller, a small UI widget.

## Data Model

### ReadingGoal

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `id` | UUID | auto | Server-generated |
| `year` | Integer | yes | e.g. 2026. Must be 2000-2100. |
| `target` | Integer | yes | Number of books to finish. Must be >= 1. |
| `createdAt` | Instant | auto | |
| `updatedAt` | Instant | auto | |

**Constraint:** One goal per year (UNIQUE on `year`).

Progress is computed at read time — count of `FINISHED` books whose `createdAt` falls within that year. No denormalized counter needed.

## Database Schema

```sql
CREATE TABLE IF NOT EXISTS reading_goals (
    id UUID PRIMARY KEY,
    year INTEGER NOT NULL UNIQUE CHECK (year BETWEEN 2000 AND 2100),
    target INTEGER NOT NULL CHECK (target >= 1),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
```

Add to `DatabaseConfig.runMigrations()` as a new migration block.

## API Endpoints

| Method | Path | Description | Success Code |
|--------|------|-------------|--------------|
| `GET` | `/goals` | List all goals (with computed progress) | `200 OK` |
| `GET` | `/goals/{year}` | Get goal for a specific year (with progress) | `200 OK` |
| `POST` | `/goals` | Create a reading goal | `201 Created` |
| `PUT` | `/goals/{year}` | Update an existing goal's target | `200 OK` |
| `DELETE` | `/goals/{year}` | Delete a goal | `204 No Content` |

Note: Goals are addressed by `year` (not UUID) in the URL for readability. Internally still stored with a UUID primary key.

### Request/Response Shapes

**POST /goals**
```json
{ "year": 2026, "target": 24 }
```
Response `201`:
```json
{
  "id": "...",
  "year": 2026,
  "target": 24,
  "progress": 5,
  "percentage": 20,
  "onPace": true,
  "createdAt": "..."
}
```

**GET /goals**
```json
[
  {
    "id": "...",
    "year": 2026,
    "target": 24,
    "progress": 5,
    "percentage": 20,
    "onPace": true,
    "createdAt": "..."
  }
]
```

**GET /goals/2026**
Same shape as above but single object (not array).

**PUT /goals/2026**
```json
{ "target": 30 }
```
Response `200`: updated goal with recomputed progress.

### Computed Fields (not stored in DB)

- `progress` — count of `FINISHED` books where `createdAt` is within the goal's year (Jan 1 00:00:00 UTC to Dec 31 23:59:59 UTC)
- `percentage` — `Math.min(100, (progress * 100) / target)`, integer
- `onPace` — whether the user is on track. Formula: `progress >= expectedByNow` where `expectedByNow = target * (dayOfYear / daysInYear)`. For past years, `onPace` is simply `progress >= target`. For future years (if the current date is before Jan 1 of that year), `onPace` is `false` unless `progress >= target`.

### Error Responses

| Code | When |
|------|------|
| `400` | Missing `year` or `target`, `year` not 2000-2100, `target` < 1, invalid types |
| `404` | No goal for that year |
| `409` | Goal for that year already exists |

## Backend Changes

### New Files

**`ReadingGoal.java`** — Model class with `id`, `year`, `target`, `createdAt`, `updatedAt`.

**`GoalRepository.java`** — Interface:
```java
public interface GoalRepository {
    List<ReadingGoal> findAll();
    Optional<ReadingGoal> findByYear(int year);
    ReadingGoal save(ReadingGoal goal);
    Optional<ReadingGoal> update(int year, int newTarget);
    boolean delete(int year);
}
```

**`JdbcGoalRepository.java`** — JDBC implementation.

**`InMemoryGoalRepository.java`** — For tests. `ConcurrentHashMap<Integer, ReadingGoal>` keyed by year.

**`GoalController.java`** — HTTP handlers. Needs access to both `GoalRepository` and `BookRepository` (to count finished books for progress computation).

### Modified Files

**`App.java`** — Wire `GoalRepository`, `GoalController`, register routes.

**`DatabaseConfig.java`** — Add `reading_goals` table to `runMigrations()`.

### Routes to Register (App.java)

```java
router.addRoute("GET", "/goals", goalController::handleGetGoals);
router.addRoute("POST", "/goals", goalController::handleCreateGoal);
router.addRoute("GET", "/goals/{year}", goalController::handleGetGoal);
router.addRoute("PUT", "/goals/{year}", goalController::handleUpdateGoal);
router.addRoute("DELETE", "/goals/{year}", goalController::handleDeleteGoal);
```

### Progress Computation (GoalController)

```java
private int countFinishedInYear(int year) {
    Instant start = Instant.parse(year + "-01-01T00:00:00Z");
    Instant end = Instant.parse((year + 1) + "-01-01T00:00:00Z");
    return (int) repository.findAll().stream()
        .filter(b -> b.getReadStatus() == ReadStatus.FINISHED)
        .filter(b -> b.getCreatedAt() != null
                   && !b.getCreatedAt().isBefore(start)
                   && b.getCreatedAt().isBefore(end))
        .count();
}
```

This uses `BookRepository.findAll()` and filters in memory, consistent with the project's existing approach.

### Validation (GoalController)

- `year`: required, integer, 2000-2100
- `target`: required, integer, >= 1
- `POST` — 409 if goal for that year already exists
- `PUT` — only `target` can be changed; `year` is from the URL path
- `{year}` path param: must be a valid integer, else 404

## Frontend Changes

### index.html

Add a goal widget in the header area (visible when a goal exists for the current year):

```html
<div id="goal-widget" class="goal-widget" hidden>
  <div class="goal-header">
    <span id="goal-label">2026 Reading Goal</span>
    <button id="goal-edit-btn" class="goal-edit-btn" title="Edit goal">Edit</button>
  </div>
  <div class="goal-progress-bar">
    <div id="goal-fill" class="goal-fill"></div>
  </div>
  <div class="goal-stats">
    <span id="goal-count">5 / 24 books</span>
    <span id="goal-pace" class="goal-pace">On pace</span>
  </div>
</div>
```

Add a goal setup/edit modal:

```html
<div id="goal-modal" class="modal-overlay" hidden>
  <div class="modal goal-modal">
    <div class="modal-header">
      <h2>Reading Goal</h2>
      <button class="modal-close" id="goal-close" type="button">&times;</button>
    </div>
    <div class="modal-body">
      <div class="form-group">
        <label for="goal-year">Year</label>
        <input type="number" id="goal-year" min="2000" max="2100">
      </div>
      <div class="form-group">
        <label for="goal-target">Target (books to finish)</label>
        <input type="number" id="goal-target" min="1" placeholder="e.g. 24">
      </div>
      <div class="form-actions">
        <button id="goal-save-btn" class="btn-primary">Save Goal</button>
        <button id="goal-delete-btn" class="btn-danger" hidden>Delete Goal</button>
      </div>
    </div>
  </div>
</div>
```

If no goal exists for the current year, show a "Set a reading goal" link/button instead of the widget.

### app.js

- `loadCurrentGoal()` — `GET /goals/{currentYear}`. If 200, show widget. If 404, show "Set goal" prompt.
- `renderGoalWidget(goal)` — update progress bar width, count text, pace indicator
- `saveGoal(year, target)` — `POST /goals` or `PUT /goals/{year}` depending on whether one exists
- `deleteGoal(year)` — `DELETE /goals/{year}` with confirmation
- On page load: call `loadCurrentGoal()` alongside `loadBooks()`
- After `addBook()` or `deleteBook()` completes, refresh the goal widget (progress may have changed)

### style.css

- `.goal-widget` — compact bar at the top of the page, below header, above filter bar
- `.goal-progress-bar` — full-width thin bar (similar to reading progress but larger)
- `.goal-fill` — animated width, gold color (`var(--gold)`)
- `.goal-pace.on-pace` — green text; `.goal-pace.behind` — amber/red text
- `.goal-modal` — same style as existing edit modal
- `prefers-reduced-motion` — disable progress bar animation

## Tests

### GoalApiTest.java (new test class)

**T_GOAL_01 — Create and retrieve a goal**
1. `POST /goals` with `{"year": 2026, "target": 24}`
2. Assert `201`, response has `id`, `year`, `target`, `progress: 0`, `percentage: 0`
3. `GET /goals/2026` — assert same data

**T_GOAL_02 — List all goals**
1. Create goals for 2025 and 2026
2. `GET /goals` — assert array of 2

**T_GOAL_03 — Progress reflects finished books**
1. Create goal for current year with `target: 10`
2. Create 3 books with `readStatus: FINISHED`
3. `GET /goals/{currentYear}`
4. Assert `progress: 3`, `percentage: 30`

**T_GOAL_04 — Progress excludes non-FINISHED books**
1. Create goal, then create books with READING, WANT_TO_READ, DNF statuses
2. `GET /goals/{year}` — assert `progress: 0`

**T_GOAL_05 — Update goal target**
1. Create goal with `target: 10`
2. `PUT /goals/{year}` with `{"target": 20}`
3. Assert `200`, `target` is now 20
4. Progress/percentage recalculated

**T_GOAL_06 — Delete goal**
1. Create goal
2. `DELETE /goals/{year}` — assert `204`
3. `GET /goals/{year}` — assert `404`

**T_GOAL_07 — Duplicate year returns 409**
1. Create goal for 2026
2. `POST /goals` with `{"year": 2026, "target": 30}`
3. Assert `409`

**T_GOAL_08 — Invalid target returns 400**
1. `POST /goals` with `{"year": 2026, "target": 0}`
2. Assert `400`
3. `POST /goals` with `{"year": 2026, "target": -5}`
4. Assert `400`

**T_GOAL_09 — Invalid year returns 400**
1. `POST /goals` with `{"year": 1999, "target": 10}`
2. Assert `400`

**T_GOAL_10 — Goal not found returns 404**
1. `GET /goals/2099`
2. Assert `404`

**T_GOAL_11 — On-pace computation**
1. Create goal for current year with `target: 12` (1 per month)
2. Create enough FINISHED books to be on pace for current month
3. `GET /goals/{year}` — assert `onPace: true`

## Build Order

1. Add `reading_goals` table to `DatabaseConfig.runMigrations()`
2. Create `ReadingGoal.java` model class
3. Create `GoalRepository.java` interface
4. Create `InMemoryGoalRepository.java` (for tests)
5. Create `JdbcGoalRepository.java`
6. Create `GoalController.java` with all handlers + progress computation
7. Wire into `App.java` — create repositories, controller, register routes
8. Write 11 API tests in `GoalApiTest.java`
9. Run `./gradlew test` — verify all pass
10. Add goal widget and modal to `index.html`
11. Add goal JS logic to `app.js`
12. Style goal widget in `style.css`
13. Test in Docker: `docker compose up --build`

## Things to Watch Out For

- **Year as path param** — `GET /goals/{year}` uses an integer path param, unlike `/books/{id}` which uses UUID. `GoalController` must parse the string to int and return 404 on `NumberFormatException`.
- **Route namespace** — goals live under `/goals`, completely separate from `/books` and `/shelves`. No conflicts.
- **Progress computation queries all books** — uses `BookRepository.findAll()` + stream filter. Fine for a personal shelf. If the library grows huge, could add a `countFinishedInYear(int year)` method to `BookRepository` with a SQL `COUNT` query.
- **On-pace calculation** — uses day-of-year / days-in-year. For years that haven't started yet, `onPace` should be `false` (unless somehow already at target). For past completed years, simply `progress >= target`.
- **Timezone** — `createdAt` is stored as UTC. The year boundary check uses UTC dates (`2026-01-01T00:00:00Z`). This is consistent with how the rest of the app handles timestamps.
- **Refreshing goal after book changes** — the frontend should re-fetch the goal widget after any book create/update/delete that might change the FINISHED count.
- **UNIQUE constraint on year** — `JdbcGoalRepository.save()` should catch `SQLException` with UNIQUE violation and let the controller return 409.
