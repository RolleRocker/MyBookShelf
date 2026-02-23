// ========================================
// MyBookShelf — Frontend Application
// ========================================

const API = '';
let currentFilter = 'all';
let allBooks = [];
let groupByAuthor = false;
let searchQuery = '';
let currentSubjectFilter = '';
let currentSort = '';
const pollingTimers = new Map();

// ---- DOM References ----

const bookGrid      = document.getElementById('book-grid');
const emptyState    = document.getElementById('empty-state');
const isbnInput     = document.getElementById('isbn-input');
const addBtn        = document.getElementById('add-btn');
const isbnError     = document.getElementById('isbn-error');
const editModal     = document.getElementById('edit-modal');
const editForm      = document.getElementById('edit-form');
const editId        = document.getElementById('edit-id');
const editTitle     = document.getElementById('edit-title');
const editAuthor    = document.getElementById('edit-author');
const editGenre     = document.getElementById('edit-genre');
const editRating    = document.getElementById('edit-rating');
const editStatus    = document.getElementById('edit-status');
const editProgress  = document.getElementById('edit-progress');
const progressGroup = document.getElementById('progress-group');
const modalClose    = document.getElementById('modal-close');
const modalCancel   = document.getElementById('modal-cancel');
const filterTabs    = document.querySelectorAll('.filter-tab');
const toastContainer = document.getElementById('toast-container');
const scanBtn        = document.getElementById('scan-btn');
const scannerModal   = document.getElementById('scanner-modal');
const scannerClose   = document.getElementById('scanner-close');
const scannerError   = document.getElementById('scanner-error');
const groupToggle    = document.getElementById('group-toggle');
const refreshBtn     = document.getElementById('refresh-library-btn');
const searchInput    = document.getElementById('search-input');
const sortSelect     = document.getElementById('sort-select');

// ---- ISBN Validation ----

function isValidIsbn(isbn) {
    if (!isbn) return false;
    const cleaned = isbn.trim();
    if (cleaned.length === 13) return /^\d{13}$/.test(cleaned);
    if (cleaned.length === 10) return /^\d{9}[\dX]$/.test(cleaned);
    return false;
}

// ---- API Helpers ----

async function apiGet(path) {
    const resp = await fetch(API + path);
    if (!resp.ok) throw new Error(`GET ${path} failed: ${resp.status}`);
    return resp.json();
}

async function apiPost(path, body) {
    const resp = await fetch(API + path, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });
    if (!resp.ok) {
        const err = await resp.json().catch(() => ({}));
        throw new Error(err.error || `POST failed: ${resp.status}`);
    }
    return resp.json();
}

async function apiPut(path, body) {
    const resp = await fetch(API + path, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });
    if (!resp.ok) {
        const err = await resp.json().catch(() => ({}));
        throw new Error(err.error || `PUT failed: ${resp.status}`);
    }
    return resp.json();
}

async function apiDelete(path) {
    const resp = await fetch(API + path, { method: 'DELETE' });
    if (!resp.ok && resp.status !== 204) throw new Error(`DELETE failed: ${resp.status}`);
}

// ---- Toast Notifications ----

function showToast(message, type = 'success') {
    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    toast.textContent = message;
    toastContainer.appendChild(toast);

    setTimeout(() => {
        toast.classList.add('toast-out');
        toast.addEventListener('animationend', () => toast.remove());
    }, 4500);
}

// ---- Status Helpers ----

function statusLabel(status) {
    switch (status) {
        case 'WANT_TO_READ': return 'Want to Read';
        case 'READING':      return 'Reading';
        case 'FINISHED':     return 'Finished';
        default:             return status;
    }
}

function statusClass(status) {
    switch (status) {
        case 'WANT_TO_READ': return 'want';
        case 'READING':      return 'reading';
        case 'FINISHED':     return 'finished';
        default:             return '';
    }
}

// ---- Render Book Card ----

function renderStars(rating, bookId) {
    let html = '';
    for (let i = 1; i <= 5; i++) {
        html += `<button class="star${i <= rating ? ' filled' : ''}" data-value="${i}" data-book="${bookId}" aria-label="${i} star${i > 1 ? 's' : ''}">&#9733;</button>`;
    }
    return html;
}

function createBookCard(book) {
    const card = document.createElement('div');
    card.className = 'book-card';
    card.dataset.id = book.id;
    card.style.animationDelay = `${Math.random() * 0.15}s`;

    const isLoading = !book.title;
    const titleText = book.title || 'Loading...';
    const authorText = book.author || '';
    const rating = book.rating || 0;

    let metaParts = [];
    if (book.publisher) metaParts.push(book.publisher);
    if (book.publishDate) metaParts.push(book.publishDate);
    if (book.pageCount) metaParts.push(book.pageCount + ' pages');
    const metaText = metaParts.join(' \u00B7 ');

    card.innerHTML = `
        <div class="book-cover" id="cover-${book.id}">
            <div class="cover-placeholder">
                <svg width="32" height="40" viewBox="0 0 32 40" fill="none">
                    <rect x="2" y="0" width="28" height="40" rx="2" stroke="currentColor" stroke-width="1.5" fill="none"/>
                    <line x1="8" y1="10" x2="24" y2="10" stroke="currentColor" stroke-width="1" opacity="0.4"/>
                    <line x1="8" y1="14" x2="20" y2="14" stroke="currentColor" stroke-width="1" opacity="0.3"/>
                    <line x1="8" y1="18" x2="22" y2="18" stroke="currentColor" stroke-width="1" opacity="0.2"/>
                </svg>
            </div>
        </div>
        <div class="book-info">
            <div class="book-title${isLoading ? ' loading' : ''}">${escapeHtml(titleText)}</div>
            ${authorText ? `<div class="book-author">${escapeHtml(authorText)}</div>` : ''}
            <div class="book-badges">
                ${book.genre ? `<span class="badge badge-genre">${escapeHtml(book.genre)}</span>` : ''}
                <span class="badge badge-status ${statusClass(book.readStatus)}">${statusLabel(book.readStatus)}</span>
            </div>
            <div class="book-stars">${renderStars(rating, book.id)}</div>
            ${book.readStatus === 'READING' && book.readingProgress != null
                ? `<div class="progress-bar-wrap" title="${book.readingProgress}% read">
                     <div class="progress-bar-fill" style="width:${book.readingProgress}%"></div>
                   </div>`
                : ''}
            ${metaText ? `<div class="book-meta">${escapeHtml(metaText)}</div>` : ''}
            ${book.subjects && book.subjects.length > 0 ? `<div class="subject-tags">${book.subjects.slice(0, 4).map(s => `<button class="subject-tag${currentSubjectFilter && s.toLowerCase() === currentSubjectFilter.toLowerCase() ? ' active-filter' : ''}" data-subject="${escapeHtml(s)}" type="button" title="Filter by ${escapeHtml(s)}">${escapeHtml(s)}</button>`).join('')}</div>` : ''}
            <div class="book-actions">
                <button class="btn-edit" data-id="${book.id}">Edit</button>
                <button class="btn-delete" data-id="${book.id}">Delete</button>
            </div>
        </div>
    `;

    // Load cover image
    loadCover(book);

    return card;
}

function loadCover(book) {
    if (!book.coverUrl) return;
    const img = new Image();
    img.onload = () => {
        const container = document.getElementById(`cover-${book.id}`);
        if (container) {
            container.innerHTML = '';
            img.alt = book.title || 'Book cover';
            container.appendChild(img);
        }
    };
    img.onerror = () => {}; // Keep placeholder on error
    img.src = `/books/${book.id}/cover`;
}

function escapeHtml(str) {
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
}

// ---- Filtered Books ----

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

function getFilteredBooks() {
    let books = allBooks;
    if (searchQuery) {
        const q = searchQuery.toLowerCase();
        books = books.filter(b =>
            (b.title && b.title.toLowerCase().includes(q)) ||
            (b.author && b.author.toLowerCase().includes(q))
        );
    }
    if (currentSubjectFilter) {
        const sq = currentSubjectFilter.toLowerCase();
        books = books.filter(b => b.subjects && b.subjects.some(s => s.toLowerCase() === sq));
    }
    if (currentFilter !== 'all') {
        books = books.filter(b => b.readStatus === currentFilter);
    }
    return getSortedBooks(books);
}

function updateSubjectFilterChip() {
    const chip = document.getElementById('active-tag-filter');
    if (!chip) return;
    if (currentSubjectFilter) {
        document.getElementById('active-tag-label').textContent = currentSubjectFilter;
        chip.hidden = false;
    } else {
        chip.hidden = true;
    }
}

// ---- Render All Books ----

function renderBooks(books) {
    updateSubjectFilterChip();
    bookGrid.innerHTML = '';
    if (books.length === 0) {
        emptyState.hidden = false;
    } else {
        emptyState.hidden = true;
        if (groupByAuthor) {
            renderBooksGrouped(books);
        } else {
            books.forEach(book => {
                bookGrid.appendChild(createBookCard(book));
            });
        }
    }
}

function normalizeAuthor(author) {
    if (!author) return '';
    return author.trim().replace(/\s+/g, ' ').toLowerCase();
}

function renderBooksGrouped(books) {
    const groups = new Map();
    const displayNames = new Map(); // normalized key -> best display name
    books.forEach(book => {
        const raw = book.author || '';
        const key = normalizeAuthor(raw) || '_unknown';
        if (!groups.has(key)) {
            groups.set(key, []);
            displayNames.set(key, raw || 'Unknown Author');
        }
        groups.get(key).push(book);
    });

    const sortedKeys = [...groups.keys()].sort((a, b) => {
        if (a === '_unknown') return 1;
        if (b === '_unknown') return -1;
        return a.localeCompare(b, undefined, { sensitivity: 'base' });
    });

    sortedKeys.forEach(key => {
        const authorBooks = groups.get(key);
        const displayName = displayNames.get(key);
        authorBooks.sort((a, b) => (a.title || '').localeCompare(b.title || '', undefined, { sensitivity: 'base' }));

        const section = document.createElement('div');
        section.className = 'author-section';
        section.innerHTML = `<h3 class="author-heading">${escapeHtml(displayName)} <span class="author-count">${authorBooks.length}</span></h3>`;
        bookGrid.appendChild(section);

        authorBooks.forEach(book => {
            bookGrid.appendChild(createBookCard(book));
        });
    });
}

// ---- Update Counts ----

function updateCounts(books) {
    document.getElementById('count-all').textContent = books.length;
    document.getElementById('count-want').textContent = books.filter(b => b.readStatus === 'WANT_TO_READ').length;
    document.getElementById('count-reading').textContent = books.filter(b => b.readStatus === 'READING').length;
    document.getElementById('count-finished').textContent = books.filter(b => b.readStatus === 'FINISHED').length;
}

// ---- Load Books ----

async function loadBooks() {
    try {
        let path = '/books';
        if (currentFilter !== 'all') {
            path += `?readStatus=${currentFilter}`;
        }
        const books = await apiGet(path);
        allBooks = books;
        renderBooks(getFilteredBooks());

        // Fetch all books for counts (if filtering, we need full list for counts)
        if (currentFilter !== 'all') {
            const allList = await apiGet('/books');
            updateCounts(allList);
        } else {
            updateCounts(books);
        }
    } catch (e) {
        showToast('Failed to load books', 'error');
    }
}

// ---- Add Book ----

async function addBook() {
    const isbn = isbnInput.value.trim();
    isbnError.hidden = true;

    if (!isbn) {
        isbnError.textContent = 'Please enter an ISBN';
        isbnError.hidden = false;
        return;
    }

    if (!isValidIsbn(isbn)) {
        isbnError.textContent = 'Invalid ISBN format. Must be 10 or 13 digits (ISBN-10 may end in X).';
        isbnError.hidden = false;
        return;
    }

    try {
        addBtn.disabled = true;
        const book = await apiPost('/books', { isbn, readStatus: 'WANT_TO_READ' });
        isbnInput.value = '';
        showToast('Book added! Fetching details...');

        // Switch to "All" filter to see the new book
        if (currentFilter !== 'all') {
            setFilter('all');
        }

        await loadBooks();

        // Start polling for enrichment if title is missing
        if (!book.title) {
            pollForEnrichment(book.id);
        }
    } catch (e) {
        showToast(e.message || 'Failed to add book', 'error');
    } finally {
        addBtn.disabled = false;
    }
}

// ---- Polling for Enrichment ----

function pollForEnrichment(bookId) {
    // Stop any existing poll for this book
    if (pollingTimers.has(bookId)) {
        clearInterval(pollingTimers.get(bookId));
    }

    let elapsed = 0;
    const interval = setInterval(async () => {
        elapsed += 2000;
        if (elapsed > 30000) {
            clearInterval(interval);
            pollingTimers.delete(bookId);
            return;
        }

        try {
            const book = await apiGet(`/books/${bookId}`);
            if (book.title) {
                clearInterval(interval);
                pollingTimers.delete(bookId);
                updateCardInPlace(book);
                showToast(`"${book.title}" enriched!`);
            }
        } catch (e) {
            // Book might have been deleted; stop polling
            clearInterval(interval);
            pollingTimers.delete(bookId);
        }
    }, 2000);

    pollingTimers.set(bookId, interval);
}

function updateCardInPlace(book) {
    const existingCard = bookGrid.querySelector(`[data-id="${book.id}"]`);
    if (!existingCard) return;

    const newCard = createBookCard(book);
    newCard.style.animation = 'none'; // Don't re-animate
    existingCard.replaceWith(newCard);

    // Also update allBooks cache
    const idx = allBooks.findIndex(b => b.id === book.id);
    if (idx >= 0) allBooks[idx] = book;
}

// ---- Quick Star Rating ----

function handleStarClick(bookId, value) {
    apiPut(`/books/${bookId}`, { rating: value })
        .then(updatedBook => {
            updateCardInPlace(updatedBook);
        })
        .catch(() => showToast('Failed to update rating', 'error'));
}

// ---- Edit Modal ----

function openEditModal(bookId) {
    const book = allBooks.find(b => b.id === bookId);
    if (!book) return;

    editId.value = book.id;
    editTitle.value = book.title || '';
    editAuthor.value = book.author || '';
    editGenre.value = book.genre || '';
    editStatus.value = book.readStatus;
    editProgress.value = book.readingProgress != null ? book.readingProgress : '';
    progressGroup.hidden = book.readStatus !== 'READING';

    // Set star rating
    const stars = editRating.querySelectorAll('.star');
    const rating = book.rating || 0;
    stars.forEach(star => {
        star.classList.toggle('filled', parseInt(star.dataset.value) <= rating);
    });
    editRating.dataset.currentValue = rating;

    editModal.hidden = false;
    editTitle.focus();
}

function closeEditModal() {
    editModal.hidden = true;
}

async function submitEdit(e) {
    e.preventDefault();

    const id = editId.value;
    const updates = {};

    const titleVal = editTitle.value.trim();
    const authorVal = editAuthor.value.trim();
    const genreVal = editGenre.value.trim();

    updates.title = titleVal || null;
    updates.author = authorVal || null;
    updates.genre = genreVal || null;
    updates.readStatus = editStatus.value;
    updates.readingProgress = editStatus.value === 'READING' && editProgress.value !== ''
        ? parseInt(editProgress.value, 10)
        : null;

    const ratingVal = parseInt(editRating.dataset.currentValue) || 0;
    if (ratingVal >= 1 && ratingVal <= 5) {
        updates.rating = ratingVal;
    }

    try {
        const updated = await apiPut(`/books/${id}`, updates);
        closeEditModal();
        showToast('Book updated');
        await loadBooks();
    } catch (e) {
        showToast(e.message || 'Failed to update', 'error');
    }
}

// ---- Delete ----

async function deleteBook(bookId) {
    const book = allBooks.find(b => b.id === bookId);
    const name = book?.title || 'this book';
    if (!confirm(`Delete "${name}"? This cannot be undone.`)) return;

    try {
        await apiDelete(`/books/${bookId}`);
        showToast('Book deleted');
        // Stop any polling
        if (pollingTimers.has(bookId)) {
            clearInterval(pollingTimers.get(bookId));
            pollingTimers.delete(bookId);
        }
        await loadBooks();
    } catch (e) {
        showToast('Failed to delete', 'error');
    }
}

// ---- Filter Tabs ----

function setFilter(status) {
    currentFilter = status;
    filterTabs.forEach(tab => {
        tab.classList.toggle('active', tab.dataset.status === status);
    });
    loadBooks();
}

// ---- ISBN Scanner (zbar-wasm) ----

let isScanning = false;
let activeStream = null;
let scannerTimeoutTimer = null;
let scannerHintTimer = null;
let isBulkMode = false;
let bulkScannedIds = [];
let bulkCooldownCode = null;

const cameraSelect = document.getElementById('camera-select');
const scannerTimeoutHint = document.getElementById('scanner-timeout-hint');
const scannerModalTitle = document.getElementById('scanner-modal-title');
const scannerBulkFooter = document.getElementById('scanner-bulk-footer');
const scannerBulkCount  = document.getElementById('scanner-bulk-count');
const scannerDoneBtn    = document.getElementById('scanner-done');
const bulkScanBtn       = document.getElementById('bulk-scan-btn');

const SCANNER_CAMERA_KEY = 'mybookshelf-camera-id';

async function enumerateCameras() {
    try {
        const devices = await navigator.mediaDevices.enumerateDevices();
        const cameras = devices.filter(d => d.kind === 'videoinput');
        if (cameras.length === 0) return [];

        cameraSelect.innerHTML = '';
        cameras.forEach((cam, i) => {
            const opt = document.createElement('option');
            opt.value = cam.deviceId;
            opt.textContent = cam.label || `Camera ${i + 1}`;
            cameraSelect.appendChild(opt);
        });

        // Restore last-used camera, or default to last in list (USB cams appear last)
        const saved = localStorage.getItem(SCANNER_CAMERA_KEY);
        const savedExists = cameras.some(c => c.deviceId === saved);
        if (saved && savedExists) {
            cameraSelect.value = saved;
        } else {
            cameraSelect.value = cameras[cameras.length - 1].deviceId;
        }

        cameraSelect.hidden = cameras.length <= 1;
        return cameras;
    } catch (e) {
        cameraSelect.hidden = true;
        return [];
    }
}

function getVideoConstraints() {
    const deviceId = cameraSelect.value;
    const constraints = {
        width: { ideal: 1920 },
        height: { ideal: 1080 },
    };
    if (deviceId) {
        constraints.deviceId = { exact: deviceId };
    } else {
        constraints.facingMode = 'environment';
    }
    constraints.focusMode = { ideal: 'continuous' };
    constraints.torch = false;
    return constraints;
}

// ---- Image Processing Pipeline ----

const SCAN_WIDTH = 800;

// Grab a downscaled grayscale snapshot from the center of the video (ROI).
function captureROI(video) {
    const vw = video.videoWidth, vh = video.videoHeight;
    const roiW = Math.floor(vw * 0.8);
    const roiH = Math.floor(vh * 0.5);
    const roiX = Math.floor((vw - roiW) / 2);
    const roiY = Math.floor((vh - roiH) / 2);

    const scale = Math.min(1, SCAN_WIDTH / roiW);
    const w = Math.floor(roiW * scale);
    const h = Math.floor(roiH * scale);

    const canvas = document.createElement('canvas');
    canvas.width = w;
    canvas.height = h;
    const ctx = canvas.getContext('2d', { willReadFrequently: true });
    ctx.drawImage(video, roiX, roiY, roiW, roiH, 0, 0, w, h);

    const imgData = ctx.getImageData(0, 0, w, h);
    const data = imgData.data;
    const gray = new Uint8Array(w * h);
    for (let i = 0, j = 0; i < data.length; i += 4, j++) {
        gray[j] = (data[i] * 77 + data[i + 1] * 150 + data[i + 2] * 29) >> 8;
    }
    return { gray, width: w, height: h };
}

// Unsharp mask — boosts edge contrast for slightly out-of-focus USB cams.
function unsharpMask(gray, w, h, amount) {
    const blurred = new Uint8Array(gray.length);
    for (let y = 1; y < h - 1; y++) {
        for (let x = 1; x < w - 1; x++) {
            let sum = 0;
            for (let dy = -1; dy <= 1; dy++) {
                for (let dx = -1; dx <= 1; dx++) {
                    sum += gray[(y + dy) * w + (x + dx)];
                }
            }
            blurred[y * w + x] = (sum / 9) | 0;
        }
    }
    for (let i = 0; i < gray.length; i++) {
        gray[i] = Math.min(255, Math.max(0, gray[i] + amount * (gray[i] - blurred[i]))) | 0;
    }
}

// Global threshold — returns a new Uint8Array of thresholded grayscale values.
function globalThresholdGray(gray, w, h, fraction) {
    let min = 255, max = 0;
    for (let i = 0; i < gray.length; i++) {
        if (gray[i] < min) min = gray[i];
        if (gray[i] > max) max = gray[i];
    }
    const thresh = min + (max - min || 1) * fraction;
    const out = new Uint8Array(gray.length);
    for (let i = 0; i < gray.length; i++) {
        out[i] = gray[i] < thresh ? 0 : 255;
    }
    return out;
}

// Adaptive (local mean) thresholding — handles uneven lighting / glare hotspots.
function adaptiveThresholdGray(gray, w, h, blockSize, C) {
    const integral = new Float64Array((w + 1) * (h + 1));
    for (let y = 0; y < h; y++) {
        let rowSum = 0;
        for (let x = 0; x < w; x++) {
            rowSum += gray[y * w + x];
            integral[(y + 1) * (w + 1) + (x + 1)] = integral[y * (w + 1) + (x + 1)] + rowSum;
        }
    }

    const half = blockSize >> 1;
    const out = new Uint8Array(w * h);
    for (let y = 0; y < h; y++) {
        for (let x = 0; x < w; x++) {
            const x1 = Math.max(0, x - half), y1 = Math.max(0, y - half);
            const x2 = Math.min(w - 1, x + half), y2 = Math.min(h - 1, y + half);
            const area = (x2 - x1 + 1) * (y2 - y1 + 1);
            const sum = integral[(y2 + 1) * (w + 1) + (x2 + 1)]
                      - integral[y1 * (w + 1) + (x2 + 1)]
                      - integral[(y2 + 1) * (w + 1) + x1]
                      + integral[y1 * (w + 1) + x1];
            const mean = sum / area;
            out[y * w + x] = gray[y * w + x] < (mean - C) ? 0 : 255;
        }
    }
    return out;
}

// Convert a grayscale Uint8Array to ImageData for zbar-wasm.
function grayToImageData(gray, w, h) {
    const imgData = new ImageData(w, h);
    const data = imgData.data;
    for (let i = 0, j = 0; i < gray.length; i++, j += 4) {
        data[j] = data[j + 1] = data[j + 2] = gray[i];
        data[j + 3] = 255;
    }
    return imgData;
}

// Optimize camera hardware settings after stream starts.
async function optimizeCameraSettings(stream) {
    const track = stream.getVideoTracks()[0];
    try {
        const caps = track.getCapabilities();
        const settings = {};
        if (caps.focusMode?.includes('continuous')) settings.focusMode = 'continuous';
        if (caps.exposureMode?.includes('continuous')) settings.exposureMode = 'continuous';
        if (caps.exposureCompensation) {
            settings.exposureCompensation = Math.max(caps.exposureCompensation.min, -1.0);
        }
        if (caps.whiteBalanceMode?.includes('continuous')) settings.whiteBalanceMode = 'continuous';
        if (Object.keys(settings).length > 0) {
            await track.applyConstraints({ advanced: [settings] });
        }
    } catch (e) { /* not all cameras support all constraints */ }
}

function startScannerTimeouts() {
    scannerTimeoutHint.hidden = true;
    scannerTimeoutHint.innerHTML = '';

    scannerHintTimer = setTimeout(() => {
        if (!isScanning) return;
        scannerTimeoutHint.textContent = 'Having trouble? Tilt the book slightly to reduce glare, move closer, or try switching cameras.';
        scannerTimeoutHint.hidden = false;
    }, 15000);

    scannerTimeoutTimer = setTimeout(() => {
        if (!isScanning) return;
        scannerTimeoutHint.innerHTML = 'Still no detection. <a id="scanner-type-manually">Type ISBN manually</a>';
        scannerTimeoutHint.hidden = false;
        document.getElementById('scanner-type-manually')?.addEventListener('click', () => {
            closeScanner();
            isbnInput.focus();
        });
    }, 30000);
}

function clearScannerTimeouts() {
    if (scannerHintTimer) { clearTimeout(scannerHintTimer); scannerHintTimer = null; }
    if (scannerTimeoutTimer) { clearTimeout(scannerTimeoutTimer); scannerTimeoutTimer = null; }
    scannerTimeoutHint.hidden = true;
}

// Scan a single ImageData with zbar-wasm. Returns barcode string or null.
async function zbarScan(imageData) {
    const symbols = await zbarWasm.scanImageData(imageData);
    for (const sym of symbols) {
        const value = sym.decode();
        if (value) return value;
    }
    return null;
}

async function startZbarScanner() {
    const viewfinder = document.getElementById('scanner-viewfinder');

    let stream;
    try {
        stream = await navigator.mediaDevices.getUserMedia({ video: getVideoConstraints() });
    } catch (err) {
        isScanning = false;
        showScannerError(err);
        return;
    }

    activeStream = stream;
    await optimizeCameraSettings(stream);

    const video = document.createElement('video');
    video.srcObject = stream;
    video.setAttribute('playsinline', 'true');
    video.play();
    viewfinder.innerHTML = '';
    viewfinder.appendChild(video);

    if (cameraSelect.value) localStorage.setItem(SCANNER_CAMERA_KEY, cameraSelect.value);

    let scanBusy = false;
    let lastScanTime = 0;
    const MIN_INTERVAL = 80; // ~12 fps

    function scanLoop(timestamp) {
        if (!isScanning) return;
        if (timestamp - lastScanTime >= MIN_INTERVAL && video.readyState >= 2 && !scanBusy) {
            lastScanTime = timestamp;
            scanBusy = true;
            (async () => {
                try {
                    const roi = captureROI(video);
                    const { gray, width: w, height: h } = roi;

                    // Pass 1: Raw grayscale (fastest — catches clean barcodes)
                    let result = await zbarScan(grayToImageData(gray, w, h));
                    if (result) { handleScanResult(result); scanBusy = false; return; }

                    // Sharpen in-place for enhanced passes
                    unsharpMask(gray, w, h, 1.0);

                    // Pass 2: Multiple global thresholds
                    for (const frac of [0.35, 0.50, 0.65]) {
                        const thresholded = globalThresholdGray(gray, w, h, frac);
                        result = await zbarScan(grayToImageData(thresholded, w, h));
                        if (result) { handleScanResult(result); scanBusy = false; return; }
                    }

                    // Pass 3: Adaptive local threshold
                    const adaptive = adaptiveThresholdGray(gray, w, h, 31, 10);
                    result = await zbarScan(grayToImageData(adaptive, w, h));
                    if (result) { handleScanResult(result); scanBusy = false; return; }
                } catch (e) {}
                scanBusy = false;
            })();
        }
        requestAnimationFrame(scanLoop);
    }
    requestAnimationFrame(scanLoop);
}

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

function showScannerError(err) {
    const msg = String(err);
    if (msg.includes('NotAllowedError') || msg.includes('Permission')) {
        scannerError.textContent = 'Camera permission denied. Please allow camera access in your browser settings.';
    } else if (msg.includes('NotFoundError') || msg.includes('Requested device not found')) {
        scannerError.textContent = 'No camera found on this device.';
    } else {
        scannerError.textContent = 'Could not start camera: ' + msg;
    }
    scannerError.hidden = false;
}

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

function stopCurrentCamera() {
    isScanning = false;
    if (activeStream) {
        activeStream.getTracks().forEach(t => t.stop());
        activeStream = null;
    }
}

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

async function bulkAddBook(code) {
    if (!isBulkMode) return; // guard: Done may have been pressed while this was in-flight
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

async function onBarcodeScanned(code) {
    if (!isValidIsbn(code)) {
        showToast('Scanned barcode is not a valid ISBN', 'error');
        return;
    }

    isbnInput.value = code;
    isbnError.hidden = true;

    // Check for existing copy
    try {
        const resp = await fetch(API + '/books/isbn/' + code);
        if (resp.ok) {
            const existing = await resp.json();
            const title = existing.title || code;
            if (!confirm(`You already have "${title}". Add another copy?`)) {
                return;
            }
        }
        addBook();
    } catch (e) {
        addBook();
    }
}

// Hide scan button if zbar-wasm is not available
if (typeof zbarWasm === 'undefined') {
    scanBtn.style.display = 'none';
    bulkScanBtn.style.display = 'none';
}

// ---- Event Listeners ----

// Scanner
scanBtn.addEventListener('click', () => openScanner());
bulkScanBtn.addEventListener('click', () => openScanner(true));
scannerDoneBtn.addEventListener('click', doneBulkScan);
scannerClose.addEventListener('click', closeScanner);
scannerModal.addEventListener('click', (e) => {
    if (e.target === scannerModal) closeScanner();
});
cameraSelect.addEventListener('change', () => {
    if (!isScanning && scannerModal.hidden) return;
    stopCurrentCamera();
    isScanning = true;
    clearScannerTimeouts();
    startScannerTimeouts();
    const viewfinder = document.getElementById('scanner-viewfinder');
    viewfinder.innerHTML = '';
    viewfinder.classList.add('scanning');
    startZbarScanner();
});

// Add book
addBtn.addEventListener('click', addBook);
isbnInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
        e.preventDefault();
        addBook();
    }
    // Clear error on typing
    if (isbnError.hidden === false) {
        isbnError.hidden = true;
    }
});

// Search bar
searchInput.addEventListener('input', () => {
    searchQuery = searchInput.value.trim();
    renderBooks(getFilteredBooks());
});

// Filter tabs
filterTabs.forEach(tab => {
    tab.addEventListener('click', () => setFilter(tab.dataset.status));
});

// Group by author toggle
groupToggle.addEventListener('click', () => {
    groupByAuthor = !groupByAuthor;
    groupToggle.classList.toggle('active', groupByAuthor);
    renderBooks(getFilteredBooks());
});

// Sort select
sortSelect.addEventListener('change', () => {
    currentSort = sortSelect.value;
    renderBooks(getFilteredBooks());
});

// Refresh Library
refreshBtn.addEventListener('click', async () => {
    refreshBtn.disabled = true;
    refreshBtn.textContent = 'Refreshing...';
    try {
        const resp = await fetch(API + '/books/re-enrich', { method: 'POST' });
        if (!resp.ok) throw new Error('Request failed');
        const data = await resp.json();
        const count = data.queued || 0;
        if (count === 0) {
            showToast('No books with ISBNs to refresh');
        } else {
            showToast(`Refreshing ${count} book${count > 1 ? 's' : ''}...`);

            // Show per-book "Refreshing: X..." toasts timed to match server processing (~3 s each)
            const booksToRefresh = allBooks
                .filter(b => b.isbn)
                .sort((a, b) => (!a.title ? -1 : !b.title ? 1 : 0)); // null-title books first
            booksToRefresh.forEach((book, i) => {
                setTimeout(() => showToast(`Refreshing: ${book.title || book.isbn}...`, 'info'), i * 3000);
            });

            // Poll for updates without re-rendering the whole page
            const totalTime = Math.max(10000, count * 4000);
            let elapsed = 0;
            let updatedCount = 0;
            const pollInterval = setInterval(async () => {
                elapsed += 4000;
                try {
                    const freshBooks = await apiGet('/books');
                    for (const fresh of freshBooks) {
                        const cached = allBooks.find(b => b.id === fresh.id);
                        if (!cached) continue;
                        const changed = fresh.title !== cached.title
                            || fresh.author !== cached.author
                            || fresh.coverUrl !== cached.coverUrl
                            || fresh.publisher !== cached.publisher
                            || fresh.genre !== cached.genre;
                        if (changed) {
                            updateCardInPlace(fresh);
                            updatedCount++;
                            const name = fresh.title || fresh.isbn || 'Book';
                            showToast(`Updated: ${name}`);
                        }
                    }
                    allBooks = freshBooks;
                    updateCounts(freshBooks);
                } catch (e) { /* ignore polling errors */ }
                if (elapsed >= totalTime) {
                    clearInterval(pollInterval);
                    showToast(`Refresh complete — ${updatedCount} book${updatedCount !== 1 ? 's' : ''} updated`);
                }
            }, 4000);
        }
    } catch (e) {
        showToast('Failed to refresh library', 'error');
    } finally {
        // Re-enable after a short delay to prevent double-clicks
        setTimeout(() => {
            refreshBtn.disabled = false;
            refreshBtn.textContent = 'Refresh Library';
        }, 5000);
    }
});

// Book grid event delegation
bookGrid.addEventListener('click', (e) => {
    const editBtn = e.target.closest('.btn-edit');
    if (editBtn) {
        openEditModal(editBtn.dataset.id);
        return;
    }

    const deleteBtn = e.target.closest('.btn-delete');
    if (deleteBtn) {
        deleteBook(deleteBtn.dataset.id);
        return;
    }

    const starBtn = e.target.closest('.book-stars .star');
    if (starBtn) {
        handleStarClick(starBtn.dataset.book, parseInt(starBtn.dataset.value));
        return;
    }

    const subjectTag = e.target.closest('.subject-tag');
    if (subjectTag) {
        const subject = subjectTag.dataset.subject;
        if (currentSubjectFilter && currentSubjectFilter.toLowerCase() === subject.toLowerCase()) {
            currentSubjectFilter = '';
        } else {
            currentSubjectFilter = subject;
        }
        renderBooks(getFilteredBooks());
        return;
    }
});

// Modal
modalClose.addEventListener('click', closeEditModal);
modalCancel.addEventListener('click', closeEditModal);
editModal.addEventListener('click', (e) => {
    if (e.target === editModal) closeEditModal();
});
editForm.addEventListener('submit', submitEdit);

// Show/hide progress group based on reading status
editStatus.addEventListener('change', () => {
    progressGroup.hidden = editStatus.value !== 'READING';
});

// Star picker in modal
editRating.addEventListener('click', (e) => {
    const star = e.target.closest('.star');
    if (!star) return;

    const value = parseInt(star.dataset.value);
    editRating.dataset.currentValue = value;
    editRating.querySelectorAll('.star').forEach(s => {
        s.classList.toggle('filled', parseInt(s.dataset.value) <= value);
    });
});

// Escape key closes modals
document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
        if (!scannerModal.hidden) closeScanner();
        else if (!editModal.hidden) closeEditModal();
    }
});

// Clear subject tag filter
document.getElementById('clear-tag-filter').addEventListener('click', () => {
    currentSubjectFilter = '';
    renderBooks(getFilteredBooks());
});

// ---- Init ----
loadBooks();
