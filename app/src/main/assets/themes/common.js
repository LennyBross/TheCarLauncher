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
