// MyDS — the 3D DS4 in any box of any theme (DS4+, Literatür, Panel 80…).
// mountDS4(el, opts): a canvas fills `el`, the car turns slowly; drag to turn it.
// opts: az (start angle, deg), spin (deg/s), tint (glass colour), wire (Panel 80: glowing outline look),
//       color (body hex), zoom (1 = fits the box), lift (camera height), onFail(why).
(function () {
  var loading = null, waiters = [];
  function need(done) {
    if (window.THREE && THREE.GLTFLoader && THREE.RoomEnvironment && window.DS4_GLB) return done();
    waiters.push(done);
    if (loading) return;
    loading = true;
    var list = [];
    if (!window.THREE) list.push('3d/three.min.js');
    if (!window.THREE || !THREE.GLTFLoader) list.push('3d/GLTFLoader.js');
    if (!window.THREE || !THREE.RoomEnvironment) list.push('3d/RoomEnvironment.js');
    if (!window.DS4_GLB) list.push('3d/ds4model.js');
    (function next() {
      if (!list.length) { var w = waiters; waiters = []; w.forEach(function (f) { f(); }); return; }
      var s = document.createElement('script'); s.src = list.shift(); s.onload = next;
      s.onerror = function () { var w = waiters; waiters = []; w.forEach(function (f) { f('nofile'); }); };
      document.head.appendChild(s);
    })();
  }
  function b64(s) { var b = atob(s), n = b.length, u = new Uint8Array(n); for (var i = 0; i < n; i++) u[i] = b.charCodeAt(i); return u.buffer; }

  window.mountDS4 = function (el, opts) {
    opts = opts || {};
    var fail = function (why) { if (opts.onFail) opts.onFail(why); };
    try { var t = document.createElement('canvas'); if (!(t.getContext('webgl') || t.getContext('experimental-webgl'))) return fail('nogl'), null; } catch (e) { return fail('nogl'), null; }
    var cv = document.createElement('canvas');
    cv.style.cssText = 'position:absolute;left:0;top:0;width:100%;height:100%;touch-action:none;opacity:0;transition:opacity .8s';
    el.appendChild(cv);
    var api = { canvas: cv, stop: function () { stopped = true; } }, stopped = false;
    need(function (err) {
      if (err) return fail(err);
      try { start(); } catch (e) { cv.remove(); fail('error'); }
    });
    function start() {
      var R = new THREE.WebGLRenderer({ canvas: cv, antialias: true, alpha: true });
      R.setPixelRatio(typeof sharpRatio === 'function' ? sharpRatio() : Math.min(window.devicePixelRatio || 1, 1.5));
      R.outputEncoding = THREE.sRGBEncoding; R.toneMapping = THREE.ACESFilmicToneMapping; R.toneMappingExposure = opts.wire ? 1.0 : 1.08;
      R.setClearColor(0x000000, 0);
      var S = new THREE.Scene();
      var pm = new THREE.PMREMGenerator(R); S.environment = pm.fromScene(new THREE.RoomEnvironment(), 0.04).texture;
      S.add(new THREE.HemisphereLight(0xfff6e8, 0x1a1a1a, 0.55));
      var key = new THREE.DirectionalLight(0xffffff, 1.3); key.position.set(3, 6, 4); S.add(key);
      var rim = new THREE.DirectionalLight(0xdfe8ff, 0.6); rim.position.set(-5, 3, -4); S.add(rim);
      var cam = new THREE.PerspectiveCamera(26, 1, 0.05, 100);
      var model, ctr = new THREE.Vector3(), rad = 2.4;
      new THREE.GLTFLoader().parse(b64(window.DS4_GLB), '', function (g) {
        model = g.scene;
        var accent = opts.wireColor || 0x6ff2ff;
        model.traverse(function (o) {
          if (!o.isMesh) return;
          var ms = Array.isArray(o.material) ? o.material : [o.material];
          ms.forEach(function (m, k) {
            var nm = m.name || '', put = function (n) { if (Array.isArray(o.material)) o.material[k] = n; else o.material = n; };
            if (opts.wire) {
              // Panel 80: a dark glass body with glowing edges, like the VFD
              put(new THREE.MeshBasicMaterial({ color: 0x031416, transparent: true, opacity: .55, depthWrite: true }));
              var e = new THREE.LineSegments(new THREE.EdgesGeometry(o.geometry, 28), new THREE.LineBasicMaterial({ color: accent, transparent: true, opacity: .85 }));
              o.add(e);
              return;
            }
            if (nm === 'body') put(new THREE.MeshStandardMaterial({ color: opts.color || +(store('myds.colorHex') || 0xf2f1ee), metalness: .35, roughness: .28, envMapIntensity: 1.1, side: THREE.DoubleSide }));
            else if (m.transparent || /lights_lod0 \[env|window/.test(nm)) {
              if (/lights_lod0 \[env|window/.test(nm)) { m.color && m.color.setHex(opts.tint || 0x0b0f14); m.opacity = .78; m.roughness = .05; m.metalness = .2; m.envMapIntensity = .8; }
              m.transparent = true; m.depthWrite = false; o.renderOrder = 2;
            } else m.side = THREE.DoubleSide;   // no see-through gaps where panels are single-sided
          });
        });
        var box = new THREE.Box3().setFromObject(model); box.getCenter(ctr); rad = box.getSize(new THREE.Vector3()).length() / 2;
        S.add(model);
        cv.style.opacity = 1; el.__ds4 = true;
      });
      var az = (opts.az != null ? opts.az : 35) * Math.PI / 180, azT = az, spin = (opts.spin != null ? opts.spin : 4) * Math.PI / 180;
      var drag = null, idle = 0;
      cv.addEventListener('pointerdown', function (e) { drag = { x: e.clientX, a: azT }; try { cv.setPointerCapture(e.pointerId); } catch (x) {} });
      cv.addEventListener('pointermove', function (e) { if (drag) { azT = drag.a - (e.clientX - drag.x) / 140; idle = performance.now(); } });
      var up = function () { drag = null; idle = performance.now(); };
      cv.addEventListener('pointerup', up); cv.addEventListener('pointercancel', up);
      var last = 0, W = 0, H = 0;
      function frame(now) {
        if (stopped) return;
        requestAnimationFrame(frame);
        if (document.hidden || now - last < 33) return;   // 30 fps is plenty for a slow turn
        var dt = Math.min(.1, (now - (last || now)) / 1000); last = now;
        var w = el.clientWidth, h = el.clientHeight;
        if (!w || !h) return;
        if (w !== W || h !== H) { W = w; H = h; R.setSize(w, h, false); cam.aspect = w / h; cam.updateProjectionMatrix(); }
        if (!model) return;
        if (!drag && now - idle > 4000) azT += spin * dt;
        az += (azT - az) * Math.min(1, dt * 6);
        var th = Math.tan(cam.fov * Math.PI / 360), fit = Math.max(rad * .92 / (th * cam.aspect), rad * .62 / th) / (opts.zoom || 1);
        var lift = opts.lift != null ? opts.lift : .32;
        cam.position.set(ctr.x + Math.sin(az) * fit, ctr.y + fit * lift, ctr.z + Math.cos(az) * fit);
        cam.lookAt(ctr.x, ctr.y - rad * .06, ctr.z);
        R.render(S, cam);
      }
      requestAnimationFrame(frame);
    }
    return api;
  };
})();
