/**
 * background.js
 * Service worker for Amazon Orders Year Report Exporter
 */

chrome.runtime.onMessage.addListener((message, sender, _sendResponse) => {
  if (!message || !message.type) return false;

  if (message.type === 'EXPORT_PROGRESS' && sender.tab && sender.tab.id) {
    const payload = message.payload || {};
    if (payload.isRunning) {
      const text = payload.ordersCollected ? String(payload.ordersCollected) : '...';
      chrome.action.setBadgeText({ tabId: sender.tab.id, text });
      chrome.action.setBadgeBackgroundColor({ tabId: sender.tab.id, color: '#007185' });
    } else if (payload.progressPercent === 100) {
      chrome.action.setBadgeText({ tabId: sender.tab.id, text: '✓' });
      chrome.action.setBadgeBackgroundColor({ tabId: sender.tab.id, color: '#067d62' });
      setTimeout(() => {
        chrome.action.setBadgeText({ tabId: sender.tab.id, text: '' });
      }, 6000);
    }
  }
  return false;
});
