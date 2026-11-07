/**
 * popup.js
 * Controller for the Chrome Extension Popup UI.
 * Syncs with and drives the Amazon page's time period dropdown, coordinates
 * multi-page live-DOM exports, and manages persistent category caches.
 */

(function () {
  'use strict';

  const accountPill = document.getElementById('account-pill');
  const notAmazonView = document.getElementById('not-amazon-view');
  const exporterView = document.getElementById('exporter-view');
  const openAmazonBtn = document.getElementById('open-amazon-orders-btn');
  const yearSelect = document.getElementById('year-select');
  const detectedHint = document.getElementById('detected-count-hint');
  const cacheStatusHint = document.getElementById('cache-status-hint');
  const embedImagesCb = document.getElementById('embed-images-cb');
  const startDownloadBtn = document.getElementById('start-download-btn');
  const startPreviewBtn = document.getElementById('start-preview-btn');
  const rebuildCacheBtn = document.getElementById('rebuild-cache-btn');
  const stopExportBtn = document.getElementById('stop-export-btn');
  const addRuleBtn = document.getElementById('add-rule-btn');
  const ruleKwInput = document.getElementById('rule-kw-input');
  const ruleHighInput = document.getElementById('rule-high-input');
  const ruleFineInput = document.getElementById('rule-fine-input');
  const progressBox = document.getElementById('progress-box');
  const progressFill = document.getElementById('progress-fill');
  const progressStatus = document.getElementById('progress-status');

  let activeTabId = null;

  function isAmazonUrl(url) {
    if (!url) return false;
    try {
      const u = new URL(url);
      return /(^|\.)amazon\.(com|co\.uk|ca|de|co\.jp|fr|es|it|com\.au|in|com\.mx|com\.br)$/i.test(u.hostname);
    } catch (_) {
      return false;
    }
  }

  async function ensureContentScriptAndGetState(tabId) {
    try {
      const response = await chrome.tabs.sendMessage(tabId, { type: 'GET_PAGE_STATE' });
      if (response && response.ok) return response;
    } catch (_) {
      try {
        await chrome.scripting.insertCSS({
          target: { tabId },
          files: ['content.css']
        });
        await chrome.scripting.executeScript({
          target: { tabId },
          files: ['report-builder.js', 'content.js']
        });
        const retryResponse = await chrome.tabs.sendMessage(tabId, { type: 'GET_PAGE_STATE' });
        if (retryResponse && retryResponse.ok) return retryResponse;
      } catch (err) {
        console.warn('[Popup] Could not inject into tab:', err);
      }
    }
    return null;
  }

  function updateProgressUI(state) {
    if (!state) return;
    if (state.isRunning || state.progressPercent > 0) {
      progressBox.style.display = 'block';
      progressFill.style.width = `${state.progressPercent || 0}%`;
      progressStatus.textContent = state.statusText || 'Working...';
    }
    startDownloadBtn.disabled = Boolean(state.isRunning);
    startPreviewBtn.disabled = Boolean(state.isRunning);
    stopExportBtn.style.display = state.isRunning ? 'inline-flex' : 'none';
  }

  async function initPopup() {
    const tabs = await chrome.tabs.query({ active: true, currentWindow: true });
    const tab = tabs && tabs[0];
    if (!tab || !isAmazonUrl(tab.url)) {
      accountPill.textContent = 'Not on Amazon';
      notAmazonView.style.display = 'block';
      exporterView.style.display = 'none';
      return;
    }

    activeTabId = tab.id;
    notAmazonView.style.display = 'none';
    exporterView.style.display = 'block';

    const pageState = await ensureContentScriptAndGetState(activeTabId);
    if (!pageState) {
      accountPill.textContent = 'Amazon Tab';
      return;
    }

    accountPill.textContent = pageState.accountName
      ? `Hello, ${pageState.accountName}`
      : 'Logged In';

    if (Array.isArray(pageState.timeFilterOptions) && pageState.timeFilterOptions.length > 0) {
      yearSelect.innerHTML = pageState.timeFilterOptions
        .map(
          (opt) =>
            `<option value="${opt.value.replace(/"/g, '&quot;')}" ${
              opt.value === pageState.activeFilter ? 'selected' : ''
            }>${opt.label}</option>`
        )
        .join('');
    }

    if (pageState.totalExpectedOrders !== null && pageState.totalExpectedOrders !== undefined) {
      detectedHint.innerHTML = `Detected <strong>${pageState.totalExpectedOrders} orders</strong> on current period. Will step through all pages automatically.`;
    }

    if (pageState.cachedKeysCount > 0 || pageState.cachedOrdersCount > 0) {
      cacheStatusHint.textContent = `✓ Persistent Cache: ${pageState.cachedKeysCount || 0} categorized items${
        pageState.cachedOrdersCount ? ` • ${pageState.cachedOrdersCount} cached orders for this period` : ''
      }`;
    }

    if (pageState.cachedOrdersCount > 0 && rebuildCacheBtn) {
      rebuildCacheBtn.style.display = 'inline-flex';
      rebuildCacheBtn.textContent = `⚡ Instant Rebuild from Cache (${pageState.cachedOrdersCount} orders)`;
    }

    if (pageState.state) {
      updateProgressUI(pageState.state);
    }
  }

  yearSelect.addEventListener('change', () => {
    if (!activeTabId) return;
    chrome.tabs.sendMessage(activeTabId, {
      type: 'SET_TIME_FILTER',
      timeFilter: yearSelect.value
    });
  });

  function triggerExport(actionMode) {
    if (!activeTabId) return;
    const options = {
      timeFilter: yearSelect.value,
      embedImages: embedImagesCb ? embedImagesCb.checked : true,
      actionMode
    };
    progressBox.style.display = 'block';
    progressFill.style.width = '5%';
    progressStatus.textContent = 'Starting multi-page live DOM export...';
    startDownloadBtn.disabled = true;
    startPreviewBtn.disabled = true;
    stopExportBtn.style.display = 'inline-flex';

    chrome.tabs.sendMessage(activeTabId, {
      type: 'START_EXPORT',
      options
    });
  }

  startDownloadBtn.addEventListener('click', () => triggerExport('download'));
  startPreviewBtn.addEventListener('click', () => triggerExport('preview'));

  if (rebuildCacheBtn) {
    rebuildCacheBtn.addEventListener('click', () => {
      if (!activeTabId) return;
      chrome.tabs.sendMessage(activeTabId, {
        type: 'REBUILD_FROM_CACHE',
        options: {
          timeFilter: yearSelect.value,
          actionMode: 'download'
        }
      });
    });
  }

  if (addRuleBtn) {
    addRuleBtn.addEventListener('click', () => {
      if (!activeTabId) return;
      const keyword = (ruleKwInput.value || '').trim();
      const high = (ruleHighInput.value || '').trim();
      const fine = (ruleFineInput.value || '').trim() || high;
      if (!keyword || !high) return;
      chrome.tabs.sendMessage(
        activeTabId,
        {
          type: 'ADD_CUSTOM_RULE',
          rule: { keyword, high, fine }
        },
        () => {
          ruleKwInput.value = '';
          ruleHighInput.value = '';
          ruleFineInput.value = '';
          cacheStatusHint.textContent = `✓ Saved custom rule: "${keyword}" → ${high} (${fine})`;
        }
      );
    });
  }

  stopExportBtn.addEventListener('click', () => {
    if (!activeTabId) return;
    chrome.tabs.sendMessage(activeTabId, { type: 'STOP_EXPORT' });
  });

  openAmazonBtn.addEventListener('click', () => {
    chrome.tabs.create({ url: 'https://www.amazon.com/your-orders/orders' });
  });

  chrome.runtime.onMessage.addListener((message) => {
    if (message && message.type === 'EXPORT_PROGRESS' && message.payload) {
      updateProgressUI(message.payload);
    }
  });

  initPopup();
})();
