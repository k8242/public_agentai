/**
 * content.js
 * Content script injected into Amazon "Your Orders" pages.
 *
 * Key capabilities:
 * 1. Syncs with and drives the actual Amazon page's time period dropdown when changed.
 * 2. Uses a persistent Live-DOM Multi-Page Crawler (backed by chrome.storage.local)
 *    that steps through Page 1 -> Page 2 -> ... -> Last Page in the live browser tab,
 *    ensuring client-rendered order cards and lazy-loaded thumbnails on every page
 *    are captured accurately before generating the single HTML report.
 * 3. Caches order results and item categorizations (including custom categories,
 *    custom keyword rules, and user overrides) in chrome.storage.local
 *    (key: amzCategorizationCache_v2) so prior reports that are rerun persist
 *    their categorizations and can also be rebuilt instantaneously from cache.
 */

(function () {
  'use strict';

  if (window.__amazonOrdersExporterLoaded) return;
  window.__amazonOrdersExporterLoaded = true;

  const Builder = window.AmazonOrdersReportBuilder;
  if (!Builder) {
    console.error('[Amazon Orders Exporter] report-builder.js not found.');
    return;
  }

  const STORAGE_KEY = 'amzActiveExportJob_v1';
  const CACHE_KEY = 'amzCategorizationCache_v2';

  const uiState = {
    isRunning: false,
    abortRequested: false,
    progressPercent: 0,
    statusText: 'Ready to export all pages for the selected time period.',
    pagesFetched: 0,
    maxPages: 1,
    ordersCollected: 0,
    totalExpectedOrders: null
  };

  function sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }

  function getStorageJob() {
    return new Promise((resolve) => {
      try {
        chrome.storage.local.get([STORAGE_KEY], (res) => {
          resolve((res && res[STORAGE_KEY]) || null);
        });
      } catch (_) {
        resolve(null);
      }
    });
  }

  function setStorageJob(job) {
    return new Promise((resolve) => {
      try {
        chrome.storage.local.set({ [STORAGE_KEY]: job }, () => resolve());
      } catch (_) {
        resolve();
      }
    });
  }

  function clearStorageJob() {
    return new Promise((resolve) => {
      try {
        chrome.storage.local.remove([STORAGE_KEY], () => resolve());
      } catch (_) {
        resolve();
      }
    });
  }

  function getCategoryCache() {
    return new Promise((resolve) => {
      const fallback = {
        itemCategories: {},
        customHighCategories: [],
        subCategoriesByHigh: {},
        customRules: [],
        cachedOrdersByFilter: {}
      };
      try {
        chrome.storage.local.get([CACHE_KEY], (res) => {
          const data = (res && res[CACHE_KEY]) || fallback;
          if (!data.itemCategories) data.itemCategories = {};
          if (!Array.isArray(data.customHighCategories)) data.customHighCategories = [];
          if (!data.subCategoriesByHigh || typeof data.subCategoriesByHigh !== 'object') {
            data.subCategoriesByHigh = {};
          }
          if (!Array.isArray(data.customRules)) data.customRules = [];
          if (!data.cachedOrdersByFilter) data.cachedOrdersByFilter = {};
          resolve(data);
        });
      } catch (_) {
        resolve(fallback);
      }
    });
  }

  function setCategoryCache(cacheData) {
    return new Promise((resolve) => {
      try {
        chrome.storage.local.set({ [CACHE_KEY]: cacheData }, () => resolve());
      } catch (_) {
        resolve();
      }
    });
  }

  function broadcastProgress() {
    updateBannerUI();
    try {
      chrome.runtime.sendMessage({
        type: 'EXPORT_PROGRESS',
        payload: { ...uiState }
      });
    } catch (_) {}
  }

  /**
   * Determines the base order history path for the current Amazon domain
   */
  function getOrdersBasePath() {
    const p = window.location.pathname;
    if (p.includes('/your-orders')) return '/your-orders/orders';
    if (p.includes('/gp/your-account/order-history')) return '/gp/your-account/order-history';
    if (p.includes('/gp/css/order-history')) return '/gp/css/order-history';
    return '/your-orders/orders';
  }

  /**
   * Builds the URL for a specific 1-indexed page number of a given timeFilter.
   * Note: On Amazon's /your-orders/orders, `page` is 0-indexed:
   * - Page 1: no `page` param (or page=0)
   * - Page 2: page=1
   * - Page 7: page=6
   */
  function buildPageUrl(timeFilter, pageNumberOneIndexed = 1) {
    const basePath = getOrdersBasePath();
    const url = new URL(basePath, window.location.origin);
    if (timeFilter) {
      url.searchParams.set('timeFilter', timeFilter);
    }
    if (pageNumberOneIndexed > 1) {
      if (basePath.includes('/your-orders')) {
        url.searchParams.set('page', String(pageNumberOneIndexed - 1));
      } else {
        url.searchParams.set('startIndex', String((pageNumberOneIndexed - 1) * 10));
      }
    }
    return url.href;
  }

  /**
   * Drives the actual Amazon page's time period dropdown to the specified timeFilter
   * and navigates the page so the live DOM displays Page 1 of that time period.
   */
  function drivePageTimeFilter(timeFilter) {
    if (!timeFilter) return;

    const bannerSelect = document.getElementById('amz-exp-year-select');
    if (bannerSelect && bannerSelect.value !== timeFilter) {
      bannerSelect.value = timeFilter;
    }

    const pageSelect = Builder.findPageTimeFilterSelect(document);
    if (pageSelect) {
      if (pageSelect.value !== timeFilter) {
        pageSelect.value = timeFilter;
      }
      const matchedOpt = Array.from(pageSelect.options || []).find((o) => o.value === timeFilter);
      const promptEl = document.querySelector(
        '.time-filter-section .a-dropdown-prompt, #time-filter + .a-button .a-dropdown-prompt, .a-dropdown-container .a-dropdown-prompt'
      );
      if (promptEl && matchedOpt) {
        promptEl.textContent = Builder.cleanText(matchedOpt.textContent);
      }

      pageSelect.dispatchEvent(new Event('input', { bubbles: true }));
      pageSelect.dispatchEvent(new Event('change', { bubbles: true }));

      const form = pageSelect.closest('form');
      if (form && typeof form.submit === 'function') {
        try {
          form.submit();
          return;
        } catch (_) {}
      }
    }

    const targetUrl = buildPageUrl(timeFilter, 1);
    if (window.location.href !== targetUrl) {
      window.location.href = targetUrl;
    }
  }

  /**
   * Checks if the current live browser tab is already viewing Page 1 of the target timeFilter
   */
  function isCurrentDocumentPageOneOf(targetTimeFilter) {
    const { activeFilter } = Builder.extractTimeFilters(document, window.location.href);
    if (targetTimeFilter && activeFilter && activeFilter !== targetTimeFilter) {
      return false;
    }

    const url = new URL(window.location.href);
    const urlTimeFilter = url.searchParams.get('timeFilter') || url.searchParams.get('orderFilter');
    if (urlTimeFilter && targetTimeFilter && urlTimeFilter !== targetTimeFilter) {
      return false;
    }

    const pageParam = url.searchParams.get('page');
    const startIdx = url.searchParams.get('startIndex');
    if (startIdx && startIdx !== '0') return false;
    if (pageParam && pageParam !== '0') return false;

    const selectedPageEl = document.querySelector('.a-pagination li.a-selected');
    if (selectedPageEl) {
      const num = Builder.cleanText(selectedPageEl.textContent);
      if (num && num !== '1') return false;
    }
    return true;
  }

  /**
   * Waits until the live DOM has rendered the order cards on the current page
   * (or until we confirm there are 0 orders for this period).
   */
  async function waitForLiveOrderCards(timeoutMs = 10000) {
    const start = Date.now();
    while (Date.now() - start < timeoutMs) {
      const cards = Builder.findOrderCardElements(document);
      if (cards.length > 0) {
        return cards;
      }
      const totalCount = Builder.extractTotalOrdersCount(document);
      if (totalCount === 0) {
        return [];
      }
      await sleep(220);
    }
    return Builder.findOrderCardElements(document);
  }

  /**
   * Scrolls through the current page's order cards and pagination bar so that
   * lazy-loaded product thumbnail <img> tags and pagination controls finish rendering.
   */
  async function triggerLazyImagesAndPaginationOnPage() {
    const cards = Builder.findOrderCardElements(document);
    for (let i = 0; i < cards.length; i += 2) {
      try {
        cards[i].scrollIntoView({ behavior: 'instant', block: 'center' });
      } catch (_) {}
      await sleep(50);
    }
    const paginationEl = document.querySelector('.a-pagination, ul.a-pagination, nav[aria-label*="pagination" i]');
    if (paginationEl) {
      try {
        paginationEl.scrollIntoView({ behavior: 'instant', block: 'center' });
      } catch (_) {}
    } else {
      window.scrollTo(0, document.body.scrollHeight);
    }
    await sleep(300);
    window.scrollTo(0, 0);
  }

  /**
   * Converts a remote product thumbnail URL to a Base64 data URL
   */
  async function fetchImageAsDataUrl(imgUrl) {
    if (!imgUrl || imgUrl.startsWith('data:')) return imgUrl;
    try {
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 5000);
      const res = await fetch(imgUrl, { signal: controller.signal });
      clearTimeout(timeoutId);
      if (!res.ok) return imgUrl;
      const blob = await res.blob();
      return await new Promise((resolve) => {
        const reader = new FileReader();
        reader.onloadend = () => resolve(reader.result || imgUrl);
        reader.onerror = () => resolve(imgUrl);
        reader.readAsDataURL(blob);
      });
    } catch (_) {
      return imgUrl;
    }
  }

  /**
   * Embeds Base64 thumbnails for the orders extracted on the current page
   */
  async function embedImagesForPageOrders(pageOrders) {
    const items = [];
    for (const o of pageOrders) {
      for (const s of o.shipments || []) {
        for (const it of s.items || []) {
          if (it.imageUrl && !it.imageDataUrl) items.push(it);
        }
      }
      for (const it of o.items || []) {
        if (it.imageUrl && !it.imageDataUrl) items.push(it);
      }
    }
    if (items.length === 0) return;

    const cache = new Map();
    await Promise.all(
      items.map(async (it) => {
        if (cache.has(it.imageUrl)) {
          it.imageDataUrl = cache.get(it.imageUrl);
        } else {
          const dataUrl = await fetchImageAsDataUrl(it.imageUrl);
          cache.set(it.imageUrl, dataUrl);
          it.imageDataUrl = dataUrl;
        }
      })
    );
  }

  /**
   * Starts a new full-year multi-page export job
   */
  async function startExportJob(options = {}) {
    if (uiState.isRunning) return;

    const pageFilters = Builder.extractTimeFilters(document, window.location.href);
    const bannerSelect = document.getElementById('amz-exp-year-select');
    const selectedFromBanner = bannerSelect ? bannerSelect.value : '';

    const timeFilter =
      options.timeFilter ||
      selectedFromBanner ||
      pageFilters.activeFilter ||
      `year-${new Date().getFullYear()}`;

    const matchedOpt = pageFilters.options.find((o) => o.value === timeFilter);
    const yearLabel = matchedOpt ? matchedOpt.label : timeFilter.replace(/^year-/, '');
    const embedImages = options.embedImages !== undefined ? Boolean(options.embedImages) : true;
    const actionMode = options.actionMode || 'download';

    const job = {
      active: true,
      timeFilter,
      yearLabel,
      embedImages,
      actionMode,
      currentPageNum: 1,
      pagesFetched: 0,
      maxPages: 1,
      totalExpectedOrders: null,
      accountName: Builder.extractAccountName(document) || '',
      orders: [],
      seenOrderKeys: {},
      visitedPageSignatures: {},
      startedAt: Date.now()
    };

    await setStorageJob(job);

    uiState.isRunning = true;
    uiState.abortRequested = false;
    uiState.progressPercent = 4;
    uiState.pagesFetched = 0;
    uiState.ordersCollected = 0;
    uiState.statusText = `Preparing export for ${yearLabel}...`;
    broadcastProgress();

    // If the live page is not currently on Page 1 of the chosen timeFilter, drive the page there first.
    if (!isCurrentDocumentPageOneOf(timeFilter)) {
      uiState.statusText = `Switching page to ${yearLabel} (Page 1)...`;
      broadcastProgress();
      drivePageTimeFilter(timeFilter);
      return;
    }

    await processCurrentLivePageForActiveJob();
  }

  /**
   * Rebuilds and delivers a report immediately from cached orders for the selected timeFilter,
   * applying all persistent categorizations, custom rules, and user overrides.
   */
  async function rebuildReportFromCache(options = {}) {
    const pageFilters = Builder.extractTimeFilters(document, window.location.href);
    const bannerSelect = document.getElementById('amz-exp-year-select');
    const timeFilter =
      options.timeFilter ||
      (bannerSelect && bannerSelect.value) ||
      pageFilters.activeFilter ||
      `year-${new Date().getFullYear()}`;

    const cacheData = await getCategoryCache();
    const cachedEntry = cacheData.cachedOrdersByFilter && cacheData.cachedOrdersByFilter[timeFilter];
    if (!cachedEntry || !Array.isArray(cachedEntry.orders) || cachedEntry.orders.length === 0) {
      uiState.statusText = `No cached orders found for ${timeFilter}. Starting full multi-page export...`;
      broadcastProgress();
      await startExportJob({ ...options, timeFilter });
      return;
    }

    await finalizeAndDeliverReport({
      timeFilter,
      yearLabel: cachedEntry.yearLabel || timeFilter.replace(/^year-/, ''),
      accountName: cachedEntry.accountName || Builder.extractAccountName(document) || 'Customer',
      orders: cachedEntry.orders,
      pagesFetched: cachedEntry.pagesFetched || 1,
      totalExpectedOrders: cachedEntry.totalExpectedOrders || cachedEntry.orders.length,
      actionMode: options.actionMode || 'download'
    });
  }

  /**
   * Processes the current live DOM page for an active multi-page export job,
   * persists extracted orders into chrome.storage.local, and either navigates
   * to the next page or finalizes the single HTML report.
   */
  async function processCurrentLivePageForActiveJob() {
    const job = await getStorageJob();
    if (!job || !job.active) return;

    uiState.isRunning = true;
    uiState.pagesFetched = job.pagesFetched || 0;
    uiState.maxPages = job.maxPages || 1;
    uiState.ordersCollected = (job.orders && job.orders.length) || 0;
    uiState.totalExpectedOrders = job.totalExpectedOrders;
    uiState.statusText = `Waiting for Page ${job.currentPageNum} orders to render...`;
    broadcastProgress();

    try {
      await waitForLiveOrderCards(10000);
      await triggerLazyImagesAndPaginationOnPage();

      const cacheData = await getCategoryCache();
      const parsedPage = Builder.parseOrdersPage(
        document,
        window.location.href,
        job.currentPageNum,
        cacheData.customRules || []
      );

      if (parsedPage.isSignInPage) {
        await clearStorageJob();
        uiState.isRunning = false;
        uiState.statusText = '⚠ Amazon requested Sign-In. Please sign in and click Export again.';
        broadcastProgress();
        return;
      }

      if (parsedPage.accountName && !job.accountName) {
        job.accountName = parsedPage.accountName;
      }
      if (parsedPage.totalExpectedOrders !== null && job.totalExpectedOrders === null) {
        job.totalExpectedOrders = parsedPage.totalExpectedOrders;
      }
      if (parsedPage.pagination && parsedPage.pagination.maxPageNumber > (job.maxPages || 1)) {
        job.maxPages = parsedPage.pagination.maxPageNumber;
      }

      const pageOrders = parsedPage.orders || [];

      if (job.embedImages && pageOrders.length > 0) {
        uiState.statusText = `Capturing thumbnails on Page ${job.currentPageNum}${job.maxPages > 1 ? ` of ${job.maxPages}` : ''}...`;
        broadcastProgress();
        await embedImagesForPageOrders(pageOrders);
      }

      const pageSig = pageOrders.map((o) => o.orderId || o.datePlaced).join('|');
      const isDuplicatePage = Boolean(pageSig && job.visitedPageSignatures[pageSig]);
      if (pageSig) {
        job.visitedPageSignatures[pageSig] = true;
      }

      let newOrdersOnThisPage = 0;
      if (!isDuplicatePage) {
        for (const order of pageOrders) {
          const key =
            order.orderId ||
            `${order.datePlaced}-${order.total}-${(order.items[0] && order.items[0].title) || ''}`;
          if (!job.seenOrderKeys[key]) {
            job.seenOrderKeys[key] = true;
            job.orders.push(order);
            newOrdersOnThisPage++;
          }
        }
      }

      job.pagesFetched = job.currentPageNum;

      const expectedPages =
        job.maxPages ||
        (job.totalExpectedOrders ? Math.ceil(job.totalExpectedOrders / 10) : job.currentPageNum);

      uiState.pagesFetched = job.pagesFetched;
      uiState.maxPages = expectedPages;
      uiState.ordersCollected = job.orders.length;
      uiState.totalExpectedOrders = job.totalExpectedOrders;
      uiState.progressPercent = Math.min(
        95,
        Math.round((job.currentPageNum / Math.max(1, expectedPages)) * 90)
      );
      uiState.statusText = `Collected ${job.orders.length}${
        job.totalExpectedOrders ? ` / ${job.totalExpectedOrders}` : ''
      } orders (Page ${job.currentPageNum} of ${expectedPages})...`;
      broadcastProgress();

      const reachedExpectedCount =
        job.totalExpectedOrders !== null &&
        job.totalExpectedOrders > 0 &&
        job.orders.length >= job.totalExpectedOrders;

      const reachedMaxPages = job.maxPages > 1 && job.currentPageNum >= job.maxPages;
      const nextExplicitlyDisabled = Boolean(
        parsedPage.pagination && parsedPage.pagination.isNextExplicitlyDisabled
      );

      const hasMorePages =
        !uiState.abortRequested &&
        newOrdersOnThisPage > 0 &&
        !reachedExpectedCount &&
        !reachedMaxPages &&
        !nextExplicitlyDisabled &&
        ((parsedPage.pagination && parsedPage.pagination.hasEnabledNext) ||
          job.currentPageNum < expectedPages ||
          (job.totalExpectedOrders && job.orders.length < job.totalExpectedOrders));

      if (hasMorePages && job.currentPageNum < 250) {
        const prevOrderSignature = pageSig;
        job.currentPageNum += 1;
        await setStorageJob(job);

        const nextUrl =
          (parsedPage.pagination && parsedPage.pagination.nextUrl) ||
          buildPageUrl(job.timeFilter, job.currentPageNum);

        uiState.statusText = `Navigating to Page ${job.currentPageNum} of ${expectedPages} (${job.orders.length} orders collected so far)...`;
        broadcastProgress();

        await sleep(350);
        await navigateToNextPage(nextUrl, job.timeFilter, job.currentPageNum, prevOrderSignature);
        return;
      }

      await finalizeAndDeliverReport(job);
    } catch (err) {
      console.error('[Amazon Orders Exporter] Error during page processing:', err);
      await clearStorageJob();
      uiState.isRunning = false;
      uiState.statusText = `⚠ Error: ${err.message || String(err)}`;
      broadcastProgress();
    }
  }

  /**
   * Navigates the live browser tab to the next page of orders.
   */
  async function navigateToNextPage(nextUrl, timeFilter, targetPageNum, prevOrderSignature) {
    const fallbackUrl = buildPageUrl(timeFilter, targetPageNum);
    const destinationUrl = nextUrl || fallbackUrl;

    const nextBtn = Array.from(
      document.querySelectorAll('.a-pagination li.a-last:not(.a-disabled) a, .a-pagination a, .a-pagination button, a, button')
    ).find((el) => {
      const t = Builder.cleanText(el.textContent);
      return /^Next\s*→?$/i.test(t) && !el.closest('.a-disabled, [aria-disabled="true"]');
    });

    if (destinationUrl && destinationUrl !== window.location.href) {
      window.location.href = destinationUrl;
      return;
    }

    if (nextBtn) {
      nextBtn.click();
      const start = Date.now();
      while (Date.now() - start < 2500) {
        await sleep(250);
        const currentCards = Builder.findOrderCardElements(document);
        const currentSig = currentCards
          .map((c) => {
            const m = (c.textContent || '').match(/\b(\d{3}-\d{7}-\d{7})\b/);
            return m ? m[1] : '';
          })
          .join('|');
        if (currentSig && currentSig !== prevOrderSignature) {
          await processCurrentLivePageForActiveJob();
          return;
        }
      }
    }

    window.location.href = fallbackUrl;
  }

  /**
   * Finalizes the active export job, syncs with persistent categorization cache,
   * generates the single HTML report, and delivers it.
   */
  async function finalizeAndDeliverReport(job) {
    await clearStorageJob();

    const orders = job.orders || [];
    uiState.statusText = `Applying persistent category cache & generating report for ${orders.length} orders...`;
    uiState.progressPercent = 98;
    broadcastProgress();

    // Load persistent categorization cache & apply cached overrides + holiday gift rule
    const cacheData = await getCategoryCache();
    Builder.applyAndSyncCategoryCache(orders, cacheData);

    // Save updated categorizations AND cached orders for this timeFilter so reruns persist
    if (!cacheData.cachedOrdersByFilter) cacheData.cachedOrdersByFilter = {};
    cacheData.cachedOrdersByFilter[job.timeFilter || 'year-2026'] = {
      timeFilter: job.timeFilter || 'year-2026',
      yearLabel: job.yearLabel || '2026',
      accountName: job.accountName || (orders[0] && orders[0].shipTo) || 'Customer',
      orders,
      pagesFetched: job.pagesFetched || 1,
      totalExpectedOrders: job.totalExpectedOrders || orders.length,
      cachedAt: new Date().toISOString()
    };
    await setCategoryCache(cacheData);
    await refreshCachePillInBanner();

    const htmlReport = Builder.generateSingleHtmlReport({
      yearLabel: job.yearLabel || '2026',
      timeFilter: job.timeFilter || 'year-2026',
      accountName: job.accountName || (orders[0] && orders[0].shipTo) || 'Customer',
      originUrl: window.location.origin,
      orders,
      pagesFetched: job.pagesFetched || 1,
      totalExpectedOrders: job.totalExpectedOrders || orders.length,
      customHighCategories: cacheData.customHighCategories || [],
      subCategoriesByHigh: cacheData.subCategoriesByHigh || {},
      generatedAt: new Date().toISOString()
    });

    const safeYearSlug = (job.yearLabel || 'orders').toLowerCase().replace(/[^a-z0-9-]+/g, '-');
    const filename = `amazon-orders-${safeYearSlug}-report.html`;

    triggerHtmlDelivery(htmlReport, filename, job.actionMode || 'download');

    uiState.isRunning = false;
    uiState.progressPercent = 100;
    uiState.ordersCollected = orders.length;
    uiState.statusText = `✓ Complete! Exported ${orders.length} orders (${job.pagesFetched || 1} ${
      (job.pagesFetched || 1) === 1 ? 'page' : 'pages'
    }) to ${filename}. Categorizations cached for future reruns.`;
    broadcastProgress();
  }

  function triggerHtmlDelivery(htmlContent, filename, actionMode) {
    const blob = new Blob([htmlContent], { type: 'text/html;charset=utf-8' });
    const blobUrl = URL.createObjectURL(blob);

    if (actionMode === 'preview') {
      window.open(blobUrl, '_blank');
      return;
    }

    const a = document.createElement('a');
    a.href = blobUrl;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(blobUrl), 60000);
  }

  async function refreshCachePillInBanner() {
    const bannerSelect = document.getElementById('amz-exp-year-select');
    const rebuildBtn = document.getElementById('amz-exp-rebuild-cache-btn');
    const cacheStatsEl = document.getElementById('amz-exp-cache-stats');
    if (!bannerSelect) return;

    const cacheData = await getCategoryCache();
    const tf = bannerSelect.value;
    const cachedPeriod = cacheData.cachedOrdersByFilter && cacheData.cachedOrdersByFilter[tf];
    const cachedItemCount = Object.keys(cacheData.itemCategories || {}).length;
    const customRuleCount = (cacheData.customRules || []).length;

    if (rebuildBtn) {
      if (cachedPeriod && Array.isArray(cachedPeriod.orders) && cachedPeriod.orders.length > 0) {
        rebuildBtn.style.display = 'inline-flex';
        rebuildBtn.textContent = `⚡ Rebuild Cached (${cachedPeriod.orders.length} orders)`;
      } else {
        rebuildBtn.style.display = 'none';
      }
    }

    if (cacheStatsEl) {
      cacheStatsEl.textContent = `Cached Categorizations: ${cachedItemCount} keys • Custom Categories: ${
        (cacheData.customHighCategories || []).length
      } • Custom Keyword Rules: ${customRuleCount}`;
    }
  }

  /**
   * Injects the in-page Exporter Banner right above the Amazon Orders list
   */
  function injectInPageBanner() {
    if (document.getElementById('amz-year-exporter-banner')) return;

    const { activeFilter, options } = Builder.extractTimeFilters(document, window.location.href);
    const totalCount = Builder.extractTotalOrdersCount(document);

    const banner = document.createElement('div');
    banner.id = 'amz-year-exporter-banner';

    const optionsHtml = options
      .map(
        (opt) =>
          `<option value="${opt.value.replace(/"/g, '&quot;')}" ${
            opt.value === activeFilter ? 'selected' : ''
          }>${opt.label}</option>`
      )
      .join('');

    banner.innerHTML = `
      <div class="amz-exp-top-row">
        <div class="amz-exp-brand">
          <span class="amz-exp-badge">Full Year HTML + AI Agent Exporter</span>
          <div>
            <div class="amz-exp-title">Download All Pages of Amazon Orders as a Single Categorized HTML Report</div>
            <div class="amz-exp-subtitle">1st Col Sortable Dates (Placed / Delivered) • Customizable Categories (Christmas Presents, Grocery, Sports &amp; Fitness, UNCATEGORIZED) • Persistent Cache${
              totalCount !== null ? ` • <strong>${totalCount} orders</strong> in current view` : ''
            }</div>
          </div>
        </div>
        <div class="amz-exp-controls">
          <label style="font-size:12px; font-weight:600;">Year / Period:
            <select id="amz-exp-year-select" class="amz-exp-select" title="Selecting a time period here also switches the Amazon page's time period dropdown">
              ${optionsHtml}
            </select>
          </label>
          <label class="amz-exp-checkbox-label" title="Embeds product thumbnail images as Base64 data URIs inside the HTML file so it works 100% offline">
            <input type="checkbox" id="amz-exp-embed-imgs" checked />
            Embed thumbnails
          </label>
          <button type="button" id="amz-exp-download-btn" class="amz-exp-btn amz-exp-btn-primary">
            ⬇ Download Single HTML Report
          </button>
          <button type="button" id="amz-exp-preview-btn" class="amz-exp-btn amz-exp-btn-secondary">
            ↗ Open in New Tab
          </button>
          <button type="button" id="amz-exp-rebuild-cache-btn" class="amz-exp-btn amz-exp-btn-secondary" style="display:none;" title="Instantly rebuild the HTML report for this period using cached orders and your saved category overrides">
            ⚡ Rebuild Cached
          </button>
          <button type="button" id="amz-exp-toggle-cat-mgr-btn" class="amz-exp-btn amz-exp-btn-secondary" title="Customize categories, keyword rules, and persistent categorization cache">
            ⚙ Categories &amp; Cache
          </button>
          <button type="button" id="amz-exp-stop-btn" class="amz-exp-btn amz-exp-btn-stop" style="display:none;">
            ■ Stop &amp; Save Collected
          </button>
        </div>
      </div>

      <!-- Collapsible Categories & Cache Drawer -->
      <div id="amz-exp-cat-drawer" class="amz-exp-cat-drawer" style="display:none;">
        <div class="amz-exp-cat-drawer-grid">
          <div class="amz-exp-cat-box">
            <div class="amz-exp-cat-box-title">Add Custom Keyword → Category Rule</div>
            <div class="amz-exp-cat-row">
              <input type="text" id="amz-rule-keyword" class="amz-exp-input" placeholder="Keyword (e.g. pickleball, lego)" />
              <input type="text" id="amz-rule-high" class="amz-exp-input" placeholder="High-Level (e.g. Sports &amp; Fitness)" />
              <input type="text" id="amz-rule-fine" class="amz-exp-input" placeholder="Finer-Grain (e.g. Pickleball Gear)" />
              <button type="button" id="amz-add-rule-btn" class="amz-exp-btn amz-exp-btn-primary">Add Rule</button>
            </div>
          </div>
          <div class="amz-exp-cat-box">
            <div class="amz-exp-cat-box-title">Sync / Import Category Overrides from Report</div>
            <div class="amz-exp-cat-row">
              <input type="text" id="amz-import-cache-input" class="amz-exp-input" placeholder="Paste JSON from '🔄 Copy Category Cache' in report..." />
              <button type="button" id="amz-import-cache-btn" class="amz-exp-btn amz-exp-btn-secondary">Import Cache</button>
              <button type="button" id="amz-clear-cache-btn" class="amz-exp-btn amz-exp-btn-stop">Clear Cache</button>
            </div>
          </div>
        </div>
        <div id="amz-exp-cache-stats" class="amz-exp-cache-stats"></div>
      </div>

      <div id="amz-exp-progress-wrap" class="amz-exp-progress-wrap">
        <div class="amz-exp-progress-bar-bg">
          <div id="amz-exp-progress-fill" class="amz-exp-progress-bar-fill"></div>
        </div>
        <div class="amz-exp-status-row">
          <span id="amz-exp-status-text">${uiState.statusText}</span>
          <span id="amz-exp-count-badge"></span>
        </div>
      </div>
    `;

    const targetMount =
      document.querySelector('.time-filter-section') ||
      document.querySelector('#ordersContainer') ||
      document.querySelector('.your-orders-content-container__content') ||
      document.querySelector('.a-row.a-spacing-base');

    if (targetMount && targetMount.parentNode) {
      targetMount.parentNode.insertBefore(banner, targetMount);
    } else {
      document.body.prepend(banner);
    }

    const bannerYearSelect = document.getElementById('amz-exp-year-select');
    bannerYearSelect.addEventListener('change', () => {
      if (uiState.isRunning) return;
      const newTimeFilter = bannerYearSelect.value;
      refreshCachePillInBanner();
      drivePageTimeFilter(newTimeFilter);
    });

    const pageSelect = Builder.findPageTimeFilterSelect(document);
    if (pageSelect) {
      pageSelect.addEventListener('change', () => {
        if (bannerYearSelect.value !== pageSelect.value) {
          bannerYearSelect.value = pageSelect.value;
          refreshCachePillInBanner();
        }
      });
    }

    document.getElementById('amz-exp-download-btn').addEventListener('click', () => {
      const tf = bannerYearSelect.value;
      const embedImages = document.getElementById('amz-exp-embed-imgs').checked;
      startExportJob({ timeFilter: tf, embedImages, actionMode: 'download' });
    });

    document.getElementById('amz-exp-preview-btn').addEventListener('click', () => {
      const tf = bannerYearSelect.value;
      const embedImages = document.getElementById('amz-exp-embed-imgs').checked;
      startExportJob({ timeFilter: tf, embedImages, actionMode: 'preview' });
    });

    document.getElementById('amz-exp-rebuild-cache-btn').addEventListener('click', () => {
      const tf = bannerYearSelect.value;
      rebuildReportFromCache({ timeFilter: tf, actionMode: 'download' });
    });

    document.getElementById('amz-exp-toggle-cat-mgr-btn').addEventListener('click', () => {
      const drawer = document.getElementById('amz-exp-cat-drawer');
      if (drawer) {
        drawer.style.display = drawer.style.display === 'none' ? 'block' : 'none';
        refreshCachePillInBanner();
      }
    });

    document.getElementById('amz-add-rule-btn').addEventListener('click', async () => {
      const kwInput = document.getElementById('amz-rule-keyword');
      const highInput = document.getElementById('amz-rule-high');
      const fineInput = document.getElementById('amz-rule-fine');
      const keyword = (kwInput.value || '').trim().toLowerCase();
      const high = (highInput.value || '').trim();
      const fine = (fineInput.value || '').trim() || high;
      if (!keyword || !high) return;

      const cacheData = await getCategoryCache();
      cacheData.customRules.push({ keyword, high, fine });
      if (!cacheData.customHighCategories.includes(high)) {
        cacheData.customHighCategories.push(high);
      }
      if (!cacheData.subCategoriesByHigh[high]) {
        cacheData.subCategoriesByHigh[high] = [];
      }
      if (!cacheData.subCategoriesByHigh[high].includes(fine)) {
        cacheData.subCategoriesByHigh[high].push(fine);
        cacheData.subCategoriesByHigh[high].sort((a, b) => a.localeCompare(b, undefined, { sensitivity: 'base' }));
      }
      await setCategoryCache(cacheData);
      kwInput.value = '';
      highInput.value = '';
      fineInput.value = '';
      await refreshCachePillInBanner();
      uiState.statusText = `✓ Added custom rule: "${keyword}" → ${high} (${fine})`;
      updateBannerUI();
    });

    document.getElementById('amz-import-cache-btn').addEventListener('click', async () => {
      const input = document.getElementById('amz-import-cache-input');
      const raw = (input.value || '').trim();
      if (!raw) return;
      try {
        const parsed = JSON.parse(raw);
        const cacheData = await getCategoryCache();
        if (parsed.itemCategories && typeof parsed.itemCategories === 'object') {
          Object.assign(cacheData.itemCategories, parsed.itemCategories);
        }
        if (Array.isArray(parsed.customHighCategories)) {
          cacheData.customHighCategories = Array.from(
            new Set([...(cacheData.customHighCategories || []), ...parsed.customHighCategories])
          );
        }
        if (parsed.subCategoriesByHigh && typeof parsed.subCategoriesByHigh === 'object') {
          for (const [hc, subs] of Object.entries(parsed.subCategoriesByHigh)) {
            if (!Array.isArray(subs)) continue;
            if (!cacheData.subCategoriesByHigh[hc]) cacheData.subCategoriesByHigh[hc] = [];
            cacheData.subCategoriesByHigh[hc] = Array.from(
              new Set([...cacheData.subCategoriesByHigh[hc], ...subs])
            ).sort((a, b) => a.localeCompare(b, undefined, { sensitivity: 'base' }));
          }
        }
        await setCategoryCache(cacheData);
        input.value = '';
        await refreshCachePillInBanner();
        uiState.statusText = '✓ Imported custom category & sub-category overrides into persistent extension cache!';
        updateBannerUI();
      } catch (e) {
        uiState.statusText = '⚠ Invalid JSON cache token.';
        updateBannerUI();
      }
    });

    document.getElementById('amz-clear-cache-btn').addEventListener('click', async () => {
      await setCategoryCache({
        itemCategories: {},
        customHighCategories: [],
        subCategoriesByHigh: {},
        customRules: [],
        cachedOrdersByFilter: {}
      });
      await refreshCachePillInBanner();
      uiState.statusText = 'Cleared persistent categorization and order cache.';
      updateBannerUI();
    });

    document.getElementById('amz-exp-stop-btn').addEventListener('click', async () => {
      uiState.abortRequested = true;
      uiState.statusText = 'Stopping and saving orders collected so far...';
      updateBannerUI();
      const job = await getStorageJob();
      if (job && job.active) {
        await finalizeAndDeliverReport(job);
      }
    });

    refreshCachePillInBanner();
  }

  function updateBannerUI() {
    const wrap = document.getElementById('amz-exp-progress-wrap');
    const fill = document.getElementById('amz-exp-progress-fill');
    const statusEl = document.getElementById('amz-exp-status-text');
    const badgeEl = document.getElementById('amz-exp-count-badge');
    const dlBtn = document.getElementById('amz-exp-download-btn');
    const pvBtn = document.getElementById('amz-exp-preview-btn');
    const stopBtn = document.getElementById('amz-exp-stop-btn');

    if (!wrap) return;
    if (uiState.isRunning || uiState.progressPercent > 0 || uiState.statusText) {
      wrap.classList.add('active');
    }
    if (fill) fill.style.width = `${uiState.progressPercent}%`;
    if (statusEl) statusEl.textContent = uiState.statusText;
    if (badgeEl) {
      badgeEl.textContent =
        uiState.ordersCollected > 0
          ? `${uiState.ordersCollected}${
              uiState.totalExpectedOrders ? ` / ${uiState.totalExpectedOrders}` : ''
            } orders • Page ${uiState.pagesFetched}${uiState.maxPages > 1 ? ` of ${uiState.maxPages}` : ''}`
          : '';
    }
    if (dlBtn) dlBtn.disabled = uiState.isRunning;
    if (pvBtn) pvBtn.disabled = uiState.isRunning;
    if (stopBtn) stopBtn.style.display = uiState.isRunning ? 'inline-flex' : 'none';
  }

  // Listen for messages from the Chrome Extension Popup
  chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
    if (!message || !message.type) return false;

    if (message.type === 'GET_PAGE_STATE') {
      const { activeFilter, options } = Builder.extractTimeFilters(document, window.location.href);
      const accountName = Builder.extractAccountName(document);
      const totalExpectedOrders = Builder.extractTotalOrdersCount(document);
      getCategoryCache().then((cacheData) => {
        const cachedEntry = cacheData.cachedOrdersByFilter && cacheData.cachedOrdersByFilter[activeFilter];
        sendResponse({
          ok: true,
          isOrdersPage: true,
          accountName,
          activeFilter,
          timeFilterOptions: options,
          totalExpectedOrders,
          cachedOrdersCount: cachedEntry && Array.isArray(cachedEntry.orders) ? cachedEntry.orders.length : 0,
          cachedKeysCount: Object.keys(cacheData.itemCategories || {}).length,
          customRulesCount: (cacheData.customRules || []).length,
          state: { ...uiState }
        });
      });
      return true;
    }

    if (message.type === 'SET_TIME_FILTER') {
      if (message.timeFilter) {
        drivePageTimeFilter(message.timeFilter);
      }
      sendResponse({ ok: true });
      return true;
    }

    if (message.type === 'START_EXPORT') {
      startExportJob(message.options || {});
      sendResponse({ ok: true, started: true });
      return true;
    }

    if (message.type === 'REBUILD_FROM_CACHE') {
      rebuildReportFromCache(message.options || {});
      sendResponse({ ok: true, rebuilt: true });
      return true;
    }

    if (message.type === 'ADD_CUSTOM_RULE') {
      getCategoryCache().then(async (cacheData) => {
        const { keyword, high, fine } = message.rule || {};
        if (keyword && high) {
          const h = high.trim();
          const f = (fine || high).trim();
          cacheData.customRules.push({
            keyword: keyword.trim().toLowerCase(),
            high: h,
            fine: f
          });
          if (!cacheData.customHighCategories.includes(h)) {
            cacheData.customHighCategories.push(h);
          }
          if (!cacheData.subCategoriesByHigh[h]) {
            cacheData.subCategoriesByHigh[h] = [];
          }
          if (!cacheData.subCategoriesByHigh[h].includes(f)) {
            cacheData.subCategoriesByHigh[h].push(f);
            cacheData.subCategoriesByHigh[h].sort((a, b) => a.localeCompare(b, undefined, { sensitivity: 'base' }));
          }
          await setCategoryCache(cacheData);
          await refreshCachePillInBanner();
        }
        sendResponse({ ok: true, customRulesCount: cacheData.customRules.length });
      });
      return true;
    }

    if (message.type === 'STOP_EXPORT') {
      uiState.abortRequested = true;
      getStorageJob().then((job) => {
        if (job && job.active) {
          finalizeAndDeliverReport(job);
        }
      });
      sendResponse({ ok: true, stopping: true });
      return true;
    }

    return false;
  });

  async function initOnPageLoad() {
    injectInPageBanner();
    const job = await getStorageJob();
    if (job && job.active) {
      if (job.startedAt && Date.now() - job.startedAt > 30 * 60 * 1000) {
        await clearStorageJob();
        return;
      }
      const bannerSelect = document.getElementById('amz-exp-year-select');
      if (bannerSelect && job.timeFilter) {
        bannerSelect.value = job.timeFilter;
      }
      await processCurrentLivePageForActiveJob();
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initOnPageLoad);
  } else {
    initOnPageLoad();
  }
})();
