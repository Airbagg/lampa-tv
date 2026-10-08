#!/usr/bin/env python3
"""
Прототип: время реплик субтитров из оглавления MKV (Cues) без чтения фильма.

mkvmerge кладёт в Cues отметку на каждый блок субтитров. Оглавление лежит
в конце файла (или в начале), читаем только его. По паузам между репликами в
конце фильма видно титры и сцены после них.

    python3 stinger.py <url TorrServer stream>
"""
import struct
import sys
import urllib.request

ID_SEGMENT, ID_SEEKHEAD, ID_SEEK, ID_SEEKID, ID_SEEKPOS = 0x18538067, 0x114D9B74, 0x4DBB, 0x53AB, 0x53AC
ID_INFO, ID_TIMESCALE, ID_DURATION = 0x1549A966, 0x2AD7B1, 0x4489
ID_TRACKS, ID_TRACKENTRY, ID_TRACKNUM, ID_TRACKTYPE, ID_CODEC, ID_LANG, ID_NAME, ID_FORCED = \
    0x1654AE6B, 0xAE, 0xD7, 0x83, 0x86, 0x22B59C, 0x536E, 0x55AA
ID_CUES, ID_CUEPOINT, ID_CUETIME, ID_CUETRACKPOS, ID_CUETRACK = 0x1C53BB6B, 0xBB, 0xB3, 0xB7, 0xF7


def fetch(url, start, length):
    req = urllib.request.Request(url, headers={'Range': f'bytes={start}-{start + length - 1}'})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def vint(b, i, strip=True):
    first = b[i]
    n = 1
    mask = 0x80
    while n <= 8 and not first & mask:
        n += 1
        mask >>= 1
    v = first & (mask - 1) if strip else first
    for k in range(1, n):
        v = (v << 8) | b[i + k]
    return v, n


def elements(b, start, end):
    i = start
    while i < end:
        eid, n1 = vint(b, i, strip=False)
        size, n2 = vint(b, i + n1)
        data = i + n1 + n2
        if size == (1 << (7 * n2)) - 1:  # неизвестный размер
            size = end - data
        yield eid, data, size
        i = data + size


def uint(b, i, n):
    return int.from_bytes(b[i:i + n], 'big')


def main(url):
    head = fetch(url, 0, 1 << 20)
    # EBML header, затем Segment
    _, d, s = next(elements(head, 0, len(head)))
    seg_id, seg_data, _ = next(elements(head, d + s, len(head)))
    assert seg_id == ID_SEGMENT, hex(seg_id)

    positions = {}
    timescale, duration, tracks = 1_000_000, None, {}
    for eid, d, s in elements(head, seg_data, len(head)):
        if eid == ID_SEEKHEAD:
            for e2, d2, s2 in elements(head, d, d + s):
                if e2 == ID_SEEK:
                    sid = pos = None
                    for e3, d3, s3 in elements(head, d2, d2 + s2):
                        if e3 == ID_SEEKID:
                            sid = uint(head, d3, s3)
                        elif e3 == ID_SEEKPOS:
                            pos = uint(head, d3, s3)
                    positions[sid] = seg_data + pos
        elif eid == ID_INFO:
            for e2, d2, s2 in elements(head, d, d + s):
                if e2 == ID_TIMESCALE:
                    timescale = uint(head, d2, s2)
                elif e2 == ID_DURATION:
                    duration = struct.unpack('>f' if s2 == 4 else '>d', head[d2:d2 + s2])[0]
        elif eid == ID_TRACKS:
            for e2, d2, s2 in elements(head, d, d + s):
                if e2 != ID_TRACKENTRY:
                    continue
                t = {}
                for e3, d3, s3 in elements(head, d2, d2 + s2):
                    if e3 == ID_TRACKNUM: t['num'] = uint(head, d3, s3)
                    elif e3 == ID_TRACKTYPE: t['type'] = uint(head, d3, s3)
                    elif e3 == ID_CODEC: t['codec'] = head[d3:d3 + s3].decode()
                    elif e3 == ID_LANG: t['lang'] = head[d3:d3 + s3].decode()
                    elif e3 == ID_NAME: t['name'] = head[d3:d3 + s3].decode('utf-8', 'replace')
                    elif e3 == ID_FORCED: t['forced'] = uint(head, d3, s3)
                tracks[t.get('num')] = t
        elif eid == 0x1F43B675:  # Cluster — дальше заголовков нет
            break

    dur_sec = duration * timescale / 1e9 if duration else None
    print('duration', round(dur_sec or 0), 's;', 'cues at', positions.get(ID_CUES))
    subs = {n: t for n, t in tracks.items() if t.get('type') == 0x11}
    for n, t in subs.items():
        print('  sub track', n, t.get('codec'), t.get('lang'), t.get('name'), 'forced' if t.get('forced') else '')

    cues_pos = positions.get(ID_CUES)
    hdr = fetch(url, cues_pos, 16)
    _, n1 = vint(hdr, 0, strip=False)
    size, n2 = vint(hdr, n1)
    cues = fetch(url, cues_pos, n1 + n2 + size)
    times = {n: [] for n in subs}
    for eid, d, s in elements(cues, n1 + n2, len(cues)):
        if eid != ID_CUEPOINT:
            continue
        t = None
        trks = []
        for e2, d2, s2 in elements(cues, d, d + s):
            if e2 == ID_CUETIME:
                t = uint(cues, d2, s2) * timescale / 1e9
            elif e2 == ID_CUETRACKPOS:
                for e3, d3, s3 in elements(cues, d2, d2 + s2):
                    if e3 == ID_CUETRACK:
                        trks.append(uint(cues, d3, s3))
        for tr in trks:
            if tr in times:
                times[tr].append(t)

    for n, ts in times.items():
        ts.sort()
        print(f'track {n} ({subs[n].get("lang")} {subs[n].get("name")}): {len(ts)} cues')
        if not ts or not dur_sec:
            continue
        tail = [x for x in ts if x > dur_sec - 1800]
        gaps = [(a, b) for a, b in zip(tail, tail[1:]) if b - a > 60]
        print('   last 30 min gaps > 60 s:', [(round(a), round(b), round(b - a)) for a, b in gaps])
        print('   last cue at', round(ts[-1]), 's of', round(dur_sec))


if __name__ == '__main__':
    main(sys.argv[1])
