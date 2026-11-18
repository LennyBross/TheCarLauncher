// Bridge to the launcher: the app calls car.update(state) once a second; the page calls Car.*.
window.car = {
  s: {},
  fns: [],
  update: function (st) { this.s = st || {}; for (var i = 0; i < this.fns.length; i++) this.fns[i](this.s); },
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
