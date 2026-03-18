// ========================================
// MyBookShelf — Frontend Application
// ========================================

const API = "";
let currentFilter = "all";
let allBooks = [];
let groupByAuthor = false;
let searchQuery = "";
let currentSubjectFilter = "";
let currentSort = "";
let currentPage = 1;
let pageSize = 20;
let totalPages = 1;
const pollingTimers = new Map();
let allShelves = [];
let activeShelfId = null;
let activeShelfData = null;

const SHELF_COLORS = [
  { hex: "#C4975A", name: "Antique Gold" },
  { hex: "#8B4513", name: "Saddle Brown" },
  { hex: "#6B2D2D", name: "Deep Crimson" },
  { hex: "#2D5016", name: "Forest Green" },
  { hex: "#1B3A5C", name: "Navy Blue" },
  { hex: "#5C3D6E", name: "Royal Purple" },
  { hex: "#7A6652", name: "Weathered Bronze" },
  { hex: "#4A6741", name: "Sage Green" },
  { hex: "#8B6914", name: "Dark Amber" },
  { hex: "#4A4A6A", name: "Slate Blue" },
];

// ---- DOM References ----

const bookGrid = document.getElementById("book-grid");
const emptyState = document.getElementById("empty-state");
const isbnInput = document.getElementById("isbn-input");
const addBtn = document.getElementById("add-btn");
const isbnError = document.getElementById("isbn-error");
const editModal = document.getElementById("edit-modal");
const editForm = document.getElementById("edit-form");
const editId = document.getElementById("edit-id");
const editTitle = document.getElementById("edit-title");
const editAuthor = document.getElementById("edit-author");
const editGenre = document.getElementById("edit-genre");
const editRating = document.getElementById("edit-rating");
const editStatus = document.getElementById("edit-status");
const editProgress = document.getElementById("edit-progress");
const editReview = document.getElementById("edit-review");
const progressGroup = document.getElementById("progress-group");
const modalClose = document.getElementById("modal-close");
const modalCancel = document.getElementById("modal-cancel");
const filterTabs = document.querySelectorAll(".filter-tab");
const toastContainer = document.getElementById("toast-container");
const scanBtn = document.getElementById("scan-btn");
const scannerModal = document.getElementById("scanner-modal");
const scannerClose = document.getElementById("scanner-close");
const scannerError = document.getElementById("scanner-error");
const groupToggle = document.getElementById("group-toggle");
const refreshBtn = document.getElementById("refresh-library-btn");
const searchInput = document.getElementById("search-input");
const sortSelect = document.getElementById("sort-select");

// Shelf DOM references
const shelfSidebar = document.getElementById("shelf-sidebar");
const shelfList = document.getElementById("shelf-list");
const sidebarAllBooks = document.getElementById("sidebar-all-books");
const newShelfBtn = document.getElementById("new-shelf-btn");
const sidebarToggle = document.getElementById("sidebar-toggle");
const shelfStatsBar = document.getElementById("shelf-stats-bar");
const shelfModal = document.getElementById("shelf-modal");
const shelfForm = document.getElementById("shelf-form");
const shelfEditId = document.getElementById("shelf-edit-id");
const shelfNameInput = document.getElementById("shelf-name");
const shelfDescInput = document.getElementById("shelf-description");
const shelfNotesGroup = document.getElementById("shelf-notes-group");
const shelfNotesInput = document.getElementById("shelf-notes");
const shelfColorPicker = document.getElementById("shelf-color-picker");
const shelfSortGroup = document.getElementById("shelf-sort-group");
const shelfSortSelect = document.getElementById("shelf-sort");
const shelfModalTitle = document.getElementById("shelf-modal-title");
const shelfModalClose = document.getElementById("shelf-modal-close");
const shelfModalCancel = document.getElementById("shelf-modal-cancel");
const shelfDeleteBtn = document.getElementById("shelf-delete-btn");
const shelfSaveBtn = document.getElementById("shelf-save-btn");
const bookShelvesCheckboxes = document.getElementById(
  "book-shelves-checkboxes",
);

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
    method: "POST",
    headers: { "Content-Type": "application/json" },
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
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!resp.ok) {
    const err = await resp.json().catch(() => ({}));
    throw new Error(err.error || `PUT failed: ${resp.status}`);
  }
  return resp.json();
}

async function apiDelete(path) {
  const resp = await fetch(API + path, { method: "DELETE" });
  if (!resp.ok && resp.status !== 204)
    throw new Error(`DELETE failed: ${resp.status}`);
}

// ---- Toast Notifications ----

function showToast(message, type = "success") {
  const toast = document.createElement("div");
  toast.className = `toast ${type}`;
  toast.textContent = message;
  toastContainer.appendChild(toast);

  setTimeout(() => {
    toast.classList.add("toast-out");
    toast.addEventListener("animationend", () => toast.remove());
    setTimeout(() => {
      if (toast.parentNode) toast.remove();
    }, 500);
  }, 4500);
}

// ---- Status Helpers ----

function statusLabel(status) {
  switch (status) {
    case "WANT_TO_READ":
      return "Want to Read";
    case "READING":
      return "Reading";
    case "FINISHED":
      return "Finished";
    case "DNF":
      return "Did Not Finish";
    default:
      return status;
  }
}

function statusClass(status) {
  switch (status) {
    case "WANT_TO_READ":
      return "want";
    case "READING":
      return "reading";
    case "FINISHED":
      return "finished";
    case "DNF":
      return "dnf";
    default:
      return "";
  }
}

// ---- Render Book Card ----

function renderStars(rating, bookId) {
  let html = "";
  for (let i = 1; i <= 5; i++) {
    const isFull = rating >= i;
    const isHalf = !isFull && rating >= i - 0.5;
    let cls = "star";
    if (isFull) cls += " filled";
    else if (isHalf) cls += " half-filled";
    html += `<button class="${cls}" data-value="${i}" data-book="${bookId}" aria-label="${i} star${i > 1 ? "s" : ""}">` +
      `<span class="star-bg">&#9733;</span>` +
      `<span class="star-half-fill">&#9733;</span>` +
      `</button>`;
  }
  return html;
}

function createBookCard(book) {
  const card = document.createElement("div");
  card.className = `book-card${book.readStatus === "DNF" ? " dnf" : ""}`;
  card.dataset.id = book.id;
  card.setAttribute("draggable", "true");
  card.style.animationDelay = `${Math.random() * 0.15}s`;

  const isLoading = !book.title;
  const titleText = book.title || "Loading...";
  const authorText = book.author || "";
  const rating = book.rating || 0;

  let metaParts = [];
  if (book.publisher) metaParts.push(book.publisher);
  if (book.publishDate) metaParts.push(book.publishDate);
  if (book.pageCount) metaParts.push(book.pageCount + " pages");
  const metaText = metaParts.join(" \u00B7 ");

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
            <div class="book-title${isLoading ? " loading" : ""}">${escapeHtml(titleText)}</div>
            ${authorText ? `<div class="book-author">${escapeHtml(authorText)}</div>` : ""}
            <div class="book-badges">
                ${book.genre ? `<span class="badge badge-genre">${escapeHtml(book.genre)}</span>` : ""}
                <span class="badge badge-status ${statusClass(book.readStatus)}">${statusLabel(book.readStatus)}</span>
            </div>
            ${book.startedAt || book.finishedAt ? `<div class="book-dates">${book.startedAt ? `Started ${formatDate(book.startedAt)}` : ""}${book.startedAt && book.finishedAt ? " · " : ""}${book.finishedAt ? `Finished ${formatDate(book.finishedAt)}` : ""}</div>` : ""}
            <div class="book-stars">${renderStars(rating, book.id)}</div>
            ${
              (book.readStatus === "READING" || book.readStatus === "DNF") &&
              book.readingProgress != null
                ? `<div class="progress-bar-wrap" title="${book.readingProgress}% read">
                     <div class="progress-bar-fill" style="width:${book.readingProgress}%"></div>
                   </div>`
                : ""
            }
            ${metaText ? `<div class="book-meta">${escapeHtml(metaText)}</div>` : ""}
            ${
              book.subjects && book.subjects.length > 0
                ? `<div class="subject-tags">${book.subjects
                    .slice(0, 4)
                    .map(
                      (s) =>
                        `<button class="subject-tag${currentSubjectFilter && s.toLowerCase() === currentSubjectFilter.toLowerCase() ? " active-filter" : ""}" data-subject="${escapeHtml(s)}" type="button" title="Filter by ${escapeHtml(s)}">${escapeHtml(s)}</button>`,
                    )
                    .join("")}</div>`
                : ""
            }
            ${renderShelfTags(book)}
            ${book.review ? `<button class="review-toggle" data-id="${book.id}" type="button" title="Show notes/review">&#128221; Notes</button><div class="book-review" id="review-${book.id}" hidden><p class="review-text">${escapeHtml(book.review)}</p></div>` : ""}
            <div class="book-actions">
                <button class="btn-edit" data-id="${book.id}">Edit</button>
                <button class="btn-delete" data-id="${book.id}">Delete</button>
            </div>
        </div>
    `;

  // Load cover image
  loadCover(book);

  // Setup drag start for book-to-shelf
  initBookDrag(card, book);

  return card;
}

function loadCover(book) {
  if (!book.coverUrl) return;
  const img = new Image();
  img.onload = () => {
    const container = document.getElementById(`cover-${book.id}`);
    if (container) {
      container.innerHTML = "";
      img.alt = book.title || "Book cover";
      container.appendChild(img);
    }
  };
  img.onerror = () => {}; // Keep placeholder on error
  img.src = `/books/${book.id}/cover`;
}

function formatDate(dateStr) {
  const [y, m, d] = dateStr.split("-");
  const date = new Date(+y, +m - 1, +d);
  return date.toLocaleDateString("en-US", { month: "short", day: "numeric", year: "numeric" });
}

function escapeHtml(str) {
  return str
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}

// ---- Filtered Books ----

function getSortedBooks(books) {
  if (!currentSort) return books;
  const [field, dir] = currentSort.split(",");
  const asc = dir !== "desc";
  return [...books].sort((a, b) => {
    let av, bv;
    if (field === "title") {
      av = (a.title || "").toLowerCase();
      bv = (b.title || "").toLowerCase();
    } else if (field === "author") {
      av = (a.author || "").toLowerCase();
      bv = (b.author || "").toLowerCase();
    } else if (field === "rating") {
      av = a.rating || 0;
      bv = b.rating || 0;
    } else if (field === "created") {
      av = a.createdAt || "";
      bv = b.createdAt || "";
    } else return 0;
    if (av < bv) return asc ? -1 : 1;
    if (av > bv) return asc ? 1 : -1;
    return 0;
  });
}

function getFilteredBooks() {
  let books = allBooks;
  if (searchQuery) {
    const q = searchQuery.toLowerCase();
    books = books.filter(
      (b) =>
        (b.title && b.title.toLowerCase().includes(q)) ||
        (b.author && b.author.toLowerCase().includes(q)),
    );
  }
  if (currentSubjectFilter) {
    const sq = currentSubjectFilter.toLowerCase();
    books = books.filter(
      (b) => b.subjects && b.subjects.some((s) => s.toLowerCase() === sq),
    );
  }
  if (currentFilter !== "all") {
    books = books.filter((b) => b.readStatus === currentFilter);
  }
  return getSortedBooks(books);
}

function updateSubjectFilterChip() {
  const chip = document.getElementById("active-tag-filter");
  if (!chip) return;
  if (currentSubjectFilter) {
    document.getElementById("active-tag-label").textContent =
      currentSubjectFilter;
    chip.hidden = false;
  } else {
    chip.hidden = true;
  }
}

// ---- Pagination ----

function renderFilteredBooks() {
  const filtered = getFilteredBooks();
  totalPages = Math.max(1, Math.ceil(filtered.length / pageSize));
  if (currentPage > totalPages) currentPage = totalPages;
  const startIdx = (currentPage - 1) * pageSize;
  const pageItems = filtered.slice(startIdx, startIdx + pageSize);
  renderBooks(pageItems);
  updatePaginationNav();
}

function updatePaginationNav() {
  const nav = document.getElementById("pagination-nav");
  const info = document.getElementById("page-info");
  const prev = document.getElementById("prev-page");
  const next = document.getElementById("next-page");
  if (!nav) return;

  const filtered = getFilteredBooks();
  totalPages = Math.max(1, Math.ceil(filtered.length / pageSize));
  if (currentPage > totalPages) currentPage = totalPages;

  nav.style.display = filtered.length > pageSize ? "flex" : "none";
  info.textContent = "Page " + currentPage + " of " + totalPages;
  prev.disabled = currentPage <= 1;
  next.disabled = currentPage >= totalPages;
}

// ---- Render All Books ----

function renderBooks(books) {
  updateSubjectFilterChip();
  bookGrid.innerHTML = "";
  if (books.length === 0) {
    emptyState.hidden = false;
  } else {
    emptyState.hidden = true;
    if (groupByAuthor) {
      renderBooksGrouped(books);
    } else {
      books.forEach((book) => {
        bookGrid.appendChild(createBookCard(book));
      });
    }
  }
}

function normalizeAuthor(author) {
  if (!author) return "";
  return author.trim().replace(/\s+/g, " ").toLowerCase();
}

function renderBooksGrouped(books) {
  const groups = new Map();
  const displayNames = new Map(); // normalized key -> best display name
  books.forEach((book) => {
    const raw = book.author || "";
    const key = normalizeAuthor(raw) || "_unknown";
    if (!groups.has(key)) {
      groups.set(key, []);
      displayNames.set(key, raw || "Unknown Author");
    }
    groups.get(key).push(book);
  });

  const sortedKeys = [...groups.keys()].sort((a, b) => {
    if (a === "_unknown") return 1;
    if (b === "_unknown") return -1;
    return a.localeCompare(b, undefined, { sensitivity: "base" });
  });

  sortedKeys.forEach((key) => {
    const authorBooks = groups.get(key);
    const displayName = displayNames.get(key);
    authorBooks.sort((a, b) =>
      (a.title || "").localeCompare(b.title || "", undefined, {
        sensitivity: "base",
      }),
    );

    const section = document.createElement("div");
    section.className = "author-section";
    section.innerHTML = `<h3 class="author-heading">${escapeHtml(displayName)} <span class="author-count">${authorBooks.length}</span></h3>`;
    bookGrid.appendChild(section);

    authorBooks.forEach((book) => {
      bookGrid.appendChild(createBookCard(book));
    });
  });
}

// ---- Update Counts ----

function updateCounts(books) {
  document.getElementById("count-all").textContent = books.length;
  document.getElementById("count-want").textContent = books.filter(
    (b) => b.readStatus === "WANT_TO_READ",
  ).length;
  document.getElementById("count-reading").textContent = books.filter(
    (b) => b.readStatus === "READING",
  ).length;
  document.getElementById("count-finished").textContent = books.filter(
    (b) => b.readStatus === "FINISHED",
  ).length;
  document.getElementById("count-dnf").textContent = books.filter(
    (b) => b.readStatus === "DNF",
  ).length;
}

// ---- Load Books ----

async function loadBooks() {
  try {
    const books = await apiGet("/books");
    allBooks = books;
    if (activeShelfId) {
      await loadShelfBooks(activeShelfId);
    } else {
      renderFilteredBooks();
    }
    updateCounts(books);
    loadShelves();
  } catch (e) {
    showToast("Failed to load books", "error");
  }
}

// ---- Add Book ----

async function addBook() {
  const isbn = isbnInput.value.trim();
  isbnError.hidden = true;

  if (!isbn) {
    isbnError.textContent = "Please enter an ISBN";
    isbnError.hidden = false;
    return;
  }

  if (!isValidIsbn(isbn)) {
    isbnError.textContent =
      "Invalid ISBN format. Must be 10 or 13 digits (ISBN-10 may end in X).";
    isbnError.hidden = false;
    return;
  }

  try {
    addBtn.disabled = true;
    const book = await apiPost("/books", { isbn, readStatus: "WANT_TO_READ" });
    isbnInput.value = "";
    showToast("Book added! Fetching details...");

    // Switch to "All Books" view to see the new book
    if (activeShelfId) {
      clearShelfFilter();
    }
    if (currentFilter !== "all") {
      setFilter("all");
    }

    await loadBooks();
    loadCurrentGoal();

    // Start polling for enrichment if title is missing
    if (!book.title) {
      pollForEnrichment(book.id);
    }
  } catch (e) {
    showToast(e.message || "Failed to add book", "error");
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
  newCard.style.animation = "none"; // Don't re-animate
  existingCard.replaceWith(newCard);

  // Also update allBooks cache
  const idx = allBooks.findIndex((b) => b.id === book.id);
  if (idx >= 0) allBooks[idx] = book;
}

// ---- Quick Star Rating ----

function handleStarClick(bookId, value) {
  apiPut(`/books/${bookId}`, { rating: value })
    .then((updatedBook) => {
      updateCardInPlace(updatedBook);
    })
    .catch(() => showToast("Failed to update rating", "error"));
}

function getStarRatingFromEvent(e) {
  const star = e.target.closest(".star");
  if (!star) return null;
  const value = parseInt(star.dataset.value);
  const rect = star.getBoundingClientRect();
  const x = e.clientX - rect.left;
  const isLeftHalf = x < rect.width / 2;
  return isLeftHalf ? value - 0.5 : value;
}

// ---- Edit Modal ----

function openEditModal(bookId) {
  const book = allBooks.find((b) => b.id === bookId);
  if (!book) return;

  editId.value = book.id;
  editTitle.value = book.title || "";
  editAuthor.value = book.author || "";
  editGenre.value = book.genre || "";
  editStatus.value = book.readStatus;
  editProgress.value = book.readingProgress != null ? book.readingProgress : "";
  progressGroup.hidden =
    book.readStatus !== "READING" && book.readStatus !== "DNF";

  // Set star rating (supports half-stars)
  const stars = editRating.querySelectorAll(".star");
  const rating = book.rating || 0;
  stars.forEach((star) => {
    const sv = parseInt(star.dataset.value);
    const isFull = rating >= sv;
    const isHalf = !isFull && rating >= sv - 0.5;
    star.classList.toggle("filled", isFull);
    star.classList.toggle("half-filled", isHalf);
  });
  editRating.dataset.currentValue = rating;

  editReview.value = book.review || "";

  // Set date fields
  document.getElementById("edit-started").value = book.startedAt || "";
  document.getElementById("edit-finished").value = book.finishedAt || "";

  // Populate shelf checkboxes
  populateBookShelfCheckboxes(book);

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
  if (
    (editStatus.value === "READING" || editStatus.value === "DNF") &&
    editProgress.value !== ""
  ) {
    const progressVal = parseInt(editProgress.value, 10);
    if (isNaN(progressVal) || progressVal < 0 || progressVal > 100) {
      showToast("Reading progress must be between 0 and 100", "error");
      return;
    }
    updates.readingProgress = progressVal;
  } else {
    updates.readingProgress = null;
  }

  const ratingVal = parseFloat(editRating.dataset.currentValue) || 0;
  if (ratingVal >= 0.5 && ratingVal <= 5) {
    updates.rating = ratingVal;
  }

  const reviewVal = editReview.value.trim();
  updates.review = reviewVal || null;

  const startedVal = document.getElementById("edit-started").value;
  const finishedVal = document.getElementById("edit-finished").value;
  updates.startedAt = startedVal || null;
  updates.finishedAt = finishedVal || null;

  try {
    const updated = await apiPut(`/books/${id}`, updates);
    closeEditModal();
    showToast("Book updated");
    await loadBooks();
    loadCurrentGoal();
  } catch (e) {
    showToast(e.message || "Failed to update", "error");
  }
}

// ---- Delete ----

async function deleteBook(bookId) {
  const book = allBooks.find((b) => b.id === bookId);
  const name = book?.title || "this book";
  if (!confirm(`Delete "${name}"? This cannot be undone.`)) return;

  try {
    await apiDelete(`/books/${bookId}`);
    showToast("Book deleted");
    // Stop any polling
    if (pollingTimers.has(bookId)) {
      clearInterval(pollingTimers.get(bookId));
      pollingTimers.delete(bookId);
    }
    await loadBooks();
    loadCurrentGoal();
  } catch (e) {
    showToast("Failed to delete", "error");
  }
}

// ---- Filter Tabs ----

function setFilter(status) {
  currentFilter = status;
  currentPage = 1;
  filterTabs.forEach((tab) => {
    tab.classList.toggle("active", tab.dataset.status === status);
  });
  renderFilteredBooks();
}

// ---- ISBN Scanner (zbar-wasm) ----

let isScanning = false;
let activeStream = null;
let scannerTimeoutTimer = null;
let scannerHintTimer = null;
let isBulkMode = false;
let bulkScannedIds = [];
let bulkCooldownCode = null;

const cameraSelect = document.getElementById("camera-select");
const scannerTimeoutHint = document.getElementById("scanner-timeout-hint");
const scannerModalTitle = document.getElementById("scanner-modal-title");
const scannerBulkFooter = document.getElementById("scanner-bulk-footer");
const scannerBulkCount = document.getElementById("scanner-bulk-count");
const scannerDoneBtn = document.getElementById("scanner-done");
const bulkScanBtn = document.getElementById("bulk-scan-btn");

const SCANNER_CAMERA_KEY = "mybookshelf-camera-id";

async function enumerateCameras() {
  try {
    const devices = await navigator.mediaDevices.enumerateDevices();
    const cameras = devices.filter((d) => d.kind === "videoinput");
    if (cameras.length === 0) return [];

    cameraSelect.innerHTML = "";
    cameras.forEach((cam, i) => {
      const opt = document.createElement("option");
      opt.value = cam.deviceId;
      opt.textContent = cam.label || `Camera ${i + 1}`;
      cameraSelect.appendChild(opt);
    });

    // Restore last-used camera, or default to last in list (USB cams appear last)
    const saved = localStorage.getItem(SCANNER_CAMERA_KEY);
    const savedExists = cameras.some((c) => c.deviceId === saved);
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
    constraints.facingMode = "environment";
  }
  constraints.focusMode = { ideal: "continuous" };
  constraints.torch = false;
  return constraints;
}

// ---- Image Processing Pipeline ----

const SCAN_WIDTH = 800;

let roiCanvas = null;
let roiCtx = null;

// Grab a downscaled grayscale snapshot from the center of the video (ROI).
function captureROI(video) {
  const vw = video.videoWidth,
    vh = video.videoHeight;
  const roiW = Math.floor(vw * 0.8);
  const roiH = Math.floor(vh * 0.5);
  const roiX = Math.floor((vw - roiW) / 2);
  const roiY = Math.floor((vh - roiH) / 2);

  const scale = Math.min(1, SCAN_WIDTH / roiW);
  const w = Math.floor(roiW * scale);
  const h = Math.floor(roiH * scale);

  if (!roiCanvas) {
    roiCanvas = document.createElement("canvas");
    roiCtx = roiCanvas.getContext("2d", { willReadFrequently: true });
  }
  roiCanvas.width = w;
  roiCanvas.height = h;
  roiCtx.drawImage(video, roiX, roiY, roiW, roiH, 0, 0, w, h);

  const imgData = roiCtx.getImageData(0, 0, w, h);
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
    gray[i] =
      Math.min(255, Math.max(0, gray[i] + amount * (gray[i] - blurred[i]))) | 0;
  }
}

// Global threshold — returns a new Uint8Array of thresholded grayscale values.
function globalThresholdGray(gray, w, h, fraction) {
  let min = 255,
    max = 0;
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
      integral[(y + 1) * (w + 1) + (x + 1)] =
        integral[y * (w + 1) + (x + 1)] + rowSum;
    }
  }

  const half = blockSize >> 1;
  const out = new Uint8Array(w * h);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const x1 = Math.max(0, x - half),
        y1 = Math.max(0, y - half);
      const x2 = Math.min(w - 1, x + half),
        y2 = Math.min(h - 1, y + half);
      const area = (x2 - x1 + 1) * (y2 - y1 + 1);
      const sum =
        integral[(y2 + 1) * (w + 1) + (x2 + 1)] -
        integral[y1 * (w + 1) + (x2 + 1)] -
        integral[(y2 + 1) * (w + 1) + x1] +
        integral[y1 * (w + 1) + x1];
      const mean = sum / area;
      out[y * w + x] = gray[y * w + x] < mean - C ? 0 : 255;
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
    if (caps.focusMode?.includes("continuous"))
      settings.focusMode = "continuous";
    if (caps.exposureMode?.includes("continuous"))
      settings.exposureMode = "continuous";
    if (caps.exposureCompensation) {
      settings.exposureCompensation = Math.max(
        caps.exposureCompensation.min,
        -1.0,
      );
    }
    if (caps.whiteBalanceMode?.includes("continuous"))
      settings.whiteBalanceMode = "continuous";
    if (Object.keys(settings).length > 0) {
      await track.applyConstraints({ advanced: [settings] });
    }
  } catch (e) {
    /* not all cameras support all constraints */
  }
}

function startScannerTimeouts() {
  scannerTimeoutHint.hidden = true;
  scannerTimeoutHint.innerHTML = "";

  scannerHintTimer = setTimeout(() => {
    if (!isScanning) return;
    scannerTimeoutHint.textContent =
      "Having trouble? Tilt the book slightly to reduce glare, move closer, or try switching cameras.";
    scannerTimeoutHint.hidden = false;
  }, 15000);

  scannerTimeoutTimer = setTimeout(() => {
    if (!isScanning) return;
    scannerTimeoutHint.innerHTML =
      'Still no detection. <a id="scanner-type-manually">Type ISBN manually</a>';
    scannerTimeoutHint.hidden = false;
    document
      .getElementById("scanner-type-manually")
      ?.addEventListener("click", () => {
        closeScanner();
        isbnInput.focus();
      });
  }, 30000);
}

function clearScannerTimeouts() {
  if (scannerHintTimer) {
    clearTimeout(scannerHintTimer);
    scannerHintTimer = null;
  }
  if (scannerTimeoutTimer) {
    clearTimeout(scannerTimeoutTimer);
    scannerTimeoutTimer = null;
  }
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
  const viewfinder = document.getElementById("scanner-viewfinder");

  let stream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({
      video: getVideoConstraints(),
    });
  } catch (err) {
    isScanning = false;
    showScannerError(err);
    return;
  }

  activeStream = stream;
  await optimizeCameraSettings(stream);

  const video = document.createElement("video");
  video.srcObject = stream;
  video.setAttribute("playsinline", "true");
  video.play();
  viewfinder.innerHTML = "";
  viewfinder.appendChild(video);

  if (cameraSelect.value)
    localStorage.setItem(SCANNER_CAMERA_KEY, cameraSelect.value);

  let scanBusy = false;
  let lastScanTime = 0;
  const MIN_INTERVAL = 80; // ~12 fps

  function scanLoop(timestamp) {
    if (!isScanning) return;
    if (
      timestamp - lastScanTime >= MIN_INTERVAL &&
      video.readyState >= 2 &&
      !scanBusy
    ) {
      lastScanTime = timestamp;
      scanBusy = true;
      (async () => {
        try {
          const roi = captureROI(video);
          const { gray, width: w, height: h } = roi;

          // Pass 1: Raw grayscale (fastest — catches clean barcodes)
          let result = await zbarScan(grayToImageData(gray, w, h));
          if (result) {
            handleScanResult(result);
            scanBusy = false;
            return;
          }

          // Sharpen in-place for enhanced passes
          unsharpMask(gray, w, h, 1.0);

          // Pass 2: Multiple global thresholds
          for (const frac of [0.35, 0.5, 0.65]) {
            const thresholded = globalThresholdGray(gray, w, h, frac);
            result = await zbarScan(grayToImageData(thresholded, w, h));
            if (result) {
              handleScanResult(result);
              scanBusy = false;
              return;
            }
          }

          // Pass 3: Adaptive local threshold
          const adaptive = adaptiveThresholdGray(gray, w, h, 31, 10);
          result = await zbarScan(grayToImageData(adaptive, w, h));
          if (result) {
            handleScanResult(result);
            scanBusy = false;
            return;
          }
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

  scannerModalTitle.textContent = bulk
    ? "Scan ISBN Barcodes"
    : "Scan ISBN Barcode";
  scannerBulkFooter.hidden = !bulk;
  scannerBulkCount.textContent = "0 books scanned";

  scannerError.hidden = true;
  scannerTimeoutHint.hidden = true;
  scannerModal.hidden = false;
  isScanning = true;

  // Need an initial getUserMedia to get labeled device list
  try {
    const tempStream = await navigator.mediaDevices.getUserMedia({
      video: true,
    });
    tempStream.getTracks().forEach((t) => t.stop());
  } catch (e) {
    // Permission denied — enumerateCameras will still work but labels may be empty
  }

  await enumerateCameras();
  startScannerTimeouts();

  const viewfinder = document.getElementById("scanner-viewfinder");
  viewfinder.classList.add("scanning");

  startZbarScanner();
}

function showScannerError(err) {
  const msg = String(err);
  if (msg.includes("NotAllowedError") || msg.includes("Permission")) {
    scannerError.textContent =
      "Camera permission denied. Please allow camera access in your browser settings.";
  } else if (
    msg.includes("NotFoundError") ||
    msg.includes("Requested device not found")
  ) {
    scannerError.textContent = "No camera found on this device.";
  } else {
    scannerError.textContent = "Could not start camera: " + msg;
  }
  scannerError.hidden = false;
}

function closeScanner() {
  isScanning = false;
  isBulkMode = false;
  bulkCooldownCode = null;
  clearScannerTimeouts();

  const viewfinder = document.getElementById("scanner-viewfinder");
  viewfinder.classList.remove("scanning");

  if (activeStream) {
    activeStream.getTracks().forEach((t) => t.stop());
    activeStream = null;
  }

  scannerModal.hidden = true;
  roiCanvas = null;
  roiCtx = null;
}

function stopCurrentCamera() {
  isScanning = false;
  if (activeStream) {
    activeStream.getTracks().forEach((t) => t.stop());
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
    setTimeout(() => {
      if (bulkCooldownCode === code) bulkCooldownCode = null;
    }, 1500);
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
    showToast("Not a valid ISBN: " + code, "error");
    return;
  }

  // Check for duplicate — silent skip with distinct toast
  try {
    const resp = await fetch(API + "/books/isbn/" + code);
    if (resp.ok) {
      const existing = await resp.json();
      const label = existing.title || code;
      showToast(`Already in your library: "${label}"`, "info");
      return;
    }
  } catch (e) {
    /* network error — try to add anyway */
  }

  try {
    const book = await apiPost("/books", {
      isbn: code,
      readStatus: "WANT_TO_READ",
    });
    bulkScannedIds.push(book.id);

    // Update counter in modal
    scannerBulkCount.textContent = `${bulkScannedIds.length} book${bulkScannedIds.length !== 1 ? "s" : ""} scanned`;

    showToast("Added: " + code);

    // Reset timeout hints so the user gets the full window for the next book
    clearScannerTimeouts();
    startScannerTimeouts();

    if (!book.title) pollForEnrichment(book.id);
  } catch (e) {
    showToast(e.message || "Failed to add book", "error");
  }
}

async function doneBulkScan() {
  const idsToScroll = [...bulkScannedIds]; // capture before closeScanner clears
  closeScanner();

  if (idsToScroll.length === 0) return;

  if (activeShelfId) clearShelfFilter();
  if (currentFilter !== "all") setFilter("all");
  await loadBooks();

  // Scroll to the first newly added book
  const firstId = idsToScroll[0];
  const card = bookGrid.querySelector(`[data-id="${firstId}"]`);
  if (card) card.scrollIntoView({ behavior: "smooth", block: "center" });
}

async function onBarcodeScanned(code) {
  if (!isValidIsbn(code)) {
    showToast("Scanned barcode is not a valid ISBN", "error");
    return;
  }

  isbnInput.value = code;
  isbnError.hidden = true;

  // Check for existing copy
  try {
    const resp = await fetch(API + "/books/isbn/" + code);
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
if (typeof zbarWasm === "undefined") {
  scanBtn.style.display = "none";
  bulkScanBtn.style.display = "none";
}

// ---- Shelf Functions ----

// Render shelf tags (colored pills) on a book card
function renderShelfTags(book) {
  const shelves = book.shelves;
  if (!shelves || shelves.length === 0) return "";
  const maxVisible = 3;
  const visible = shelves.slice(0, maxVisible);
  const overflow = shelves.length - maxVisible;
  let html = '<div class="shelf-tags">';
  visible.forEach((s) => {
    html += `<button class="shelf-tag" data-shelf-id="${s.id}" type="button" title="${escapeHtml(s.name)}"><span class="shelf-tag-dot" style="background:${s.color}"></span>${escapeHtml(s.name)}</button>`;
  });
  if (overflow > 0) {
    html += `<span class="shelf-tag-overflow" title="${shelves
      .slice(maxVisible)
      .map((s) => escapeHtml(s.name))
      .join(", ")}">+${overflow} more</span>`;
  }
  html += "</div>";
  return html;
}

// Load all shelves for sidebar
async function loadShelves() {
  try {
    const shelves = await apiGet("/shelves");
    allShelves = shelves;
    renderSidebar(shelves);
  } catch (e) {
    // Shelves API may not exist yet; fail silently
    console.error("Failed to load shelves:", e);
  }
}

// Render sidebar shelf cards
function renderSidebar(shelves) {
  shelfList.innerHTML = "";
  shelves.forEach((shelf) => {
    const card = renderShelfCard(shelf);
    shelfList.appendChild(card);
  });
}

// Single shelf card for sidebar
function renderShelfCard(shelf) {
  const card = document.createElement("div");
  card.className = "shelf-card" + (activeShelfId === shelf.id ? " active" : "");
  card.dataset.shelfId = shelf.id;
  card.setAttribute("draggable", "true");
  card.style.borderLeftColor = shelf.color;

  // Build collage from coverBookIds
  let collageHtml = "";
  const ids = shelf.coverBookIds || [];
  for (let i = 0; i < 4; i++) {
    if (i < ids.length) {
      collageHtml += `<img src="/books/${ids[i]}/cover" alt="" class="collage-img" onerror="this.style.background='${shelf.color}';this.src='';">`;
    } else {
      collageHtml += `<div class="collage-empty" style="background:${shelf.color}"></div>`;
    }
  }

  const count = shelf.bookCount || 0;
  card.innerHTML = `
    <div class="shelf-collage">${collageHtml}</div>
    <div class="shelf-card-info">
      <div class="shelf-card-name" title="${escapeHtml(shelf.name)}">${escapeHtml(shelf.name)}</div>
      <div class="shelf-card-count">${count} book${count !== 1 ? "s" : ""}</div>
    </div>
  `;

  // Click to select shelf
  card.addEventListener("click", (e) => {
    if (e.defaultPrevented) return;
    selectShelf(shelf.id);
  });

  // Right-click context menu
  card.addEventListener("contextmenu", (e) => {
    e.preventDefault();
    openEditShelfModal(shelf);
  });

  // Setup as drop target for books
  initShelfDropTarget(card, shelf);

  // Setup shelf reorder drag
  initShelfReorderDrag(card, shelf);

  return card;
}

// Select a shelf — show its books
async function selectShelf(shelfId) {
  currentPage = 1;
  activeShelfId = shelfId;
  sidebarAllBooks.classList.remove("active");
  // Highlight in sidebar
  shelfList.querySelectorAll(".shelf-card").forEach((c) => {
    c.classList.toggle("active", c.dataset.shelfId === shelfId);
  });
  await loadShelfBooks(shelfId);
}

// Load and render books for a specific shelf
async function loadShelfBooks(shelfId) {
  try {
    const data = await apiGet(`/shelves/${shelfId}`);
    activeShelfData = data;
    const books = data.books || [];
    // Apply current filters/search on top of shelf books
    let filtered = books;
    if (searchQuery) {
      const q = searchQuery.toLowerCase();
      filtered = filtered.filter(
        (b) =>
          (b.title && b.title.toLowerCase().includes(q)) ||
          (b.author && b.author.toLowerCase().includes(q)),
      );
    }
    if (currentSubjectFilter) {
      const sq = currentSubjectFilter.toLowerCase();
      filtered = filtered.filter(
        (b) => b.subjects && b.subjects.some((s) => s.toLowerCase() === sq),
      );
    }
    if (currentFilter !== "all") {
      filtered = filtered.filter((b) => b.readStatus === currentFilter);
    }
    renderBooks(filtered);
    renderShelfStats(data.stats, data.notes, data.name, data.color);
  } catch (e) {
    showToast("Failed to load shelf", "error");
  }
}

// Clear shelf filter — back to all books
function clearShelfFilter() {
  currentPage = 1;
  activeShelfId = null;
  activeShelfData = null;
  sidebarAllBooks.classList.add("active");
  shelfList
    .querySelectorAll(".shelf-card")
    .forEach((c) => c.classList.remove("active"));
  shelfStatsBar.hidden = true;
  renderFilteredBooks();
}

// Render shelf stats bar
function renderShelfStats(stats, notes, name, color) {
  if (!stats) {
    shelfStatsBar.hidden = true;
    return;
  }
  const dismissed =
    localStorage.getItem("bookshelf_stats_dismissed") === activeShelfId;
  if (dismissed) {
    shelfStatsBar.hidden = true;
    return;
  }

  const avgRating = stats.averageRating ? stats.averageRating.toFixed(1) : "0";
  const totalPages = stats.totalPages ? stats.totalPages.toLocaleString() : "0";
  const genres = stats.genreBreakdown || {};
  const genreEntries = Object.entries(genres).sort((a, b) => b[1] - a[1]);
  const maxGenreCount = genreEntries.length > 0 ? genreEntries[0][1] : 1;

  let genreBarsHtml = "";
  if (genreEntries.length > 0) {
    genreBarsHtml = '<div class="stats-genres">';
    genreEntries.forEach(([genre, count]) => {
      const pct = Math.max(10, (count / maxGenreCount) * 100);
      genreBarsHtml += `<div class="stats-genre-row"><span class="stats-genre-label">${escapeHtml(genre)}</span><div class="stats-genre-bar"><div class="stats-genre-fill" style="width:${pct}%;background:${color || "var(--gold)"}"></div></div><span class="stats-genre-count">${count}</span></div>`;
    });
    genreBarsHtml += "</div>";
  }

  let notesHtml = "";
  if (notes) {
    notesHtml = `<div class="stats-notes"><em>${escapeHtml(notes)}</em></div>`;
  }

  shelfStatsBar.innerHTML = `
    <div class="stats-top-row">
      <span class="stats-item">${stats.bookCount} book${stats.bookCount !== 1 ? "s" : ""}</span>
      <span class="stats-item stats-divider">|</span>
      <span class="stats-item">${avgRating} avg rating</span>
      <span class="stats-item stats-divider">|</span>
      <span class="stats-item">${totalPages} pages</span>
      <button class="stats-dismiss" title="Hide stats" aria-label="Dismiss stats bar">&times;</button>
    </div>
    ${genreBarsHtml}
    ${notesHtml}
  `;
  shelfStatsBar.hidden = false;

  shelfStatsBar
    .querySelector(".stats-dismiss")
    .addEventListener("click", () => {
      localStorage.setItem("bookshelf_stats_dismissed", activeShelfId);
      shelfStatsBar.hidden = true;
    });
}

// Toggle sidebar collapse
function toggleSidebar() {
  const collapsed = shelfSidebar.classList.toggle("collapsed");
  localStorage.setItem("bookshelf_sidebar_collapsed", collapsed ? "1" : "0");
  sidebarToggle.innerHTML = collapsed ? "&#x25B6;" : "&#x25C0;";
  sidebarToggle.title = collapsed ? "Expand sidebar" : "Collapse sidebar";
}

// Restore sidebar state from localStorage
function restoreSidebarState() {
  if (localStorage.getItem("bookshelf_sidebar_collapsed") === "1") {
    shelfSidebar.classList.add("collapsed");
    sidebarToggle.innerHTML = "&#x25B6;";
    sidebarToggle.title = "Expand sidebar";
  }
}

// ---- Shelf CRUD ----

async function createShelf(data) {
  try {
    const shelf = await apiPost("/shelves", data);
    showToast(`Shelf "${shelf.name}" created`);
    await loadShelves();
    return shelf;
  } catch (e) {
    showToast(e.message || "Failed to create shelf", "error");
    return null;
  }
}

async function updateShelf(id, data) {
  try {
    const shelf = await apiPut(`/shelves/${id}`, data);
    showToast(`Shelf "${shelf.name}" updated`);
    await loadShelves();
    if (activeShelfId === id) {
      await loadShelfBooks(id);
    }
    return shelf;
  } catch (e) {
    showToast(e.message || "Failed to update shelf", "error");
    return null;
  }
}

async function deleteShelf(id) {
  const shelf = allShelves.find((s) => s.id === id);
  const name = shelf ? shelf.name : "this shelf";
  if (!confirm(`Delete shelf "${name}"? Books won't be deleted.`)) return;
  try {
    await apiDelete(`/shelves/${id}`);
    showToast(`Shelf "${name}" deleted`);
    if (activeShelfId === id) {
      clearShelfFilter();
    }
    await loadShelves();
    // Refresh books to update shelf tags
    const books = await apiGet("/books");
    allBooks = books;
    if (!activeShelfId) renderFilteredBooks();
  } catch (e) {
    showToast(e.message || "Failed to delete shelf", "error");
  }
}

// ---- Shelf Membership ----

async function addBookToShelf(shelfId, bookId) {
  try {
    const resp = await fetch(API + `/shelves/${shelfId}/books`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ bookId }),
    });
    if (resp.status === 409) {
      const shelf = allShelves.find((s) => s.id === shelfId);
      showToast(`Already on "${shelf ? shelf.name : "shelf"}"`, "info");
      return false;
    }
    if (!resp.ok) {
      const err = await resp.json().catch(() => ({}));
      throw new Error(err.error || `Failed: ${resp.status}`);
    }
    const shelf = allShelves.find((s) => s.id === shelfId);
    const book = allBooks.find((b) => b.id === bookId);
    showToast(
      `Added "${book ? book.title || "book" : "book"}" to "${shelf ? shelf.name : "shelf"}"`,
    );
    // Refresh data
    await loadShelves();
    const books = await apiGet("/books");
    allBooks = books;
    if (activeShelfId) {
      await loadShelfBooks(activeShelfId);
    } else {
      renderFilteredBooks();
    }
    return true;
  } catch (e) {
    showToast(e.message || "Failed to add book to shelf", "error");
    return false;
  }
}

async function removeBookFromShelf(shelfId, bookId) {
  try {
    await apiDelete(`/shelves/${shelfId}/books/${bookId}`);
    await loadShelves();
    const books = await apiGet("/books");
    allBooks = books;
    if (activeShelfId) {
      await loadShelfBooks(activeShelfId);
    } else {
      renderFilteredBooks();
    }
    return true;
  } catch (e) {
    showToast(e.message || "Failed to remove book from shelf", "error");
    return false;
  }
}

// ---- Shelf Ordering ----

async function reorderShelves(shelfIds) {
  try {
    await apiPut("/shelves/reorder", { order: shelfIds });
    await loadShelves();
  } catch (e) {
    showToast("Failed to reorder shelves", "error");
    await loadShelves();
  }
}

async function reorderShelfBooks(shelfId, bookIds) {
  try {
    await apiPut(`/shelves/${shelfId}/books/reorder`, { order: bookIds });
    if (activeShelfId === shelfId) await loadShelfBooks(shelfId);
  } catch (e) {
    showToast("Failed to reorder books", "error");
  }
}

// ---- Shelf Modal ----

let selectedShelfColor = SHELF_COLORS[0].hex;
let customShelfColor = null;

function renderColorPicker(selectedColor) {
  shelfColorPicker.innerHTML = "";
  selectedShelfColor = selectedColor || SHELF_COLORS[0].hex;
  customShelfColor = null;

  SHELF_COLORS.forEach((c) => {
    const circle = document.createElement("button");
    circle.type = "button";
    circle.className =
      "color-swatch" +
      (c.hex.toLowerCase() === selectedColor?.toLowerCase() ? " selected" : "");
    circle.style.background = c.hex;
    circle.title = c.name;
    circle.dataset.color = c.hex;
    circle.addEventListener("click", () => {
      selectedShelfColor = c.hex;
      customShelfColor = null;
      shelfColorPicker
        .querySelectorAll(".color-swatch")
        .forEach((s) => s.classList.remove("selected"));
      circle.classList.add("selected");
      // Remove custom swatch selection
      const customSwatch = shelfColorPicker.querySelector(
        ".color-swatch-custom",
      );
      if (customSwatch) customSwatch.classList.remove("selected");
    });
    shelfColorPicker.appendChild(circle);
  });

  // Check if selected color is a preset
  const isPreset = SHELF_COLORS.some(
    (c) => c.hex.toLowerCase() === selectedColor?.toLowerCase(),
  );

  // Custom color button
  const customBtn = document.createElement("button");
  customBtn.type = "button";
  customBtn.className = "color-swatch-custom-btn";
  customBtn.textContent = "Custom";
  const hiddenInput = document.createElement("input");
  hiddenInput.type = "color";
  hiddenInput.className = "color-input-hidden";
  hiddenInput.value = selectedColor || SHELF_COLORS[0].hex;

  customBtn.addEventListener("click", () => hiddenInput.click());
  hiddenInput.addEventListener("input", (e) => {
    const hex = e.target.value;
    selectedShelfColor = hex;
    customShelfColor = hex;
    shelfColorPicker
      .querySelectorAll(".color-swatch")
      .forEach((s) => s.classList.remove("selected"));
    // Show custom swatch
    let customSwatch = shelfColorPicker.querySelector(".color-swatch-custom");
    if (!customSwatch) {
      customSwatch = document.createElement("span");
      customSwatch.className = "color-swatch color-swatch-custom selected";
      shelfColorPicker.insertBefore(customSwatch, customBtn);
    }
    customSwatch.style.background = hex;
    customSwatch.classList.add("selected");
  });

  shelfColorPicker.appendChild(customBtn);
  shelfColorPicker.appendChild(hiddenInput);

  // If not a preset color, show it as custom
  if (selectedColor && !isPreset) {
    customShelfColor = selectedColor;
    selectedShelfColor = selectedColor;
    const customSwatch = document.createElement("span");
    customSwatch.className = "color-swatch color-swatch-custom selected";
    customSwatch.style.background = selectedColor;
    shelfColorPicker.insertBefore(customSwatch, customBtn);
    hiddenInput.value = selectedColor;
  }
}

function openCreateShelfModal() {
  shelfEditId.value = "";
  shelfNameInput.value = "";
  shelfDescInput.value = "";
  shelfNotesInput.value = "";
  shelfNotesGroup.hidden = true;
  shelfSortGroup.hidden = true;
  shelfDeleteBtn.hidden = true;
  shelfModalTitle.textContent = "Create Shelf";
  shelfSaveBtn.textContent = "Create Shelf";
  renderColorPicker(SHELF_COLORS[0].hex);
  shelfModal.hidden = false;
  shelfNameInput.focus();
}

function openEditShelfModal(shelf) {
  shelfEditId.value = shelf.id;
  shelfNameInput.value = shelf.name || "";
  shelfDescInput.value = shelf.description || "";
  shelfNotesInput.value = shelf.notes || "";
  shelfNotesGroup.hidden = false;
  shelfSortGroup.hidden = false;
  shelfDeleteBtn.hidden = false;
  shelfModalTitle.textContent = "Edit Shelf";
  shelfSaveBtn.textContent = "Save";
  renderColorPicker(shelf.color);

  // Set sort select
  const sortVal =
    (shelf.sortField || "custom") + "," + (shelf.sortDirection || "asc");
  shelfSortSelect.value = sortVal;

  shelfModal.hidden = false;
  shelfNameInput.focus();
}

function closeShelfModal() {
  shelfModal.hidden = true;
}

async function submitShelfForm(e) {
  e.preventDefault();
  const name = shelfNameInput.value.trim();
  if (!name) {
    showToast("Shelf name is required", "error");
    return;
  }

  const data = {
    name,
    description: shelfDescInput.value.trim() || null,
    color: selectedShelfColor,
  };

  const id = shelfEditId.value;
  if (id) {
    // Edit mode
    data.notes = shelfNotesInput.value.trim() || null;
    const [sortField, sortDirection] = shelfSortSelect.value.split(",");
    data.sortField = sortField;
    data.sortDirection = sortDirection;
    await updateShelf(id, data);
  } else {
    // Create mode
    await createShelf(data);
  }
  closeShelfModal();
}

// ---- Book Edit Modal Shelf Checkboxes ----

function populateBookShelfCheckboxes(book) {
  bookShelvesCheckboxes.innerHTML = "";
  if (allShelves.length === 0) {
    bookShelvesCheckboxes.innerHTML =
      '<span class="shelf-cb-empty">No shelves yet</span>';
    return;
  }
  const bookShelfIds = (book.shelves || []).map((s) => s.id);
  allShelves.forEach((shelf) => {
    const isOn = bookShelfIds.includes(shelf.id);
    const label = document.createElement("label");
    label.className = "shelf-cb-label";
    label.innerHTML = `<input type="checkbox" class="shelf-cb" data-shelf-id="${shelf.id}" data-book-id="${book.id}" ${isOn ? "checked" : ""}><span class="shelf-cb-dot" style="background:${shelf.color}"></span>${escapeHtml(shelf.name)}`;
    bookShelvesCheckboxes.appendChild(label);
  });

  // Add "new shelf" link
  const newLink = document.createElement("button");
  newLink.type = "button";
  newLink.className = "shelf-cb-new";
  newLink.textContent = "+ New shelf...";
  newLink.addEventListener("click", () => {
    closeEditModal();
    openCreateShelfModal();
  });
  bookShelvesCheckboxes.appendChild(newLink);

  // Handle checkbox changes
  bookShelvesCheckboxes.addEventListener("change", async (e) => {
    const cb = e.target.closest(".shelf-cb");
    if (!cb) return;
    const shelfId = cb.dataset.shelfId;
    const bookId = cb.dataset.bookId;
    if (cb.checked) {
      await addBookToShelf(shelfId, bookId);
    } else {
      await removeBookFromShelf(shelfId, bookId);
    }
  });
}

// ---- Drag and Drop ----

const prefersReducedMotion = window.matchMedia(
  "(prefers-reduced-motion: reduce)",
).matches;

function initBookDrag(cardEl, book) {
  cardEl.addEventListener("dragstart", (e) => {
    e.dataTransfer.setData("text/plain", book.id);
    e.dataTransfer.effectAllowed = "copy";
    if (!prefersReducedMotion) {
      cardEl.classList.add("dragging");
    }
  });
  cardEl.addEventListener("dragend", () => {
    cardEl.classList.remove("dragging");
  });
}

function initShelfDropTarget(cardEl, shelf) {
  cardEl.addEventListener("dragover", (e) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = "copy";
    cardEl.classList.add("drop-target");
  });
  cardEl.addEventListener("dragleave", () => {
    cardEl.classList.remove("drop-target");
  });
  cardEl.addEventListener("drop", async (e) => {
    e.preventDefault();
    cardEl.classList.remove("drop-target");
    const bookId = e.dataTransfer.getData("text/plain");
    if (!bookId) return;
    await addBookToShelf(shelf.id, bookId);
  });
}

// Shelf reorder via drag in sidebar
function initShelfReorderDrag(cardEl, shelf) {
  let draggedShelfId = null;

  cardEl.addEventListener("dragstart", (e) => {
    // Only reorder if dragging from sidebar (not a book card drag)
    if (e.target !== cardEl) return;
    draggedShelfId = shelf.id;
    e.dataTransfer.setData("application/x-shelf-id", shelf.id);
    e.dataTransfer.effectAllowed = "move";
    if (!prefersReducedMotion) {
      cardEl.classList.add("shelf-dragging");
    }
  });

  cardEl.addEventListener("dragover", (e) => {
    // Accept shelf reorder drops
    if (e.dataTransfer.types.includes("application/x-shelf-id")) {
      e.preventDefault();
      e.dataTransfer.dropEffect = "move";
      cardEl.classList.add("shelf-reorder-target");
    }
  });

  cardEl.addEventListener("dragleave", () => {
    cardEl.classList.remove("shelf-reorder-target");
  });

  cardEl.addEventListener("drop", (e) => {
    const srcShelfId = e.dataTransfer.getData("application/x-shelf-id");
    cardEl.classList.remove("shelf-reorder-target");
    if (!srcShelfId || srcShelfId === shelf.id) return;
    e.preventDefault();

    // Compute new order
    const currentIds = allShelves.map((s) => s.id);
    const srcIdx = currentIds.indexOf(srcShelfId);
    const destIdx = currentIds.indexOf(shelf.id);
    if (srcIdx === -1 || destIdx === -1) return;
    currentIds.splice(srcIdx, 1);
    currentIds.splice(destIdx, 0, srcShelfId);
    reorderShelves(currentIds);
  });

  cardEl.addEventListener("dragend", () => {
    cardEl.classList.remove("shelf-dragging");
  });
}

// ---- Event Listeners ----

// Scanner
scanBtn.addEventListener("click", () => openScanner());
bulkScanBtn.addEventListener("click", () => openScanner(true));
scannerDoneBtn.addEventListener("click", doneBulkScan);
scannerClose.addEventListener("click", closeScanner);
scannerModal.addEventListener("click", (e) => {
  if (e.target === scannerModal) closeScanner();
});
cameraSelect.addEventListener("change", () => {
  if (!isScanning && scannerModal.hidden) return;
  stopCurrentCamera();
  isScanning = true;
  clearScannerTimeouts();
  startScannerTimeouts();
  const viewfinder = document.getElementById("scanner-viewfinder");
  viewfinder.innerHTML = "";
  viewfinder.classList.add("scanning");
  startZbarScanner();
});

// Add book
addBtn.addEventListener("click", addBook);
isbnInput.addEventListener("keydown", (e) => {
  if (e.key === "Enter") {
    e.preventDefault();
    addBook();
  }
  // Clear error on typing
  if (isbnError.hidden === false) {
    isbnError.hidden = true;
  }
});

// Search bar — debounced to avoid re-rendering on every keystroke
let searchDebounceTimer;
searchInput.addEventListener("input", () => {
  clearTimeout(searchDebounceTimer);
  searchDebounceTimer = setTimeout(() => {
    searchQuery = searchInput.value.trim();
    currentPage = 1;
    renderFilteredBooks();
  }, 300);
});

// Filter tabs
filterTabs.forEach((tab) => {
  tab.addEventListener("click", () => setFilter(tab.dataset.status));
});

// Group by author toggle
groupToggle.addEventListener("click", () => {
  groupByAuthor = !groupByAuthor;
  groupToggle.classList.toggle("active", groupByAuthor);
  currentPage = 1;
  renderFilteredBooks();
});

// Sort select
sortSelect.addEventListener("change", () => {
  currentSort = sortSelect.value;
  currentPage = 1;
  renderFilteredBooks();
});

// Refresh Library
refreshBtn.addEventListener("click", async () => {
  refreshBtn.disabled = true;
  refreshBtn.textContent = "Refreshing...";
  try {
    const resp = await fetch(API + "/books/re-enrich", { method: "POST" });
    if (!resp.ok) throw new Error("Request failed");
    const data = await resp.json();
    const count = data.queued || 0;
    if (count === 0) {
      showToast("No books with ISBNs to refresh");
    } else {
      showToast(`Refreshing ${count} book${count > 1 ? "s" : ""}...`);

      // Show per-book "Refreshing: X..." toasts timed to match server processing (~3 s each)
      const booksToRefresh = allBooks
        .filter((b) => b.isbn)
        .sort((a, b) => (!a.title ? -1 : !b.title ? 1 : 0)); // null-title books first
      booksToRefresh.forEach((book, i) => {
        setTimeout(
          () => showToast(`Refreshing: ${book.title || book.isbn}...`, "info"),
          i * 3000,
        );
      });

      // Poll for updates without re-rendering the whole page
      const totalTime = Math.max(10000, count * 4000);
      let elapsed = 0;
      let updatedCount = 0;
      const pollInterval = setInterval(async () => {
        elapsed += 4000;
        try {
          const freshBooks = await apiGet("/books");
          for (const fresh of freshBooks) {
            const cached = allBooks.find((b) => b.id === fresh.id);
            if (!cached) continue;
            const changed =
              fresh.title !== cached.title ||
              fresh.author !== cached.author ||
              fresh.coverUrl !== cached.coverUrl ||
              fresh.publisher !== cached.publisher ||
              fresh.genre !== cached.genre;
            if (changed) {
              updateCardInPlace(fresh);
              updatedCount++;
              const name = fresh.title || fresh.isbn || "Book";
              showToast(`Updated: ${name}`);
            }
          }
          allBooks = freshBooks;
          updateCounts(freshBooks);
        } catch (e) {
          /* ignore polling errors */
        }
        if (elapsed >= totalTime) {
          clearInterval(pollInterval);
          showToast(
            `Refresh complete — ${updatedCount} book${updatedCount !== 1 ? "s" : ""} updated`,
          );
        }
      }, 4000);
    }
  } catch (e) {
    showToast("Failed to refresh library", "error");
  } finally {
    // Re-enable after a short delay to prevent double-clicks
    setTimeout(() => {
      refreshBtn.disabled = false;
      refreshBtn.textContent = "Refresh Library";
    }, 5000);
  }
});

// Book grid event delegation
bookGrid.addEventListener("click", (e) => {
  const editBtn = e.target.closest(".btn-edit");
  if (editBtn) {
    openEditModal(editBtn.dataset.id);
    return;
  }

  const deleteBtn = e.target.closest(".btn-delete");
  if (deleteBtn) {
    deleteBook(deleteBtn.dataset.id);
    return;
  }

  const reviewToggle = e.target.closest(".review-toggle");
  if (reviewToggle) {
    const reviewDiv = document.getElementById(`review-${reviewToggle.dataset.id}`);
    if (reviewDiv) {
      reviewDiv.hidden = !reviewDiv.hidden;
      reviewToggle.classList.toggle("expanded", !reviewDiv.hidden);
    }
    return;
  }

  const starBtn = e.target.closest(".book-stars .star");
  if (starBtn) {
    const rating = getStarRatingFromEvent(e);
    if (rating !== null) handleStarClick(starBtn.dataset.book, rating);
    return;
  }

  const subjectTag = e.target.closest(".subject-tag");
  if (subjectTag) {
    const subject = subjectTag.dataset.subject;
    if (
      currentSubjectFilter &&
      currentSubjectFilter.toLowerCase() === subject.toLowerCase()
    ) {
      currentSubjectFilter = "";
    } else {
      currentSubjectFilter = subject;
    }
    currentPage = 1;
    renderFilteredBooks();
    return;
  }
});

// Modal
modalClose.addEventListener("click", closeEditModal);
modalCancel.addEventListener("click", closeEditModal);
editModal.addEventListener("click", (e) => {
  if (e.target === editModal) closeEditModal();
});
editForm.addEventListener("submit", submitEdit);

// Show/hide progress group based on reading status
editStatus.addEventListener("change", () => {
  progressGroup.hidden =
    editStatus.value !== "READING" && editStatus.value !== "DNF";
});

// Star picker in modal
editRating.addEventListener("click", (e) => {
  const rating = getStarRatingFromEvent(e);
  if (rating === null) return;

  editRating.dataset.currentValue = rating;
  editRating.querySelectorAll(".star").forEach((s) => {
    const sv = parseInt(s.dataset.value);
    const isFull = rating >= sv;
    const isHalf = !isFull && rating >= sv - 0.5;
    s.classList.toggle("filled", isFull);
    s.classList.toggle("half-filled", isHalf);
  });
});

// Escape key closes modals
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape") {
    if (!shelfModal.hidden) closeShelfModal();
    else if (!scannerModal.hidden) closeScanner();
    else if (!editModal.hidden) closeEditModal();
  }
});

// Clear subject tag filter
document.getElementById("clear-tag-filter").addEventListener("click", () => {
  currentSubjectFilter = "";
  currentPage = 1;
  renderFilteredBooks();
});

// ---- Shelf Event Listeners ----

sidebarAllBooks.addEventListener("click", clearShelfFilter);
newShelfBtn.addEventListener("click", openCreateShelfModal);
sidebarToggle.addEventListener("click", toggleSidebar);

shelfForm.addEventListener("submit", submitShelfForm);
shelfModalClose.addEventListener("click", closeShelfModal);
shelfModalCancel.addEventListener("click", closeShelfModal);
shelfModal.addEventListener("click", (e) => {
  if (e.target === shelfModal) closeShelfModal();
});
shelfDeleteBtn.addEventListener("click", () => {
  const id = shelfEditId.value;
  if (id) {
    closeShelfModal();
    deleteShelf(id);
  }
});

// Shelf tag click in book grid — navigate to that shelf
bookGrid.addEventListener("click", (e) => {
  const shelfTag = e.target.closest(".shelf-tag");
  if (shelfTag) {
    const shelfId = shelfTag.dataset.shelfId;
    if (shelfId) selectShelf(shelfId);
    return;
  }
});

// ---- Reading Goals ----

let currentGoal = null;

async function loadCurrentGoal() {
  const year = new Date().getFullYear();
  try {
    const res = await fetch(`${API}/goals/${year}`);
    if (res.ok) {
      currentGoal = await res.json();
      renderGoalWidget(currentGoal);
    } else {
      currentGoal = null;
      document.getElementById("goal-widget").hidden = true;
      const prompt = document.getElementById("goal-prompt");
      prompt.hidden = false;
      document.getElementById("goal-prompt-year").textContent = year;
    }
  } catch {
    currentGoal = null;
  }
}

function renderGoalWidget(goal) {
  const widget = document.getElementById("goal-widget");
  const prompt = document.getElementById("goal-prompt");
  widget.hidden = false;
  prompt.hidden = true;
  document.getElementById("goal-label").textContent = `${goal.year} Reading Goal`;
  document.getElementById("goal-fill").style.width = goal.percentage + "%";
  document.getElementById("goal-count").textContent = `${goal.progress} / ${goal.target} books`;
  const paceEl = document.getElementById("goal-pace");
  if (goal.onPace) {
    const delta = goal.paceDelta;
    paceEl.textContent = delta > 0 ? `${delta} ahead` : "On pace";
    paceEl.className = "goal-pace on-pace";
  } else {
    paceEl.textContent = `${Math.abs(goal.paceDelta)} behind`;
    paceEl.className = "goal-pace behind";
  }
}

function openGoalModal(isEdit) {
  const year = new Date().getFullYear();
  document.getElementById("goal-year").value = isEdit && currentGoal ? currentGoal.year : year;
  document.getElementById("goal-target").value = isEdit && currentGoal ? currentGoal.target : "";
  document.getElementById("goal-year").disabled = isEdit;
  document.getElementById("goal-delete-btn").hidden = !isEdit;
  document.getElementById("goal-modal-title").textContent = isEdit ? "Edit Goal" : "Set Reading Goal";
  document.getElementById("goal-modal").hidden = false;
}

function closeGoalModal() {
  document.getElementById("goal-modal").hidden = true;
}

document.getElementById("goal-edit-btn").addEventListener("click", () => openGoalModal(true));
document.getElementById("goal-set-btn").addEventListener("click", () => openGoalModal(false));
document.getElementById("goal-close").addEventListener("click", closeGoalModal);
document.getElementById("goal-cancel").addEventListener("click", closeGoalModal);

document.getElementById("goal-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const year = parseInt(document.getElementById("goal-year").value);
  const target = parseInt(document.getElementById("goal-target").value);
  if (!year || !target || target < 1) {
    showToast("Please enter a valid target", "error");
    return;
  }
  try {
    const isEdit = currentGoal && currentGoal.year === year;
    const url = isEdit ? `${API}/goals/${year}` : `${API}/goals`;
    const method = isEdit ? "PUT" : "POST";
    const body = isEdit ? { target } : { year, target };
    const res = await fetch(url, {
      method,
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    if (!res.ok) {
      const err = await res.text();
      showToast(err || "Failed to save goal", "error");
      return;
    }
    closeGoalModal();
    showToast(isEdit ? "Goal updated" : "Goal set!");
    await loadCurrentGoal();
  } catch {
    showToast("Failed to save goal", "error");
  }
});

document.getElementById("goal-delete-btn").addEventListener("click", async () => {
  if (!currentGoal) return;
  if (!confirm("Delete this reading goal?")) return;
  try {
    await fetch(`${API}/goals/${currentGoal.year}`, { method: "DELETE" });
    closeGoalModal();
    showToast("Goal deleted");
    await loadCurrentGoal();
  } catch {
    showToast("Failed to delete goal", "error");
  }
});

// ---- Pagination Buttons ----
document.getElementById("prev-page")?.addEventListener("click", () => {
  if (currentPage > 1) {
    currentPage--;
    renderFilteredBooks();
  }
});
document.getElementById("next-page")?.addEventListener("click", () => {
  if (currentPage < totalPages) {
    currentPage++;
    renderFilteredBooks();
  }
});

// ---- Init ----
restoreSidebarState();
loadBooks();
loadCurrentGoal();
