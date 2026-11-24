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
