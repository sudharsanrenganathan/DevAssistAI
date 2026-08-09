/* DevAssist Offline Studio — service worker.
 * Caches the Offline Studio shell + heavy assets for offline use, but uses a
 * NETWORK-FIRST strategy: when online, pages/JS always come fresh from the
 * network (so app updates are never masked); the cache is only a fallback when
 * the network is unavailable. The previous version was cache-first for the
 * whole origin, which served stale pages forever after the first visit.
 */
const CACHE = 'devassist-offline-v2';
const ASSETS = [
  'local-ai.html',
  'manifest.json',
  'lib/pdf.min.js',
  'lib/pdf.worker.min.js',
  'img/logo.png',
  'img/favicon.png'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE).then((c) => c.addAll(ASSETS)).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  // Deleting every cache whose name !== CACHE purges the old cache-first store
  // (devassist-offline-v1) that was holding stale pages.
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);

  // Never intercept the local AI engine — always hit the live Ollama server.
  if ((url.hostname === 'localhost' || url.hostname === '127.0.0.1') && url.port === '11434') return;
  if (event.request.method !== 'GET') return;
  if (url.origin !== self.location.origin) return;

  // Network-first: try the network, refresh the cache on success, and fall back
  // to the cache only when offline. This guarantees fresh pages while online
  // yet keeps Offline Studio working with no connection.
  event.respondWith(
    fetch(event.request).then((res) => {
      if (res && res.ok) {
        const copy = res.clone();
        caches.open(CACHE).then((c) => c.put(event.request, copy));
      }
      return res;
    }).catch(() => caches.match(event.request))
  );
});
