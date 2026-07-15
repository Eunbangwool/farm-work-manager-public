/**
 * 농작이 PWA Service Worker — 오프라인 셸 캐싱.
 *
 * 전략:
 *  · install: shell 파일 (index.html, manifest, 지도 HTML) precache.
 *  · navigation (HTML): network-first → 오프라인 시 cached index.html fallback.
 *    └ 새 빌드 배포 시 항상 최신 index.html (= 최신 hashed wasm 참조) 받음.
 *  · 그 외 static asset (wasm/js/css/img): stale-while-revalidate.
 *  · cross-origin (Firebase SDK CDN, Firestore API): 손대지 않음 — 브라우저 HTTP 캐시 + 네트워크.
 *  · POST/PUT/PATCH: 무시 — Firestore mutate 는 그대로 통과.
 *
 * 캐시 버전 증가 = activate 단계에서 기존 캐시 삭제 + 즉시 controller 교체.
 */
const SHELL_CACHE = 'nongjagi-shell-v1';
const RUNTIME_CACHE = 'nongjagi-runtime-v1';

const SHELL_FILES = [
  './',
  './index.html',
  './manifest.webmanifest',
  './map/farmwork_map.html',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(SHELL_CACHE)
      .then((cache) => cache.addAll(SHELL_FILES))
      .then(() => self.skipWaiting())
      .catch((err) => { console.warn('[sw] precache 실패', err); })
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(
        keys
          .filter((k) => k !== SHELL_CACHE && k !== RUNTIME_CACHE)
          .map((k) => caches.delete(k))
      ))
      .then(() => self.clients.claim())
  );
});

// 사용자가 "업데이트 적용" 누르면 클라이언트가 보내는 메시지 — waiting 워커 즉시 활성화.
self.addEventListener('message', (event) => {
  if (event.data && event.data.type === 'SKIP_WAITING') {
    self.skipWaiting();
  }
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin) return;  // cross-origin → browser default

  // 페이지 navigation — 항상 네트워크 우선, 실패 시 캐시 셸.
  if (req.mode === 'navigate') {
    event.respondWith(
      fetch(req)
        .then((resp) => {
          // 새 index.html 도 셸 캐시에 갱신 (다음 오프라인 대비).
          if (resp && resp.status === 200) {
            const copy = resp.clone();
            caches.open(SHELL_CACHE).then((c) => c.put(req, copy)).catch(() => {});
          }
          return resp;
        })
        .catch(() => caches.match('./index.html').then((r) => r || caches.match(req)))
    );
    return;
  }

  // 정적 자원: stale-while-revalidate.
  event.respondWith(staleWhileRevalidate(req));
});

async function staleWhileRevalidate(req) {
  const cache = await caches.open(RUNTIME_CACHE);
  const cached = await cache.match(req);
  const network = fetch(req)
    .then((resp) => {
      if (resp && resp.status === 200 && (resp.type === 'basic' || resp.type === 'cors')) {
        try { cache.put(req, resp.clone()); } catch (_) {}
      }
      return resp;
    })
    .catch(() => cached);
  return cached || network;
}
