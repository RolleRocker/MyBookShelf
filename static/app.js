// ========================================
// MyBookShelf — Frontend Application
// ========================================

const API = '';
let currentFilter = 'all';
let allBooks = [];
let groupByAuthor = false;
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
            ${metaText ? `<div class="book-meta">${escapeHtml(metaText)}</div>` : ''}
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

// ---- Render All Books ----

function renderBooks(books) {
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
        renderBooks(books);

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

// ---- ISBN Scanner ----

let html5Qrcode = null;
let isScanning = false;
let nativeStream = null;
let nativeScanTimer = null;

const hasNativeBarcodeDetector = 'BarcodeDetector' in window;

function openScanner() {
    scannerError.hidden = true;
    scannerModal.hidden = false;
    isScanning = true;

    if (hasNativeBarcodeDetector) {
        openNativeScanner();
    } else {
        openHtml5Scanner();
    }
}

function openNativeScanner() {
    const detector = new BarcodeDetector({ formats: ['ean_13', 'ean_8', 'code_128', 'upc_a'] });
    const viewfinder = document.getElementById('scanner-viewfinder');

    navigator.mediaDevices.getUserMedia({
        video: { facingMode: 'environment', width: { ideal: 1280 }, height: { ideal: 720 } }
    }).then(stream => {
        nativeStream = stream;
        const video = document.createElement('video');
        video.srcObject = stream;
        video.setAttribute('playsinline', 'true');
        video.play();
        viewfinder.innerHTML = '';
        viewfinder.appendChild(video);

        nativeScanTimer = setInterval(async () => {
            if (!isScanning || video.readyState < 2) return;
            try {
                const barcodes = await detector.detect(video);
                if (barcodes.length > 0) {
                    handleScanResult(barcodes[0].rawValue);
                }
            } catch (e) {}
        }, 50);
    }).catch(err => {
        isScanning = false;
        showScannerError(err);
    });
}

function openHtml5Scanner() {
    html5Qrcode = new Html5Qrcode('scanner-viewfinder');

    html5Qrcode.start(
        { facingMode: 'environment' },
        {
            fps: 20,
            qrbox: { width: 340, height: 160 },
            disableFlip: true,
            videoConstraints: {
                facingMode: 'environment',
                width: { ideal: 1280 },
                height: { ideal: 720 }
            },
            formatsToSupport: [
                Html5QrcodeSupportedFormats.EAN_13,
                Html5QrcodeSupportedFormats.EAN_8,
                Html5QrcodeSupportedFormats.CODE_128,
                Html5QrcodeSupportedFormats.UPC_A
            ]
        },
        onBarcodeDetected,
        () => {} // Ignore per-frame errors
    ).catch(err => {
        isScanning = false;
        showScannerError(err);
    });
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

    if (nativeScanTimer) {
        clearInterval(nativeScanTimer);
        nativeScanTimer = null;
    }
    if (nativeStream) {
        nativeStream.getTracks().forEach(t => t.stop());
        nativeStream = null;
    }
    if (html5Qrcode) {
        html5Qrcode.stop().then(() => {
            html5Qrcode.clear();
            html5Qrcode = null;
        }).catch(() => {
            html5Qrcode = null;
        });
    }

    scannerModal.hidden = true;
}

function handleScanResult(decodedText) {
    if (!isScanning) return;
    isScanning = false;
    closeScanner();
    onBarcodeScanned(decodedText.trim());
}

async function onBarcodeDetected(decodedText) {
    // html5-qrcode callback
    if (!isScanning) return;
    isScanning = false;

    if (html5Qrcode) {
        try { await html5Qrcode.stop(); } catch (e) {}
        try { html5Qrcode.clear(); } catch (e) {}
        html5Qrcode = null;
    }
    scannerModal.hidden = true;

    onBarcodeScanned(decodedText.trim());
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

// Hide scan button if no scanning capability available
if (typeof Html5Qrcode === 'undefined' && !hasNativeBarcodeDetector) {
    scanBtn.style.display = 'none';
}

// ---- Event Listeners ----

// Scanner
scanBtn.addEventListener('click', openScanner);
scannerClose.addEventListener('click', closeScanner);
scannerModal.addEventListener('click', (e) => {
    if (e.target === scannerModal) closeScanner();
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

// Filter tabs
filterTabs.forEach(tab => {
    tab.addEventListener('click', () => setFilter(tab.dataset.status));
});

// Group by author toggle
groupToggle.addEventListener('click', () => {
    groupByAuthor = !groupByAuthor;
    groupToggle.classList.toggle('active', groupByAuthor);
    renderBooks(allBooks);
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
});

// Modal
modalClose.addEventListener('click', closeEditModal);
modalCancel.addEventListener('click', closeEditModal);
editModal.addEventListener('click', (e) => {
    if (e.target === editModal) closeEditModal();
});
editForm.addEventListener('submit', submitEdit);

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

// ---- Init ----
loadBooks();
