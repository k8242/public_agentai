# Amazon Orders Year Report Exporter (Chrome Extension)

A Manifest V3 Chrome Extension that downloads **all pages** of your logged-in Amazon account's orders for any selected year and combines them into a **single self-contained HTML report** with:

1. **Exact Amazon "Your Orders" Visual Layout & Details**:
   - Top Amazon header, `Your Orders` search bar, tabs (`Orders`, `Subscribe & Save`, `AI Agent / Table View`), order count & total spend summary.
   - Every order card rendered with its gray metadata header (`ORDER PLACED`, `TOTAL`, `SHIP TO`, `ORDER #`, `View order details`, `View invoice`), product thumbnail images (embedded as Base64 for offline viewing), full product titles & links, return window dates (`Return window closed on ...`), subscription frequency (`Auto-delivered: Every ...`), `Buy it again` yellow pill buttons, and right-hand action buttons (`Get product support`, `View your Subscribe & Save`, `Write a product review`, etc.).
2. **AI Agent Ingestion Ready**:
   - Embeds a normalized `<script type="application/json" id="amazon-orders-data">` block right at the top of the `.html` file containing the structured JSON dataset for all orders, items, ASINs, totals, dates, return windows, and URLs.
   - Annotates every `<article class="order-card">` with semantic `data-order-id`, `data-order-date`, `data-order-iso-date`, `data-order-total`, `data-order-total-num`, `data-ship-to`, and `data-asin` attributes, stripped of Amazon's tracking scripts and hidden popover bloat.

---

## Installation (Load Unpacked in Chrome)

1. Open Chrome and navigate to `chrome://extensions/`.
2. Enable **Developer mode** (toggle in the top-right corner).
3. Click **Load unpacked** (top-left button).
4. Select the `/home/antigravity/amazon-orders-exporter` directory (or unzip `amazon-orders-exporter.zip` and select the extracted folder).

---

## Usage

1. Log into Amazon and go to **Returns & Orders** (`https://www.amazon.com/your-orders/orders`).
2. Use **either**:
   - The **in-page Exporter Banner** automatically injected at the top of your `Your Orders` list, **or**
   - The **Extension Icon** in your Chrome toolbar.
3. Choose the **Year / Period** (e.g., `2026`), toggle **Embed images (Offline)** or **Deep invoice details** if desired, and click **⬇ Download Single HTML Report** (or **↗ Open in New Tab**).
4. The extension will automatically start from **Page 1** of that year, follow pagination through the final page using your active browser session, and download `amazon-orders-<year>-report.html`.
