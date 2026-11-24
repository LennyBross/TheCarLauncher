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
  function src(k) { return '3d/foto/c' + col + '_' + k + '.webp'; }
  // warm the cache with the neighbours so a swipe never waits
  function warm(k) { if (k === 'top') return; [45, -45].forEach(function (d) { var im = new Image(); im.src = src((k + d + 360) % 360); }); }
  api.show = function (az, instant) {
    var k = az === 'top' ? 'top' : ((Math.round(az / 45) * 45) % 360 + 360) % 360;
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
  api.nose = function () { return Math.sin(((Math.round(api.az / 45) * 45)) * Math.PI / 180); };
  return api;
}
