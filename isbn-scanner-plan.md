# ISBN Barcode Scanner — Implementation Plan

## Overview

Add a webcam-based ISBN barcode scanner to the MyBookShelf frontend. Users click a
"Scan" button, grant camera access, point the camera at a book's barcode, and the
ISBN is detected, validated, and the book is automatically added to the shelf.

**No backend changes required** — this is a purely frontend feature that feeds into
the existing `POST /books` flow.

## Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Library | **html5-qrcode** | Simplest API, built-in camera management, supports EAN-13 (ISBN barcodes) |
| Loading | **Local file** in `static/lib/` | Self-contained, no CDN dependency, works offline |
| After scan | **Auto-submit if no duplicate** | If no copy of that ISBN exists on shelf, add immediately. If a copy exists, prompt "You already have this book. Add another copy?" |
| Multi-scan | **One-at-a-time** | Scanner closes after each successful scan. Click "Scan" again for the next book. |

## Technical Details

### Library: html5-qrcode

- **Source**: Download `html5-qrcode.min.js` from the html5-qrcode GitHub releases
- **Location**: `static/lib/html5-qrcode.min.js`
- **Size**: ~370KB minified
- **Barcode formats needed**: `EAN_13` (ISBN-13), `EAN_8` (some older books)
- **API used**: The lower-level `Html5Qrcode` class (not the full scanner widget), giving
  us full control over the UI while the library handles camera + detection

### Why not the built-in `Html5QrcodeScanner` widget?

The built-in widget comes with its own HTML/CSS that would clash with the antiquarian
theme. Using the lower-level `Html5Qrcode` class lets us:
- Build our own modal UI that matches the existing dark/gold theme
- Control exactly what happens on detection (duplicate check + auto-add)
- Keep the camera viewfinder styled consistently

## UX Flow

```
1. User clicks "Scan" button (camera icon, next to "Add to Shelf")
2. Browser prompts for camera permission (first time only)
3. Scanner modal opens with live camera feed
4. User points camera at book's ISBN barcode (back cover)
5. Library detects barcode → extracts EAN-13 digits
6. Modal closes, ISBN appears in the input field
7. System checks: GET /books/isbn/{isbn}
   ├── 404 (no copy) → auto-submit: POST /books with ISBN → toast "Book added!"
   └── 200 (copy exists) → confirm dialog: "You already have [title]. Add another copy?"
        ├── Yes → POST /books with ISBN
        └── No → ISBN stays in input, user can edit or clear
8. Existing enrichment polling kicks in (placeholder card → enriched card)
```

## Implementation Steps

### Step 1: Add the library file

- Download `html5-qrcode.min.js` (latest stable release)
- Create directory `static/lib/`
- Place the file at `static/lib/html5-qrcode.min.js`
- The existing `StaticFileHandler` already serves subdirectories, so the file will
  be accessible at `/lib/html5-qrcode.min.js` with no Java changes

### Step 2: HTML changes (`static/index.html`)

Add to `<head>`:
```html
<script src="/lib/html5-qrcode.min.js"></script>
```

Add a "Scan" button next to the existing "Add to Shelf" button inside `.search-terminal`:
```html
<button id="scan-btn" type="button" title="Scan ISBN barcode">
    <!-- camera/barcode SVG icon -->
</button>
```

Add a scanner modal (after the edit modal):
```html
<div id="scanner-modal" class="modal-overlay" hidden>
    <div class="modal scanner-modal">
        <div class="modal-header">
            <h2>Scan ISBN Barcode</h2>
            <button class="modal-close" id="scanner-close" type="button" aria-label="Close">
                <!-- X icon (reuse existing SVG) -->
            </button>
        </div>
        <div class="scanner-body">
            <div id="scanner-viewfinder"></div>
            <p class="scanner-hint">Point your camera at the barcode on the back of a book</p>
            <div id="scanner-error" class="scanner-error" hidden></div>
        </div>
    </div>
</div>
```

The `#scanner-viewfinder` div is where `Html5Qrcode` renders the camera feed.

### Step 3: CSS changes (`static/style.css`)

Add styles for:

1. **Scan button** — icon-only button matching the header style, positioned next to
   "Add to Shelf" (or between the input and the Add button)

2. **Scanner modal** — reuses the existing `.modal-overlay` base styles, with additions:
   - `.scanner-modal` — wider than edit modal to fit the camera feed
   - `#scanner-viewfinder` — fixed aspect ratio container (4:3), rounded corners,
     border matching the gold accent
   - `.scanner-hint` — subtle text below the viewfinder
   - `.scanner-error` — error message styling (camera denied, etc.)
   - Scanning animation — subtle pulsing border or sweeping line over the viewfinder
     to indicate active scanning

3. **Mobile responsive** — viewfinder scales to fit screen width on small devices

All styles match the existing antiquarian theme (dark backgrounds, gold accents,
serif headings, DM Sans body text).

### Step 4: JavaScript changes (`static/app.js`)

Add ~80–100 lines of scanner logic:

#### New DOM references
```javascript
const scanBtn = document.getElementById('scan-btn');
const scannerModal = document.getElementById('scanner-modal');
const scannerClose = document.getElementById('scanner-close');
const scannerError = document.getElementById('scanner-error');
```

#### Scanner state
```javascript
let html5Qrcode = null;
let isScanning = false;
```

#### `openScanner()`
1. Show the scanner modal
2. Create `Html5Qrcode` instance targeting `#scanner-viewfinder`
3. Start scanning with config:
   - `fps: 10` (frames per second for detection)
   - `qrbox: { width: 300, height: 150 }` — landscape rectangle matching barcode shape
   - `formatsToSupport: [Html5QrcodeSupportedFormats.EAN_13, Html5QrcodeSupportedFormats.EAN_8]`
   - Prefer back camera (`facingMode: "environment"`) on mobile
4. On success callback → call `onBarcodeDetected(decodedText)`
5. On error → ignore (continuous scanning, errors are expected between frames)

#### `closeScanner()`
1. If scanning, call `html5Qrcode.stop()`
2. Hide the scanner modal
3. Clear any error messages

#### `onBarcodeDetected(code)`
1. Stop the scanner and close the modal
2. Validate the code as an ISBN using existing `isValidIsbn(code)`
   - If invalid: show toast "Scanned barcode is not a valid ISBN", keep scanner open
3. Set `isbnInput.value = code`
4. Check for existing copy: `GET /books/isbn/{code}`
   - If **404** (no copy): call `addBook()` directly (auto-submit)
   - If **200** (copy exists): show `confirm()` dialog:
     `"You already have '${book.title}'. Add another copy?"`
     - Yes → call `addBook()`
     - No → leave ISBN in input, user can clear or edit

#### Error handling
- **Camera not available**: show error in `#scanner-error`: "No camera found"
- **Permission denied**: show error: "Camera permission denied. Please allow camera
  access in your browser settings."
- **Library not loaded**: if `typeof Html5Qrcode === 'undefined'`, hide the scan button
  entirely on page load (graceful degradation)

#### Event listeners
```javascript
scanBtn.addEventListener('click', openScanner);
scannerClose.addEventListener('click', closeScanner);
scannerModal.addEventListener('click', (e) => {
    if (e.target === scannerModal) closeScanner();
});
// Escape key also closes scanner (extend existing handler)
```

### Step 5: Testing

Manual testing checklist:
- [ ] Scan button visible in header
- [ ] Clicking "Scan" opens camera feed in modal
- [ ] Camera permission prompt appears (first time)
- [ ] Scanning a valid ISBN-13 barcode auto-fills input and adds book
- [ ] Scanning a book that already exists shows confirmation prompt
- [ ] Declining the duplicate prompt leaves ISBN in input
- [ ] Closing the modal stops the camera
- [ ] Escape key closes the scanner
- [ ] Camera permission denied shows a clear error message
- [ ] On desktop without camera: shows appropriate error
- [ ] On mobile: back camera is preferred
- [ ] Scanner button hidden if library fails to load
- [ ] Enrichment polling works normally after scan-triggered add

## File Changes Summary

| File | Change | Lines |
|------|--------|-------|
| `static/lib/html5-qrcode.min.js` | **New** — vendored library | ~370KB |
| `static/index.html` | Add script tag, scan button, scanner modal | ~25 new lines |
| `static/app.js` | Add scanner logic | ~80–100 new lines |
| `static/style.css` | Add scanner styles | ~60–80 new lines |
| Backend (Java) | **No changes** | 0 |

## HTTPS Note

`navigator.mediaDevices.getUserMedia()` requires a **secure context** (HTTPS or
`localhost`). This works fine for local development (`localhost:8080`). For any
non-localhost deployment, HTTPS is required — this is a browser security requirement,
not something we need to handle in code.

## Future Enhancements (out of scope)

- Continuous/batch scanning mode (scan a whole shelf without closing)
- Scan from photo/image file upload (html5-qrcode supports this)
- Visual/audio feedback on successful scan (beep sound, green flash)
- Barcode overlay highlighting (draw box around detected barcode)
