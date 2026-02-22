# Bulk Barcode Scan Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a second scan button that keeps the camera open between scans, silently skips duplicates, shows per-scan toasts, and closes with a "Done" button that scrolls to the first newly added book.

**Architecture:** Pure frontend change — three files touched (`index.html`, `style.css`, `app.js`). Reuses the existing scanner modal, `openScanner()`, `closeScanner()`, `pollForEnrichment()`, and `showToast()`. New `isBulkMode` flag changes behaviour of `handleScanResult()`. No backend changes needed.

**Tech Stack:** Vanilla JS, HTML, CSS — same stack as the rest of the frontend.

---

## Context

- Single-scan button: `#scan-btn` (line 39 `index.html`) → calls `openScanner()` (line 815 `app.js`)
- Scanner modal: `#scanner-modal` (line 175 `index.html`), title h2 at line 178
- `handleScanResult(decodedText)` at line 873 — stops scanner, closes modal, calls `onBarcodeScanned()`
- `onBarcodeScanned(code)` at line 880 — checks for duplicate via `GET /books/isbn/{code}`, shows `confirm()` dialog, calls `addBook()`
- `closeScanner()` at line 850 — stops stream, hides modal
- `showToast(message, type)` at line 94 — existing types: `'success'` (default), `'error'`, `'info'` (amber)
- Existing `pollingTimers` Map + `pollForEnrichment(bookId)` handle async enrichment per book

---

## Task 1: HTML — add bulk scan button and Done button

**Files:**
- Modify: `static/index.html:39-44` (scan button area)
- Modify: `static/index.html:175-195` (scanner modal)

**Step 1: Add `id` to scanner modal `<h2>` for dynamic text**

At line 178, change:
```html
<h2>Scan ISBN Barcode</h2>
```
to:
```html
<h2 id="scanner-modal-title">Scan ISBN Barcode</h2>
```

**Step 2: Add Done button and scan counter to scanner modal body**

After the `<p class="scanner-hint">` at line 190, add before `scanner-timeout-hint`:
```html
<div id="scanner-bulk-footer" hidden>
    <p id="scanner-bulk-count" class="scanner-bulk-count">0 books scanned</p>
    <button id="scanner-done" type="button" class="scanner-done-btn">Done</button>
</div>
```

Full updated scanner body (lines 185–193) becomes:
```html
<div class="scanner-body">
    <select id="camera-select" class="camera-select" hidden>
        <option value="">Detecting cameras...</option>
    </select>
    <div id="scanner-viewfinder"></div>
    <p class="scanner-hint">Point your camera at the barcode. Tilt glossy covers slightly to reduce glare.</p>
    <div id="scanner-bulk-footer" hidden>
        <p id="scanner-bulk-count" class="scanner-bulk-count">0 books scanned</p>
        <button id="scanner-done" type="button" class="scanner-done-btn">Done</button>
    </div>
    <p id="scanner-timeout-hint" class="scanner-timeout-hint" hidden></p>
    <p id="scanner-error" class="scanner-error" hidden></p>
</div>
```

**Step 3: Add bulk scan button next to `#scan-btn`**

After the closing `</button>` of `#scan-btn` (line 44), before `#add-btn`, add:
```html
<button id="bulk-scan-btn" type="button" title="Scan multiple ISBN barcodes">
    <svg width="18" height="18" viewBox="0 0 18 18" fill="none">
        <path d="M1 4V2a1 1 0 011-1h3M14 1h2a1 1 0 011 1v3M17 14v2a1 1 0 01-1 1h-3M4 17H2a1 1 0 01-1-1v-3" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
        <path d="M4 5v8M7 5v8M10 5v8M13 5v8" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" opacity="0.8"/>
        <circle cx="14.5" cy="3.5" r="3" fill="var(--bg-page)" stroke="currentColor" stroke-width="1.1"/>
        <path d="M13.5 3.5h2M14.5 2.5v2" stroke="currentColor" stroke-width="1.1" stroke-linecap="round"/>
    </svg>
</button>
```

**Step 4: Verify HTML looks right**

Open `static/index.html` in a browser (or just inspect the diff). No server needed for this step.

---

## Task 2: CSS — style bulk scan button and Done button

**Files:**
- Modify: `static/style.css` (after the `#scan-btn` block, around line 943)

**Step 1: Add `#bulk-scan-btn` styles**

After the `#scan-btn:active` block (around line 966), add:
```css
#bulk-scan-btn {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 2.2rem;
    height: 2.2rem;
    background: transparent;
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    color: var(--text-muted);
    cursor: pointer;
    transition: color 0.15s, border-color 0.15s;
    flex-shrink: 0;
    position: relative;
}

#bulk-scan-btn:hover {
    color: var(--gold);
    border-color: var(--gold-dim);
}

#bulk-scan-btn:active {
    transform: scale(0.95);
}
```

**Step 2: Add `.scanner-done-btn` and `.scanner-bulk-count` styles**

Add after the scanner modal styles (around line 1000):
```css
/* ---- Bulk Scan Footer ---- */

#scanner-bulk-footer {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 0.75rem;
    margin-top: 1rem;
}

.scanner-bulk-count {
    font-size: 0.8rem;
    color: var(--text-secondary);
    margin: 0;
}

.scanner-done-btn {
    padding: 0.55rem 2rem;
    background: var(--gold);
    border: none;
    border-radius: var(--radius-sm);
    color: var(--bg-page);
    font-family: inherit;
    font-size: 0.9rem;
    font-weight: 600;
    cursor: pointer;
    transition: opacity 0.15s, transform 0.1s;
}

.scanner-done-btn:hover {
    opacity: 0.88;
}

.scanner-done-btn:active {
    transform: scale(0.97);
}
```

---

## Task 3: JS — bulk mode state and logic

**Files:**
- Modify: `static/app.js`

### Step 1: Add DOM references (after line 30 block of const refs)

Find the `const scannerTimeoutHint` line (line 539). After that line, add:
```js
const scannerModalTitle = document.getElementById('scanner-modal-title');
const scannerBulkFooter = document.getElementById('scanner-bulk-footer');
const scannerBulkCount  = document.getElementById('scanner-bulk-count');
const scannerDoneBtn    = document.getElementById('scanner-done');
const bulkScanBtn       = document.getElementById('bulk-scan-btn');
```

### Step 2: Add bulk mode state variables (after line 536 `let scannerHintTimer`)

```js
let isBulkMode = false;
let bulkScannedIds = [];
let bulkCooldownCode = null;
```

### Step 3: Modify `openScanner()` to accept `bulk` param

Replace the existing `async function openScanner()` (lines 815–836) with:
```js
async function openScanner(bulk = false) {
    isBulkMode = bulk;
    bulkScannedIds = [];
    bulkCooldownCode = null;

    scannerModalTitle.textContent = bulk ? 'Scan ISBN Barcodes' : 'Scan ISBN Barcode';
    scannerBulkFooter.hidden = !bulk;
    scannerBulkCount.textContent = '0 books scanned';

    scannerError.hidden = true;
    scannerTimeoutHint.hidden = true;
    scannerModal.hidden = false;
    isScanning = true;

    // Need an initial getUserMedia to get labeled device list
    try {
        const tempStream = await navigator.mediaDevices.getUserMedia({ video: true });
        tempStream.getTracks().forEach(t => t.stop());
    } catch (e) {
        // Permission denied — enumerateCameras will still work but labels may be empty
    }

    await enumerateCameras();
    startScannerTimeouts();

    const viewfinder = document.getElementById('scanner-viewfinder');
    viewfinder.classList.add('scanning');

    startZbarScanner();
}
```

### Step 4: Modify `closeScanner()` to reset bulk state

Replace the existing `function closeScanner()` (lines 850–863) with:
```js
function closeScanner() {
    isScanning = false;
    isBulkMode = false;
    bulkCooldownCode = null;
    clearScannerTimeouts();

    const viewfinder = document.getElementById('scanner-viewfinder');
    viewfinder.classList.remove('scanning');

    if (activeStream) {
        activeStream.getTracks().forEach(t => t.stop());
        activeStream = null;
    }

    scannerModal.hidden = true;
}
```

Note: `bulkScannedIds` is intentionally NOT cleared here — `doneBulkScan()` needs to read it after calling `closeScanner()`.

### Step 5: Modify `handleScanResult()` for bulk path

Replace the existing `function handleScanResult(decodedText)` (lines 873–878) with:
```js
function handleScanResult(decodedText) {
    if (!isScanning) return;
    const code = decodedText.trim();

    if (isBulkMode) {
        // Ignore same barcode during cooldown to prevent re-triggering
        if (bulkCooldownCode === code) return;
        bulkCooldownCode = code;
        setTimeout(() => { if (bulkCooldownCode === code) bulkCooldownCode = null; }, 1500);
        bulkAddBook(code);
        return;
    }

    // Single mode — existing behaviour
    isScanning = false;
    closeScanner();
    onBarcodeScanned(code);
}
```

### Step 6: Add `bulkAddBook(code)` function

Add immediately after `handleScanResult` (before `onBarcodeScanned`):
```js
async function bulkAddBook(code) {
    if (!isValidIsbn(code)) {
        showToast('Not a valid ISBN: ' + code, 'error');
        return;
    }

    // Check for duplicate — silent skip with distinct toast
    try {
        const resp = await fetch(API + '/books/isbn/' + code);
        if (resp.ok) {
            const existing = await resp.json();
            const label = existing.title || code;
            showToast(`Already in your library: "${label}"`, 'info');
            return;
        }
    } catch (e) { /* network error — try to add anyway */ }

    try {
        const book = await apiPost('/books', { isbn: code, readStatus: 'WANT_TO_READ' });
        bulkScannedIds.push(book.id);

        // Update counter in modal
        scannerBulkCount.textContent = `${bulkScannedIds.length} book${bulkScannedIds.length !== 1 ? 's' : ''} scanned`;

        showToast('Added: ' + code);

        // Reset timeout hints so the user gets the full window for the next book
        clearScannerTimeouts();
        startScannerTimeouts();

        if (!book.title) pollForEnrichment(book.id);
    } catch (e) {
        showToast(e.message || 'Failed to add book', 'error');
    }
}
```

### Step 7: Add `doneBulkScan()` function

Add immediately after `bulkAddBook`:
```js
async function doneBulkScan() {
    const idsToScroll = [...bulkScannedIds]; // capture before closeScanner clears
    closeScanner();

    if (idsToScroll.length === 0) return;

    if (currentFilter !== 'all') setFilter('all');
    await loadBooks();

    // Scroll to the first newly added book
    const firstId = idsToScroll[0];
    const card = bookGrid.querySelector(`[data-id="${firstId}"]`);
    if (card) card.scrollIntoView({ behavior: 'smooth', block: 'center' });
}
```

### Step 8: Add event listeners

Find the scanner event listeners block (around line 913). After `scanBtn.addEventListener('click', openScanner)`, add:
```js
bulkScanBtn.addEventListener('click', () => openScanner(true));
scannerDoneBtn.addEventListener('click', doneBulkScan);
```

### Step 9: Hide `bulkScanBtn` if zbar-wasm unavailable

Find the existing zbar-wasm check (around line 906):
```js
if (typeof zbarWasm === 'undefined') {
    scanBtn.style.display = 'none';
}
```

Change to:
```js
if (typeof zbarWasm === 'undefined') {
    scanBtn.style.display = 'none';
    bulkScanBtn.style.display = 'none';
}
```

---

## Task 4: Manual testing

No automated frontend tests exist in this project. Verify manually by running the server.

**Step 1: Build and run**
```bash
./gradlew run
```
Open `http://localhost:8080` in browser.

**Step 2: Verify single scan unchanged**
- Click the original scan button (no `+` badge)
- Scanner opens with title "Scan ISBN Barcode", no Done button visible
- Scan a barcode → modal closes, book added as before
- Duplicate: confirm dialog still appears (existing behaviour preserved)

**Step 3: Verify bulk scan — happy path**
- Click the new bulk scan button (barcode icon with `+` badge)
- Modal title reads "Scan ISBN Barcodes", Done button is visible, counter shows "0 books scanned"
- Scan first barcode → toast "Added: [ISBN]", counter updates to "1 book scanned", camera stays open
- Scan second barcode → toast "Added: [ISBN]", counter "2 books scanned"
- Click Done → modal closes, view switches to All, page scrolls to first added book

**Step 4: Verify duplicate handling in bulk mode**
- With bulk scanner open, scan a barcode for a book already in library
- Expect: amber toast "Already in your library: "[title]"", no confirm dialog, camera stays open

**Step 5: Verify cooldown**
- Hold barcode in frame without moving it
- Expect: scanned once, then ignored for ~1.5 seconds, not added multiple times

**Step 6: Verify X button in bulk mode**
- Open bulk scanner, scan one book, click X
- Modal closes, no scroll, book was already added (stays in library)

**Step 7: Run backend tests to confirm nothing broken**
```bash
./gradlew test
```
Expected: all 45 tests pass.

---

## Task 5: Commit

```bash
git add static/index.html static/style.css static/app.js
git commit -m "feat: add bulk barcode scan mode with per-scan toasts and Done button"
```
