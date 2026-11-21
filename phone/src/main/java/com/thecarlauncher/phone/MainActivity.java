package com.thecarlauncher.phone;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** The phone screen: link status, set-up steps, and a remote for MYDS in the unit's own theme. */
public final class MainActivity extends Activity {
    private WebView web;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "P");
        web.loadUrl("file:///android_asset/phone.html");
        setContentView(web);
        askPermissions();
        if (!LinkService.prefs(this).getString("unit", "").isEmpty()) LinkService.start(this);
    }

    private void askPermissions() {
        List<String> want = new ArrayList<>();
        want.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 31) want.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT >= 33) want.add(Manifest.permission.POST_NOTIFICATIONS);
        List<String> miss = new ArrayList<>();
        for (String p : want) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) miss.add(p);
        if (!miss.isEmpty()) requestPermissions(miss.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        if (!LinkService.prefs(this).getString("unit", "").isEmpty()) {
            stopService(new Intent(this, LinkService.class));
            LinkService.start(this);
        }
    }

    private class Bridge {
        @JavascriptInterface
        public String state() {
            try {
                JSONObject o = new JSONObject();
                o.put("connected", LinkService.connected()).put("status", LinkService.status).put("via", LinkService.via);
                o.put("notif", MediaWatch.enabled(MainActivity.this));
                o.put("unit", LinkService.prefs(MainActivity.this).getString("unitName", ""));
                o.put("code", LinkService.prefs(MainActivity.this).getString("code", ""));
                JSONObject u = LinkService.unitState;
                if (u != null && System.currentTimeMillis() - LinkService.stateAt < 5000) o.put("car", u);
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        /** Paired Bluetooth devices to pick the head unit from. */
        @SuppressLint("MissingPermission")
        @JavascriptInterface
        public String devices() {
            JSONArray a = new JSONArray();
            try {
                BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
                for (BluetoothDevice d : ad.getBondedDevices())
                    a.put(new JSONObject().put("name", d.getName() == null ? d.getAddress() : d.getName()).put("addr", d.getAddress()));
            } catch (Throwable ignored) {
            }
            return a.toString();
        }

        @JavascriptInterface
        public void pick(String addr, String name) {
            LinkService.prefs(MainActivity.this).edit().putString("unit", addr).putString("unitName", name).apply();
            stopService(new Intent(MainActivity.this, LinkService.class));
            LinkService.start(MainActivity.this);
        }

        @JavascriptInterface
        public void setCode(String c) { LinkService.prefs(MainActivity.this).edit().putString("code", c.trim()).apply(); }

        @JavascriptInterface
        public void notifAccess() {
            runOnUiThread(new Runnable() { public void run() {
                try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); } catch (Exception ignored) { }
            } });
        }

        @JavascriptInterface
        public void open(String what) { LinkService.open(what); }

        @JavascriptInterface
        public void pref(String key, boolean value) {
            try { LinkService.send(new JSONObject().put("t", "pref").put("key", key).put("value", value)); } catch (Exception ignored) { }
        }
    }
}
