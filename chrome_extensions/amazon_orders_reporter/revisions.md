# Amazon Full-Year Orders Exporter — Revisions Log (`revisions.md`)

This document tracks all feature additions and bug fixes applied to the **Amazon Full-Year Orders Report Exporter** Chrome Extension in **reverse chronological order** (newest first).

---

## Revision 6 — Interactive Item Formatted JSON & Keywords Popup Modal
- **Timestamp**: `2026-11-07 11:06:55 AM EST`
- **Agent**: `antigravity-remote`
- **Model**: `4 Argon Medium`
- **Request Summary**:
  > - *"Feature: link popup for formatted json for each item so that user can see what details / keywords the tool has to work with."*
- **Changes Applied**:
  - **Keyword Extraction & Categorization Diagnostics (`report-builder.js`)**:
    - Added `extractKeywordsFromText(title, metadataLines)` to tokenize and deduplicate meaningful keywords from each item's product title (`titleKeywords`), secondary DOM lines (`metadataKeywords`), and combined text (`allExtractedKeywords`).
    - Enhanced `categorizeProduct(title, metadataLines, customRules)` and the embedded item data with `orderContext` and `toolKeywordAnalysis` (`inspectedText`, `titleKeywords`, `metadataKeywords`, `allExtractedKeywords`, `matchedKeywords`, `classificationSource`, `confidence`, `ruleScore`, and `candidateCategoryMatches`).
  - **Per-Item `{ } Item JSON & Keywords` Trigger Link (`report-builder.js`)**:
    - Added a `{ } Item JSON & Keywords` link (`.js-view-item-json`) on every item row in both the **Categorized Thumbnail Table** and the **Order Cards View**.
  - **Formatted JSON & Keyword Inspector Popup Modal (`#item-json-modal-backdrop` in `report-builder.js`)**:
    - Clicking `{ } Item JSON & Keywords` opens a modal popup displaying:
      1. **Classification Source & Confidence** badge (`ruleScore` and confidence level).
      2. **Matched Rule Keywords** (green pills showing which taxonomy/custom keywords matched, or an explicit notice when `UNCATEGORIZED`).
      3. **Available Item Keywords** (clickable pills for every token extracted from the item's title & metadata — clicking any keyword pill immediately filters the report by that keyword).
      4. **Syntax-Highlighted Formatted JSON** (`#item-json-modal-code`) showing the complete per-item JSON structure (live-updated if the user customizes the item's High-Level Category or Sub-Category) with a **`📋 Copy Formatted JSON`** button (`#copy-item-json-btn`).

---

## Revision 5 — Sort by Cost, Item & Cost Search/Filter Controls & Category/Sub-Category Totals Tab
- **Timestamp**: `2026-11-07 10:56:34 AM EST`
- **Agent**: `antigravity-remote`
- **Model**: `4 Argon Medium`
- **Request Summary**:
  > - *"Feature: Add sorting by cost."*
  > - *"Feature: Add a seart/filter field for items and cost."*
  > - *"Feature: Report tab of totals for categories and sub categories."*
- **Changes Applied**:
  - **Sorting by Cost (`report-builder.js`)**:
    - Added `Cost (High → Low ↓)` and `Cost (Low → High ↑)` sort buttons to the Sort toolbar (`.sort-custom-bar`) and an interactive `Cost ⇅` sort button (`#th-sort-cost`) in the `Cost` table column header.
    - Updated `applySort(field, dir)` to sort table rows numerically by item/order cost (`data-item-cost-num` / `data-cost-num`) and order cards numerically by total order cost (`data-order-total-num`).
  - **Search / Filter Fields for Items and Cost (`report-builder.js`)**:
    - Enhanced `.yo-search-bar` with an Item/Keyword search field (`#order-search-input`), a Cost expression filter (`#cost-filter-input` supporting `>50`, `>=100`, `<25`, `20-100`, `49.99`), `Min $` (`#cost-min-input`) and `Max $` (`#cost-max-input`) range inputs, an active Sub-Category filter pill (`#active-subcat-pill`), and a `✕ Clear` button (`#clear-filters-btn`).
    - Added live updates to `#visible-order-count`, `#visible-item-count`, and `#visible-spend-summary` showing the exact filtered item/order counts and filtered dollar total.
  - **Report Tab of Totals for Categories and Sub-Categories (`report-builder.js`)**:
    - Added the `📊 Category & Sub-Category Totals` tab (`data-view="totals"`, `#category-totals-section`) displaying a full-width hierarchical table (`#category-totals-table`) of all High-Level Categories and their indented Sub-Categories with Orders count, Items count, Total Cost, `% of Category`, `% of Report Spend` (with visual progress bar), `Filter Category →` / `Filter Sub-Cat →` quick-filter buttons, and a Grand Total footer.
    - Wired `recalculateAndRenderCategoryBreakdown()` and `renderCategoryTotalsTabClient()` so any category or sub-category change dynamically updates both the Category & Sub-Category Totals tab and `reportSummary.subCategoryBreakdown` in the embedded JSON.

---

## Revision 4 — Live Category Total Updates, Alphabetical Category Dropdowns, Full-Width Resizable Layout & Remembered Sub-Category Dropdowns
- **Timestamp**: `2026-11-07 10:25:03 AM EST`
- **Agent**: `antigravity-remote`
- **Model**: `4 Argon Medium`
- **Request Summary**:
  > - *"Bug: Total cost across categories don't update when categories are updated."*
  > - *"Bug: Category override selection drop down should be sorted alphabetically."*
  > - *"Bug: Cards and tables should use the full width of the resizable browser page. It's currently cutting off the right side and the horizontal scroll is often not within view."*
  > - *"Feature: Sub categories should also be drop down and should be remembered as part of the categore for subsequent use."*
- **Changes Applied**:
  - **Live Category Spend & Count Recalculation (`report-builder.js`)**: Added `computeOrderItemCostShares(order)` and `data-item-cost-num` on each item row paired with `recalculateAndRenderCategoryBreakdown()` so changing any item's category immediately recalculates and updates total dollar spend, item counts, and order counts in the category filter chips and embedded JSON.
  - **Alphabetical Dropdown Sorting (`report-builder.js`)**: Sorted `DEFAULT_HIGH_CATEGORIES` and all rendered `<select>` options (`js-high-cat-select` and `js-fine-cat-select`) alphabetically (`A–Z`) via `sortAlpha()`, keeping `+ Custom Category...` and `+ Custom Sub-Category...` at the bottom.
  - **Full-Width Resizable Layout (`report-builder.js`)**: Updated `.yo-container`, `.report-table-wrap`, `.report-table`, and `.order-card` to `width: 100%; max-width: 100%;` with proportional column widths totaling `100%` and a synchronized top horizontal scrollbar (`#table-top-scroll`) for narrow viewports.
  - **Sub-Category Dropdowns Remembered per Category (`report-builder.js`, `content.js`)**: Replaced static finer-grain badges with alphabetical `<select class="cat-select-fine js-fine-cat-select">` dropdowns in both the table and order cards, backed by a persistent `subCategoriesByHigh` dictionary (`{ [highCategory]: string[] }`) saved in `localStorage`, `#amazon-orders-data`, and `chrome.storage.local`.

---

## Revision 3 — Sortable First Column (Order Placed / Delivery Date), Custom Categories, Holiday/Sports/Grocery/Uncategorized Rules & Persistent Cache
- **Timestamp**: `2026-11-07 09:50:09 AM EST`
- **Agent**: `antigravity-remote`
- **Model**: `4 Argon Medium`
- **Request Summary**:
  > - *"Feature: Sort by Order place or by Delivery date. Make that the first column. Allow categories to customizable. Large sets of purchase at the end of Nov and early Dec should be categorized as Christmas presents. Sports related purchases should be in a Sports & Fitness category. Any food item is a grocery item. Anything that cannot be categorized with confidence should be in UNCATEGORIZED. Extension should cache results so that categorizations for prior reports that are rerun should persist."*
- **Changes Applied**:
  - **First Column & Date Sorting (`report-builder.js`)**: Moved **Order Placed & Delivery Date** to Column 1 of the report table and added interactive sorting by either **Order Placed Date** (Newest/Oldest) or **Delivery Date** (Newest/Oldest).
  - **Categorization Rules & Confidence Threshold (`report-builder.js`)**:
    - Any food, beverage, snack, produce, or pantry item is categorized under **`Grocery`**.
    - All sports, workout, gym, and athletic equipment is categorized under **`Sports & Fitness`**.
    - Large sets of purchases (`>= 3` items or orders) placed between **Nov 18 and Dec 18** (end of Nov / early Dec) are automatically categorized as **`Christmas Presents`** via `applyHolidayGiftCategorization()`.
    - Word-boundary matching (`hasKeywordBoundaryMatch`) and confidence scoring ensure any item that cannot be categorized with confidence defaults to **`UNCATEGORIZED`**.
  - **Customizable Categories & Persistent Extension Cache (`report-builder.js`, `content.js`, `popup.js`)**:
    - Added interactive category override dropdowns on every item in the HTML report, plus `+ Add Custom Category` and `💾 Save Updated HTML Report`.
    - Added persistent storage in `chrome.storage.local` and `localStorage` caching item categorizations by Order+Item key, ASIN, and normalized title, as well as caching full order sets per year for instant `⚡ Rebuild from Cache`.

---

## Revision 2 — Time Period Dropdown Sync, Multi-Page Live Crawler & Two-Tier Product Categorization
- **Timestamp**: `2026-11-07 09:14:42 AM EST`
- **Agent**: `antigravity-remote`
- **Model**: `4 Argon Medium`
- **Request Summary**:
  > - *"Bug: Report time period drop down does not drive actual page time period drop down and generates nothing if not manually selected."*
  > - *"Bug: Report does not show results from any page beyond the first."*
  > - *"Feature: Change report to show order number, items with image thumbnails, order placed and delivery date, cost, and a high level product category such as clothing, beauty, grocery, computing as well as finer grain category."*
- **Changes Applied**:
  - **Time Filter Synchronization (`content.js`)**: Connected the extension's time-period dropdown (`#amz-exp-year-select` and popup selector) directly to Amazon's page dropdown (`select[name="timeFilter"]` / `#time-filter`) via `drivePageTimeFilter()`, automatically submitting/navigating to Page 1 of the chosen year before starting extraction.
  - **Multi-Page Live-DOM Crawler (`content.js`)**: Replaced background `fetch()` scraping (which missed client-rendered order cards on pages 2+) with a stateful `chrome.storage.local` Live-DOM pagination crawler that steps through `Page 1 -> Page 2 -> ... -> Last Page` (`?timeFilter=year-YYYY&page=N`), scrolls cards to trigger lazy-loaded product thumbnails, and aggregates all orders across all pages.
  - **Itemized Report Columns & Two-Tier Categorization (`report-builder.js`)**: Added a structured Itemized Order Table displaying Order Number, Items with Image Thumbnails, Order Placed & Delivery Date, Cost, High-Level Product Category (`Clothing`, `Beauty`, `Grocery`, `Computing`, `Electronics`, etc.), and Finer-Grain Sub-Category.

---

## Revision 1 — Initial Full-Year Amazon Orders Report Chrome Extension
- **Timestamp**: `2026-11-07 01:39:35 AM EST`
- **Agent**: `antigravity-remote`
- **Model**: `4 Argon Medium`
- **Request Summary**:
  > - *"Chrome extension that will download all of the pages of Amazon orders for a given year for the account that's logged in so that both I and an agent can ingest and review, with the same layout but in a single html report with the same details."*
- **Changes Applied**:
  - Created Manifest V3 Chrome Extension (`manifest.json`, `report-builder.js`, `content.js`, `popup.html`, `popup.js`, `background.js`).
  - Built an in-page Amazon "Your Orders" export banner and extension popup UI to select a target year/period and export all orders into a single self-contained HTML file.
  - Preserved Amazon's visual "Your Orders" card layout while embedding a machine-ingestible `<script type="application/json" id="amazon-orders-data">` payload at the top of the HTML report for AI agent ingestion.
  - Added optional Base64 product thumbnail image embedding for offline viewing.
