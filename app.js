'use strict';
(function () {
  /* =====================================================================
   *  M3U — IPTV playlist studio for Tiger receivers
   * ===================================================================== */
  const $ = (s, r) => (r || document).querySelector(s);
  const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const enc = encodeURIComponent;
  const IS_TV = /[?&]tv=1/.test(location.search);
  const PAGE = 60;
  const AR_RE = /[؀-ۿ]/;
  const reEsc = s => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const fmtN = n => Number(n || 0).toLocaleString('en-US');
  const cv = h => { const n = parseInt((h || '#3b82f6').slice(1), 16), r = n >> 16, g = (n >> 8) & 255, b = n & 255;
    return `--c:${h};` + [22, 25, 26, 30, 40, 45, 55, 60].map(a => `--c${a}:rgba(${r},${g},${b},${a / 100})`).join(';'); };
  const fmtMB = b => (b / 1048576).toFixed(b > 10485760 ? 0 : 1) + ' MB';

  /* ---------------------------------------------------------------- packages */
  const BUILTIN = [
    { id: 'fav', ar: 'المفضلة', en: 'Favorites', ic: '⭐', col: '#f5b301', auto: false },
    { id: 'uhd', ar: 'قنوات 4K', en: '4K UHD', ic: '💎', col: '#8b5cf6' },
    { id: 'sports', ar: 'رياضة', en: 'Sports', ic: '⚽', col: '#22c55e' },
    { id: 'news', ar: 'أخبار', en: 'News', ic: '📰', col: '#ef4444' },
    { id: 'islamic', ar: 'إسلامي', en: 'Islamic', ic: '🕌', col: '#14b8a6' },
    { id: 'movies', ar: 'أفلام', en: 'Movies', ic: '🎬', col: '#f97316' },
    { id: 'series', ar: 'مسلسلات', en: 'Series', ic: '🎭', col: '#ec4899' },
    { id: 'kids', ar: 'أطفال', en: 'Kids', ic: '🧸', col: '#facc15' },
    { id: 'docs', ar: 'طبيعة ووثائقي', en: 'Nature & Docs', ic: '🌿', col: '#84cc16' },
    { id: 'variety', ar: 'منوع', en: 'Entertainment', ic: '🎉', col: '#06b6d4' }
  ];
  const LANG_NAMES = { all: 'كل اللغات', ar: 'عربي', en: 'إنجليزي', aren: 'عربي + إنجليزي', for: 'غير عربي' };
  const DEFAULT_ON = ['fav', 'uhd', 'sports', 'news', 'islamic', 'movies', 'series', 'kids', 'docs', 'variety'];
  const COLORS = ['#22d3ee', '#3b82f6', '#8b5cf6', '#ec4899', '#f97316', '#22c55e', '#14b8a6', '#facc15', '#ef4444'];
  const RULES = {
    uhd: /(^|[^a-z0-9])(4k|uhd|2160p?|8k)([^a-z0-9]|$)|ᵁᴴᴰ|⁴ᴷ/i,
    sports: /sport|bein|ssc|espn|eurosport|dazn|arena|al ?kass|kass|ad ?sports?|abu ?dhabi ?sport|dubai ?sport|on ?time ?sport|\bnba\b|\bnfl\b|\bnhl\b|\bmlb\b|\bufc\b|\bwwe\b|premier ?league|la ?liga|serie ?a\b|bundesliga|ligue ?1|\bf1\b|formula|moto ?gp|tennis|golf|fight|boxing|racing|football|soccer|cricket|\byas\b|رياض|سبورت|الكأس|ملاعب|كورة|دوري/i,
    news: /news|\bcnn\b|\bbbc\b|jazeera|jazira|arabiya|sky ?news|cnbc|bloomberg|france ?24|\brt\b|euronews|hadath|hurra|asharq|ekhbariya|trt ?world|\bdw\b|mayadeen|أخبار|اخبار|الجزيرة|العربية|الحدث|نيوز|الإخبارية|الاخبارية|الميادين|الشرق/i,
    islamic: /quran|qur.?an|islam|makkah|makka|mecca|madina|medina|sunnah|sunna|iqraa?|\bmajd\b|al ?majd|resalah|risala|hidaya|\bhuda\b|peace ?tv|\bzad\b|قرآن|قران|اسلام|إسلام|مكة|المدينة|السنة|اقرأ|المجد|الرسالة|الهدى|زاد|الرحمة|طيبة|الحرم/i,
    movies: /movie|film|cinema|\bcine|kino|\bhbo|starz|showtime|paramount|mbc ?2\b|mbc ?max|mbc ?action|mbc ?bollywood|rotana ?(cinema|classic|aflam)|fox ?movies|osn ?movies|zee ?aflam|aflam|أفلام|افلام|فيلم|سينما|اكشن|أكشن/i,
    series: /series|drama|\bserie|shahid|mbc ?4\b|mbc ?5\b|osn ?series|rotana ?drama|novela|مسلسل|دراما/i,
    kids: /\bkid|kids|cartoon|toon|disney|nick|junior|baby|spacetoon|space ?toon|majid|ajyal|\bjeem|cbeebies|boomerang|mbc ?3\b|baraem|karameesh|toyor|أطفال|اطفال|كرتون|سبيستون|ماجد|براعم|طيور|كراميش|أجيال/i,
    docs: /nat ?geo|national ?geographic|discovery|animal|planet|nature|wild|documentar|\bdocu|history|science|travel|وثائق|ناشيونال|طبيعة|ديسكفري|حيوان|تاريخ|علوم|سفر/i,
    variety: /entertain|general|variety|music|comedy|lifestyle|food|cooking|fashion|reality|mbc ?1\b|\blbc\b|\bmtv\b|rotana ?(khalijia|music|clip|comedy)|wanasah|منوع|ترفيه|عامة|موسيق|أغاني|اغاني|كوميد|طبخ|مطبخ/i
  };
  const NEG = {
    news: /sport|docu|وثائق|رياض|kids|أطفال|business ?class/i,
    movies: /sport|news|kids|أطفال/i,
    variety: /sport|news|kids|أطفال|movie|film|أفلام|افلام/i
  };
  const AR_TAG = /(^|[\s|:\[\](){}\-_./])(ar|ara|arab|arabic|arabia|ksa|uae|egy|egypt|eg|kw|kuwait|qa|qatar|bh|bahrain|oman|jo|jordan|iq|iraq|sy|syria|lb|leb|lebanon|morocco|ma|mar|maroc|dz|algeria|tn|tunisia|ly|libya|sudan|ye|yemen|palestine|gcc|mena|saudi|khaleej|khaliji)(?=$|[\s|:\[\](){}\-_./])/i;
  const AR_BRAND = /mbc|rotana|al ?kass|\bssc\b|al ?arabiya|jazeera|\blbc\b|dubai|abu ?dhabi|sharjah|ajman|oman ?tv|saudi|\bsbc\b|\bnile\b|\bcbc\b|al ?nahar|al ?hayah|\bdmc\b|shahid|spacetoon|majid|iqraa|al ?majd|al ?resalah|ajyal|\bjeem|baraem|bein ?sports? ?(ar|arabic|\d+ ?ar)/i;
  const EN_TAG = /(^|[\s|:\[\](){}\-_./])(uk|gb|us|usa|en|eng|english|ca|can|canada|au|aus|australia|ie|ireland|nz|int|intl)(?=$|[\s|:\[\](){}\-_./])/i;
  const OTHER_TAG = /(^|[\s|:\[\](){}\-_./])(fr|france|de|ger|germany|it|italy|es|spain|tr|turk|turkey|türk|in|ind|india|pk|pak|nl|pl|pt|gr|ro|al|ru|se|no|dk|fi|be|ch|at|cz|hu|bg|rs|hr|ba|mk|br|mx|latino|latam|ir|iran|af|afg|bd|cn|jp|kr|ph|th|vn|my|id|ku|kurd|kurdish|persian|farsi|hindi|urdu|punjabi|tamil|french|german|italian|spanish|portugal|exyu|ex-yu|balkan|africa|somali|ethiopia)(?=$|[\s|:\[\](){}\-_./])/i;
  const LANG_OK = { all: () => true, ar: c => c.lang === 'ar', en: c => c.lang === 'en', aren: c => c.lang !== 'other', for: c => c.lang !== 'ar' };
  const ADULT = /xxx|adult|\b18\+|\+18|for adults|porn|\bsex|playboy|hustler|brazzers|redlight|dorcel|vivid|للكبار|اباحي/i;

  /* ---------------------------------------------------------------- i18n + theme */
  const EN = () => (S.settings || {}).lang === 'en';
  const L = (a, e) => EN() ? e : a;
  const DICT = window.I18N_EN || {};
  const DKEYS = Object.keys(DICT).sort((a, b) => b.length - a.length);
  function tr(t) {
    if (!EN() || !t || !AR_RE.test(t)) return t;
    const k = t.trim();
    if (DICT[k]) return t.replace(k, DICT[k]);
    let o = t;
    for (const d of DKEYS) if (o.indexOf(d) >= 0) { o = o.split(d).join(DICT[d]); if (!AR_RE.test(o)) break; }
    return o;
  }
  function translate(root) {
    if (!EN() || !root) return;
    const w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, { acceptNode: n => n.parentNode.closest('.nt,script,style') ? 2 : 1 });
    const nodes = []; let n; while ((n = w.nextNode())) if (AR_RE.test(n.nodeValue)) nodes.push(n);
    nodes.forEach(x => { x.nodeValue = tr(x.nodeValue); });
    root.querySelectorAll('[placeholder],[title]').forEach(e => {
      if (e.placeholder && AR_RE.test(e.placeholder)) e.placeholder = tr(e.placeholder);
      if (e.title && AR_RE.test(e.title)) e.title = tr(e.title);
    });
  }
  function applyLook() {
    const st = S.settings || {};
    document.documentElement.lang = EN() ? 'en' : 'ar';
    document.documentElement.dir = EN() ? 'ltr' : 'rtl';
    let th = st.theme || 'dark';
    if (th === 'auto') { const h = new Date().getHours(); th = h >= 6 && h < 18 ? 'light' : 'dark'; }
    document.documentElement.dataset.theme = th;
  }
  setInterval(() => { if ((S.settings || {}).theme === 'auto') applyLook(); }, 600000);

  /* ---------------------------------------------------------------- state */
  let S = { sources: [], active: null, cfg: {}, settings: { targetPkg: '', targetLabel: '' } };
  let CH = [];            // channels of the active source
  let BYK = new Map();    // key -> channel
  let GROUPS = [];        // [{name, list}]
  let ACC = null;         // xtream account info
  let INFO = {};          // device info from the receiver
  let MEMO = null;        // package -> members cache
  const ui = {
    view: 'subs', hist: [], pk: null, grp: null, q: '', page: 0, move: null, target: 'fav',
    showRem: false, loading: null, srcTab: 'link', modal: null, editSrc: null, dragKey: null
  };

  function newCfg() {
    return {
      pk: BUILTIN.map(b => ({ id: b.id, ar: b.ar, en: b.en, ic: b.ic, col: b.col, on: DEFAULT_ON.includes(b.id), auto: b.auto !== false, lang: 'all', kw: '', grp: [], add: [], rem: [], ord: [] })),
      opt: { dup: false, vod: false, adult: true, lang: 'all', nameLang: 'ar', clean: true, logos: true, icons: false, chno: false, fname: 'M3U_TV' },
      cren: {}
    };
  }
  function cfg() {
    if (!S.active) return newCfg();
    if (!S.cfg[S.active]) S.cfg[S.active] = newCfg();
    return S.cfg[S.active];
  }
  const pkById = id => cfg().pk.find(p => p.id === id);
  const src = () => S.sources.find(s => s.id === S.active);
  const pkName = pk => {
    const m = cfg().opt.nameLang, ar = pk.ar || pk.en, en = pk.en || pk.ar;
    return m === 'en' ? en : m === 'both' ? (ar === en ? ar : ar + ' | ' + en) : ar;
  };

  let saveT = null;
  function save() {
    clearTimeout(saveT);
    saveT = setTimeout(() => {
      fetch('/api/state', { method: 'POST', body: JSON.stringify(S) }).catch(() => { });
    }, 600);
  }
  function changed() { MEMO = null; save(); }

  /* ---------------------------------------------------------------- parsing */
  function parseInf(l) {
    let inq = false, idx = -1;
    for (let i = 8; i < l.length; i++) {
      const ch = l[i];
      if (ch === '"') inq = !inq;
      else if (ch === ',' && !inq) { idx = i; break; }
    }
    const head = idx < 0 ? l : l.slice(0, idx);
    const name = idx < 0 ? '' : l.slice(idx + 1).trim();
    const a = {};
    head.replace(/([\w-]+)=("([^"]*)"|([^\s"]+))/g, (m, k, _, v1, v2) => { a[k.toLowerCase()] = v1 != null ? v1 : (v2 || ''); return ''; });
    return { n: name || a['tvg-name'] || '', a };
  }
  /* streaming parser: lines are parsed while the file is still downloading */
  function M3UParser() {
    const out = [];
    let buf = '', cur = null, grp = null, opts = null;
    function line(raw) {
      const l = raw.trim();
      if (!l) return;
      if (l.charCodeAt(0) === 35) {
        if (l.startsWith('#EXTINF')) { cur = parseInf(l); opts = null; grp = null; }
        else if (l.startsWith('#EXTGRP:')) grp = l.slice(8).trim();
        else if (l.startsWith('#EXTVLCOPT') || l.startsWith('#KODIPROP') || l.startsWith('#EXTHTTP')) (opts || (opts = [])).push(l);
        return;
      }
      if (!cur && !/^[a-z]+:\/\//i.test(l)) return;
      const c = cur || { n: '', a: {} };
      let n = c.n;
      if (!n) { try { n = decodeURIComponent(l.split('?')[0].split('/').pop() || 'Channel'); } catch (e) { n = 'Channel'; } }
      out.push({ n, a: c.a, u: l, g: c.a['group-title'] || grp || '', o: opts });
      cur = null; opts = null; grp = null;
    }
    return {
      out,
      feed(t) {
        buf += t;
        let s = 0, k;
        while ((k = buf.indexOf('\n', s)) >= 0) { line(buf.slice(s, k)); s = k + 1; }
        buf = buf.slice(s);
      },
      end() { if (buf) line(buf); buf = ''; return out; }
    };
  }
  function parseM3U(text) { const p = M3UParser(); p.feed(text); return p.end(); }
  function langOf(c) {
    if (AR_RE.test(c.n) || AR_RE.test(c.g) || AR_TAG.test(c.g) || AR_TAG.test(c.n) || AR_BRAND.test(c.n)) return 'ar';
    if (EN_TAG.test(c.g) || EN_TAG.test(c.n)) return 'en';
    if (OTHER_TAG.test(c.g) || OTHER_TAG.test(c.n)) return 'other';
    return 'en';
  }
  function qualityOf(t) {
    if (/(^|[^a-z0-9])(4k|uhd|2160p?|8k)([^a-z0-9]|$)|ᵁᴴᴰ|⁴ᴷ/i.test(t)) return 4;
    if (/fhd|1080|full ?hd|ᶠᴴᴰ/i.test(t)) return 3;
    if (/(^|[^a-z0-9])(hd|720p?|hevc|h265)([^a-z0-9]|$)|ᴴᴰ/i.test(t)) return 2;
    return 1;
  }
  function typeOf(u) {
    if (/\/movie\//i.test(u) || /\.(mp4|mkv|avi|mov|wmv)(\?|$)/i.test(u)) return 'movie';
    if (/\/series\//i.test(u)) return 'series';
    return 'live';
  }
  function setChannels(list) {
    const dupCount = new Map();
    BYK = new Map();
    const gm = new Map();
    CH = list.map((c, i) => {
      c.i = i;
      c.g = (c.g || '').trim() || 'بدون مجموعة';
      c.n = (c.n || '').trim() || 'Channel';
      c.l = c.a['tvg-logo'] || c.a.logo || '';
      c.t = typeOf(c.u);
      c.txt = c.g + ' ' + c.n;
      c.lang = langOf(c);
      c.q = qualityOf(c.n);
      c.x = ADULT.test(c.txt);
      let k = c.g + '\u0001' + c.n;
      const d = dupCount.get(k) || 0;
      dupCount.set(k, d + 1);
      if (d) k += '#' + d;
      c.k = k;
      BYK.set(k, c);
      if (!gm.has(c.g)) gm.set(c.g, []);
      gm.get(c.g).push(c);
      return c;
    });
    GROUPS = [...gm].map(([name, list]) => ({ name, list }));
    GMAP = gm;
    MEMO = null; BASE = new Map(); POOL = null;
  }
  function cleanName(n) {
    let s = n;
    for (let i = 0; i < 2; i++) {
      s = s.replace(/^\s*(\|[^|]{1,6}\|\s*|\[[^\]]{1,8}\]\s*|\([A-Z]{2,4}\)\s*|[A-Z]{2,5}\s*[:|•\-–]\s+|[A-Z]{2,5}\s*\|\s*|[A-Z]{2,5}:\s*)/, '');
    }
    s = s.replace(/\s{2,}/g, ' ').trim();
    return s || n;
  }
  const dispName = c => cfg().cren[c.k] || (cfg().opt.clean ? cleanName(c.n) : c.n);

  /* ---------------------------------------------------------------- engine */
  function poolOK(o) {
    const ok = LANG_OK[o.lang] || LANG_OK.all;
    return c => (c.t === 'live' || o.vod) && !(o.adult && c.x) && ok(c);
  }
  function poolList() { return CH.filter(poolOK(cfg().opt)); }
  function baseMatch(pk, c, kw, grp) {
    if (pk.id === 'fav') return false;
    let m = false;
    if (pk.auto !== false) {
      if (pk.id === 'arabic') m = c.lang === 'ar';
      else if (pk.id === 'foreign') m = c.lang !== 'ar';
      else if (RULES[pk.id]) m = RULES[pk.id].test(c.txt) && !(NEG[pk.id] && NEG[pk.id].test(c.txt));
    }
    if (!m && kw) m = kw.test(c.txt);
    if (!m && grp.size) m = grp.has(c.g);
    if (m && pk.lang && pk.lang !== 'all') m = (LANG_OK[pk.lang] || LANG_OK.all)(c);
    return m;
  }
  /* The expensive part (regex matching over every channel) is cached per package and only redone
     when that package's rules change; ordinary edits (move / add / remove) are cheap set operations. */
  let BASE = new Map(), POOL = null, POOLSIG = '';
  function basesFor(pk, o) {
    const sig = [pk.auto, pk.kw, (pk.grp || []).join('\u0002'), pk.lang, o.vod, o.adult, o.lang, CH.length].join('|');
    let b = BASE.get(pk.id);
    if (b && b.sig === sig) return b.list;
    const psig = [o.vod, o.adult, o.lang, CH.length].join('|');
    if (!POOL || POOLSIG !== psig) { POOL = poolList(); POOLSIG = psig; }
    const words = (pk.kw || '').split(/[,،]/).map(s => s.trim()).filter(Boolean);
    const kw = words.length ? new RegExp(words.map(reEsc).join('|'), 'i') : null;
    const grp = new Set(pk.grp || []);
    let list;
    if (pk.id === 'fav') list = [];
    else if (pk.auto === false && !kw) {             // provider-group package: no scanning needed
      const ok = poolOK(o), lg = LANG_OK[pk.lang] || LANG_OK.all;
      list = [];
      for (const g of grp) { const G = GMAP.get(g); if (G) for (const c of G) if (ok(c) && (pk.lang === 'all' || !pk.lang || lg(c))) list.push(c); }
    } else list = POOL.filter(c => baseMatch(pk, c, kw, grp));
    BASE.set(pk.id, { sig, list, set: null });
    return list;
  }
  let MEMOSET = new Map(), GMAP = new Map();
  function inBase(pk, c) {
    const b = BASE.get(pk.id); if (!b) return false;
    if (!b.set) b.set = new Set(b.list.map(x => x.k));
    return b.set.has(c.k);
  }
  function computeAll() {
    if (MEMO) return MEMO;
    const C = cfg(), claimed = new Set();
    MEMO = new Map(); MEMOSET = new Map();
    for (const pk of C.pk) {
      const base = basesFor(pk, C.opt);
      const rem = new Set(pk.rem || []);
      const list = [], inList = new Set();
      for (const c of base) {
        if (rem.has(c.k)) continue;
        if (!C.opt.dup && claimed.has(c.k)) continue;
        list.push(c); inList.add(c.k);
      }
      for (const k of pk.add || []) {
        const c = BYK.get(k);
        if (c && !inList.has(k) && !rem.has(k)) { list.push(c); inList.add(k); }
      }
      if (pk.ord && pk.ord.length) {
        const pos = new Map(pk.ord.map((k, i) => [k, i]));
        const seq = new Map(list.map((c, i) => [c.k, i]));
        list.sort((a, b) => (pos.has(a.k) ? pos.get(a.k) : 1e7 + seq.get(a.k)) - (pos.has(b.k) ? pos.get(b.k) : 1e7 + seq.get(b.k)));
      }
      MEMO.set(pk.id, list); MEMOSET.set(pk.id, inList);
      if (pk.on && pk.id !== 'fav') for (const k of inList) claimed.add(k);
    }
    return MEMO;
  }
  const members = id => computeAll().get(id) || [];
  function inPk(pk, c) { computeAll(); const st = MEMOSET.get(pk.id); return !!(st && st.has(c.k)); }
  function addTo(pk, c) {
    const was = inPk(pk, c);
    if (pk.rem && pk.rem.length) pk.rem = pk.rem.filter(k => k !== c.k);
    if (!was && !inBase(pk, c)) (pk.add || (pk.add = [])).push(c.k);
    changed();
  }
  function removeFrom(pk, c) {
    computeAll();
    if (pk.add && pk.add.length) pk.add = pk.add.filter(k => k !== c.k);
    if (inBase(pk, c)) (pk.rem || (pk.rem = [])).push(c.k);
    if (pk.ord && pk.ord.length) { const i = pk.ord.indexOf(c.k); if (i >= 0) pk.ord.splice(i, 1); }
    changed();
  }
  function providerPacks() {
    const o = cfg().opt, ok = poolOK(o);
    const fav = pkById('fav') || newCfg().pk[0];
    const pks = [fav];
    GROUPS.forEach((g, i) => {
      if (!g.list.some(ok)) return;
      pks.push({ id: 'g' + i + '_' + g.name.length, ar: g.name, en: g.name, ic: '📺', col: COLORS[i % COLORS.length], on: true, auto: false, lang: 'all', kw: '', grp: [g.name], add: [], rem: [], ord: [], prov: true });
    });
    cfg().pk = pks; o.mode = 'provider'; o.dup = true; o.clean = false;
    changed();
  }
  function smartPacks() {
    const fav = pkById('fav'), fresh = newCfg();
    fresh.pk[0] = fav || fresh.pk[0];
    cfg().pk = fresh.pk; cfg().opt.mode = 'smart'; cfg().opt.dup = false;
    changed();
  }
  function toggleFav(c) {
    const f = pkById('fav');
    if (inPk(f, c)) removeFrom(f, c); else addTo(f, c);
  }

  function buildM3U() {
    const C = cfg(), o = C.opt, M = computeAll();
    const L = ['#EXTM3U'];
    let n = 0;
    const q = s => String(s).replace(/"/g, "'");
    const per = [];
    for (const pk of C.pk) {
      if (!pk.on) continue;
      const list = M.get(pk.id) || [];
      if (!list.length) continue;
      const title = (o.icons ? pk.ic + ' ' : '') + pkName(pk);
      per.push({ pk, count: list.length });
      for (const c of list) {
        n++;
        const nm = dispName(c);
        let inf = '#EXTINF:-1';
        if (c.a['tvg-id']) inf += ` tvg-id="${q(c.a['tvg-id'])}"`;
        inf += ` tvg-name="${q(nm)}"`;
        if (o.logos && c.l) inf += ` tvg-logo="${q(c.l)}"`;
        if (o.chno) inf += ` tvg-chno="${n}"`;
        inf += ` group-title="${q(title)}",${nm}`;
        L.push(inf);
        if (c.o) L.push(...c.o);
        L.push(c.u);
      }
    }
    return { text: L.join('\n') + '\n', count: n, per };
  }

  /* ---------------------------------------------------------------- sources */
  function detectLink(raw) {
    const s = raw.trim();
    let u;
    try { u = new URL(s); } catch (e) { return null; }
    const user = u.searchParams.get('username'), pass = u.searchParams.get('password');
    if (user && pass && /(get|player_api|xmltv)\.php$/i.test(u.pathname)) {
      const base = u.origin + u.pathname.replace(/\/[^/]*$/, '');
      return { type: 'xtream', server: base, user, pass, output: u.searchParams.get('output') || 'ts' };
    }
    const m = u.pathname.match(/^\/(?:live\/)?([^/]+)\/([^/]+)\/?$/);
    if (m && !/\.(m3u8?|txt)$/i.test(m[2]) && /^\d+$/.test(u.port || '80') && !u.search && u.port) {
      return { type: 'xtream', server: u.origin, user: m[1], pass: m[2], output: 'ts' };
    }
    return { type: 'url', url: s };
  }
  function xtBase(s) { return s.server.replace(/\/+$/, ''); }
  function apiUrl(s, action) {
    return xtBase(s) + '/player_api.php?username=' + enc(s.user) + '&password=' + enc(s.pass) + (action ? '&action=' + action : '');
  }
  function m3uUrl(s) {
    if (s.type === 'url') return s.url;
    return xtBase(s) + '/get.php?username=' + enc(s.user) + '&password=' + enc(s.pass) + '&type=m3u_plus&output=' + (s.output || 'ts');
  }
  async function readList(res, label) {
    const total = +(res.headers.get('X-Length') || 0);
    const P = M3UParser(), dec = new TextDecoder('utf-8');
    let got = 0, head = '';
    if (!res.body || !res.body.getReader) { const t = await res.text(); head = t.slice(0, 4000); P.feed(t); }
    else {
      const rd = res.body.getReader();
      for (; ;) {
        const { done, value } = await rd.read();
        if (done) break;
        got += value.length;
        const t = dec.decode(value, { stream: true });
        if (head.length < 4000) head += t.slice(0, 4000);
        P.feed(t);
        setLoading(label + ' · ' + fmtN(P.out.length) + ' ' + L('قناة', 'channels') + ' · ' + fmtMB(got), total > 0 ? got / total : null);
      }
      P.feed(dec.decode());
    }
    const list = P.end();
    if (!list.length) {
      if (/^\s*[{[]/.test(head)) throw new Error('الرابط يرجع بيانات وليس قائمة M3U');
      throw new Error('الرابط لا يحتوي على قائمة قنوات M3U صالحة');
    }
    return list;
  }
  async function getJSON(url) {
    const r = await fetch('/api/fetch?url=' + enc(url));
    if (!r.ok) throw new Error(await r.text());
    const t = await r.text();
    try { return JSON.parse(t); } catch (e) { throw new Error('رد غير متوقع من السيرفر'); }
  }
  async function checkAccount(s) {
    ACC = null;
    if (s.type !== 'xtream') return null;
    try {
      const j = await getJSON(apiUrl(s));
      if (j && j.user_info) { ACC = j.user_info; ACC.server = j.server_info || {}; }
      else if (j && j.user_info === undefined) ACC = { auth: 0 };
    } catch (e) { ACC = { error: e.message }; }
    return ACC;
  }
  async function xtreamLive(s) {
    setLoading('جلب التصنيفات…', null);
    const cats = await getJSON(apiUrl(s, 'get_live_categories'));
    setLoading('جلب القنوات المباشرة…', null);
    const streams = await getJSON(apiUrl(s, 'get_live_streams'));
    if (!Array.isArray(streams) || !streams.length) throw new Error('لا توجد قنوات');
    const cm = new Map(), corder = new Map();
    (Array.isArray(cats) ? cats : []).forEach((c, i) => { cm.set(String(c.category_id), c.category_name); corder.set(String(c.category_id), i); });
    const ext = s.output === 'm3u8' ? 'm3u8' : 'ts';
    streams.sort((a, b) => ((corder.get(String(a.category_id)) || 0) - (corder.get(String(b.category_id)) || 0)) || ((+a.num || 0) - (+b.num || 0)));
    return streams.map(x => ({
      n: x.name || '', g: cm.get(String(x.category_id)) || '',
      a: { 'tvg-id': x.epg_channel_id || '', 'tvg-logo': x.stream_icon || '' },
      u: xtBase(s) + '/live/' + enc(s.user) + '/' + enc(s.pass) + '/' + x.stream_id + '.' + ext
    }));
  }
  async function loadSource(s, fromCache) {
    S.active = s.id; save();
    ui.loading = { text: 'جاري الاتصال…', pct: null }; render();
    try {
      let list = null;
      if (fromCache || s.type === 'file') {
        const r = await fetch('/api/cache?id=' + enc(s.id));
        if (r.ok) list = await readList(r, L('قراءة القائمة', 'Reading list'));
        else if (s.type === 'file') throw new Error('الملف غير موجود، أعد استيراده');
      }
      if (!list || !list.length) {
        if (s.type === 'xtream') {
          checkAccount(s).then(render);
          const o = cfg().opt;
          if (!o.vod) { try { list = await xtreamLive(s); } catch (e) { list = null; } }
        }
        if (!list || !list.length) {
          const r = await fetch('/api/fetch?cache=' + enc(s.id) + '&url=' + enc(m3uUrl(s)));
          if (!r.ok) throw new Error(await r.text());
          list = await readList(r, L('تحميل القائمة', 'Downloading'));
        }
      } else if (s.type === 'xtream') checkAccount(s).then(render);
      setLoading(L('ترتيب الباقات…', 'Building packages…'), 1);
      await new Promise(r => setTimeout(r, 16));
      setChannels(list);
      s.count = CH.length; s.updated = Date.now();
      save();
      ui.loading = null;
      if (cfg().opt.asked) toast(L('تم جلب ' + fmtN(CH.length) + ' قناة ✓', fmtN(CH.length) + ' channels loaded ✓'), 'ok');
      go('packs');
      if (!cfg().opt.asked) onboard();
    } catch (e) {
      ui.loading = null;
      CH = []; GROUPS = []; MEMO = null;
      render();
      modal(`<h2>تعذر جلب القنوات</h2><p class="mut">${esc(e.message || e)}</p>
        <ul class="hint-list"><li>تأكد من صحة الرابط واسم المستخدم وكلمة المرور.</li><li>تأكد أن الاشتراك فعّال وغير منتهي.</li><li>جرّب نوع الإخراج m3u8 بدل ts.</li></ul>
        <div class="bar end"><button class="btn pri f" data-act="closeModal">حسناً</button></div>`);
    }
  }
  function setLoading(text, pct) {
    ui.loading = { text, pct };
    const el = $('#ld-text'); if (el) el.textContent = text;
    const p = $('#ld-bar');
    if (p) { p.parentNode.classList.toggle('ind', pct == null); p.style.width = pct == null ? '' : Math.round(pct * 100) + '%'; }
  }

  /* ---------------------------------------------------------------- navigation */
  function go(view, params, push) {
    if (push !== false && ui.view !== view) ui.hist.push({ view: ui.view, pk: ui.pk, grp: ui.grp, page: ui.page, q: ui.q });
    if (ui.hist.length > 30) ui.hist.shift();
    ui.view = view; ui.page = 0; ui.q = ''; ui.move = null; ui.showRem = false;
    Object.assign(ui, params || {});
    render(true);
  }
  window.appBack = function () {
    if (ui.forced) return true;
    if (ui.modal) { closeModal(); return true; }
    if (ui.move) { ui.move = null; render(); return true; }
    const h = ui.hist.pop();
    if (h) { ui.view = h.view; ui.pk = h.pk; ui.grp = h.grp; ui.page = h.page; ui.q = h.q; render(true); return true; }
    if (ui.view !== 'packs' && CH.length) { go('packs', null, false); return true; }
    return false;
  };
  window.remoteKey = function (k) {
    if (k === 'pageup' || k === 'pagedown') {
      const d = k === 'pagedown' ? 1 : -1;
      if (ui.move) moveBy(d * 10); else { const pg = $('[data-act="page"][data-arg="' + (ui.page + d) + '"]'); if (pg) pg.click(); }
    } else if (k === 'menu') { const n = $('.nav.cur') || $('.nav'); n && n.focus(); }
  };
  window.onAppResume = function () { if (ui.ovWait) { ui.ovWait = false; setTimeout(() => ACT_EXTRA.ovGo(), 400); } refreshInfo().then(() => { if (ui.afterPerm && INFO.storage) { ui.afterPerm = false; ACT.browse(''); } }); };

  /* spatial navigation for the remote */
  function focusables() {
    const root = ui.modal ? $('#modal') : document;
    return [...root.querySelectorAll('.f')].filter(e => !e.disabled && e.offsetParent !== null);
  }
  function nav(dir) {
    const cur = document.activeElement, list = focusables();
    if (!list.length) return;
    if (!cur || cur === document.body || !list.includes(cur)) { (list.find(e => e.classList.contains('auto')) || list[0]).focus(); return; }
    const zone = e => e.closest('#modal') ? 'm' : e.closest('#side') ? 's' : 'c';
    const z0 = zone(cur), vert = dir === 'up' || dir === 'down';
    const r = cur.getBoundingClientRect();
    let best = null, bs = Infinity;
    for (const e of list) {
      if (e === cur) continue;
      const q = e.getBoundingClientRect();
      if (q.width === 0 && q.height === 0) continue;
      const z = zone(e);
      if (vert && z !== z0) continue;                 // up/down never jumps between menu and content
      let main;
      if (dir === 'down') main = q.top - r.bottom;
      else if (dir === 'up') main = r.top - q.bottom;
      else if (dir === 'right') main = q.left - r.right;
      else main = r.left - q.right;
      if (main < -Math.min(r.height, r.width, q.height, q.width) * 0.5) continue; // must be in that direction
      main = Math.max(0, main);
      // gap on the other axis (0 when the two overlap = same row / same column)
      const gap = vert ? Math.max(0, q.left - r.right, r.left - q.right) : Math.max(0, q.top - r.bottom, r.top - q.bottom);
      if (!vert && gap > 40) continue;                // left/right stays on the same row
      let sc = main + gap * 2 + (gap > 0 ? 4000 : 0) + (z !== z0 ? 3000 : 0);
      if (sc < bs) { bs = sc; best = e; }
    }
    if (best) { best.focus({ preventScroll: true }); keepVisible(best); }
    else if (vert && !ui.modal) {
      const pg = $('[data-act="page"][data-arg="' + (ui.page + (dir === 'down' ? 1 : -1)) + '"]');
      if (pg && !pg.disabled) pg.click();
    }
  }
  function keepVisible(el) {
    const sc = el.closest('#main, #modal .box') || $('#main');
    if (!sc) return;
    const r = el.getBoundingClientRect(), b = sc.getBoundingClientRect(), pad = 70;
    if (r.top < b.top + pad) sc.scrollTop -= (b.top + pad - r.top);
    else if (r.bottom > b.bottom - pad) sc.scrollTop += (r.bottom - (b.bottom - pad));
  }
  document.addEventListener('keydown', e => {
    const k = e.key, ae = document.activeElement;
    if (k === 'Enter' && ae && ae.tagName === 'INPUT' && ae.readOnly && ae.classList.contains('in')) { e.preventDefault(); nativeEdit(ae); return; }
    const typing = ae && ((ae.tagName === 'INPUT' && !ae.readOnly && !['checkbox', 'button', 'file'].includes(ae.type)) || ae.tagName === 'TEXTAREA');
    if (ui.move) {
      if (k === 'ArrowUp' || k === 'ArrowDown') { e.preventDefault(); moveBy(k === 'ArrowDown' ? 1 : -1); return; }
      if (k === 'PageUp' || k === 'PageDown') { e.preventDefault(); moveBy(k === 'PageDown' ? 10 : -10); return; }
      if (k === 'Enter' || k === 'Escape') { e.preventDefault(); ui.move = null; render(); toast('تم تثبيت الموضع ✓', 'ok'); return; }
    }
    const dirs = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right' };
    if (dirs[k]) {
      if (typing && (k === 'ArrowLeft' || k === 'ArrowRight') && ae.value) return;
      e.preventDefault(); nav(dirs[k]); return;
    }
    if (k === 'Enter' && ae && ae.classList.contains('f') && ae.tagName !== 'BUTTON' && ae.tagName !== 'INPUT') { e.preventDefault(); ae.click(); return; }
    if (k === 'Escape' || (k === 'Backspace' && !typing)) { e.preventDefault(); window.appBack(); return; }
    if (k === 'PageDown' || k === 'PageUp') { e.preventDefault(); window.remoteKey(k.toLowerCase()); }
  });

  /* inputs on the TV: arrows pass over them, OK opens the keyboard */
  function lockInputs(root) {
    if (!IS_TV) return;
    (root || document).querySelectorAll('input.in').forEach(i => { if (!i.dataset.edit) i.readOnly = true; });
  }
  function editInput(i) {
    i.readOnly = false; i.dataset.edit = '1';
    setTimeout(() => { i.blur(); i.focus(); try { i.click(); } catch (e) { } const v = i.value; i.setSelectionRange && i.type !== 'number' && i.setSelectionRange(v.length, v.length); }, 30);
  }
  let nativeEl = null;
  function nativeEdit(i) {
    if (window.AndroidBridge && AndroidBridge.prompt) {
      nativeEl = i;
      const lab = i.previousElementSibling && i.previousElementSibling.classList.contains('fl') ? i.previousElementSibling.textContent : (i.placeholder || '');
      AndroidBridge.prompt(i.id || i.dataset.fid || 'x', tr(lab).replace(/^🔍\s*/, ''), i.value || '', i.id === 'f-pass');
    } else editInput(i);
  }
  window.onNativeInput = function (id, text) {
    let el = nativeEl && document.contains(nativeEl) ? nativeEl : (document.getElementById(id) || [...document.querySelectorAll('[data-fid]')].find(x => x.dataset.fid === id));
    if (!el) return;
    el.value = text;
    el.dispatchEvent(new Event('input', { bubbles: true }));
    if (el.id && el.id.indexOf('f-') === 0) readForm();
    el.focus({ preventScroll: true });
  };
  document.addEventListener('blur', e => {
    const i = e.target;
    if (IS_TV && i && i.classList && i.classList.contains('in')) { setTimeout(() => { if (document.activeElement !== i) { delete i.dataset.edit; i.readOnly = true; } }, 60); }
  }, true);

  /* ---------------------------------------------------------------- moving */
  function currentOrderList() {
    if (ui.move.kind === 'pk') return cfg().pk;
    return members(ui.move.pk);
  }
  function moveBy(d) {
    const m = ui.move; if (!m) return;
    if (m.kind === 'pk') {
      const arr = cfg().pk, i = arr.findIndex(p => p.id === m.key), j = Math.max(0, Math.min(arr.length - 1, i + d));
      if (i < 0 || i === j) return;
      const [x] = arr.splice(i, 1); arr.splice(j, 0, x);
      changed(); render(); return;
    }
    const pk = pkById(m.pk), list = members(pk.id).map(c => c.k);
    const i = list.indexOf(m.key), j = Math.max(0, Math.min(list.length - 1, i + d));
    if (i < 0 || i === j) return;
    list.splice(i, 1); list.splice(j, 0, m.key);
    pk.ord = list; changed();
    ui.page = Math.floor(j / PAGE);
    render();
  }
  function moveTo(pk, key, pos) {
    const list = members(pk.id).map(c => c.k);
    const i = list.indexOf(key); if (i < 0) return;
    list.splice(i, 1); list.splice(Math.max(0, Math.min(list.length, pos)), 0, key);
    pk.ord = list; changed();
  }

  /* ---------------------------------------------------------------- render */
  let lastFid = null;
  function render(resetFocus) {
    const ae = document.activeElement;
    lastFid = !resetFocus && ae && ae.dataset ? ae.dataset.fid : null;
    document.querySelectorAll('.nav').forEach(b => b.classList.toggle('cur', b.dataset.arg === navOf(ui.view)));
    renderSide();
    $('#top').innerHTML = topHtml();
    $('#main').innerHTML = ui.loading ? loadingHtml() : (VIEWS[ui.view] || VIEWS.subs)();
    $('#keys').innerHTML = keysHtml();
    applyLook();
    if (EN()) { translate($('#side')); translate($('#top')); translate($('#main')); translate($('#keys')); }
    lockInputs($('#main'));
    if (resetFocus) $('#main').scrollTop = 0;
    restoreFocus(resetFocus);
  }
  function navOf(v) { return { pack: 'packs', group: 'browse' }[v] || v; }
  function restoreFocus(reset) {
    if (ui.modal) return;
    let el = null;
    if (lastFid) el = [...document.querySelectorAll('[data-fid]')].find(e => e.dataset.fid === lastFid);
    if (!el && reset) el = $('#main .auto') || $('#main .f');
    if (!el) { const a = document.activeElement; if (!a || a === document.body || !document.contains(a)) el = $('#main .auto') || $('#main .f') || $('.nav.cur'); }
    if (el) { el.focus({ preventScroll: true }); if (ui.move || reset) el.scrollIntoView({ block: 'nearest' }); }
  }
  function renderSide() {
    const ip = (INFO.ips || [])[0];
    $('#sideinfo').innerHTML = `<button class="btn sm f themebtn" data-act="themeToggle" data-fid="theme" title="تبديل المظهر">${document.documentElement.dataset.theme === 'light' ? '🌙 ليلي' : '☀️ نهاري'}</button>` + (ip && INFO.port ? `<div class="pc">💻 تحكم من الكمبيوتر<b class="ltr">http://${esc(ip)}:${INFO.port}</b></div>` : '') +
      `<div class="ver">M3U v${esc((INFO.app || {}).name || INFO.version || '')}</div>`;
  }
  function topHtml() {
    const s = src();
    const titles = { subs: 'الاشتراكات', packs: 'الباقات', pack: '', browse: 'تصفح قنوات المزود', group: '', export: 'إرسال إلى الرسيفر', settings: 'الإعدادات', favs: 'المفضلة' };
    let t = titles[ui.view];
    if (ui.view === 'pack') { const pk = pkById(ui.pk); t = pk ? `<span class="pkdot" style="${cv(pk.col)}">${pk.ic}</span> <span class="nt">${esc(pkName(pk))}</span>` : ''; }
    if (ui.view === 'group') t = '<span class="nt">🗂️ ' + esc(ui.grp) + '</span>';
    let chips = '';
    if (s) chips += `<span class="chip nt"><i>📡</i>${esc(s.name)}</span>`;
    if (CH.length) chips += `<span class="chip"><i>📺</i>${fmtN(CH.length)} قناة</span>`;
    if (ACC) {
      if (ACC.error || ACC.auth === 0) chips += `<span class="chip bad">⚠ تعذر فحص الحساب</span>`;
      else {
        const st = String(ACC.status || '').toLowerCase();
        const exp = ACC.exp_date ? new Date(ACC.exp_date * 1000) : null;
        const days = exp ? Math.ceil((exp - Date.now()) / 864e5) : null;
        chips += `<span class="chip ${st === 'active' ? 'ok' : 'bad'}">${st === 'active' ? '● فعّال' : '● ' + esc(ACC.status || 'غير معروف')}</span>`;
        if (exp) chips += `<span class="chip ${days < 7 ? 'warn' : ''}">⏳ ${days > 0 ? L('باقي ' + days + ' يوم', days + ' days left') : L('منتهي', 'Expired')}</span>`;
        if (ACC.max_connections) chips += `<span class="chip">🔗 ${esc(ACC.active_cons || 0)}/${esc(ACC.max_connections)}</span>`;
      }
    }
    return `<h1>${t}</h1><div class="chips">${chips}</div>`;
  }
  function keysHtml() {
    if (ui.move) return `<span><b>▲▼</b> تحريك</span><span><b>CH±</b> تحريك 10</span><span><b>OK</b> تثبيت</span><span><b>رجوع</b> إلغاء</span>`;
    return `<span><b>▲▼◀▶</b> تنقل</span><span><b>OK</b> اختيار</span><span><b>CH±</b> صفحة</span><span><b>رجوع</b> للخلف</span><span><b>Menu</b> القائمة</span>`;
  }
  function loadingHtml() {
    const l = ui.loading;
    return `<div class="loading"><div class="spin"></div><h2 id="ld-text">${esc(l.text)}</h2>
      <div class="prog ${l.pct == null ? 'ind' : ''}"><i id="ld-bar" style="${l.pct == null ? '' : 'width:' + Math.round(l.pct * 100) + '%'}"></i></div>
      <p class="mut">القوائم الكبيرة (+10,000 قناة) قد تأخذ دقيقة</p></div>`;
  }
  function pager(total) {
    const pages = Math.ceil(total / PAGE);
    if (pages <= 1) return '';
    const p = ui.page;
    return `<div class="pager">
      <button class="btn sm f" data-act="page" data-arg="${p - 1}" ${p <= 0 ? 'disabled' : ''} data-fid="pg-prev">› السابق</button>
      <span class="mut">${L('صفحة ' + (p + 1) + ' من ' + pages, 'Page ' + (p + 1) + ' of ' + pages)}</span>
      <button class="btn sm f" data-act="page" data-arg="${p + 1}" ${p >= pages - 1 ? 'disabled' : ''} data-fid="pg-next">التالي ‹</button></div>`;
  }
  function needSource() {
    return `<div class="empty"><div class="big">📡</div><h2>لا توجد قنوات بعد</h2><p class="mut">أضف رابط اشتراكك أو ملف M3U للبدء</p>
      <button class="btn pri f auto" data-act="go" data-arg="subs">إضافة اشتراك</button></div>`;
  }
  const realQ = h => h >= 2000 ? '4K' : h >= 1000 ? '1080p' : h >= 700 ? '720p' : h > 0 ? h + 'p' : '';
  const kbpsQ = k => k >= 15000 ? '4K' : k >= 5000 ? 'FHD' : k >= 2500 ? 'HD' : k > 0 ? 'SD' : '';
  const fmtRate = k => k >= 1000 ? (k / 1000).toFixed(1) + ' Mbps' : k + ' kbps';
  function qualTag(c) {
    const qi = (cfg().qi || {})[c.k];
    if (qi && qi.fail) return '<span class="tag qx">✕</span>';
    if (qi && qi.kbps && !qi.h) { const q = kbpsQ(qi.kbps); return `<span class="tag qreal ${q === '4K' ? 'q4' : q === 'FHD' ? 'q3' : q === 'HD' ? 'q2' : ''}">≈${q} ✓</span>`; }
    if (qi && qi.h) return `<span class="tag qreal ${qi.h >= 2000 ? 'q4' : qi.h >= 1000 ? 'q3' : qi.h >= 700 ? 'q2' : ''}">${realQ(qi.h)} ✓</span>`;
    return qTag(c.q);
  }
  const qTag = q => q === 4 ? '<span class="tag q4">4K</span>' : q === 3 ? '<span class="tag q3">FHD</span>' : q === 2 ? '<span class="tag q2">HD</span>' : '';
  const logo = c => `<span class="logo">${c.l ? `<img loading="lazy" src="${esc(c.l)}" onerror="this.remove()">` : ''}<b>${esc(dispName(c).slice(0, 2))}</b></span>`;

  /* ---------------------------------------------------------------- views */
  const VIEWS = {};

  VIEWS.subs = function () {
    const t = ui.srcTab, e = ui.editSrc || {};
    const tabs = [['link', '🔗 رابط اشتراك'], ['xtream', '🔑 Xtream'], ['file', '📁 ملف M3U']]
      .map(([k, l]) => `<button class="tab f ${t === k ? 'on auto' : ''}" data-act="srcTab" data-arg="${k}" data-fid="tab-${k}">${l}</button>`).join('');
    let form = '';
    if (t === 'link') form = `
      <label class="fl">رابط الاشتراك (M3U أو رابط get.php)</label>
      <input class="in f" id="f-url" data-fid="f-url" placeholder="http://server:port/get.php?username=…&password=…" value="${esc(e.url || '')}">
      <div class="hint">يتعرّف التطبيق تلقائياً على نوع الرابط ويفحص حالة الاشتراك.</div>`;
    else if (t === 'xtream') form = `
      <label class="fl">عنوان السيرفر</label><input class="in f" id="f-server" data-fid="f-server" placeholder="http://server.com:8080" value="${esc(e.server || '')}">
      <div class="grid2"><div><label class="fl">اسم المستخدم</label><input class="in f" id="f-user" data-fid="f-user" value="${esc(e.user || '')}"></div>
      <div><label class="fl">كلمة المرور</label><input class="in f" id="f-pass" data-fid="f-pass" value="${esc(e.pass || '')}"></div></div>
      <label class="fl">نوع البث</label><div class="seg">${['ts', 'm3u8'].map(o => `<button class="btn sm f ${(e.output || 'ts') === o ? 'on' : ''}" data-act="xtOut" data-arg="${o}" data-fid="xo-${o}">${o.toUpperCase()}</button>`).join('')}</div>`;
    else form = `
      <div class="filebox">
        <button class="btn pri f" data-act="browse" data-arg="" data-fid="pick-browse">📂 تصفح ملفات الرسيفر و USB</button>
        <button class="btn f" data-act="sysPick" data-fid="pick-sys">🗂 فتح مدير الملفات</button>
        <button class="btn f" data-act="pickDevice" data-fid="pick-dev">🔍 بحث تلقائي عن ملفات M3U</button>
        ${IS_TV ? '' : `<label class="btn f" tabindex="0" data-fid="pick-pc">💻 رفع من الكمبيوتر<input type="file" id="f-file" accept=".m3u,.m3u8,.txt" hidden></label>`}
      </div><div class="hint">يبحث التطبيق عن ملفات .m3u في الذاكرة الداخلية وفلاش USB.</div>`;
    const list = S.sources.map((s, i) => {
      const cur = s.id === S.active;
      const ty = s.type === 'xtream' ? 'Xtream' : s.type === 'file' ? 'ملف' : 'رابط';
      return `<div class="src ${cur ? 'cur' : ''}">
        <div class="src-ic">${s.type === 'file' ? '📁' : s.type === 'xtream' ? '🔑' : '🔗'}</div>
        <div class="nm"><b class="nt">${esc(s.name)}</b><small>${ty}${s.count ? ' · ' + fmtN(s.count) + ' قناة' : ''}${s.updated ? ' · ' + new Date(s.updated).toLocaleDateString('ar-SA-u-nu-latn') : ''}</small></div>
        ${cur && CH.length ? '<span class="chip ok">● نشط</span>' : ''}
        <button class="btn sm pri f" data-act="srcLoad" data-arg="${i}" data-fid="sl-${s.id}">${s.type === 'file' ? 'فتح' : 'تحديث ⟳'}</button>
        ${s.count && s.type !== 'file' ? `<button class="btn sm f" data-act="srcCache" data-arg="${i}" data-fid="sc-${s.id}">فتح سريع</button>` : ''}
        <button class="btn sm danger f" data-act="srcDel" data-arg="${i}" data-fid="sd-${s.id}">حذف</button></div>`;
    }).join('');
    return `<div class="hero"><img src="icon.png" alt=""><div><h2>أضف اشتراكك وخلّ الباقي علينا</h2>
      <p>يجلب القنوات ويصنّفها تلقائياً في باقات (رياضة، أخبار، أفلام، 4K…)، ثم يرسلها مرتبة إلى IP Channels.</p></div></div>
      <div class="card"><div class="tabs">${tabs}</div>
      <label class="fl">اسم الاشتراك (اختياري)</label><input class="in rtl f" id="f-name" data-fid="f-name" placeholder="مثال: اشتراكي الأساسي" value="${esc(e.name || '')}">
      ${form}
      ${t !== 'file' ? `<div class="bar end"><button class="btn pri big f" data-act="srcAdd" data-fid="src-add">🔍 فحص وجلب القنوات</button></div>` : ''}</div>
      ${S.sources.length ? `<h3 class="sec">اشتراكاتك المحفوظة</h3><div class="list">${list}</div>` : ''}`;
  };

  VIEWS.packs = function () {
    if (!CH.length) return needSource();
    const C = cfg(), o = C.opt, M = computeAll();
    let total = 0;
    C.pk.forEach(p => { if (p.on) total += (M.get(p.id) || []).length; });
    const nameSeg = [['ar', 'عربي'], ['en', 'English'], ['both', 'عربي + English']]
      .map(([k, l]) => `<button class="btn sm f ${o.nameLang === k ? 'on' : ''}" data-act="optSet" data-arg="nameLang:${k}" data-fid="on-${k}">${l}</button>`).join('');
    const tstart = ui.page * PAGE;
    const tiles = C.pk.slice(tstart, tstart + PAGE).map((p, i) => {
      const n = (M.get(p.id) || []).length;
      const mv = ui.move && ui.move.kind === 'pk' && ui.move.key === p.id;
      return `<div class="tile ${p.on ? 'on' : 'off'} ${mv ? 'moving' : ''}" style="${cv(p.col)}" draggable="${!IS_TV}" data-drag="pk:${p.id}">
        <button class="tile-main f ${i === 0 ? 'auto' : ''}" data-act="pkOpen" data-arg="${p.id}" data-fid="pk-${p.id}">
          <span class="tile-ic">${p.ic}</span><span class="tile-nm nt">${esc(pkName(p))}</span>
          <span class="tile-n">${fmtN(n)} <small>قناة</small></span></button>
        <div class="tile-acts">
          <button class="sw f ${p.on ? 'on' : ''}" data-act="pkToggle" data-arg="${p.id}" data-fid="pt-${p.id}" title="تفعيل"><i></i></button>
          <button class="ib f" data-act="pkMove" data-arg="${p.id}" data-fid="pm-${p.id}" title="ترتيب">⇅</button>
          <button class="ib f" data-act="pkEdit" data-arg="${p.id}" data-fid="pe-${p.id}" title="إعدادات">✎</button>
        </div></div>`;
    }).join('');
    return `<div class="summary">
        <div class="stat"><b>${fmtN(C.pk.filter(p => p.on).length)}</b><span>باقة مفعّلة</span></div>
        <div class="stat"><b>${fmtN(total)}</b><span>قناة في القائمة النهائية</span></div>
        <div class="stat"><b>${fmtN(CH.length)}</b><span>قناة عند المزود</span></div>
        <button class="btn pri big f" data-act="go" data-arg="export" data-fid="go-export">🚀 إرسال إلى الرسيفر</button></div>
      <div class="card opts">
        <div class="opt wide"><span>طريقة الترتيب</span><div class="seg">
          <button class="btn sm f ${o.mode !== 'provider' ? 'on' : ''}" data-act="setMode" data-arg="smart" data-fid="mode-smart">✨ ترتيب ذكي</button>
          <button class="btn sm f ${o.mode === 'provider' ? 'on' : ''}" data-act="setMode" data-arg="provider" data-fid="mode-prov">📡 كما هو من المزود</button></div></div>
        <div class="opt wide"><span>أسماء الباقات</span><div class="seg">${nameSeg}</div></div>
        <div class="opt"><span>القناة في أكثر من باقة</span><button class="sw f ${o.dup ? 'on' : ''}" data-act="optTog" data-arg="dup" data-fid="o-dup"><i></i></button></div>
        <div class="opt"><span>إخفاء محتوى البالغين</span><button class="sw f ${o.adult ? 'on' : ''}" data-act="optTog" data-arg="adult" data-fid="o-adult"><i></i></button></div>
      </div>
      ${ui.move ? '<div class="movebar">⇅ وضع الترتيب: استخدم ⬆⬇ لتحريك الباقة ثم OK للتثبيت</div>' : ''}
      <div class="tiles">${tiles}
        <button class="tile add f" data-act="pkNew" data-fid="pk-new"><span class="tile-ic">＋</span><span class="tile-nm">إضافة باقة</span></button></div>${pager(C.pk.length)}`;
  };

  function chRow(c, idx, mode, pk) {
    const fav = inPk(pkById('fav'), c);
    const mv = ui.move && ui.move.key === c.k;
    const fid = 'r-' + c.i;
    let acts = '';
    if (mode === 'pack') {
      acts = `<button class="ib play f" data-act="pvPlay" data-arg="${c.i}" data-fid="${fid}-p" title="معاينة">▶</button>
        <button class="ib f" data-act="chMove" data-arg="${c.i}" data-fid="${fid}-m" title="تحريك">⇅</button>
        ${pk.id !== 'fav' ? `<button class="ib star f ${fav ? 'on' : ''}" data-act="chFav" data-arg="${c.i}" data-fid="${fid}-f">★</button>` : ''}
        <button class="ib f" data-act="chMenu" data-arg="${c.i}" data-fid="${fid}-o" title="خيارات">⋯</button>
        <button class="ib del f" data-act="chRemove" data-arg="${c.i}" data-fid="${fid}-x" title="حذف من الباقة">✕</button>`;
    } else if (mode === 'removed') {
      acts = `<button class="btn sm f" data-act="chRestore" data-arg="${c.i}" data-fid="${fid}-rs">↩ إرجاع</button>`;
    } else {
      const tp = pkById(ui.target) || pkById('fav');
      const on = inPk(tp, c);
      acts = `<button class="ib play f" data-act="pvPlay" data-arg="${c.i}" data-fid="${fid}-p" title="معاينة">▶</button>
        <button class="ib star f ${fav ? 'on' : ''}" data-act="chFav" data-arg="${c.i}" data-fid="${fid}-f">★</button>
        <button class="ib f" data-act="chMenu" data-arg="${c.i}" data-fid="${fid}-o">⋯</button>
        <button class="addbtn f ${on ? 'on' : ''}" data-act="chTarget" data-arg="${c.i}" data-fid="${fid}-t" style="${cv(tp.col)}">${on ? '✓ في ' : '＋ '}${esc(pkName(tp))}</button>`;
    }
    const main = mode === 'pack'
      ? `<button class="rowmain f" data-act="chMove" data-arg="${c.i}" data-fid="${fid}">`
      : `<button class="rowmain f" data-act="chMenu" data-arg="${c.i}" data-fid="${fid}">`;
    return `<div class="row ${mv ? 'moving' : ''}" draggable="${mode === 'pack' && !IS_TV && !ui.q}" data-drag="ch:${c.i}">
      <span class="num">${idx + 1}</span>${main}${logo(c)}
      <span class="nm nt"><b>${esc(dispName(c))}</b>${qualTag(c)}<small>${esc(c.g)}</small></span></button>${acts}</div>`;
  }

  VIEWS.pack = function () {
    if (!CH.length) return needSource();
    const pk = pkById(ui.pk); if (!pk) return '';
    let list = ui.showRem ? (pk.rem || []).map(k => BYK.get(k)).filter(Boolean) : members(pk.id);
    const all = list.length;
    if (ui.q) { const q = ui.q.toLowerCase(); list = list.filter(c => dispName(c).toLowerCase().includes(q) || c.g.toLowerCase().includes(q)); }
    const start = ui.page * PAGE, page = list.slice(start, start + PAGE);
    const remN = (pk.rem || []).length;
    const rows = page.map((c, i) => chRow(c, start + i, ui.showRem ? 'removed' : 'pack', pk)).join('');
    return `<div class="pkhead" style="${cv(pk.col)}">
        <div class="pkinfo"><span class="big-ic">${pk.ic}</span><div><b>${fmtN(all)}</b> قناة ${pk.on ? '<span class="chip ok">مفعّلة</span>' : '<span class="chip">غير مفعّلة</span>'}</div></div>
        <div class="bar">
          <button class="sw f ${pk.on ? 'on' : ''}" data-act="pkToggle" data-arg="${pk.id}" data-fid="ph-on"><i></i></button>
          <button class="btn f" data-act="pkAddCh" data-arg="${pk.id}" data-fid="ph-add">＋ إضافة قنوات</button>
          <button class="btn f" data-act="pkSort" data-arg="${pk.id}" data-fid="ph-sort">⇅ ترتيب تلقائي</button>
          <button class="btn f" data-act="pkProbe" data-arg="${pk.id}" data-fid="ph-probe">🩺 فحص القنوات</button>
          <button class="btn f" data-act="pkEdit" data-arg="${pk.id}" data-fid="ph-edit">✎ الإعدادات</button>
        </div></div>
      <div class="bar">
        <input class="in rtl search f auto" data-inp="q" data-fid="q" placeholder="🔍 بحث داخل الباقة…" value="${esc(ui.q)}">
        ${remN ? `<button class="btn sm f ${ui.showRem ? 'on' : ''}" data-act="showRem" data-fid="ph-rem">🗑 المحذوفة (${remN})</button>` : ''}
      </div>
      ${ui.move ? '<div class="movebar">⇅ وضع الترتيب: ⬆⬇ لتحريك القناة · CH± لتحريك 10 · OK للتثبيت</div>' : `<div class="hint top">اضغط <b>OK</b> على أي قناة لتحريكها بالأسهم${IS_TV ? '' : '، أو اسحبها بالماوس'}</div>`}
      <div class="list">${rows || `<div class="empty"><div class="big">${pk.ic}</div><p>لا توجد قنوات في هذه الباقة</p><button class="btn pri f" data-act="pkAddCh" data-arg="${pk.id}">＋ إضافة قنوات</button></div>`}</div>
      ${pager(list.length)}`;
  };

  VIEWS.favs = function () { ui.pk = 'fav'; return VIEWS.pack(); };

  VIEWS.browse = function () {
    if (!CH.length) return needSource();
    let groups = GROUPS;
    const o = cfg().opt;
    if (ui.q) {
      const q = ui.q.toLowerCase();
      const chs = CH.filter(c => c.n.toLowerCase().includes(q) || c.g.toLowerCase().includes(q));
      const start = ui.page * PAGE;
      return browseHead() + `<p class="mut">${fmtN(chs.length)} ${L('نتيجة', 'results')}</p><div class="list">${chs.slice(start, start + PAGE).map((c, i) => chRow(c, start + i, 'browse')).join('')}</div>` + pager(chs.length);
    }
    const start = ui.page * PAGE;
    const rows = groups.slice(start, start + PAGE).map((g, i) => {
      const live = g.list.filter(c => c.t === 'live').length;
      return `<div class="row"><button class="rowmain f ${i === 0 ? 'auto' : ''}" data-act="grpOpen" data-arg="${start + i}" data-fid="g-${start + i}">
        <span class="gic">🗂️</span><span class="nm"><b class="nt">${esc(g.name)}</b><small>${fmtN(g.list.length)} قناة${live !== g.list.length ? ' · ' + fmtN(live) + ' مباشر' : ''}</small></span></button>
        <button class="btn sm f" data-act="grpToPk" data-arg="${start + i}" data-fid="g-${start + i}-p">＋ إلى باقة</button></div>`;
    }).join('');
    return browseHead() + `<div class="list">${rows}</div>` + pager(groups.length);
  };
  function browseHead() {
    const tp = pkById(ui.target) || pkById('fav');
    return `<div class="bar"><input class="in rtl search f ${ui.q ? 'auto' : ''}" data-inp="q" data-fid="q" placeholder="🔍 ابحث في كل القنوات…" value="${esc(ui.q)}">
      <button class="btn f target" style="${cv(tp.col)}" data-act="pickTarget" data-fid="tgt">الإضافة إلى: <b class="nt">${tp.ic} ${esc(pkName(tp))}</b> ▾</button></div>`;
  }

  VIEWS.group = function () {
    const g = GROUPS.find(x => x.name === ui.grp); if (!g) return '';
    let list = g.list;
    if (ui.q) { const q = ui.q.toLowerCase(); list = list.filter(c => c.n.toLowerCase().includes(q)); }
    const start = ui.page * PAGE;
    return browseHead().replace('ابحث في كل القنوات', 'بحث في المجموعة') +
      `<div class="bar"><button class="btn f" data-act="grpAll" data-fid="ga-all">＋ إضافة كل المجموعة للباقة</button>
       <button class="btn f" data-act="grpNewPk" data-fid="ga-new">✨ باقة جديدة من هذه المجموعة</button></div>
       <div class="list">${list.slice(start, start + PAGE).map((c, i) => chRow(c, start + i, 'browse')).join('')}</div>` + pager(list.length);
  };

  VIEWS.export = function () {
    if (!CH.length) return needSource();
    const C = cfg(), o = C.opt, b = buildM3U();
    const sz = new Blob([b.text]).size;
    const rows = b.per.map(x => `<div class="erow" style="${cv(x.pk.col)}"><span class="nt">${x.pk.ic} ${esc(pkName(x.pk))}</span><b>${fmtN(x.count)}</b></div>`).join('');
    const tg = (k, l) => `<div class="opt"><span>${l}</span><button class="sw f ${o[k] ? 'on' : ''}" data-act="optTog" data-arg="${k}" data-fid="ex-${k}"><i></i></button></div>`;
    const ip = (INFO.ips || [])[0];
    const res = ui.exportRes;
    const result = res ? `<div class="result">
        <h3 class="ok">✓ ${L('تم الحفظ — ' + fmtN(res.count) + ' قناة', 'Saved — ' + fmtN(res.count) + ' channels')}</h3>
        <p>${L('الملف جاهز في مجلد Download باسم', 'The file is ready in the Download folder as')} <b class="ltr nt">${esc(o.fname)}.m3u</b>${res.saved.some(p => !/emulated|Download/.test(p)) ? L(' ونسخة على فلاش USB', ' plus a copy on USB') : ''}.</p>
        <div class="tigersteps"><b>${L('الآن في الرسيفر:', 'Now on the receiver:')}</b>
          <span>Menu</span>›<span>${L('التركيب', 'Installation')}</span>›<span>${L('بحث قنوات IP', 'IP channel search')}</span>›<span>${L('اختر ملف', 'Select file')}: <b class="ltr nt">Local(${esc(o.fname)}.m3u)</b></span>›<span>${L('بدء البحث', 'Start search')}</span></div>
      </div>` : '';
    const permBox = INFO.storage === false ? `<div class="warnbox perm">🔐 لحفظ القائمة على فلاش USB وفي الذاكرة الرئيسية امنح التطبيق صلاحية «الوصول إلى كل الملفات». بدونها تُحفظ في مجلد Download فقط.
      <button class="btn sm f" data-act="perm" data-fid="ex-perm">منح الصلاحية</button></div>` : '';
    return `${permBox}<div class="card send">
        <div class="sendrow"><div class="sendinfo"><b class="ltr">${fmtN(b.count)}</b> <span>${L('قناة في ' + fmtN(b.per.length) + ' باقة', 'channels in ' + fmtN(b.per.length) + ' packages')} · <span class="ltr">${fmtMB(sz)}</span></span></div>
        <button class="btn pri huge f auto" data-act="doExport" data-fid="ex-go">🚀 حفظ القائمة وإرسالها إلى الرسيفر</button></div>
        ${IS_TV ? '' : '<button class="btn f" data-act="download" data-fid="ex-dl">⬇ تنزيل الملف على الكمبيوتر</button>'}
        ${b.count > 5000 ? '<p class="warnbox">⚠ عدد كبير من القنوات؛ إذا رفض الرسيفر الاستيراد قلّل الباقات المفعّلة.</p>' : ''}
        ${result}</div>
      <div class="grid-ex">
      <div class="card"><h2>محتوى القائمة</h2><div class="erows">${rows || '<p class="mut">لا توجد باقات مفعّلة</p>'}</div></div>
      <div class="card"><h2>خيارات الملف</h2>
        <label class="fl">اسم الملف</label><input class="in f" data-inp="fname" data-fid="ex-fname" value="${esc(o.fname)}">
        ${tg('clean', 'تنظيف الأسماء (حذف AR | وغيرها)')}${tg('logos', 'تضمين شعارات القنوات')}${tg('icons', 'رموز بجانب أسماء الباقات')}${tg('chno', 'ترقيم القنوات')}
      </div></div>
      <div class="card tigerhow"><h2>📺 طريقة الإضافة في رسيفر Tiger</h2><ol class="steps">
        <li>اضغط زر <b>حفظ القائمة وإرسالها</b> بالأعلى، فيُحفظ الملف <b class="ltr nt">${esc(o.fname)}.m3u</b> في مجلد <b>Download</b>.</li>
        <li>اضغط زر <b>Menu</b> في ريموت الرسيفر ← <b>القائمة الرئيسية</b> ← <b>التركيب</b>.</li>
        <li>اختر <b>بحث قنوات IP</b> ← <b>اختر ملف</b> ← <b class="ltr nt">Local(${esc(o.fname)}.m3u)</b> ← <b>موافق</b>.</li>
        <li>اضغط <b>بدء البحث</b>، وتظهر القنوات مرتبة حسب الباقات.</li>
      </ol></div>`;
  };
  VIEWS.settings = function () {
    const st = S.settings, o = cfg().opt;
    const seg = (act, cur, items) => items.map(([k, l]) => `<button class="btn sm f ${cur === k ? 'on' : ''}" data-act="${act}" data-arg="${k}" data-fid="${act}-${k}">${l}</button>`).join('');
    return `<div class="card"><h2>لغة وأسماء الباقات</h2>
        <div class="opt"><span>لغة التطبيق</span><div class="seg">${seg('setLang', st.lang || 'ar', [['ar', '<span class="nt">العربية</span>'], ['en', '<span class="nt">English</span>']])}</div></div>
        <div class="opt"><span>أسماء الباقات في الملف المرسل للرسيفر</span><div class="seg">${seg('setName', o.nameLang, [['ar', 'عربي'], ['en', '<span class="nt">English</span>'], ['both', 'عربي + English']])}</div></div></div>
      <div class="card"><h2>المظهر</h2>
        <div class="opt"><span>الوضع النهاري والليلي</span><div class="seg">${seg('setTheme', st.theme || 'dark', [['dark', '🌙 ليلي'], ['light', '☀️ نهاري'], ['auto', '🌗 تلقائي حسب الوقت']])}</div></div></div>
      <div class="card"><h2>التخزين</h2>
        <p>${INFO.storage ? '<span class="ok">✓ صلاحية التخزين ممنوحة</span>' : '<span class="bad">✕ صلاحية التخزين غير ممنوحة؛ يلزم منحها لحفظ الملف</span>'}</p>
        <button class="btn f auto" data-act="perm" data-fid="st-perm">منح صلاحية التخزين</button>
        <div class="hint">مواقع الحفظ: ${(INFO.exportDirs || []).map(d => `<span class="code">${esc(d)}</span>`).join(' ')}</div></div>
      <div class="card"><h2>تطبيق IP Channels</h2>
        <p>${S.settings.targetPkg ? 'التطبيق المختار: <b>' + esc(S.settings.targetLabel) + '</b>' : 'اختر تطبيق القنوات في الرسيفر لفتحه مباشرة بعد الإرسال.'}</p>
        <button class="btn f" data-act="pickApp" data-fid="st-app">اختيار التطبيق</button></div>
      <div class="card"><h2>التحكم من الكمبيوتر أو الجوال</h2>
        <p>افتح هذا الرابط من أي جهاز على نفس شبكة الواي فاي لترتيب القنوات بالماوس:</p>
        ${(INFO.ips || []).map(ip => `<span class="code big">http://${esc(ip)}:${INFO.port}</span>`).join(' ') || '<span class="mut">غير متصل بالشبكة</span>'}</div>
      <div class="card"><h2>النسخ الاحتياطي</h2><div class="bar">
        ${IS_TV ? '' : '<button class="btn f" data-act="backup" data-fid="st-bk">⬇ حفظ نسخة من الإعدادات</button><label class="btn f" tabindex="0">⬆ استعادة<input type="file" id="f-restore" accept=".json" hidden></label>'}
        <button class="btn danger f" data-act="resetCfg" data-fid="st-reset">إعادة ضبط باقات الاشتراك الحالي</button></div></div>
      <div class="card"><h2>التحديثات</h2><div class="opt"><span>${L('النسخة الحالية', 'Current version')} <b class="ltr">v${esc((INFO.app || {}).name || INFO.version || '')}</b></span>
        <button class="btn f" data-act="checkUpd" data-fid="st-upd">🔄 ${L('التحقق من التحديثات', 'Check for updates')}</button></div>
        <div class="hint">${L('التطبيق يتحقق تلقائياً عند كل تشغيل، ويثبّت التحديث بضغطة بدون Downloader.', 'The app checks on every launch and installs updates in one tap, no Downloader needed.')}</div></div>
      <div class="card"><h2>عن التطبيق</h2><p class="mut">M3U v${esc((INFO.app || {}).name || INFO.version || '')} · ${esc(INFO.device || '')}</p></div>`;
  };

  /* ---------------------------------------------------------------- modal / toast */
  function modal(html) {
    if (ui.forced && !ui.updFlow) return;   // mandatory update stays on top
    ui.modal = true;
    const m = $('#modal');
    m.innerHTML = `<div class="box">${html}</div>`;
    m.classList.remove('hidden');
    translate(m);
    lockInputs(m);
    const f = m.querySelector('.auto') || m.querySelector('.f');
    if (f) setTimeout(() => f.focus(), 30);
  }
  function closeModal() {
    if (ui.forced && !ui.forceClose) return;
    if ($('#pv')) { stopVideo(); if (PV.list && !ui.forceClose) PV.list = null; }
    ui.modal = null;
    $('#modal').classList.add('hidden');
    $('#modal').innerHTML = '';
    render();
  }
  let toastT;
  function toast(msg, cls) {
    const t = $('#toast');
    t.textContent = tr(msg); t.className = 'show ' + (cls || '');
    clearTimeout(toastT); toastT = setTimeout(() => { t.className = ''; }, 2600);
  }
  function chMenu(c) {
    const pks = cfg().pk.filter(p => p.id !== 'fav');
    const btns = pks.map(p => {
      const on = inPk(p, c);
      return `<button class="pkpick f ${on ? 'on' : ''}" style="${cv(p.col)}" data-act="mToggle" data-arg="${p.id}|${c.i}"><span class="nt">${p.ic} ${esc(pkName(p))}</span>${on ? ' ✓' : ''}</button>`;
    }).join('');
    modal(`<div class="mhead">${logo(c)}<div><h2 class="nt">${esc(dispName(c))}</h2><p class="mut small nt">${esc(c.g)} ${qTag(c.q)}</p></div></div>
      <label class="fl">إعادة تسمية</label>
      <div class="bar"><input class="in rtl f" id="m-name" value="${esc(dispName(c))}"><button class="btn f" data-act="mRename" data-arg="${c.i}">حفظ</button></div>
      <label class="fl">الباقات (اضغط للإضافة/الإزالة)</label><div class="pkpicks">${btns}</div>
      <div class="bar">
        <button class="btn pri f" data-act="pvPlay" data-arg="${c.i}">▶ معاينة</button>
        <button class="btn f" data-act="mProbe" data-arg="${c.i}">📶 فحص الجودة</button>
        ${ui.view === 'pack' && !ui.showRem ? `<button class="btn f" data-act="mPos" data-arg="${c.i}">#️⃣ نقل إلى رقم</button>` : ''}
        <span id="m-probe" class="mut"></span></div>
      <div class="bar end"><button class="btn pri f auto" data-act="closeModal">تم</button></div>`);
  }
  function pkEditor(pk, isNew) {
    if (!pk) isNew = true;
    pk = pk || { id: 'c' + Date.now().toString(36), ar: '', en: '', ic: '📺', col: COLORS[cfg().pk.length % COLORS.length], on: true, auto: false, lang: 'all', kw: '', grp: [], add: [], rem: [], ord: [] };
    ui.editPk = pk; ui.editNew = !!isNew;
    const icons = ['📺', '⚽', '🎬', '🎭', '📰', '🕌', '🧸', '🌿', '🎉', '💎', '🌙', '🌍', '🎵', '🏎️', '🍳', '🏆', '📡', '❤️'];
    modal(`<h2>${isNew ? 'باقة جديدة' : 'إعدادات الباقة'}</h2>
      <div class="grid2"><div><label class="fl">الاسم بالعربي</label><input class="in rtl f auto" id="e-ar" value="${esc(pk.ar)}"></div>
      <div><label class="fl">الاسم بالإنجليزي</label><input class="in f" id="e-en" value="${esc(pk.en)}"></div></div>
      <label class="fl">الرمز</label><div class="icons">${icons.map(i => `<button class="ib f ${pk.ic === i ? 'on' : ''}" data-act="eIcon" data-arg="${i}">${i}</button>`).join('')}</div>
      <label class="fl">اللون</label><div class="icons">${COLORS.concat(['#f5b301', '#84cc16', '#6366f1']).map(c => `<button class="colr f ${pk.col === c ? 'on' : ''}" style="${cv(c)}" data-act="eCol" data-arg="${c}"></button>`).join('')}</div>
      <label class="fl">كلمات للتصنيف التلقائي (افصل بفاصلة)</label><input class="in rtl f" id="e-kw" placeholder="مثال: MBC, روتانا, OSN" value="${esc(pk.kw)}">
      ${RULES[pk.id] || pk.id === 'arabic' || pk.id === 'foreign' ? `<div class="opt"><span>التصنيف التلقائي الذكي</span><button class="sw f ${pk.auto !== false ? 'on' : ''}" data-act="eAuto"><i></i></button></div>` : ''}
      ${pk.grp && pk.grp.length ? `<label class="fl">مجموعات مضافة</label><div class="icons">${pk.grp.map((g, i) => `<button class="btn sm f" data-act="eGrpDel" data-arg="${i}">${esc(g)} ✕</button>`).join('')}</div>` : ''}
      <div class="bar end">${!isNew && pk.id !== 'fav' ? '<button class="btn danger f" data-act="eDelete">🗑 حذف الباقة</button>' : ''}
      ${!isNew ? '<button class="btn f" data-act="eReset">مسح التعديلات اليدوية</button>' : ''}
      <button class="btn f" data-act="closeModal">إلغاء</button><button class="btn pri f" data-act="eSave">حفظ</button></div>`);
  }
  function onboard() {
    const o = cfg().opt;
    const ex = id => { const b = BUILTIN.find(x => x.id === id); return o.nameLang === 'en' ? b.en : o.nameLang === 'both' ? b.ar + ' | ' + b.en : b.ar; };
    modal(`<div class="onb"><img src="icon.png" alt=""><h2>${L('تم جلب ' + fmtN(CH.length) + ' قناة ورتّبناها في باقات ✓', fmtN(CH.length) + ' channels loaded and sorted into packages ✓')}</h2>
      <p class="mut">كيف تريد ترتيب القنوات؟</p></div>
      <div class="pkpicks onb-langs">
        <button class="pkpick f auto ${o.mode !== 'provider' ? 'on' : ''}" data-act="onbMode" data-arg="smart">✨ ترتيب ذكي<small>باقات تلقائية: رياضة، أخبار، أفلام، 4K…</small></button>
        <button class="pkpick f ${o.mode === 'provider' ? 'on' : ''}" data-act="onbMode" data-arg="provider">📡 كما هو من المزود<small>نفس مجموعات الاشتراك وترتيبه (${fmtN(GROUPS.length)} مجموعة)</small></button></div>
      <label class="fl">أسماء الباقات الذكية في الرسيفر</label>
      <div class="pkpicks onb-langs">
        <button class="pkpick f ${o.nameLang === 'ar' ? 'on' : ''}" data-act="onbName" data-arg="ar">عربي<small class="nt">رياضة · أخبار · أفلام</small></button>
        <button class="pkpick f ${o.nameLang === 'en' ? 'on' : ''}" data-act="onbName" data-arg="en">English<small class="nt">Sports · News · Movies</small></button>
        <button class="pkpick f ${o.nameLang === 'both' ? 'on' : ''}" data-act="onbName" data-arg="both">عربي + English<small class="nt">رياضة | Sports</small></button></div>
      <div class="bar end"><button class="btn pri f" data-act="onbDone">متابعة ‹</button></div>`);
  }
  function welcome() {
    const st = S.settings;
    const b = (act, cur, k, l) => `<button class="pkpick f ${cur === k ? 'on auto' : ''}" data-act="${act}" data-arg="${k}">${l}</button>`;
    modal(`<div class="onb"><img src="icon.png" alt=""><h2>مرحباً بك في M3U</h2><p class="mut">اختر لغة التطبيق والمظهر المناسب لك</p></div>
      <label class="fl">لغة التطبيق</label><div class="pkpicks onb-langs">${b('wLang', st.lang || 'ar', 'ar', '<span class="nt">🇸🇦 العربية</span>')}${b('wLang', st.lang || 'ar', 'en', '<span class="nt">🇬🇧 English</span>')}</div>
      <label class="fl">المظهر</label><div class="pkpicks onb-langs">${b('wTheme', st.theme || 'dark', 'dark', '🌙 ليلي')}${b('wTheme', st.theme || 'dark', 'light', '☀️ نهاري')}</div>
      <div class="bar end"><button class="btn pri big f" data-act="welcomeDone">ابدأ ‹</button></div>`);
  }
  function pickTarget(after) {
    modal(`<h2>اختر الباقة</h2><div class="pkpicks">${cfg().pk.map((p, i) => `<button class="pkpick f ${i === 0 ? 'auto' : ''} ${ui.target === p.id ? 'on' : ''}" style="${cv(p.col)}" data-act="setTarget" data-arg="${p.id}${after ? '|' + after : ''}"><span class="nt">${p.ic} ${esc(pkName(p))}</span></button>`).join('')}</div>
      <div class="bar end"><button class="btn f" data-act="closeModal">إلغاء</button></div>`);
  }

  /* ---------------------------------------------------------------- actions */
  let ACT_EXTRA = {};
  const chAt = i => CH[+i];
  const ACT = {
    go: v => go(v),
    page: p => { ui.page = Math.max(0, +p); ui.move = null; render(true); },
    closeModal: () => closeModal(),
    srcTab: t => { readForm(); ui.srcTab = t; S.settings.srcTab = t; save(); render(); },
    xtOut: o => { readForm(); ui.editSrc.output = o; render(); },
    srcAdd: () => {
      const f = readForm();
      let s;
      if (ui.srcTab === 'link') {
        if (!f.url) return toast('أدخل رابط الاشتراك', 'bad');
        s = detectLink(f.url);
        if (!s) return toast('الرابط غير صحيح، تأكد أنه يبدأ بـ http', 'bad');
      } else {
        if (!f.server || !f.user || !f.pass) return toast('أكمل بيانات Xtream', 'bad');
        let sv = f.server.trim(); if (!/^https?:\/\//i.test(sv)) sv = 'http://' + sv;
        s = { type: 'xtream', server: sv, user: f.user.trim(), pass: f.pass.trim(), output: f.output || 'ts' };
      }
      s.id = 's' + Date.now().toString(36);
      s.name = f.name || (s.type === 'xtream' ? (s.user + ' @ ' + s.server.replace(/^https?:\/\//, '')) : 'اشتراك ' + (S.sources.length + 1));
      S.sources.push(s); ui.editSrc = {};
      loadSource(s, false);
    },
    srcLoad: i => loadSource(S.sources[+i], false),
    srcCache: i => loadSource(S.sources[+i], true),
    srcDel: i => {
      const s = S.sources[+i];
      modal(`<h2>حذف "${esc(s.name)}"؟</h2><p class="mut">ستُحذف الباقات والترتيب الخاص بهذا الاشتراك.</p>
        <div class="bar end"><button class="btn f auto" data-act="closeModal">إلغاء</button><button class="btn danger f" data-act="srcDelYes" data-arg="${i}">حذف</button></div>`);
    },
    srcDelYes: i => {
      const s = S.sources.splice(+i, 1)[0];
      delete S.cfg[s.id];
      if (S.active === s.id) { S.active = null; CH = []; GROUPS = []; ACC = null; MEMO = null; }
      save(); closeModal();
    },
    pickDevice: async () => {
      modal('<h2>جاري البحث عن ملفات M3U…</h2><div class="prog ind"><i></i></div>');
      try {
        const files = await (await fetch('/api/files')).json();
        if (!files.length) return modal(`<h2>لم يتم العثور على ملفات</h2><p class="mut">انسخ ملف .m3u إلى فلاش USB أو مجلد Download ثم أعد المحاولة.</p><div class="bar end"><button class="btn pri f auto" data-act="closeModal">حسناً</button></div>`);
        ui.files = files;
        modal(`<h2>اختر ملف القنوات</h2><div class="list">${files.map((f, i) => `<button class="rowmain filerow f ${i === 0 ? 'auto' : ''}" data-act="importFile" data-arg="${i}"><span class="gic">📄</span><span class="nm"><b class="ltr">${esc(f.name)}</b><small class="ltr">${esc(f.path)} · ${fmtMB(f.size)}</small></span></button>`).join('')}</div>
          <div class="bar end"><button class="btn f" data-act="closeModal">إلغاء</button></div>`);
      } catch (e) { closeModal(); toast('تعذر قراءة الذاكرة', 'bad'); }
    },
    browse: async path => {
      let j;
      try { j = await (await fetch('/api/ls?path=' + enc(path || ''))).json(); } catch (e) { return toast('تعذر قراءة الذاكرة', 'bad'); }
      ui.lsItems = j.items || [];
      if (j.error === 'denied' || (!path && !INFO.storage)) {
        if (j.error === 'denied' || !ui.lsItems.some(x => x.readable)) {
          return modal(`<h2>🔐 يلزم إذن الوصول للملفات</h2><p class="mut">لعرض ملفات الرسيفر وفلاش USB، امنح التطبيق صلاحية «الوصول إلى كل الملفات» ثم ارجع للتطبيق.</p>
            <div class="bar end"><button class="btn f" data-act="sysPick">🗂 فتح مدير الملفات بدلاً من ذلك</button><button class="btn pri f auto" data-act="permThenBrowse">منح الصلاحية</button></div>`);
        }
      }
      const rows = ui.lsItems.map((x, i) => `<button class="rowmain filerow f ${i === 0 ? 'auto' : ''}" data-act="${x.dir ? 'browseIdx' : 'importIdx'}" data-arg="${i}">
          <span class="gic">${x.root ? (x.name.indexOf('USB') === 0 ? '💾' : '📱') : x.dir ? '📁' : '📄'}</span>
          <span class="nm"><b class="nt">${esc(x.name)}</b>${x.dir ? '' : `<small class="ltr">${fmtMB(x.size)}</small>`}</span></button>`).join('');
      modal(`<h2>📂 اختر ملف القنوات</h2><p class="mut small ltr">${esc(j.path || '/')}</p>
        <div class="list">${j.parent != null && path ? `<button class="rowmain filerow f ${rows ? '' : 'auto'}" data-act="browse" data-arg="${esc(j.parent)}"><span class="gic">⬆️</span><span class="nm"><b>رجوع للمجلد السابق</b></span></button>` : ''}
        ${rows || '<p class="mut">لا توجد مجلدات أو ملفات M3U هنا</p>'}</div>
        <div class="bar end"><button class="btn f" data-act="sysPick">🗂 مدير الملفات</button><button class="btn f" data-act="closeModal">إلغاء</button></div>`);
    },
    browseIdx: i => ACT.browse(ui.lsItems[+i].path),
    importIdx: i => { const f = ui.lsItems[+i]; ui.files = [f]; ACT.importFile(0); },
    permThenBrowse: () => { ACT.perm(); closeModal(); ui.afterPerm = true; },
    sysPick: async () => {
      const id = 's' + Date.now().toString(36);
      const r = await (await fetch('/api/pick?id=' + enc(id))).json();
      if (!r.ok) return toast('تعذر فتح مدير الملفات', 'bad');
      modal('<h2>🗂 اختر ملف M3U من مدير الملفات…</h2><div class="prog ind"><i></i></div><div class="bar end"><button class="btn f auto" data-act="closeModal">إلغاء</button></div>');
      for (let k = 0; k < 600; k++) {
        await new Promise(res => setTimeout(res, 1000));
        const st = (await (await fetch('/api/pickstatus?id=' + enc(id))).json()).state;
        if (st === 'pending' || st === 'none') continue;
        closeModal();
        if (st === 'done') {
          const s = { id, type: 'file', name: readForm().name || 'ملف M3U' };
          S.sources.push(s); loadSource(s, true);
        } else if (st === 'nopicker') toast('لا يوجد مدير ملفات في الرسيفر؛ استخدم «تصفح ملفات الرسيفر»', 'bad');
        else if (st === 'error') toast('تعذر قراءة الملف', 'bad');
        return;
      }
    },
    importFile: async i => {
      const f = ui.files[+i], name = (readForm().name) || f.name.replace(/\.[^.]+$/, '');
      const s = { id: 's' + Date.now().toString(36), type: 'file', name };
      const r = await fetch('/api/importfile?id=' + enc(s.id) + '&path=' + enc(f.path), { method: 'POST' });
      closeModal();
      if (!r.ok) return toast(await r.text(), 'bad');
      S.sources.push(s); loadSource(s, true);
    },
    optSet: a => { const [k, v] = a.split(':'); cfg().opt[k] = v; changed(); render(); },
    optTog: k => { const o = cfg().opt; o[k] = !o[k]; changed(); render(); },
    pkOpen: id => go('pack', { pk: id }),
    pkToggle: id => { const p = pkById(id); p.on = !p.on; changed(); render(); toast(p.on ? 'تم تفعيل ' + pkName(p) : 'تم إيقاف ' + pkName(p)); },
    pkMove: id => { ui.move = { kind: 'pk', key: id }; render(); },
    pkEdit: id => pkEditor(pkById(id)),
    pkNew: () => {
      const have = new Set(cfg().pk.map(p => p.id));
      const missing = BUILTIN.filter(b => !have.has(b.id));
      modal(`<h2>＋ إضافة باقة</h2><div class="pkpicks">
        <button class="pkpick f auto" data-act="pkCustom">✏️ باقة جديدة فارغة</button>
        <button class="pkpick f" data-act="pkFromGroup">🗂️ من مجموعات المزود</button>
        ${missing.map(b => `<button class="pkpick f" style="${cv(b.col)}" data-act="pkRestore" data-arg="${b.id}">${b.ic} ${esc(cfg().opt.nameLang === 'en' ? b.en : b.ar)}<small>باقة ذكية تلقائية</small></button>`).join('')}
        </div><div class="bar end"><button class="btn f" data-act="closeModal">إلغاء</button></div>`);
    },
    pkCustom: () => pkEditor(null),
    pkFromGroup: () => { closeModal(); toast('اختر مجموعة ثم «＋ إلى باقة» أو «باقة جديدة من هذه المجموعة»'); go('browse'); },
    pkRestore: id => {
      const b = BUILTIN.find(x => x.id === id);
      cfg().pk.push({ id: b.id, ar: b.ar, en: b.en, ic: b.ic, col: b.col, on: true, auto: b.auto !== false, lang: 'all', kw: '', grp: [], add: [], rem: [], ord: [] });
      changed(); closeModal(); toast('أُضيفت باقة ' + pkName(b), 'ok');
    },
    setMode: m => {
      if ((cfg().opt.mode || 'smart') === m) return;
      modal(`<h2>${m === 'provider' ? '📡 استخدام ترتيب المزود؟' : '✨ استخدام الترتيب الذكي؟'}</h2>
        <p class="mut">${m === 'provider' ? 'ستصبح كل مجموعة من مجموعات الاشتراك باقة بنفس الترتيب. تقدر بعدها تحذف أو تضيف أو تعيد الترتيب.' : 'سيتم توزيع القنوات تلقائياً على باقات: رياضة، أخبار، أفلام، 4K…'}<br>المفضلة تبقى كما هي.</p>
        <div class="bar end"><button class="btn f auto" data-act="closeModal">إلغاء</button><button class="btn pri f" data-act="setModeYes" data-arg="${m}">تطبيق</button></div>`);
    },
    setModeYes: m => { if (m === 'provider') providerPacks(); else smartPacks(); closeModal(); go('packs', null, false); toast('تم ✓', 'ok'); },
    pkAddCh: id => { ui.target = id; go('browse'); },
    pkSort: id => {
      modal(`<h2>ترتيب تلقائي</h2><div class="pkpicks">
        <button class="pkpick f auto" data-act="doSort" data-arg="${id}|az">🔤 أبجدي (أ-ي / A-Z)</button>
        <button class="pkpick f" data-act="doSort" data-arg="${id}|q">💎 الجودة الأعلى أولاً</button>
        <button class="pkpick f" data-act="doSort" data-arg="${id}|lang">🌙 العربي أولاً</button>
        <button class="pkpick f" data-act="doSort" data-arg="${id}|orig">↺ ترتيب المزود الأصلي</button></div>
        <div class="bar end"><button class="btn f" data-act="closeModal">إلغاء</button></div>`);
    },
    doSort: a => {
      const [id, how] = a.split('|'), pk = pkById(id);
      if (how === 'orig') pk.ord = [];
      else {
        MEMO = null; const list = members(id).slice();
        const coll = new Intl.Collator(['ar', 'en'], { numeric: true, sensitivity: 'base' });
        if (how === 'az') list.sort((x, y) => coll.compare(dispName(x), dispName(y)));
        if (how === 'q') list.sort((x, y) => (y.q - x.q) || coll.compare(dispName(x), dispName(y)));
        if (how === 'lang') list.sort((x, y) => ((x.lang === 'ar' ? 0 : 1) - (y.lang === 'ar' ? 0 : 1)) || (x.i - y.i));
        pk.ord = list.map(c => c.k);
      }
      changed(); closeModal(); toast('تم الترتيب ✓', 'ok');
    },
    pkProbe: async id => {
      const list = members(id).slice(0, 500);
      let ok = 0, bad = [], stop = false;
      ui.probeStop = () => { stop = true; };
      modal(`<h2>فحص القنوات</h2><p id="pr-t" class="mut">0 / ${list.length}</p><div class="prog"><i id="pr-b"></i></div>
        <p class="hint">الفحص يتم قناة بقناة حتى لا يتجاوز عدد الاتصالات المسموحة في اشتراكك.</p>
        <div class="bar end"><button class="btn f auto" data-act="probeStop">إيقاف</button></div>`);
      for (let i = 0; i < list.length && !stop; i++) {
        try { const r = await (await fetch('/api/probe?url=' + enc(list[i].u))).json(); if (r.ok) ok++; else bad.push(list[i]); } catch (e) { bad.push(list[i]); }
        const t = $('#pr-t'), b = $('#pr-b');
        if (!t) break;
        t.textContent = `${i + 1} / ${list.length} · تعمل: ${ok} · متوقفة: ${bad.length}`; b.style.width = ((i + 1) / list.length * 100) + '%';
      }
      ui.deadList = bad;
      if ($('#pr-t')) modal(`<h2>نتيجة الفحص</h2><p><span class="ok">✓ ${ok} تعمل</span> · <span class="bad">✕ ${bad.length} متوقفة</span></p>
        <div class="list small">${bad.slice(0, 30).map(c => `<div class="path">✕ ${esc(dispName(c))}</div>`).join('')}</div>
        <div class="bar end"><button class="btn f auto" data-act="closeModal">إغلاق</button>${bad.length ? `<button class="btn danger f" data-act="removeDead" data-arg="${id}">حذف المتوقفة من الباقة</button>` : ''}</div>`);
    },
    probeStop: () => { ui.probeStop && ui.probeStop(); closeModal(); },
    removeDead: id => { const pk = pkById(id); ui.deadList.forEach(c => removeFrom(pk, c)); closeModal(); toast('تم حذف ' + ui.deadList.length + ' قناة'); },
    showRem: () => { ui.showRem = !ui.showRem; ui.page = 0; render(true); },
    chMove: i => {
      const c = chAt(i);
      if (ui.q) return toast('امسح البحث أولاً لتتمكن من الترتيب', 'bad');
      if (ui.move && ui.move.key === c.k) { ui.move = null; render(); return; }
      ui.move = { kind: 'ch', pk: ui.pk, key: c.k }; render();
    },
    chFav: i => { const c = chAt(i); toggleFav(c); render(); toast(inPk(pkById('fav'), c) ? '⭐ أضيفت للمفضلة' : 'أزيلت من المفضلة'); },
    chRemove: i => { const c = chAt(i); removeFrom(pkById(ui.pk), c); render(); toast('حُذفت ' + dispName(c)); },
    chRestore: i => { const c = chAt(i), pk = pkById(ui.pk); pk.rem = pk.rem.filter(k => k !== c.k); changed(); render(); },
    chTarget: i => { const c = chAt(i), tp = pkById(ui.target) || pkById('fav'); if (inPk(tp, c)) removeFrom(tp, c); else addTo(tp, c); render(); },
    chMenu: i => chMenu(chAt(i)),
    mToggle: a => { const [id, i] = a.split('|'), pk = pkById(id), c = chAt(i); if (inPk(pk, c)) removeFrom(pk, c); else addTo(pk, c); chMenu(c); },
    mRename: i => { const c = chAt(i), v = $('#m-name').value.trim(); if (v && v !== c.n) cfg().cren[c.k] = v; else delete cfg().cren[c.k]; changed(); toast('تم ✓', 'ok'); },
    mPlay: i => fetch('/api/play?url=' + enc(chAt(i).u)).then(r => r.json()).then(j => toast(j.ok ? 'جاري التشغيل…' : 'لا يوجد مشغل فيديو', j.ok ? 'ok' : 'bad')),
    mProbe: async i => {
      const c = chAt(i), el = $('#m-probe');
      el.textContent = L('⏳ جاري قياس الجودة (4 ثواني)…', '⏳ Measuring quality (4 s)…');
      let r; try { r = await (await fetch('/api/probe?measure=1&url=' + enc(c.u))).json(); } catch (e) { r = { ok: false }; }
      const qi = cfg().qi || (cfg().qi = {});
      if (r.ok) {
        qi[c.k] = { kbps: r.kbps, ms: r.ms }; save();
        el.innerHTML = `<span class="ok">✓ ${L('تعمل', 'Working')}</span> · <b>≈ ${kbpsQ(r.kbps)}</b> · <span class="ltr">${fmtRate(r.kbps)}</span> · ${L('استجابة', 'response')} <span class="ltr">${(r.ms / 1000).toFixed(1)}s</span>`;
      } else {
        qi[c.k] = { fail: true }; save();
        el.innerHTML = `<span class="bad">✕ ${L('لا تعمل', 'Not working')}${r.code ? ' (' + r.code + ')' : ''}</span>`;
      }
    },
    mPos: i => {
      const c = chAt(i);
      modal(`<h2>نقل "${esc(dispName(c))}" إلى الرقم</h2><input class="in f auto" id="m-pos" type="number" min="1" value="1">
        <div class="bar end"><button class="btn f" data-act="closeModal">إلغاء</button><button class="btn pri f" data-act="mPosGo" data-arg="${i}">نقل</button></div>`);
    },
    mPosGo: i => { const c = chAt(i), pos = Math.max(1, +$('#m-pos').value || 1) - 1; moveTo(pkById(ui.pk), c.k, pos); ui.page = Math.floor(pos / PAGE); closeModal(); toast('تم النقل ✓', 'ok'); },
    pickTarget: () => pickTarget(),
    setTarget: a => {
      const [id, after] = a.split('|'); ui.target = id; closeModal();
      if (after === 'grp') ACT.grpAll();
    },
    grpOpen: i => go('group', { grp: GROUPS[+i].name }),
    grpToPk: i => { ui.grp = GROUPS[+i].name; pickTarget('grp'); },
    grpAll: () => {
      const tp = pkById(ui.target) || pkById('fav');
      if (tp.id === 'fav') { const g = GROUPS.find(x => x.name === ui.grp); g.list.forEach(c => { if (!inPk(tp, c)) tp.add.push(c.k); }); }
      else if (!(tp.grp || []).includes(ui.grp)) tp.grp = [...(tp.grp || []), ui.grp];
      changed(); render(); toast('أُضيفت المجموعة إلى ' + pkName(tp), 'ok');
    },
    grpNewPk: () => {
      const pk = { id: 'c' + Date.now().toString(36), ar: ui.grp, en: ui.grp, ic: '📺', col: COLORS[cfg().pk.length % COLORS.length], on: true, auto: false, lang: 'all', kw: '', grp: [ui.grp], add: [], rem: [], ord: [] };
      cfg().pk.push(pk); changed(); toast('تم إنشاء باقة ' + ui.grp, 'ok'); go('pack', { pk: pk.id });
    },
    eIcon: i => { ui.editPk.ic = i; refreshEditor(); },
    eCol: c => { ui.editPk.col = c; refreshEditor(); },
    eLang: l => { ui.editPk.lang = l; refreshEditor(); },
    eAuto: () => { ui.editPk.auto = ui.editPk.auto === false; refreshEditor(); },
    eGrpDel: i => { ui.editPk.grp.splice(+i, 1); refreshEditor(); },
    eSave: () => {
      const pk = ui.editPk; readEditor();
      if (!pk.ar && !pk.en) return toast('اكتب اسم الباقة', 'bad');
      pk.ar = pk.ar || pk.en; pk.en = pk.en || pk.ar;
      if (ui.editNew) cfg().pk.push(pk);
      changed(); closeModal(); toast('تم الحفظ ✓', 'ok');
    },
    eReset: () => { const pk = ui.editPk; pk.add = []; pk.rem = []; pk.ord = []; changed(); closeModal(); toast('تمت إعادة الباقة لوضعها التلقائي'); },
    eDelete: () => { cfg().pk = cfg().pk.filter(p => p !== ui.editPk); BASE.delete(ui.editPk.id); changed(); closeModal(); if (ui.view === 'pack') go('packs'); toast('حُذفت الباقة'); },
    doExport: async () => {
      const b = buildM3U();
      if (!b.count) return toast('لا توجد قنوات؛ فعّل باقة واحدة على الأقل', 'bad');
      toast('جاري الحفظ…');
      try {
        const r = await (await fetch('/api/export?name=' + enc(cfg().opt.fname || 'M3U_TV'), { method: 'POST', body: b.text })).json();
        ui.exportRes = { saved: r.saved || [], failed: r.failed || [], count: b.count };
        render();
        toast(r.saved && r.saved.length ? L('تم الحفظ ✓ — افتح بحث قنوات IP في الرسيفر', 'Saved ✓ — open IP channel search on the receiver') : 'تعذر الحفظ؛ امنح صلاحية التخزين', r.saved && r.saved.length ? 'ok' : 'bad');
        if (!INFO.storage && !(r.saved || []).length) ACT.perm();
      } catch (e) { toast('خطأ في الحفظ', 'bad'); }
    },
    download: () => {
      const b = buildM3U(), a = document.createElement('a');
      a.href = URL.createObjectURL(new Blob([b.text], { type: 'audio/x-mpegurl' }));
      a.download = (cfg().opt.fname || 'M3U_TV') + '.m3u'; a.click();
    },
    perm: () => { fetch('/api/permission', { method: 'POST' }); toast('امنح الصلاحية ثم ارجع للتطبيق'); },
    pickApp: async () => {
      const apps = await (await fetch('/api/apps')).json();
      apps.sort((a, b) => (/ip|tv|channel|tiger|dvb|live/i.test(b.label) ? 1 : 0) - (/ip|tv|channel|tiger|dvb|live/i.test(a.label) ? 1 : 0));
      ui.apps = apps;
      modal(`<h2>اختر تطبيق IP Channels</h2><div class="list">${apps.map((a, i) => `<button class="rowmain filerow f ${i === 0 ? 'auto' : ''}" data-act="setApp" data-arg="${i}"><span class="nm"><b>${esc(a.label)}</b><small class="ltr">${esc(a.pkg)}</small></span></button>`).join('') || '<p class="mut">لا توجد تطبيقات</p>'}</div>
        <div class="bar end"><button class="btn f" data-act="closeModal">إلغاء</button></div>`);
    },
    setApp: i => { const a = ui.apps[+i]; S.settings.targetPkg = a.pkg; S.settings.targetLabel = a.label; save(); closeModal(); toast('تم اختيار ' + a.label, 'ok'); },
    launchTarget: () => fetch('/api/launch?pkg=' + enc(S.settings.targetPkg)),
    backup: () => {
      const a = document.createElement('a');
      a.href = URL.createObjectURL(new Blob([JSON.stringify(S, null, 1)], { type: 'application/json' }));
      a.download = 'm3u-backup.json'; a.click();
    },
    resetCfg: () => {
      modal(`<h2>إعادة الضبط؟</h2><p class="mut">ستعود الباقات للتصنيف التلقائي وتُحذف المفضلة والترتيب.</p>
        <div class="bar end"><button class="btn f auto" data-act="closeModal">إلغاء</button><button class="btn danger f" data-act="resetYes">إعادة الضبط</button></div>`);
    },
    onbName: k => { cfg().opt.nameLang = k; changed(); onboard(); },
    onbMode: m => { if (m === 'provider') providerPacks(); else smartPacks(); onboard(); },
    onbDone: () => { cfg().opt.asked = true; changed(); closeModal(); },
    setLang: l => { S.settings.lang = l; save(); closeModal(); },
    setName: k => { cfg().opt.nameLang = k; changed(); render(); },
    setTheme: t => { S.settings.theme = t; save(); render(); },
    themeToggle: () => { const c = document.documentElement.dataset.theme; S.settings.theme = c === 'dark' ? 'light' : 'dark'; save(); render(); },
    welcomeDone: () => { S.settings.lang = S.settings.lang || 'ar'; S.settings.welcomed = true; save(); closeModal(); },
    wLang: l => { S.settings.lang = l; applyLook(); welcome(); },
    wTheme: t => { S.settings.theme = t; applyLook(); welcome(); },
    resetYes: () => { if (S.active) S.cfg[S.active] = newCfg(); changed(); closeModal(); }
  };
  function readForm() {
    const v = id => { const e = $('#' + id); return e ? e.value.trim() : undefined; };
    const e = ui.editSrc = ui.editSrc || {};
    ['name', 'url', 'server', 'user', 'pass'].forEach(k => { const x = v('f-' + k); if (x !== undefined) e[k] = x; });
    return e;
  }
  function readEditor() {
    const pk = ui.editPk, v = id => ($('#' + id) || {}).value;
    if (v('e-ar') !== undefined) { pk.ar = v('e-ar').trim(); pk.en = v('e-en').trim(); pk.kw = v('e-kw').trim(); }
  }
  function refreshEditor() { readEditor(); pkEditor(ui.editPk, ui.editNew); }

  document.addEventListener('click', e => {
    const t = e.target.closest('[data-act]');
    if (!t || t.disabled) return;
    const fn = ACT[t.dataset.act] || ACT_EXTRA[t.dataset.act];
    if (fn) { e.preventDefault(); fn(t.dataset.arg, t, e); }
  });
  let inpT;
  document.addEventListener('input', e => {
    const t = e.target;
    if (t.dataset.inp === 'q') {
      clearTimeout(inpT);
      inpT = setTimeout(() => { ui.q = t.value.trim(); ui.page = 0; render(); }, 280);
    } else if (t.dataset.inp === 'fname') { cfg().opt.fname = t.value.trim() || 'M3U_TV'; save(); }
  });
  document.addEventListener('change', async e => {
    const t = e.target;
    if (t.id === 'f-file' && t.files[0]) {
      const file = t.files[0], text = await file.text();
      const name = readForm().name || file.name.replace(/\.[^.]+$/, '');
      const s = { id: 's' + Date.now().toString(36), type: 'file', name };
      await fetch('/api/upload?id=' + enc(s.id), { method: 'POST', body: text });
      S.sources.push(s); loadSource(s, true);
    }
    if (t.id === 'f-restore' && t.files[0]) {
      try { const j = JSON.parse(await t.files[0].text()); if (j.sources) { S = j; save(); toast('تمت الاستعادة ✓', 'ok'); init(); } } catch (er) { toast('ملف غير صالح', 'bad'); }
    }
  });
  /* drag & drop with the mouse (PC / phone browser) */
  document.addEventListener('dragstart', e => { const r = e.target.closest('[data-drag]'); if (r) { ui.dragKey = r.dataset.drag; e.dataTransfer.effectAllowed = 'move'; r.classList.add('dragging'); } });
  document.addEventListener('dragend', e => { document.querySelectorAll('.dragging,.dropover').forEach(x => x.classList.remove('dragging', 'dropover')); });
  document.addEventListener('dragover', e => { const r = e.target.closest('[data-drag]'); if (r && ui.dragKey) { e.preventDefault(); document.querySelectorAll('.dropover').forEach(x => x.classList.remove('dropover')); r.classList.add('dropover'); } });
  document.addEventListener('drop', e => {
    const r = e.target.closest('[data-drag]'); if (!r || !ui.dragKey) return;
    e.preventDefault();
    const [k1, a] = ui.dragKey.split(':'), [k2, b] = r.dataset.drag.split(':');
    ui.dragKey = null;
    if (k1 !== k2 || a === b) return;
    if (k1 === 'pk') {
      const arr = cfg().pk, i = arr.findIndex(p => p.id === a), j = arr.findIndex(p => p.id === b);
      const [x] = arr.splice(i, 1); arr.splice(j, 0, x);
    } else {
      const pk = pkById(ui.pk), list = members(pk.id).map(c => c.k), ka = CH[+a].k, kb = CH[+b].k;
      const j = list.indexOf(kb); moveTo(pk, ka, j);
    }
    changed(); render();
  });

  /* ---------------------------------------------------------------- preview */
  const PV = { list: null, i: 0, pk: null, tried: false };
  function altUrl(u) {
    if (/\/live\/[^/]+\/[^/]+\/\d+\.ts(\?|$)/i.test(u)) return u.replace(/\.ts(\?|$)/i, '.m3u8$1');
    if (/\/live\/[^/]+\/[^/]+\/\d+\.m3u8(\?|$)/i.test(u)) return u.replace(/\.m3u8(\?|$)/i, '.ts$1');
    return '';
  }
  function previewStart(list, i, pkId) {
    if (!list || !list.length) return toast('لا توجد قنوات للمعاينة', 'bad');
    PV.list = list.slice(); PV.i = Math.max(0, Math.min(i || 0, list.length - 1)); PV.pk = pkId || null;
    previewShow();
  }
  function previewShow() {
    const c = PV.list && PV.list[PV.i];
    if (!c) { previewEnd(); return toast(L('انتهت المعاينة ✓', 'Preview finished ✓'), 'ok'); }
    const pos = (PV.i + 1) + ' / ' + PV.list.length;
    if (IS_TV && window.AndroidBridge && AndroidBridge.preview) {
      AndroidBridge.preview(JSON.stringify({ url: c.u, alt: altUrl(c.u), name: dispName(c), pos, lang: EN() ? 'en' : 'ar' }));
      return;
    }
    // phone / PC browser: HTML5 video (HLS where the browser supports it)
    const a = altUrl(c.u), first = /\.m3u8/i.test(c.u) ? c.u : (a && /\.m3u8/i.test(a) ? a : c.u);
    PV.tried = false; PV.t0 = performance.now(); PV.info = { w: 0, h: 0, ms: -1, fail: false };
    ui.forceClose = true; closeModal(); ui.forceClose = false;
    modal(`<h2 class="nt">${esc(dispName(c))} <span class="mut small ltr">${pos}</span></h2>
      <video id="pv" autoplay muted playsinline controls style="width:100%;aspect-ratio:16/9;background:#000;border-radius:12px"></video>
      <p id="pv-info" class="pvinfo">⏳ ${L('جاري التشغيل…', 'Loading…')}</p>
      <p class="mut small">${L('المعاينة تستخدم اتصالاً واحداً من اشتراكك.', 'Preview uses one connection of your subscription.')}</p>
      <div class="bar end"><button class="btn f" data-act="pvAct" data-arg="prev">› ${L('السابقة', 'Back')}</button>
        <button class="btn danger f" data-act="pvAct" data-arg="delete">🗑 ${L('احذفها', 'Delete')}</button>
        <button class="btn pri f auto" data-act="pvAct" data-arg="keep">✓ ${L('أبقِها', 'Keep')}</button>
        <button class="btn f" data-act="pvAct" data-arg="close">✕</button></div>`);
    const v = $('#pv'), info = $('#pv-info');
    const upd = () => {
      const I = PV.info;
      if (I.fail) info.innerHTML = `<span class="bad">✕ ${L('لا تعمل في المتصفح — جرّب المعاينة من شاشة الرسيفر', "Can't play in the browser — try preview on the receiver")}</span>`;
      else if (I.h) info.textContent = `${I.w}×${I.h} · ${I.h >= 2000 ? '4K UHD' : I.h >= 1000 ? 'FHD 1080p' : I.h >= 700 ? 'HD 720p' : 'SD ' + I.h + 'p'}${I.ms >= 0 ? ' · ' + L('بدأت خلال ', 'started in ') + (I.ms / 1000).toFixed(1) + L(' ث', 's') : ''}`;
    };
    v.onloadedmetadata = () => { PV.info.w = v.videoWidth; PV.info.h = v.videoHeight; upd(); };
    v.onplaying = () => { if (PV.info.ms < 0) { PV.info.ms = Math.round(performance.now() - PV.t0); upd(); saveQ(c, PV.info); } };
    v.onerror = () => {
      const other = first === c.u ? a : c.u;
      if (!PV.tried && other) { PV.tried = true; v.src = other; v.play().catch(() => { }); }
      else { PV.info.fail = true; upd(); saveQ(c, PV.info); }
    };
    v.src = first; v.play().catch(() => { });
  }
  function saveQ(c, I) { const qi = cfg().qi || (cfg().qi = {}); if (I.h || I.fail) { qi[c.k] = { w: I.w, h: I.h, ms: I.ms, fail: !!I.fail && !I.h }; save(); } }
  function stopVideo() { const v = $('#pv'); if (v) { try { v.pause(); v.removeAttribute('src'); v.load(); } catch (e) { } } }
  function previewEnd() {
    PV.list = null; stopVideo();
    if (window.AndroidBridge && AndroidBridge.previewClose && IS_TV) AndroidBridge.previewClose();
    ui.forceClose = true; if (ui.modal) closeModal(); ui.forceClose = false;
    render();
  }
  window.onPreviewAction = function (a, info) {
    if (!PV.list) return;
    const c = PV.list[PV.i];
    if (c && info && (info.h || info.fail)) saveQ(c, info);
    if (a === 'info') { return; }
    stopVideo();
    if (a === 'keep' || a === 'next') { PV.i++; previewShow(); }
    else if (a === 'prev') { PV.i = Math.max(0, PV.i - 1); previewShow(); }
    else if (a === 'delete') {
      const pk = PV.pk ? pkById(PV.pk) : null;
      if (pk && c) { removeFrom(pk, c); toast(L('حُذفت ', 'Deleted ') + dispName(c)); }
      PV.list.splice(PV.i, 1); previewShow();
    } else if (a === 'close') { PV.list = null; ui.forceClose = true; if (ui.modal) closeModal(); ui.forceClose = false; render(); }
  };

  /* ---------------------------------------------------------------- updates */
  const REPO = 'w39xw39x-commits/m3u';
  async function getNotes() {
    try {
      const j = await getJSON('https://raw.githubusercontent.com/' + REPO + '/main/changes.json?t=' + Date.now());
      return (EN() && j.notes_en ? j.notes_en : j.notes) || [];
    } catch (e) { return []; }
  }
  const fmtDate = d => { try { return new Date(d).toLocaleDateString(EN() ? 'en-GB' : 'ar-SA-u-nu-latn', { day: 'numeric', month: 'long', year: 'numeric' }); } catch (e) { return ''; } };
  function umodal(h) { ui.updFlow = true; modal(h); ui.updFlow = false; }
  async function checkUpdate(manual) {
    const cur = (INFO.app || {}).code || 0;
    if (!cur && !manual) return;
    let rel;
    try { rel = await getJSON('https://api.github.com/repos/' + REPO + '/releases/latest'); }
    catch (e) { if (manual) toast('تعذر التحقق من التحديثات', 'bad'); return; }
    const code = +(String(rel.tag_name || '').split('.').pop()) || 0;
    const asset = (rel.assets || []).find(a => /\.apk$/i.test(a.name));
    if (code > cur && asset) {
      const notes = await getNotes();
      if (!IS_TV) {   // phone / PC browser: never block typing, just inform
        const v = String(rel.tag_name).replace(/^v/, '');
        setTimeout(() => toast(L('يتوفر تحديث v' + v + ' للتطبيق، حدّثه من شاشة الرسيفر', 'Update v' + v + ' is available, install it from the receiver screen')), 1500);
        return;
      }
      const show = () => {
        ui.updUrl = asset.browser_download_url; ui.forced = true;
        umodal(`<div class="onb"><img src="icon.png" alt=""><h2>🎉 ${L('تحديث جديد متوفر', 'New update available')}</h2>
          <p class="mut"><b class="ltr">v${esc(String(rel.tag_name).replace(/^v/, ''))}</b> · ${L('نُشر في', 'Released')} ${esc(fmtDate(rel.published_at))} · ${L('نسختك', 'yours')} <span class="ltr">v${esc((INFO.app || {}).name || '')}</span></p></div>
          ${notes.length ? `<div class="notes nt">${notes.map(n => `<div>• ${esc(n)}</div>`).join('')}</div>` : ''}
          <div id="up-area"><div class="prog"><i id="up-b"></i></div><p id="up-t" class="mut">⬇ ${L('جاري تحميل التحديث تلقائياً…', 'Downloading the update automatically…')}</p></div>`);
        let ov = 'ok'; try { ov = window.AndroidBridge && AndroidBridge.overlayState ? AndroidBridge.overlayState() : 'ok'; } catch (e) { }
        if (ov === 'need' && !S.settings.ovSkip) {
          // one-time: let the app reopen itself after every update (Android blocks it otherwise)
          const a = $('#up-area');
          if (a) {
            a.innerHTML = `<p class="pvinfo">⚙️ ${L('خطوة مرة وحدة فقط: فعّل «العرض فوق التطبيقات» لتطبيق M3U عشان يرجع يفتح لحاله بعد كل تحديث.', 'One-time step: allow “Display over other apps” for M3U so it reopens by itself after every update.')}</p>
              <p class="mut small">${L('بتنفتح الإعدادات: فعّل الخيار ثم اضغط رجوع، والتحديث يكمل تلقائياً.', 'Settings will open: turn it on, press Back, and the update continues automatically.')}</p>
              <div class="bar end"><button class="btn pri big f auto" data-act="ovGrant">✅ ${L('تفعيل', 'Allow')}</button><button class="btn big auto" data-act="ovSkip">${L('تخطي', 'Skip')}</button></div>`;
            const f = a.querySelector('.f'); if (f) f.focus();
            return;
          }
        }
        ACT_EXTRA.doUpdate();      // start right away — the user only confirms the system install
      };
      show();
    } else if (manual) toast(L('لديك أحدث نسخة ✓', 'You have the latest version ✓'), 'ok');
  }
  async function whatsNew() {
    const a = INFO.app; if (!a || !a.code) return;
    const seen = S.settings.seenCode;
    S.settings.seenCode = a.code; save();
    if (!seen || seen >= a.code) return;
    const notes = await getNotes();
    if (!notes.length) return;
    const show = () => {
      if (ui.modal || ui.loading) return setTimeout(show, 3000);
      modal(`<div class="onb"><img src="icon.png" alt=""><h2>✨ ${L('ما الجديد في', "What's new in")} <span class="ltr">v${esc(a.name)}</span></h2></div>
        <div class="notes nt">${notes.map(n => `<div>• ${esc(n)}</div>`).join('')}</div>
        ${ovNeed() ? `<p class="pvinfo">⚙️ ${L('خطوة مرة وحدة فقط: فعّل «العرض فوق التطبيقات» لتطبيق M3U عشان يرجع يفتح لحاله بعد كل تحديث. فعّل الخيار ثم اضغط رجوع.', 'One-time step: allow “Display over other apps” for M3U so it reopens by itself after every update. Turn it on, then press Back.')}</p>
        <div class="bar end"><button class="btn pri big f auto" data-act="ovOnly">✅ ${L('تفعيل', 'Allow')}</button><button class="btn big auto" data-act="ovLater">${L('لاحقاً', 'Later')}</button></div>`
        : `<div class="bar end"><button class="btn pri f auto" data-act="closeModal">${L('تمام', 'Got it')}</button></div>`}`);
    };
    show();
  }
  function ovNeed() { if (!IS_TV || S.settings.ovSkip) return false; try { return !!(window.AndroidBridge && AndroidBridge.overlayState && AndroidBridge.overlayState() === 'need'); } catch (e) { return false; } }
  ACT_EXTRA = {
    doUpdate: async () => {
      if (!ui.updUrl || ui.updBusy) return;
      ui.updBusy = true;
      const area = () => $('#up-area');
      if (!area()) umodal(`<h2>⬇ ${L('تحديث التطبيق', 'Updating the app')}</h2><div id="up-area"><div class="prog"><i id="up-b"></i></div><p id="up-t" class="mut"></p></div>`);
      const setArea = h => { const a = area(); if (a) { a.innerHTML = h; const f = a.querySelector('.f'); if (f) f.focus(); } };
      try { await fetch('/api/update?url=' + enc(ui.updUrl), { method: 'POST' }); } catch (e) { }
      for (let k = 0; k < 900; k++) {
        await new Promise(r => setTimeout(r, 600));
        let st; try { st = await (await fetch('/api/updstatus')).json(); } catch (e) { continue; }
        const b = $('#up-b'), t = $('#up-t');
        if (st.total > 0 && b) b.style.width = Math.round(st.got / st.total * 100) + '%';
        if (t && st.state === 'downloading') t.textContent = '⬇ ' + L('جاري التحميل', 'Downloading') + ' · ' + fmtMB(st.got || 0);
        if (st.state === 'installing') {
          ui.updBusy = false;
          return setArea(`<p class="pvinfo">📲 ${L('اضغط «تحديث» في نافذة النظام وسيُعاد فتح التطبيق تلقائياً', 'Press “Update” in the system window; the app reopens by itself')}</p>
            <p class="mut small">${L('إذا أغلقت النافذة بالخطأ اضغط الزر أدناه.', 'If you closed it by mistake, press the button below.')}</p>
            <div class="bar end"><button class="btn pri big f auto" data-act="doUpdate">🔄 ${L('عرض نافذة التثبيت مرة أخرى', 'Show the install window again')}</button></div>`);
        }
        if (st.state === 'error') {
          ui.updBusy = false;
          return setArea(`<p class="bad">⚠ ${L('تعذر تحميل التحديث، تأكد من الإنترنت.', 'Update download failed, check the internet.')}</p>
            <div class="bar end"><button class="btn pri big f auto" data-act="doUpdate">🔄 ${L('إعادة المحاولة', 'Try again')}</button></div>`);
        }
      }
      ui.updBusy = false;
    },
    checkUpd: () => checkUpdate(true),
    ovGrant: () => {
      let ok = false; try { ok = AndroidBridge.overlayRequest(); } catch (e) { }
      if (ok) ui.ovWait = true; else ACT_EXTRA.ovSkip();
    },
    ovOnly: () => { try { AndroidBridge.overlayRequest(); } catch (e) { } closeModal(); },
    ovLater: () => { S.settings.ovSkip = true; save(); closeModal(); },
    ovSkip: () => { S.settings.ovSkip = true; save(); ACT_EXTRA.ovGo(); },
    ovGo: () => {
      const a = $('#up-area');
      if (a) a.innerHTML = `<div class="prog"><i id="up-b"></i></div><p id="up-t" class="mut">⬇ ${L('جاري تحميل التحديث تلقائياً…', 'Downloading the update automatically…')}</p>`;
      ACT_EXTRA.doUpdate();
    },
    pvPlay: async i => {
      const c = chAt(i);
      let j = { ok: false };
      try { j = await (await fetch('/api/play?url=' + enc(c.u))).json(); } catch (e) { }
      if (!j.ok) toast(L('لا يوجد مشغّل فيديو في الرسيفر', 'No video player on the receiver'), 'bad');
      else if (!IS_TV) toast(L('▶ تعمل الآن على شاشة الرسيفر', '▶ Now playing on the receiver screen'), 'ok');
    },
    pvAct: a => window.onPreviewAction(a, PV.info),
    pvStart: id => { const list = members(id); const start = Math.min(ui.page * PAGE, Math.max(0, list.length - 1)); previewStart(list, start, id); },
    pvOne: i => {
      const c = chAt(i);
      if (ui.view === 'pack' && !ui.showRem) { const list = members(ui.pk); previewStart(list, list.indexOf(c), ui.pk); }
      else previewStart([c], 0, null);
    }
  };

  /* ---------------------------------------------------------------- init */
  async function refreshInfo() {
    try { INFO = await (await fetch('/api/info')).json(); renderSide(); } catch (e) { }
  }
  async function init() {
    await refreshInfo();
    try { const st = await (await fetch('/api/state')).json(); if (st && st.sources) S = Object.assign(S, st); } catch (e) { }
    S.settings = S.settings || {};
    applyLook();
    if (S.settings.srcTab) ui.srcTab = S.settings.srcTab;
    const s = src();
    if (s && s.count) { loadSource(s, true); }
    else { ui.view = 'subs'; render(true); }
    if (!S.settings.welcomed) welcome();
    checkUpdate(false);
    setTimeout(() => { if (!ui.forced) whatsNew(); }, 2500);
  }
  init();
  window.__m3u = { parseM3U, setChannels, computeAll, buildM3U, cfg, S: () => S, cleanName, detectLink, CH: () => CH };
})();
