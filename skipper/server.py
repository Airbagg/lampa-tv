#!/usr/bin/env python3
"""
Сервер пропуска заставок для телевизора.

Телевизор (посредник «VLC для Лампы») спрашивает:
    GET /segments?hash=<infohash>&index=<id файла в TorrServer>
и получает {"intro": [начало, конец], "credits": [начало, конец], "duration": ...}
или {"status": "pending"}, если серия ещё считается. Считаем парами соседних
серий (detect.py), результат храним в cache.json и сразу берёмся за следующую
серию, чтобы к её началу всё было готово.

TorrServer берём на том же устройстве, что спрашивает (адрес клиента, порт 8090).
Сервер объявляет себя в сети через Bonjour (_lampaskip._tcp), телевизор находит
его сам, даже если у мака сменится адрес.
"""
import json
import os
import queue
import subprocess
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import detect

PORT = 8780
HERE = os.path.dirname(os.path.abspath(__file__))
CACHE_PATH = os.path.join(HERE, 'cache.json')
VIDEO_EXT = ('.mkv', '.mp4', '.avi', '.ts', '.m2ts', '.mov', '.webm', '.m4v', '.mpg', '.mpeg')

lock = threading.Lock()
cache = {}
pending = set()
jobs = queue.PriorityQueue()


def log(*a):
    print(time.strftime('%H:%M:%S'), *a, flush=True)


def load_cache():
    global cache
    try:
        with open(CACHE_PATH, encoding='utf-8') as f:
            cache = json.load(f)
    except (OSError, ValueError):
        cache = {}


def save_cache():
    tmp = CACHE_PATH + '.tmp'
    with open(tmp, 'w', encoding='utf-8') as f:
        json.dump(cache, f, ensure_ascii=False, indent=1)
    os.replace(tmp, CACHE_PATH)


def ts_files(ts, h):
    req = urllib.request.Request(
        ts + '/torrents', data=json.dumps({'action': 'get', 'hash': h}).encode(),
        headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r).get('file_stats') or []


def neighbours(files, index):
    """Видео той же папки по порядку id: (предыдущее, следующее)."""
    cur = next((f for f in files if f['id'] == index), None)
    if not cur:
        return None, None
    folder = os.path.dirname(cur['path'])
    same = sorted((f for f in files
                   if os.path.dirname(f['path']) == folder and f['path'].lower().endswith(VIDEO_EXT)),
                  key=lambda f: f['id'])
    ids = [f['id'] for f in same]
    i = ids.index(index)
    return (ids[i - 1] if i > 0 else None), (ids[i + 1] if i + 1 < len(ids) else None)


def stream_url(ts, h, index):
    return f'{ts}/stream/f?link={h}&index={index}&play'


def key(h, index):
    return f'{h}:{index}'


def enqueue(ts, h, index, priority):
    k = key(h, index)
    with lock:
        if k in cache or k in pending:
            return
        pending.add(k)
    jobs.put((priority, time.time(), ts, h, index))


def worker():
    while True:
        priority, _, ts, h, index = jobs.get()
        k = key(h, index)
        try:
            files = ts_files(ts, h)
            prev_id, next_id = neighbours(files, index)
            other = next_id if next_id is not None else prev_id
            if other is None:
                with lock:
                    cache[k] = {'none': True, 'reason': 'single file'}
                    save_cache()
                continue
            log('detect', h[:8], index, 'vs', other, '(prio', priority, ')')
            t0 = time.time()
            res = detect.detect_pair(stream_url(ts, h, index), stream_url(ts, h, other))
            log('done', h[:8], index, other, f'{time.time() - t0:.0f}s', json.dumps(res, ensure_ascii=False))
            with lock:
                cache[k] = res['a'] or {'none': True}
                ko = key(h, other)
                if ko not in cache:
                    cache[ko] = res['b'] or {'none': True}
                pending.discard(ko)
                save_cache()
            # Заранее — следующая серия после той, с которой сравнивали
            if next_id is not None:
                _, after = neighbours(files, next_id)
                if after is not None:
                    enqueue(ts, h, after, priority + 1)
        except Exception as e:  # noqa: BLE001 — сервер не должен падать из-за одной серии
            log('error', k, repr(e))
        finally:
            with lock:
                pending.discard(k)


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        url = urllib.parse.urlparse(self.path)
        q = urllib.parse.parse_qs(url.query)
        if url.path == '/health':
            return self.reply(200, {'ok': True, 'cached': len(cache), 'pending': len(pending)})
        if url.path != '/segments' or 'hash' not in q or 'index' not in q:
            return self.reply(404, {'error': 'use /segments?hash=&index='})

        h = q['hash'][0].lower()
        index = int(q['index'][0])
        ts = q.get('ts', [f'http://{self.client_address[0]}:8090'])[0].rstrip('/')
        k = key(h, index)
        with lock:
            res = cache.get(k)
        if res is not None:
            return self.reply(200, res)
        enqueue(ts, h, index, 0)
        return self.reply(200, {'status': 'pending'})

    def reply(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        log(self.client_address[0], fmt % args)


def main():
    load_cache()
    threading.Thread(target=worker, daemon=True).start()
    # Bonjour: телевизор найдёт сервер по имени службы, адрес мака не важен
    subprocess.Popen(['dns-sd', '-R', 'Lampa Skipper', '_lampaskip._tcp', 'local', str(PORT)],
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    log('listening on', PORT, 'cached', len(cache))
    ThreadingHTTPServer(('0.0.0.0', PORT), Handler).serve_forever()


if __name__ == '__main__':
    main()
