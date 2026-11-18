package com.catarapatara;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.catarapatara.engine.SoundPacks;

import org.json.JSONObject;

/** The CataraPatara screen: engine list, sound packs, data source, floating button. */
public final class MainActivity extends Activity {
    private static final int REQ_PACK = 11, REQ_PERMS = 12;
    private WebView web;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        web.setBackgroundColor(0xFF0B0E13);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "CP");
        SoundPacks.syncAssets(this);
        new Thread(new Runnable() {
            public void run() {
                if (SoundPacks.syncGitHub(MainActivity.this) > 0) runOnUiThread(new Runnable() { public void run() { web.reload(); } });
            }
        }, "packs-sync").start();
        web.loadUrl("file:///android_asset/ui.html");
        setContentView(web);
        askPermissions();
        EngineService.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= 19) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
        Bubble.refresh(this);
        if (web != null) web.evaluateJavascript("window.onResumeApp&&onResumeApp()", null);
    }

    private void askPermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        java.util.List<String> want = new java.util.ArrayList<>();
        want.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33) want.add(Manifest.permission.POST_NOTIFICATIONS);
        if (Build.VERSION.SDK_INT >= 31) want.add(Manifest.permission.BLUETOOTH_CONNECT);
        java.util.List<String> ask = new java.util.ArrayList<>();
        for (String p : want) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) ask.add(p);
        if (!ask.isEmpty()) requestPermissions(ask.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        // GPS needs to be (re)started with the new permission
        EngineService s = EngineService.get();
        if (s != null && s.playing()) { EngineService.toggle(this); EngineService.toggle(this); }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PACK && res == RESULT_OK && data != null && data.getData() != null) {
            final Uri uri = data.getData();
            Toast.makeText(this, "Ses paketi yükleniyor…", Toast.LENGTH_SHORT).show();
            new Thread(new Runnable() {
                public void run() {
                    final String name = SoundPacks.importZip(MainActivity.this, uri);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            Toast.makeText(MainActivity.this, name != null ? "Yüklendi: " + name : "Bu dosya bir ses paketi değil", Toast.LENGTH_LONG).show();
                            web.reload();
                        }
                    });
                }
            }, "import").start();
        }
    }

    private final class Bridge {
        @JavascriptInterface
        public String config() {
            SharedPreferences p = EngineService.prefs(MainActivity.this);
            EngineService s = EngineService.get();
            try {
                return new JSONObject().put("on", s != null && s.playing()).put("id", p.getString("id", EngineService.DEFAULT_ID)).put("ex", p.getInt("ex", 1))
                        .put("vol", p.getInt("vol", 70)).put("bass", p.getInt("bass", 3)).put("pops", p.getBoolean("pops", true))
                        .put("bubble", p.getBoolean("bubble", true)).put("canDraw", Bubble.canDraw(MainActivity.this))
                        .put("obd", p.getString("obd", "off")).put("obdTarget", p.getString("obdTarget", ""))
                        .put("carMax", p.getInt("carMax", 4500)).put("out", p.getString("out", "focus")).put("boot", p.getBoolean("boot", true)).toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public String profiles() { return EngineService.profiles(MainActivity.this); }

        @JavascriptInterface
        public String live() {
            EngineService s = EngineService.get();
            return s == null ? "{}" : s.live();
        }

        @JavascriptInterface
        public void set(final String key, final String value) {
            SharedPreferences.Editor e = EngineService.prefs(MainActivity.this).edit();
            switch (key) {
                case "id": case "obd": case "obdTarget": case "out": e.putString(key, value); break;
                case "ex": case "vol": case "bass": case "carMax": e.putInt(key, Integer.parseInt(value)); break;
                case "pops": case "bubble": case "boot": e.putBoolean(key, "1".equals(value)); break;
                default: return;
            }
            e.apply();
            EngineService s = EngineService.get();
            if (s != null) s.settingsChanged(key);
            if ("bubble".equals(key)) runOnUiThread(new Runnable() { public void run() { Bubble.refresh(MainActivity.this); } });
        }

        @JavascriptInterface
        public void power(boolean on) {
            SharedPreferences p = EngineService.prefs(MainActivity.this);
            if (p.getBoolean("on", false) != on) EngineService.toggle(MainActivity.this);
        }

        @JavascriptInterface
        public void rev(boolean on) {
            EngineService s = EngineService.get();
            if (s != null) s.rev(on);
        }

        @JavascriptInterface
        public void importPack() {
            runOnUiThread(new Runnable() {
                public void run() {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                    try {
                        startActivityForResult(i, REQ_PACK);
                    } catch (Exception e) {
                        try {
                            startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_PACK);
                        } catch (Exception e2) {
                            Toast.makeText(MainActivity.this, "Dosya seçici yok", Toast.LENGTH_LONG).show();
                        }
                    }
                }
            });
        }

        @JavascriptInterface
        public void deletePack(String id) {
            SoundPacks.delete(MainActivity.this, id);
            SharedPreferences p = EngineService.prefs(MainActivity.this);
            if (id.equals(p.getString("id", ""))) set("id", "v8us");
        }

        @JavascriptInterface
        public void allowOverlay() {
            if (Build.VERSION.SDK_INT >= 23) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Ayarlar → Uygulamalar → CataraPatara → Diğer uygulamaların üzerinde göster", Toast.LENGTH_LONG).show();
                }
            }
        }

        /** Sound test: switch on and rev for a moment. */
        @JavascriptInterface
        public void test() {
            SharedPreferences p = EngineService.prefs(MainActivity.this);
            if (!p.getBoolean("on", false)) EngineService.toggle(MainActivity.this);
            web.postDelayed(new Runnable() { public void run() { EngineService s = EngineService.get(); if (s != null) s.rev(true); } }, 2600);
            web.postDelayed(new Runnable() { public void run() { EngineService s = EngineService.get(); if (s != null) s.rev(false); } }, 4200);
        }

        @JavascriptInterface
        public void close() { runOnUiThread(new Runnable() { public void run() { moveTaskToBack(true); } }); }
    }
}
