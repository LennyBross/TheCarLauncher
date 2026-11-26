// Bridge to the launcher: the app calls car.update(state) once a second; the page calls Car.*.
window.car = {
  s: {},
  fns: [],
  update: function (st) {
    this.s = st || {};
    for (var i = 0; i < this.fns.length; i++) this.fns[i](this.s);
    // One day / night for every page: whatever the home screen chose (Gündüz / Gece / Oto) arrives
    // here as s.night, and every page follows it the same way.
    if (this.s.night !== undefined && document.body) {
      var day = this.s.night === false;
      document.body.classList.toggle('day', day);
      document.documentElement.classList.toggle('isday', day);
    }
  },
  on: function (fn) { this.fns.push(fn); }
};
function call(name) {
  var a = Array.prototype.slice.call(arguments, 1);
  try { if (window.Car && Car[name]) return Car[name].apply(Car, a); } catch (e) {}
  return undefined;
}
function $(id) { return document.getElementById(id); }
function num(v, d) { return (v === undefined || v === null || isNaN(v)) ? '—' : Number(v).toFixed(d).replace('.', ','); }
function store(k, v) { try { if (v === undefined) return localStorage.getItem(k); localStorage.setItem(k, v); } catch (e) { return null; } }
function upperTR(s) { return (s || '').toLocaleUpperCase('tr-TR'); }
// A vertical thumbwheel: drag up/down, mouse wheel; calls onChange(delta) with small steps.
function thumbwheel(el, onChange, sens) {
  var y = null;
  el.addEventListener('pointerdown', function (e) { y = e.clientY; try { el.setPointerCapture(e.pointerId); } catch (x) {} });
  el.addEventListener('pointermove', function (e) {
    if (y === null) return;
    var d = (e.clientY - y) * sens;
    if (Math.abs(d) >= 1) { onChange(Math.round(d)); y = e.clientY; }
  });
  var stop = function () { y = null; };
  el.addEventListener('pointerup', stop); el.addEventListener('pointercancel', stop);
  el.addEventListener('wheel', function (e) { onChange(e.deltaY > 0 ? 5 : -5); });
}
// Eco driving score 0..100 for the current trip (idea from CAR-SOUND-CHANGER's green score, MIT):
// harsh braking / acceleration (from speed change) and high revs (real rpm from OBD / PSA CAN) cost
// points, calm driving slowly earns them back. Kept per trip in localStorage.
var eco = (function () {
  var st; try { st = JSON.parse(store('eco.trip') || ''); } catch (e) {}
  if (!st) st = { pts: 100, trip: null };
  var lastV = null, lastT = 0, n = 0;
  function feed(s) {
    var now = Date.now();
    if (s.tripKm !== undefined) {
      if (st.trip !== null && s.tripKm < st.trip - 0.5) st.pts = 100;      // a new trip started
      st.trip = s.tripKm;
    }
    var v = s.gps === 'ok' ? (s.speed || 0) : null;
    if (v !== null && lastV !== null && now - lastT < 3000) {
      var a = (v - lastV) / Math.max(0.5, (now - lastT) / 1000);           // km/h per second
      if (a < -12 && lastV > 15) st.pts -= 1.5;                             // harsh braking (> ~3.3 m/s²)
      else if (a > 10) st.pts -= 0.8;                                       // harsh acceleration
      else if (v > 5) st.pts += 0.04;                                       // calm driving earns back
    }
    var realRpm = s.cpSrc && /obd|psa|can/i.test(s.cpSrc);
    if (realRpm && s.cpRpm > 3500) st.pts -= 0.3;
    st.pts = Math.max(0, Math.min(100, st.pts));
    if (v !== null) { lastV = v; lastT = now; }
    if (++n % 10 === 0) store('eco.trip', JSON.stringify(st));
    return Math.round(st.pts);
  }
  return { feed: feed, score: function () { return Math.round(st.pts); } };
})();

// Every page uses the home theme's font: Stock = Montserrat, DS4+ = Inter + Space Grotesk, Panel 80 = Share Tech Mono.
(function () {
  try {
    var t = window.Car && Car.theme ? Car.theme() : 0;
    var css = t === 7
      ? "html body,html button,html input{font-family:'Inter',sans-serif !important}html .sg,html .num,html h1,html h2{font-family:'Space Grotesk','Inter',sans-serif !important}html svg text{font-family:'Inter',sans-serif !important}"
      : t === 8
      ? "html body,html button,html input,html .sg,html .num,html h1,html h2,html svg text{font-family:'Montserrat',sans-serif !important}"
      : t === 4
      ? "html body,html button,html input,html .sg,html .num,html h1,html h2,html svg text{font-family:'Share Tech Mono',monospace !important}"
      : t === 10
      ? "html body,html button,html input,html .sg,html .num,html svg text{font-family:'Barlow Semi Condensed',sans-serif !important}html h1,html h2{font-family:'Jost',sans-serif !important;font-weight:300 !important;letter-spacing:.08em}"
      : '';
    if (css) { var st = document.createElement('style'); st.textContent = css; (document.head || document.documentElement).appendChild(st); }
  } catch (e) {}
})();

// The owner's font choice (Setup → Font) over every theme: Roboto, Montserrat, Open Sans, Google Sans,
// Source Sans 3 (all SIL OFL, bundled, Latin + Turkish only). Empty = the theme's own fonts.
var MYDS_FONTS = { 'Roboto': 'Roboto.ttf', 'Montserrat': null, 'Open Sans': 'OpenSans.ttf', 'Google Sans': 'GoogleSans.ttf', 'Source Sans 3': 'SourceSans3.ttf' };
(function () {
  try {
    var f = window.Car && Car.uiFont ? Car.uiFont() : '';
    if (!f || !(f in MYDS_FONTS)) return;
    var face = MYDS_FONTS[f] ? "@font-face{font-family:'" + f + "';src:url(fonts/" + MYDS_FONTS[f] + ") format('truetype');font-weight:400 700;font-display:block}" : '';
    var st = document.createElement('style'); st.id = 'mydsfont';
    st.textContent = face + "html body,html button,html input,html select,html .sg,html .num,html h1,html h2,html h3,html svg text{font-family:'" + f + "',sans-serif !important}";
    (document.head || document.documentElement).appendChild(st);
    document.documentElement.setAttribute('data-font', f);
  } catch (e) {}
})();

// Stock theme: every page takes the Stock look (dark, champagne accents).
(function () {
  try {
    if (window.Car && Car.theme && Car.theme() === 8 && !document.getElementById('stockcss')) {
      document.documentElement.classList.add('stock');
      var l = document.createElement('link'); l.id = 'stockcss'; l.rel = 'stylesheet'; l.href = 'stock.css';
      (document.head || document.documentElement).appendChild(l);
    }
  } catch (e) {}
})();

// DS4+: every page takes the noir look (grained black, frosted glass, white light).
(function () {
  try {
    if (window.Car && Car.theme && (Car.theme() === 7 || Car.theme() === 10) && !document.getElementById('noircss') && !/denon\.html/.test(location.pathname)) {
      document.documentElement.classList.add('noir');
      var l = document.createElement('link'); l.id = 'noircss'; l.rel = 'stylesheet'; l.href = 'noir.css';
      (document.head || document.documentElement).appendChild(l);
    }
  } catch (e) {}
})();

// Literatür: plain high-contrast pages, and no text smaller than 24 dp (Android for Cars secondary text).
(function () {
  try {
    if (!(window.Car && Car.theme && Car.theme() === 11) || /literatur\.html/.test(location.pathname)) return;
    document.documentElement.classList.add('lit');
    var l = document.createElement('link'); l.id = 'litcss'; l.rel = 'stylesheet'; l.href = 'lit.css';
    (document.head || document.documentElement).appendChild(l);
    // the 24 dp floor is for pages used on the move; set-up pages (motor, vehicle, sound) keep their layout
    if (!/(radio|obd|minimal|split|drive)[^\/]*\.html/.test(location.pathname)) return;
    var MIN = 24;
    function grow(root) {
      var all = (root || document.body).querySelectorAll('*');
      for (var i = 0; i < all.length; i++) {
        var e = all[i], t = false;
        for (var c = e.firstChild; c; c = c.nextSibling) if (c.nodeType === 3 && c.nodeValue.trim()) { t = true; break; }
        if (!t || e.tagName === 'text' || e.closest && e.closest('svg')) continue;
        var f = parseFloat(getComputedStyle(e).fontSize);
        if (f && f < MIN) e.style.fontSize = MIN + 'px';
      }
    }
    var run = function () { grow(); };
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', function () { setTimeout(run, 50); });
    else setTimeout(run, 50);
    // lists filled later (radio stations, OBD values…)
    var pend = null;
    new MutationObserver(function () { clearTimeout(pend); pend = setTimeout(run, 120); }).observe(document.documentElement, { childList: true, subtree: true });
  } catch (e) {}
})();

// Panel 80: the display colour the driver picked (VFD cyan, amber, phosphor green, ...), on every page.
var PANEL_COLORS = [['#56F2FF', 'Camgöbeği'], ['#FFB347', 'Amber'], ['#7CFF6B', 'Fosfor yeşili'], ['#FF4FD8', 'Magenta'], ['#FF4A3D', 'Kırmızı'], ['#E8F1FF', 'Buz beyazı']];
function panelLook(hex) {
  try {
    if (hex) store('panel.hue', hex);
    hex = store('panel.hue') || '#56F2FF';
    var n = parseInt(hex.slice(1), 16), r = n >> 16 & 255, g = n >> 8 & 255, b = n & 255;
    var d = function (v) { return Math.round(v * .5); };
    var s = document.documentElement.style;
    s.setProperty('--pv', hex);
    s.setProperty('--pvr', r + ',' + g + ',' + b);
    s.setProperty('--pv2', 'rgb(' + d(r) + ',' + d(g) + ',' + d(b) + ')');
    s.setProperty('--vfd', hex);
    s.setProperty('--vfd2', 'rgb(' + d(r) + ',' + d(g) + ',' + d(b) + ')');
    s.setProperty('--off', 'rgba(' + r + ',' + g + ',' + b + ',.09)');
    s.setProperty('--edge', 'rgba(' + r + ',' + g + ',' + b + ',.22)');
  } catch (e) {}
  return hex;
}
try { if (window.Car && Car.theme && Car.theme() === 4) panelLook(); } catch (e) {}

// Car photos instead of live 3D: studio shots from 8 angles (every 45°, 0 = nose towards the viewer's
// left-front, 90 = left side) plus a top view, in the six paint colours of the vehicle page.
// Two stacked <img> layers crossfade, so changing the angle costs one image decode, not a GPU loop.
// carPhoto(holder, {cls, color}) → { show(az|'top'), az, nose() }
var CAR_PHOTO_COLORS = 1;   // white only (the owner's car)
function carPhoto(holder, o) {
  o = o || {};
  var col = o.color != null ? o.color : (parseInt(store('myds.color') || '0', 10) || 0);
  if (!(col >= 0 && col < CAR_PHOTO_COLORS)) col = 0;
  var a = document.createElement('img'), b = document.createElement('img');
  [a, b].forEach(function (im) {
    im.alt = ''; im.draggable = false; if (o.cls) im.className = o.cls;
    im.style.cssText += ';transition:opacity .45s ease;pointer-events:none;-webkit-user-drag:none';
    im.style.opacity = '0'; holder.appendChild(im);
  });
  var front = a, cur = null, api = { az: 45 };
  // ¾ front and ¾ rear are real photographs (Wikimedia Commons, Vauxford, CC BY-SA 4.0); the rest are renders
  var REAL = { 45: 'real_front', 135: 'real_rear' };
  function src(k) { return '3d/foto/' + (REAL[k] ? REAL[k] : 'c' + col + '_' + k) + '.webp'; }
  // warm the cache with the neighbours so a swipe never waits
  function warm(k) { if (k === 'top' || o.realOnly) { if (o.realOnly) [45, 135].forEach(function (d) { var im = new Image(); im.src = src(d); }); return; } [45, -45].forEach(function (d) { var im = new Image(); im.src = src((k + d + 360) % 360); }); }
  api.show = function (az, instant) {
    var k = az === 'top' ? 'top' : ((Math.round(az / 45) * 45) % 360 + 360) % 360;
    // home screens: only the two real photos (front ¾ / rear ¾), so the look never jumps to a render
    if (o.realOnly && k !== 'top') k = (k >= 90 && k < 270) ? 135 : 45;
    if (az !== 'top') api.az = az;
    if (k === cur) return k;
    cur = k;
    var back = front === a ? b : a, url = src(k);
    back.onload = function () {
      back.style.transition = instant ? 'none' : 'opacity .45s ease';
      front.style.transition = instant ? 'none' : 'opacity .45s ease';
      back.style.opacity = '1'; front.style.opacity = '0'; front = back;
      warm(k);
    };
    back.src = url;
    return k;
  };
  api.color = function (c) { if (c >= 0 && c < CAR_PHOTO_COLORS && c !== col) { col = c; var k = cur; cur = null; api.show(k === 'top' ? 'top' : api.az, true); } };
  // where the nose points on screen for the current angle: +1 right … −1 left
  api.nose = function () { return REAL[cur] ? -0.75 : Math.sin(((Math.round(api.az / 45) * 45)) * Math.PI / 180); };
  return api;
}

// ---- One icon set for every theme: plain pictograms that say what the button does ----------------
// Stroke icons on a 24×24 grid (stroke = currentColor); themes pick size and colour.
var MYDS_ICONS = (function () {
  // gear with 8 square teeth, generated so it stays crisp at any size
  var g = '', n = 8, R1 = 9.2, R2 = 7.2;
  for (var i = 0; i < n * 4; i++) {
    var a = (i / (n * 4)) * Math.PI * 2 - Math.PI / 2 - Math.PI / (n * 4), r = (i % 4 < 2) ? R1 : R2;
    g += (i ? 'L' : 'M') + (12 + r * Math.cos(a)).toFixed(2) + ' ' + (12 + r * Math.sin(a)).toFixed(2);
  }
  return {
    nav: '<path d="M20.5 3.5 3.5 10.6l7 2.9 2.9 7z"/>',
    map: '<path d="M9 4 3.5 6v14L9 18l6 2 5.5-2V4L15 6z"/><path d="M9 4v14M15 6v14"/>',
    music: '<path d="M9 18V5.5l11-2.2v12.5"/><circle cx="6.5" cy="18" r="2.5"/><circle cx="17.5" cy="15.8" r="2.5"/>',
    radio: '<rect x="2.5" y="8.5" width="19" height="12" rx="2"/><path d="M7 8.5 17.5 3.5"/><circle cx="8" cy="14.5" r="3"/><path d="M14 12.5h4.5M14 15h4.5M14 17.5h4.5"/>',
    aa: '<rect x="2.5" y="5.5" width="6.5" height="12" rx="1.5"/><path d="M5 15h1.5"/><rect x="12.5" y="6.5" width="9" height="8" rx="1.4"/><path d="M15 18.5h4M17 14.5v4"/><path d="M9.8 10.5h1.8M10.8 9.5l1 1-1 1"/>',
    phone: '<path d="M5 3h3.5l1.8 4.5-2.3 1.4a11 11 0 0 0 7.1 7.1l1.4-2.3L21 15.5V19a2 2 0 0 1-2 2A16 16 0 0 1 3 5a2 2 0 0 1 2-2z"/>',
    apps: '<rect x="4" y="4" width="6.5" height="6.5" rx="1.5"/><rect x="13.5" y="4" width="6.5" height="6.5" rx="1.5"/><rect x="4" y="13.5" width="6.5" height="6.5" rx="1.5"/><rect x="13.5" y="13.5" width="6.5" height="6.5" rx="1.5"/>',
    eq: '<path d="M5 21v-7M5 10V3M12 21v-9M12 8V3M19 21v-5M19 12V3"/><path d="M2.5 14h5M9.5 8h5M16.5 16h5"/>',
    gauge: '<path d="M4.2 18a9 9 0 1 1 15.6 0"/><path d="M12 13.5 16 9"/><circle cx="12" cy="13.5" r="1.4"/><path d="M8 18h8"/>',
    engine: '<path d="M4 10h2V7.5h4V6h4v1.5h3l2 2.5h2v6h-2l-2 2.5H8L6 16H4z"/><path d="M1.5 11v4"/>',
    car: '<path d="M2.5 16.5v-3.4l2.6-.9 2.8-4.2h7.3l3.6 4.2 2.7.7v3.6h-1.6"/><path d="M9.1 16.5h5.8M4.9 16.5H2.5"/><circle cx="7" cy="16.8" r="2"/><circle cx="17" cy="16.8" r="2"/><path d="M8.8 12.2h9.4"/>',
    settings: '<path d="' + g + 'Z"/><circle cx="12" cy="12" r="3"/>',
    home: '<path d="M3 11.5 12 4l9 7.5"/><path d="M5.5 10v10h13V10"/><path d="M10 20v-5h4v5"/>',
    night: '<path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z"/>',
    screen: '<rect x="3" y="4" width="18" height="12" rx="2"/><path d="M8 20h8M12 16v4"/>',
    split: '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M12 4v16"/>'
  };
})();
// <svg> markup for an icon; cls defaults to the common "ic" stroke style
function icon(name, cls) {
  return '<svg class="' + (cls || 'ic') + '" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">' + (MYDS_ICONS[name] || '') + '</svg>';
}

// ---- Car warnings (CarAlerts.java): one strip at the top of every page ------------------------
// Level 2 (act now) is red, level 1 amber. Several warnings take turns every 6 s; a tap hides the one
// shown for 20 minutes. No animation: it appears and stays until the car says it is over.
(function () {
  var hidden = {}, at = 0, list = [], el = null, turn = null;
  try { hidden = JSON.parse(store('alerts.hidden') || '{}') || {}; } catch (e) { hidden = {}; }
  function visible() { var now = Date.now(); return list.filter(function (a) { return !(hidden[a.k] > now); }); }
  function draw() {
    var v = visible();
    if (!v.length) { if (el) el.style.display = 'none'; return; }
    if (!el) {
      el = document.createElement('div'); el.id = 'mydsAlert';
      el.style.cssText = 'position:fixed;left:50%;top:1.4vh;transform:translateX(-50%);z-index:99999;max-width:86vw;' +
        'display:flex;align-items:center;gap:1.4vh;padding:1.2vh 2.6vh 1.2vh 1.8vh;border-radius:4vh;font:600 2.6vh/1.2 Inter,system-ui,sans-serif;' +
        'color:#fff;box-shadow:0 1vh 4vh rgba(0,0,0,.45);cursor:pointer;white-space:nowrap;overflow:hidden;text-overflow:ellipsis';
      el.onclick = function () { var v2 = visible(); var a = v2[at % v2.length]; if (a) { hidden[a.k] = Date.now() + 20 * 60000; store('alerts.hidden', JSON.stringify(hidden)); } draw(); };
      document.body.appendChild(el);
    }
    var a = v[at % v.length];
    el.style.display = 'flex';
    el.style.background = a.lvl >= 2 ? '#C62828' : '#B26A00';
    el.innerHTML = '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" style="flex-shrink:0;width:3.2vh;height:3.2vh"><path d="M12 3 2 20h20z"/><path d="M12 10v4M12 17h.01"/></svg><span></span>' +
      (v.length > 1 ? '<span style="opacity:.75;font-weight:500;margin-left:.6vh">' + ((at % v.length) + 1) + '/' + v.length + '</span>' : '');
    el.children[1].textContent = a.t;
  }
  if (window.car && car.on) car.on(function (s) {
    var n = s && s.alerts ? s.alerts : [];
    var sig = n.map(function (a) { return a.k; }).join(',');
    var old = list.map(function (a) { return a.k; }).join(',');
    list = n;
    if (sig !== old) { at = 0; draw(); }
    else if (el && list.length) { var v = visible(); if (v.length) el.children[1].textContent = v[at % v.length].t; }
    if (!turn) turn = setInterval(function () { if (visible().length > 1) { at++; draw(); } }, 6000);
  });
})();

// ---- Home-screen picture of the car ----------------------------------------------------------------
// 'front' = the real ¾ front photo; 'own' = the owner's own photo (from the USB stick: arac.jpg, or picked
// in the gallery; kept only in the app's files). myds.carbg: 'cut' = background removed, 'keep' = as taken.
function homeCarSrc() {
  if (store('myds.carimg') === 'own') {
    var keep = store('myds.carbg') === 'keep', d = '';
    try { d = (keep ? call('carPhotoRaw') : call('carPhotoData')) || call('carPhotoData') || call('carPhotoRaw') || ''; } catch (e) {}
    if (d) return { src: d, mode: keep ? 'keep' : 'own' };
  }
  return { src: '3d/foto/real_front.webp', mode: 'front' };
}
// A picture that just came from the stick: cut its background once, then show it. done() when ready.
function ownCarPending(done) {
  if (!call('carPhotoPending')) return false;
  carCutout(call('carPhotoRaw'), function (url) {
    if (url) call('carPhotoSave', url);
    store('myds.carimg', 'own');
    if (done) done();
  });
  return true;
}
// Cuts a plain-ish background away: flood fill from the picture's edges, soft edge, cropped to the car.
// cb(dataUrl, share of the picture that stayed) — share near 0 or 1 means the cut didn't work.
function carCutout(srcUrl, cb) {
  var im = new Image();
  im.onload = function () {
    var w = im.naturalWidth, h = im.naturalHeight, cv = document.createElement('canvas');
    cv.width = w; cv.height = h;
    var g = cv.getContext('2d'); g.drawImage(im, 0, 0);
    var id = g.getImageData(0, 0, w, h), p = id.data, n = w * h;
    // background colour = mean of the outer frame
    var r = 0, gg = 0, b = 0, c = 0, i, x, y;
    function acc(q) { r += p[q * 4]; gg += p[q * 4 + 1]; b += p[q * 4 + 2]; c++; }
    for (x = 0; x < w; x++) { acc(x); acc((h - 1) * w + x); }
    for (y = 0; y < h; y++) { acc(y * w); acc(y * w + w - 1); }
    r /= c; gg /= c; b /= c;
    function d2(q, R, G, B) { var a = p[q * 4] - R, e = p[q * 4 + 1] - G, f = p[q * 4 + 2] - B; return a * a + e * e + f * f; }
    var bg = new Uint8Array(n), st = new Int32Array(n), sp = 0;
    var FAR = 70 * 70, STEP = 22 * 22, NEAR = 34 * 34;
    function seed(q) { if (!bg[q] && d2(q, r, gg, b) < FAR) { bg[q] = 1; st[sp++] = q; } }
    for (x = 0; x < w; x++) { seed(x); seed((h - 1) * w + x); }
    for (y = 0; y < h; y++) { seed(y * w); seed(y * w + w - 1); }
    while (sp) {
      var q = st[--sp], qx = q % w, R = p[q * 4], G = p[q * 4 + 1], B = p[q * 4 + 2];
      var nb = [qx > 0 ? q - 1 : -1, qx < w - 1 ? q + 1 : -1, q - w, q + w];
      for (var k = 0; k < 4; k++) {
        var t = nb[k]; if (t < 0 || t >= n || bg[t]) continue;
        var dm = d2(t, r, gg, b);
        if (dm < NEAR || (d2(t, R, G, B) < STEP && dm < FAR)) { bg[t] = 1; st[sp++] = t; }
      }
    }
    // soft edge: alpha from the share of background around each pixel; crop box
    var kept = 0, x0 = w, y0 = h, x1 = 0, y1 = 0;
    for (y = 0; y < h; y++) for (x = 0; x < w; x++) {
      i = y * w + x;
      if (bg[i]) { p[i * 4 + 3] = 0; continue; }
      var s = 0, m = 0;
      for (var dy = -1; dy <= 1; dy++) for (var dx = -1; dx <= 1; dx++) {
        var xx = x + dx, yy = y + dy; if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue; m++; s += bg[yy * w + xx];
      }
      p[i * 4 + 3] = Math.round(255 * (1 - s / m * 0.6));
      kept++; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y;
    }
    var share = kept / n;
    if (share < 0.04 || share > 0.97) { cb(srcUrl, share); return; }   // nothing sensible to cut: keep the photo
    g.putImageData(id, 0, 0);
    var o = document.createElement('canvas'), pad = 8;
    o.width = x1 - x0 + 1 + pad * 2; o.height = y1 - y0 + 1 + pad * 2;
    o.getContext('2d').drawImage(cv, x0, y0, x1 - x0 + 1, y1 - y0 + 1, pad, pad, x1 - x0 + 1, y1 - y0 + 1);
    cb(o.toDataURL('image/png'), share);
  };
  im.onerror = function () { cb('', 0); };
  im.src = srcUrl;
}

// ---- Car photo window, from any page: the real ¾ front photo, or the owner's own photo
// (USB stick: arac.jpg in the top folder or MYDS, or the gallery; background removed or kept as taken).
function carPhotoPicker(onDone) {
  var old = document.getElementById('cpPick'); if (old) old.remove();
  var M = document.createElement('div'); M.id = 'cpPick';
  M.style.cssText = 'position:fixed;inset:0;z-index:9000;background:rgba(0,0,0,.66);display:flex;align-items:center;justify-content:center;font-family:Inter,sans-serif';
  M.innerHTML = '<div class="cpIn" style="width:min(94vw,150vh);background:#17191d;color:#fff;border-radius:3vh;padding:3vh 3.4vh;display:flex;flex-direction:column;gap:2.2vh;box-shadow:0 2vh 6vh rgba(0,0,0,.6)">' +
    '<div style="display:flex;align-items:center;gap:1.6vh"><b style="font-size:3.4vh;flex:1">Ana ekrandaki araç fotoğrafı</b><button id="cpX" style="width:7vh;height:7vh;border-radius:50%;border:none;background:rgba(255,255,255,.1);color:#fff;font-size:3.4vh">✕</button></div>' +
    '<div id="cpOpts" style="display:grid;grid-template-columns:1fr 1fr;gap:2vh"></div>' +
    '<div id="cpMsg" style="font-size:2.2vh;opacity:.75;line-height:1.45">USB belleğe <b>arac.jpg</b> adıyla koy (ana klasör ya da MYDS klasörü), takınca kendisi gelir. Ya da galeriden seç. Fotoğraf sadece bu cihazda kalır. En iyi ölçü 1280 × 570.</div></div>';
  document.body.appendChild(M);
  function msg(t) { M.querySelector('#cpMsg').textContent = t; }
  function draw() {
    var box = M.querySelector('#cpOpts'), cur = homeCarSrc().mode === 'front' ? 'front' : 'own', keep = store('myds.carbg') === 'keep', own = '';
    try { own = (keep ? call('carPhotoRaw') : call('carPhotoData')) || call('carPhotoData') || ''; } catch (e) {}
    box.innerHTML = '';
    [['front', 'Ön ¾ fotoğraf', '3d/foto/real_front.webp'], ['own', own ? 'Kendi fotoğrafım · değiştir' : 'Kendi fotoğrafım · seç', own]].forEach(function (o) {
      var b = document.createElement('button'), on = cur === o[0];
      b.style.cssText = 'display:flex;flex-direction:column;gap:1.4vh;padding:1.6vh;border-radius:2.2vh;border:.4vh solid ' + (on ? '#fff' : 'transparent') + ';background:rgba(255,255,255,.06);color:#fff;font-size:2.5vh;font-family:inherit';
      b.innerHTML = '<div style="height:24vh;display:flex;align-items:center;justify-content:center;background:rgba(0,0,0,.3);border-radius:1.4vh;overflow:hidden">' +
        (o[2] ? '<img src="' + o[2] + '" style="max-width:100%;max-height:100%;object-fit:contain">' : '<span style="font-size:7vh;opacity:.5">+</span>') + '</div><span>' + o[1] + '</span>';
      b.onclick = function () {
        if (o[0] === 'own' && (!own || on)) {
          if ((car.s && car.s.speed || 0) > 5) { msg('Araç dururken seçebilirsin.'); return; }
          msg('Galeri açılıyor…'); call('carPhotoPick'); return;
        }
        store('myds.carimg', o[0]); draw(); msg('Tamam.'); if (onDone) onDone();
      };
      box.appendChild(b);
    });
    if (own) {
      [['Arka plan: ' + (keep ? 'olduğu gibi' : 'silindi'), function () { store('myds.carbg', keep ? 'cut' : 'keep'); draw(); if (onDone) onDone(); }],
       ['Fotoğrafımı sil', function () { call('carPhotoClear'); if (store('myds.carimg') === 'own') store('myds.carimg', 'front'); draw(); if (onDone) onDone(); }]].forEach(function (x) {
        var b = document.createElement('button'); b.textContent = x[0];
        b.style.cssText = 'height:7vh;border-radius:1.6vh;border:none;background:rgba(255,255,255,.1);color:#fff;font-size:2.3vh;font-family:inherit';
        b.onclick = x[1]; box.appendChild(b);
      });
    }
  }
  window.onCarPicked = function (ok) {
    if (!ok) { msg('Bu resim açılamadı.'); return; }
    msg('Arka plan siliniyor…');
    carCutout(call('carPhotoRaw'), function (url, share) {
      if (!url || !call('carPhotoSave', url)) { msg('Kaydedilemedi.'); return; }
      store('myds.carimg', 'own'); draw(); if (onDone) onDone();
      msg(share > 0.97 || share < 0.04 ? 'Arka plan ayrılamadı, fotoğraf olduğu gibi kullanılıyor.' : 'Hazır.');
    });
  };
  M.querySelector('#cpX').onclick = function () { M.remove(); };
  M.onclick = function (e) { if (e.target === M) M.remove(); };
  draw();
  if (ownCarPending(function () { draw(); if (onDone) onDone(); })) msg('USB\'deki fotoğraf hazırlanıyor…');
}

// ---- motion: cards and keys rise in one after another when a page opens (once), and numbers that
// change on a page give a small tick. Cheap: classes only, the CSS does the rest.
(function () {
  if (store('myds.motion') === 'off') { document.documentElement.classList.add('nomotion'); return; }
  function enter() {
    var sel = '.c,.card,.box,.pan,.tab,.dock,.db,#dock button,.dock button,.st,#mp,#np,#carBox,.row.card';   // never things placed with a transform (Stock's slanted keys, centred pop-ups)
    var els = [].slice.call(document.querySelectorAll(sel)).filter(function (e) { return e.offsetParent !== null; }).slice(0, 28);
    els.forEach(function (e, i) {
      e.style.animationDelay = (i * 35) + 'ms';
      e.classList.add('mydsIn');
      e.addEventListener('animationend', function f() { e.classList.remove('mydsIn'); e.style.animationDelay = ''; e.removeEventListener('animationend', f); });
    });
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', function () { setTimeout(enter, 30); });
  else setTimeout(enter, 30);
  // numbers: when the text of a big read-out changes, it ticks in (only short numeric texts, a few at a time)
  var last = new WeakMap(), sel2 = 'b,.n,.num,#spd,#rng,#cons,#avg,#trp,#inst,#range,#liters,#temp,#clock,.tv b';
  setInterval(function () {
    var n = 0;
    [].forEach.call(document.querySelectorAll(sel2), function (e) {
      if (n > 6 || e.offsetParent === null) return;
      var t = e.textContent; if (t.length > 12 || !/\d/.test(t)) return;
      var p = last.get(e); last.set(e, t);
      if (p !== undefined && p !== t) { n++; e.classList.remove('mydsTick'); void e.offsetWidth; e.classList.add('mydsTick'); }
    });
  }, 500);
})();
