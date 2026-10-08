/*
 * Авто-торрент для Lampa.
 *
 * В меню «Смотреть» карточки появляется пункт «Авто». Он ищет раздачи тем же
 * парсером, что и кнопка «Торренты», сам выбирает лучшую под этот телевизор,
 * открывает её и через 3 секунды запускает файл — для сериала ту серию, на
 * которой остановились (её Лампа и так подсвечивает в списке файлов).
 * Любая кнопка пульта во время отсчёта — отмена, дальше выбираете руками.
 *
 * Как выбирается раздача (больше очков — лучше):
 *  - раздача, которую уже смотрели в этой карточке, — та же озвучка, без сюрпризов;
 *  - русский дубляж > многоголосый > авторский, без русской озвучки — минус;
 *  - 1080p; 4K только если битрейт пролезает в канал (~15 Мбит/с);
 *  - AV1 в минус (VLC 3 не умеет его аппаратно, уйдёт в Just Player);
 *  - Dolby Vision без HDR10 в минус (на не-DV экране зелёно-фиолетовые цвета);
 *  - больше сидов — лучше, меньше 2 сидов — только если нет ничего другого;
 *  - экранки (CAMRip, TS, TC) не берём вообще.
 */
(function () {
    'use strict';

    if (window.lampa_tv_autotorrent) return;
    window.lampa_tv_autotorrent = true;

    var LINK_MBPS = 15;
    var COUNTDOWN_SEC = 3;
    var ICON = '<svg viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">' +
        '<path d="M7 4.5v15l12-7.5z" fill="currentColor"/>' +
        '<path d="M19.5 2l.7 1.8 1.8.7-1.8.7-.7 1.8-.7-1.8-1.8-.7 1.8-.7z" fill="currentColor"/></svg>';

    function log() {
        var args = [].slice.call(arguments);
        try { console.log.apply(console, ['AutoTorrent'].concat(args)); } catch (e) {}
        // DEBUG: временно шлём лог на мак
        try { new Image().src = 'http://192.168.0.139:8767/l?' + encodeURIComponent(JSON.stringify(args).slice(0, 1500)); } catch (e) {}
    }

    function noty(text) {
        try { Lampa.Noty.show(text); } catch (e) {}
    }

    // Те же сочетания названий, что у кнопки «Торренты» (components/full/start/torrents.js)
    function searchParams(card) {
        var year = ((card.first_air_date || card.release_date || '0000') + '').slice(0, 4);
        var combinations = {
            'df': card.original_title,
            'df_year': card.original_title + ' ' + year,
            'df_lg': card.original_title + ' ' + card.title,
            'df_lg_year': card.original_title + ' ' + card.title + ' ' + year,
            'lg': card.title,
            'lg_year': card.title + ' ' + year,
            'lg_df': card.title + ' ' + card.original_title,
            'lg_df_year': card.title + ' ' + card.original_title + ' ' + year
        };
        return {
            search: combinations[Lampa.Storage.field('parse_lang')] || card.title,
            search_one: card.title,
            search_two: card.original_title,
            movie: card,
            page: 1
        };
    }

    function videoStream(el) {
        var list = el.ffprobe || [];
        for (var i = 0; i < list.length; i++) if (list[i].codec_type == 'video') return list[i];
        return null;
    }

    function resolution(el, t) {
        if (/2160p|\b4k\b|\buhd\b/.test(t)) return 2160;
        if (/1080[pi]/.test(t)) return 1080;
        if (/720p/.test(t)) return 720;
        var v = videoStream(el);
        if (v && v.height) return v.height >= 1600 ? 2160 : v.height >= 900 ? 1080 : v.height >= 650 ? 720 : 480;
        if (el.info && el.info.quality) return parseInt(el.info.quality, 10) || 0;
        return 0;
    }

    function codec(el, t) {
        var v = videoStream(el);
        if (v && v.codec_name) return (v.codec_name + '').toLowerCase();
        if (/\bav1\b/.test(t)) return 'av1';
        if (/hevc|h\.?265|x265/.test(t)) return 'hevc';
        if (/\bavc\b|h\.?264|x264/.test(t)) return 'h264';
        return '';
    }

    // Студии многоголосой озвучки: дубляжа нет — берём их, это нормальная озвучка
    var STUDIOS = new RegExp([
        'lostfilm', 'лостфильм', 'hdrezka', 'rezka', 'newstudio', 'novafilm', 'кубик в кубе', 'kubik',
        'jaskier', 'tvshows', 'alexfilm', 'amedia', 'novamedia', 'red head sound', 'redheadsound',
        'пифагор', 'pifagor', 'кураж-бамбей', 'kuraj', 'good people', 'baibako', 'байбако', 'ideafilm',
        'coldfilm', 'syncmer', 'gears media', 'ultradox', 'dragon money', 'flarrow'
    ].join('|'));

    function voices(el, t) {
        var v = (el.info && el.info.voices ? el.info.voices.join(' ') : '').toLowerCase() + ' ' + t;
        // \b в JS не работает с кириллицей — границы слов для «дб», «пм», «лм» руками
        if (/дубляж|(^|[^а-яё])дб([^а-яё]|$)|\bdub\b|\|\s*d\s*\|/.test(v)) return 'dub';
        if (/\bmvo\b|многоголос|(^|[^а-яё])[пл]м([^а-яё]|$)/.test(v) || STUDIOS.test(v)) return 'mvo';
        if (/\bavo\b|авторск|\bvo\b|одноголос/.test(v)) return 'avo';
        if (/[а-яё]/.test(v)) return 'ru';
        return 'none';
    }

    function sizeBytes(el) {
        var s = parseFloat(el.Size);
        return isNaN(s) ? 0 : s;
    }

    function runtimeMin(card) {
        if (card.runtime) return card.runtime;
        if (card.episode_run_time && card.episode_run_time.length) return card.episode_run_time[0];
        return 0;
    }

    function seasonsCovered(t) {
        var m = t.match(/(\d{1,2})\s*-\s*(\d{1,2})\s*сезон/) || t.match(/сезон[ыа-я]*[:\s]*(\d{1,2})\s*-\s*(\d{1,2})/) ||
            t.match(/s(\d{1,2})\s*-\s*s?(\d{1,2})/);
        if (m) return Math.max(1, parseInt(m[2], 10) - parseInt(m[1], 10) + 1);
        return /сезон|season|\bs\d{1,2}/.test(t) ? 1 : 0;
    }

    function score(el, card, viewedHashes, rec) {
        var t = ((el.Title || el.title || '') + '').toLowerCase();

        if (/camrip|\bcam\b|telesync|\bhdts\b|\bts\b|\btc\b|экранк|трейлер|trailer|soundtrack|\bost\b/.test(t)) return null;

        var res = resolution(el, t);
        var cod = codec(el, t);
        var voice = voices(el, t);
        var seeds = parseInt(el.Seeders, 10) || 0;
        var isSerial = !!card.number_of_seasons;
        var s = 0;
        var why = [];

        // Раздача, которую смотрели в этой карточке последней, — та же озвучка.
        // Просто когда-то открытая — небольшой плюс.
        if (rec && rec.torrent && el.hash == rec.torrent) { s += 200; why.push('последняя'); }
        else if (el.hash && viewedHashes.indexOf(el.hash) >= 0) { s += 40; why.push('смотрели'); }

        s += ({2160: 90, 1080: 100, 720: 70})[res] || (res ? 30 : 50);
        s += ({dub: 50, mvo: 25, avo: 10, ru: 5, none: -30})[voice];

        if (cod == 'av1') s -= 80;
        if (/dolby vision|\bdv\b|\bdovi\b/.test(t) && !/hdr10|hdr\b/.test(t)) s -= 60;

        if (seeds < 2) s -= 100;
        s += Math.min(40, Math.log(seeds + 1) / Math.LN2 * 6);

        var mbps = 0;
        var rt = runtimeMin(card);
        if (!isSerial && rt && sizeBytes(el)) {
            mbps = sizeBytes(el) * 8 / (rt * 60) / 1e6;
            if (mbps > LINK_MBPS) s -= (mbps - LINK_MBPS) * 4;
            if (res >= 1080 && mbps < 2) s -= 20;
        }

        if (isSerial) s += Math.min(30, seasonsCovered(t) * 3);

        return {
            el: el, score: s, res: res, codec: cod, voice: voice, seeds: seeds,
            mbps: Math.round(mbps * 10) / 10, why: why
        };
    }

    function pick(results, card) {
        var viewed = [];
        try { viewed = Lampa.Storage.cache('torrents_view', 5000, []) || []; } catch (e) {}

        var scored = [];
        for (var i = 0; i < results.length; i++) {
            var r = score(results[i], card, viewed, getRecord(card.id));
            if (r) scored.push(r);
        }
        scored.sort(function (a, b) { return b.score - a.score; });
        log('top', scored.slice(0, 5).map(function (r) {
            return Math.round(r.score) + ' | ' + r.res + 'p ' + r.codec + ' ' + r.voice + ' ' + r.seeds + 's ' + r.mbps + 'Mbps | ' + r.el.Title;
        }));
        return scored[0] || null;
    }

    function describe(r) {
        var voice = {dub: 'дубляж', mvo: 'многоголосый', avo: 'авторский', ru: 'рус.', none: 'без рус. озвучки'}[r.voice];
        var parts = [(r.res ? (r.res == 2160 ? '4K' : r.res + 'p') : '?'), voice, r.seeds + ' сидов'];
        try { if (r.el.Size) parts.push(Lampa.Utils.bytesToSize(r.el.Size)); } catch (e) {}
        return parts.join(' · ');
    }

    // Память плагина: по каждой карточке — последняя раздача и серия.
    var REC_KEY = 'autotorrent_last';

    function getRecord(id) {
        try { return (Lampa.Storage.get(REC_KEY, '{}') || {})[id] || null; } catch (e) { return null; }
    }

    function setRecord(id, patch) {
        if (!id) return;
        try {
            var all = Lampa.Storage.get(REC_KEY, '{}') || {};
            var r = all[id] || {};
            for (var k in patch) r[k] = patch[k];
            r.time = Date.now();
            all[id] = r;
            Lampa.Storage.set(REC_KEY, all);
        } catch (e) {}
    }

    function parseSE(path) {
        var p = (path || '').toLowerCase();
        var m = p.match(/s(\d{1,2})\s?e(\d{1,3})/) || p.match(/(\d{1,2})x(\d{2,3})/);
        if (m) return [parseInt(m[1], 10), parseInt(m[2], 10)];
        var se = p.match(/(\d{1,2})\s*сезон/) || p.match(/сезон\s*(\d{1,2})/) || p.match(/season\s*(\d{1,2})/);
        var ep = p.match(/(\d{1,3})\s*сери/) || p.match(/сери[яи]\s*(\d{1,3})/) || p.match(/episode\s*(\d{1,3})/);
        return se && ep ? [parseInt(se[1], 10), parseInt(ep[1], 10)] : null;
    }

    function tsPost(path, body, done) {
        var base = '';
        try { base = Lampa.Torserver.url(); } catch (e) {}
        if (!base) return done(null);
        var x = new XMLHttpRequest();
        x.open('POST', base.replace(/\/$/, '') + path, true);
        x.setRequestHeader('Content-Type', 'application/json');
        x.timeout = 5000;
        x.onload = function () { try { done(JSON.parse(x.responseText)); } catch (e) { done(null); } };
        x.onerror = x.ontimeout = function () { done(null); };
        x.send(JSON.stringify(body));
    }

    // Нет памяти плагина (сериал начат до него) — ищем в истории TorrServer:
    // самая дальняя серия этого сериала среди всех раздач, что он помнит.
    function serverLastEpisode(card, done) {
        var names = [card.original_name, card.name, card.original_title, card.title]
            .filter(Boolean).map(function (n) { return (n + '').toLowerCase(); });
        tsPost('/viewed', {action: 'list'}, function (viewed) {
            tsPost('/torrents', {action: 'list'}, function (torrents) {
                if (!viewed || !viewed.length || !torrents) return done(null);
                var best = null, pending = 0;
                torrents.forEach(function (t) {
                    var title = (t.title || t.name || '').toLowerCase();
                    var mine = names.some(function (n) { return title.indexOf(n) >= 0; });
                    var idx = viewed.filter(function (v) { return v.hash == t.hash; })
                        .map(function (v) { return v.file_index; });
                    if (!mine || !idx.length) return;
                    pending++;
                    tsPost('/torrents', {action: 'get', hash: t.hash}, function (full) {
                        ((full && full.file_stats) || []).forEach(function (f) {
                            if (idx.indexOf(f.id) < 0) return;
                            var se = parseSE(f.path);
                            if (se && (!best || se[0] > best[0] || (se[0] == best[0] && se[1] > best[1]))) best = se;
                        });
                        if (--pending == 0) done(best);
                    });
                });
                if (!pending) done(null);
            });
        });
    }

    function findEpisode(items, se) {
        for (var k = 0; k < items.length; k++) {
            if (items[k].season == se[0] && items[k].episode == se[1]) return k;
        }
        for (var j = 0; j < items.length; j++) {
            var p = parseSE(items[j].path || items[j].path_human);
            if (p && p[0] == se[0] && p[1] == se[1]) return j;
        }
        return -1;
    }

    // Какой файл запускать: серия из памяти плагина → последний открытый файл
    // этой раздачи по TorrServer → самая дальняя серия сериала по истории
    // TorrServer → то, что подсветила сама Лампа.
    function decideTarget(items, card, done) {
        var rec = getRecord(card.id);
        if (rec && rec.s && rec.e) {
            var k = findEpisode(items, [rec.s, rec.e]);
            if (k >= 0) return done(k, 'память');
        }

        // Своя история Лампы: строка «Torrent • Сезон 2 • Серия 1» в торрентах
        try {
            var key = Lampa.Utils.hash(card.number_of_seasons ? card.original_name : card.original_title);
            var w = (Lampa.Storage.cache('online_watched_last', 5000, {}) || {})[key];
            if (w && w.season && w.episode) {
                var kw = findEpisode(items, [parseInt(w.season, 10), parseInt(w.episode, 10)]);
                if (kw >= 0) return done(kw, 'лампа');
            }
        } catch (e) {}

        var link = '';
        for (var i = 0; i < items.length && !link; i++) {
            var m = (items[i].url || '').match(/[?&]link=([0-9a-f]{40})/i);
            if (m) link = m[1].toLowerCase();
        }

        tsPost('/viewed', {action: 'list', hash: link}, function (viewed) {
            var lastId = -1;
            (viewed || []).forEach(function (v) {
                if (v.hash == link && v.file_index > lastId) lastId = v.file_index;
            });
            if (lastId >= 0) {
                for (var j = 0; j < items.length; j++) if (items[j].id == lastId) return done(j, 'раздача');
            }
            if (!card.number_of_seasons) return done(-1, '');
            serverLastEpisode(card, function (se) {
                done(se ? findEpisode(items, se) : -1, se ? 'история' : '');
            });
        });
    }

    // После открытия списка файлов: ждём, пока Лампа его нарисует, выбираем
    // файл и через пару секунд жмём на него.
    function autoplayFiles(items, card) {
        var cancelled = false;
        var started = Date.now();

        function onKey() {
            cancelled = true;
            noty('Авто: запуск отменён, выберите файл сами');
            cleanup();
        }

        function cleanup() {
            try { Lampa.Keypad.listener.remove('keydown', onKey); } catch (e) {}
        }

        function waitList() {
            var box = $('.torrent-files');
            var found = box.find('.selector');
            if (!found.length) {
                if (Date.now() - started < 15000) setTimeout(waitList, 300);
                return;
            }
            // Лампа сама включает 10-секундный автостарт, если файл один, —
            // глушим его так же, как это делает нажатие любой кнопки.
            try { Lampa.Keypad.listener.send('keydown', {code: 0, enabled: true, event: {}}); } catch (e) {}
            try { Lampa.Keypad.listener.follow('keydown', onKey); } catch (e) {}

            decideTarget(items, card, function (k, how) {
                if (cancelled) return;
                var files = box.find('.torrent-file, .torrent-serial');
                var target = k >= 0 && files.eq(k).length ? files.eq(k) : box.find('.selector.focus').first();
                if (!target.length) target = files.length ? files.first() : found.first();
                var it = k >= 0 ? items[k] : null;
                var what = it ? (it.season && it.episode ? 'S' + it.season + ' E' + it.episode
                    : (it.path_human || it.path || '').split('/').pop()) : '';
                log('target', {k: k, how: how, what: what, files: files.length});

                noty('Авто: ' + (what ? what + ' · ' : '') + 'запуск через ' + COUNTDOWN_SEC + ' с · любая кнопка — отмена');

                setTimeout(function () {
                    cleanup();
                    if (cancelled || !$.contains(document, target[0])) return;
                    target.trigger('hover:enter');
                }, COUNTDOWN_SEC * 1000);
            });
        }

        setTimeout(waitList, 300);
    }

    function run(card) {
        noty('Авто: ищу лучшую раздачу…');

        Lampa.Parser.get(searchParams(card), function (data) {
            var results = data && data.Results ? data.Results : [];
            var best = pick(results, card);

            if (!best) {
                noty('Авто: подходящих раздач не нашлось');
                return;
            }

            noty('Авто: ' + describe(best));

            var el = best.el;
            el.poster = card.img;
            setRecord(card.id, {torrent: el.hash, title: el.Title});

            var onList = function (e) {
                if (e.type == 'list_open') {
                    Lampa.Listener.remove('torrent_file', onList);
                    autoplayFiles(e.items || [], card);
                }
            };
            Lampa.Listener.follow('torrent_file', onList);
            // Если раздача не открылась (ошибка сервера и т.п.), не ловим чужие списки
            setTimeout(function () { Lampa.Listener.remove('torrent_file', onList); }, 120000);

            Lampa.Torrent.opened(function () {
                try {
                    var viewed = Lampa.Storage.cache('torrents_view', 5000, []);
                    if (el.hash && viewed.indexOf(el.hash) == -1) {
                        viewed.push(el.hash);
                        Lampa.Storage.set('torrents_view', viewed);
                    }
                } catch (e) {}
            });

            Lampa.Torrent.start(el, card);
        }, function (text) {
            noty('Авто: парсер не ответил' + (text ? ': ' + text : ''));
        });
    }

    function addButton(e) {
        if (e.type != 'complite') return;

        var body = e.body || (e.object && e.object.activity ? e.object.activity.render() : null);
        var card = e.data && e.data.movie;
        if (!body || !card) return;

        var torrentBtn = body.find('.view--torrent');
        if (!torrentBtn.length || body.find('.view--autotorrent').length) return;

        var btn = $('<div class="full-start__button selector view--autotorrent" data-subtitle="Сам выберет раздачу и запустит">' +
            ICON + '<span>Авто</span></div>');
        btn.attr('class', (torrentBtn.attr('class') || 'full-start__button selector')
            .replace('view--torrent', 'view--autotorrent').replace(/\bfocus\b/, '').replace(/\bhide\b/, ''));
        btn.on('hover:enter', function () { run(card); });

        torrentBtn.before(btn);
    }

    function remember() {
        // Запоминаем и ручной выбор: раздачу из списка торрентов и серию из списка файлов
        Lampa.Listener.follow('torrent', function (e) {
            if (e.type != 'onenter' || !e.element) return;
            var a = null;
            try { a = Lampa.Activity.active(); } catch (err) {}
            if (a && a.movie) setRecord(a.movie.id, {torrent: e.element.hash, title: e.element.Title});
        });
        Lampa.Listener.follow('torrent_file', function (e) {
            if (e.type != 'onenter' || !e.element || !e.params || !e.params.movie) return;
            var se = e.element.season && e.element.episode ? [e.element.season, e.element.episode]
                : parseSE(e.element.path || e.element.path_human);
            setRecord(e.params.movie.id, se ? {s: se[0], e: se[1]} : {s: 0, e: 0});
        });
    }

    function start() {
        remember();
        Lampa.Listener.follow('full', addButton);
        log('ready');
    }

    if (window.appready) start();
    else Lampa.Listener.follow('app', function (e) { if (e.type == 'ready') start(); });
})();
