# ISBN Barcode Scanner — Implementation Plan

> **Status:** Implemented. Originally built with html5-qrcode, then replaced with
> **zbar-wasm** (WebAssembly) for unified cross-platform scanning. The html5-qrcode
> library ignored our image preprocessing pipeline on Windows Chrome (no native
> `BarcodeDetector` API). zbar-wasm accepts `ImageData` directly, enabling a multi-pass
> scan loop (raw grayscale → sharpen → global thresholds → adaptive threshold) that
> works on all platforms.

## Overview

Add a webcam-based ISBN barcode scanner to the MyBookShelf frontend. Users click a
"Scan" button, grant camera access, point the camera at a book's barcode, and the
ISBN is detected, validated, and the book is automatically added to the shelf.

**No backend changes required** — this is a purely frontend feature that feeds into
the existing `POST /books` flow.

## Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Library | **zbar-wasm** (`@undecaf/zbar-wasm@0.11.0`) | WASM-based C barcode decoder, accepts `ImageData` input directly (integrates with our preprocessing pipeline), 2-10ms per decode, works on all platforms without `BarcodeDetector` API |
| Loading | **Local file** in `static/lib/` (inlined UMD build, 326 KB) | Self-contained, no CDN dependency, works offline |
| After scan | **Auto-submit if no duplicate** | If no copy of that ISBN exists on shelf, add immediately. If a copy exists, prompt "You already have this book. Add another copy?" |
| Multi-scan | **One-at-a-time** | Scanner closes after each successful scan. Click "Scan" again for the next book. |

## Technical Details

### Library: zbar-wasm

- **Source**: `@undecaf/zbar-wasm@0.11.0` inlined UMD build from jsdelivr
- **Location**: `static/lib/zbar-wasm.js`
- **Size**: ~326 KB (WASM binary inlined in JS)
- **Barcode formats supported**: EAN-13, EAN-8, UPC-A, CODE-128 (covers all ISBN formats)
- **API used**: `zbarWasm.scanImageData(imageData)` — accepts `ImageData` directly
- **Exposes**: global `zbarWasm` object via UMD module

### Why zbar-wasm instead of html5-qrcode?

html5-qrcode uses a pure JavaScript ZXing port that is 5-10x slower than WASM and
ignores external image preprocessing — its internal scanner captures its own video
frames. On Windows Chrome (no native `BarcodeDetector` API), our entire image
processing pipeline (ROI crop, unsharp mask, multi-threshold, adaptive threshold)
was dead code. zbar-wasm:
- Accepts `ImageData` input so our preprocessing integrates directly
- 2-10ms per decode vs 40-80ms for ZXing-js
- Better at damaged/low-quality/glossy barcodes (designed for industrial scanners)
- No platform branching needed — one unified scan pipeline everywhere

### Scan Pipeline (per frame, ~12 fps)

```
1. captureROI(video)                       ~2ms  (downscale 1080p → 800px, crop center 80%×50%)
2. grayToImageData → scanImageData          ~5ms  (raw grayscale, fastest path)
3. unsharpMask (in-place on gray)           ~2ms  (3×3 box sharpen)
4. globalThresholdGray(0.35) → scan         ~5ms  (dark-bar recovery under glare)
5. globalThresholdGray(0.50) → scan         ~5ms  (balanced)
6. globalThresholdGray(0.65) → scan         ~5ms  (light-bar recovery in shadow)
7. adaptiveThresholdGray(31, 10) → scan     ~15ms (handles uneven glare)
   Total worst case: ~39ms — well within 80ms budget
```

Short-circuits on first successful decode, so clean barcodes decode in ~7ms.

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

- Download inlined UMD build from `https://cdn.jsdelivr.net/npm/@undecaf/zbar-wasm@0.11.0/dist/inlined/index.js`
- Save as `static/lib/zbar-wasm.js`
- The existing `StaticFileHandler` already serves subdirectories, so the file will
  be accessible at `/lib/zbar-wasm.js` with no Java changes

### Step 2: HTML changes (`static/index.html`)

Add to `<head>`:
```html
<script src="/lib/zbar-wasm.js"></script>
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

The `#scanner-viewfinder` div is where the `<video>` element is placed for the camera feed.

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
let isScanning = false;
let activeStream = null;
```

#### `openScanner()`
1. Show the scanner modal
2. Request camera permission, enumerate cameras
3. Call `startZbarScanner()` to begin the scan loop

#### `startZbarScanner()`
1. `getUserMedia` with `getVideoConstraints()` (supports camera selection)
2. `optimizeCameraSettings(stream)` (continuous focus, slight negative exposure)
3. Create `<video>`, attach to viewfinder
4. `requestAnimationFrame` scan loop at ~12 fps with multi-pass pipeline:
   - Pass 1: Raw ROI grayscale → `zbarWasm.scanImageData()`
   - Pass 2: Sharpened + global thresholds (0.35, 0.50, 0.65)
   - Pass 3: Adaptive local threshold
   - Short-circuit on first successful decode

#### `closeScanner()`
1. Set `isScanning = false` (stops rAF loop)
2. Stop all stream tracks
3. Hide the scanner modal, clear timeouts

#### `handleScanResult(code)` → `onBarcodeScanned(code)`
1. Stop the scanner and close the modal
2. Validate the code as an ISBN using existing `isValidIsbn(code)`
   - If invalid: show toast "Scanned barcode is not a valid ISBN"
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
- **Library not loaded**: if `typeof zbarWasm === 'undefined'`, hide the scan button
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
| `static/lib/zbar-wasm.js` | **New** — vendored WASM barcode library | ~326 KB |
| `static/index.html` | Add script tag, scan button, scanner modal | ~25 new lines |
| `static/app.js` | Scanner logic + image processing pipeline | ~250 lines |
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
