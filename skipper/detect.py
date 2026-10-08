#!/usr/bin/env python3
"""
Поиск заставки и финальных титров сравнением звука двух серий одного сериала.

Идея как у Plex / Jellyfin Intro Skipper: заставка и титры у серий общие,
а сюжет разный. Снимаем акустический отпечаток (chromaprint, fpcalc -raw)
начала и конца двух серий и ищем самый длинный общий кусок.

Отпечаток chromaprint — 32-битное число на каждые ~0.1238 с звука.
Два кадра «совпадают», если отличаются не больше чем MAX_BIT_DIFF битами.
"""
import json
import subprocess
import sys

import numpy as np

FRAME_SEC = 4096 / 3 / 11025          # шаг отпечатка chromaprint, ~0.1238 с
INTRO_SCAN_SEC = 600                   # заставку ищем в первых 10 минутах
CREDITS_SCAN_SEC = 240                 # титры — в последних 4 минутах
MAX_BIT_DIFF = 6                       # кадры совпадают, если различий ≤ 6 бит из 32
MAX_GAP_FRAMES = 8                     # провалы до ~1 с внутри совпадения прощаем
MIN_INTRO_SEC = 8
MAX_INTRO_SEC = 150
MIN_CREDITS_SEC = 10


def duration(url):
    out = subprocess.run(
        ['ffprobe', '-v', 'error', '-rw_timeout', '60000000', '-show_entries', 'format=duration',
         '-of', 'default=nw=1:nk=1', url],
        capture_output=True, text=True, timeout=180)
    return float(out.stdout.strip())


def fingerprint(url, start, length):
    """Отпечаток куска [start, start+length) секунд."""
    ff = subprocess.Popen(
        ['ffmpeg', '-v', 'error', '-rw_timeout', '60000000', '-ss', str(start), '-i', url,
         '-t', str(length), '-vn', '-ac', '1', '-ar', '11025', '-f', 's16le', '-'],
        stdout=subprocess.PIPE)
    fp = subprocess.run(
        ['fpcalc', '-raw', '-json', '-format', 's16le', '-rate', '11025', '-channels', '1',
         '-length', str(int(length) + 5), '-'],
        stdin=ff.stdout, capture_output=True, text=True, timeout=900)
    ff.wait()
    data = json.loads(fp.stdout)
    return data['fingerprint']


_POP8 = np.array([bin(i).count('1') for i in range(256)], dtype=np.uint8)


def popcount32(x):
    x = x.astype(np.uint32)
    return (_POP8[x & 0xff] + _POP8[(x >> 8) & 0xff] + _POP8[(x >> 16) & 0xff]
            + _POP8[(x >> 24) & 0xff])


def best_common(a, b, max_shift):
    """
    Самый длинный общий кусок двух отпечатков при сдвиге b относительно a
    на -max_shift..+max_shift кадров. Возвращает (длина, начало_в_a, начало_в_b)
    в кадрах.
    """
    a = np.asarray(a, dtype=np.int64) & 0xffffffff
    b = np.asarray(b, dtype=np.int64) & 0xffffffff
    best = (0, 0, 0)
    for shift in range(-max_shift, max_shift + 1):
        i0 = max(0, -shift)
        i1 = min(len(a), len(b) - shift)
        if i1 - i0 <= best[0]:
            continue
        match = np.flatnonzero(popcount32(a[i0:i1] ^ b[i0 + shift:i1 + shift]) <= MAX_BIT_DIFF)
        if not len(match):
            continue
        # Разбиваем совпавшие кадры на куски по провалам > MAX_GAP_FRAMES
        breaks = np.flatnonzero(np.diff(match) > MAX_GAP_FRAMES)
        starts = np.concatenate(([0], breaks + 1))
        ends = np.concatenate((breaks, [len(match) - 1]))
        lengths = match[ends] - match[starts] + 1
        k = int(np.argmax(lengths))
        if lengths[k] > best[0]:
            start = i0 + int(match[starts[k]])
            best = (int(lengths[k]), start, start + shift)
    return best


def detect_pair(url_a, url_b):
    """Заставка и титры для двух серий. Время — секунды от начала каждой серии."""
    result = {'a': {}, 'b': {}}

    fa = fingerprint(url_a, 0, INTRO_SCAN_SEC)
    fb = fingerprint(url_b, 0, INTRO_SCAN_SEC)
    n, ia, ib = best_common(fa, fb, max_shift=int(INTRO_SCAN_SEC / FRAME_SEC) // 2)
    sec = n * FRAME_SEC
    if MIN_INTRO_SEC <= sec <= MAX_INTRO_SEC:
        result['a']['intro'] = [round(ia * FRAME_SEC, 1), round((ia + n) * FRAME_SEC, 1)]
        result['b']['intro'] = [round(ib * FRAME_SEC, 1), round((ib + n) * FRAME_SEC, 1)]

    da, db = duration(url_a), duration(url_b)
    sa, sb = max(0, da - CREDITS_SCAN_SEC), max(0, db - CREDITS_SCAN_SEC)
    ca = fingerprint(url_a, sa, CREDITS_SCAN_SEC)
    cb = fingerprint(url_b, sb, CREDITS_SCAN_SEC)
    n, ia, ib = best_common(ca, cb, max_shift=int(CREDITS_SCAN_SEC / FRAME_SEC) // 2)
    sec = n * FRAME_SEC
    if sec >= MIN_CREDITS_SEC:
        # Титры — от начала общего куска до конца серии
        result['a']['credits'] = [round(sa + ia * FRAME_SEC, 1), round(da, 1)]
        result['b']['credits'] = [round(sb + ib * FRAME_SEC, 1), round(db, 1)]

    result['a']['duration'] = round(da, 1)
    result['b']['duration'] = round(db, 1)
    return result


if __name__ == '__main__':
    print(json.dumps(detect_pair(sys.argv[1], sys.argv[2]), ensure_ascii=False, indent=1))
