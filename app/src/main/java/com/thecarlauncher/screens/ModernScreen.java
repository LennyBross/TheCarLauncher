package com.thecarlauncher.screens;

import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.thecarlauncher.LauncherActivity;
import com.thecarlauncher.R;
import com.thecarlauncher.car.FuelCost;
import com.thecarlauncher.car.FuelPrice;
import com.thecarlauncher.media.MediaHub;
import com.thecarlauncher.mib.Mib;
import com.thecarlauncher.nav.NavHelper;
import com.thecarlauncher.util.DayNight;
import com.thecarlauncher.util.NightDim;
import com.thecarlauncher.views.GaugeCluster;
import com.thecarlauncher.util.Prefs;
import com.thecarlauncher.util.Ui;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * "Modern" home page, chosen in Setup › Display instead of the MENU tiles: a wallpaper with the
 * clock, four cards (fuel cost, media, navigation, phone) and an app bar along the bottom.
 */
public class ModernScreen extends Screen implements MediaHub.Listener {
    public static final String P_MODERN = "home_modern", P_WALLPAPER = "home_modern_wallpaper";
    /** Wallpapers: {Serbian, English, top colour, bottom colour}. */
    public static final Object[][] WALLPAPERS = {
            {"Grafit", "Graphite", 0xFF3A3B40, 0xFF1A1B1F},
            {"Noćno plava", "Night blue", 0xFF24365A, 0xFF141B2E},
            {"Sumrak", "Dusk", 0xFF6E4A63, 0xFF2B2340},
    };

    private final DayNight.Pal pal;
    private final int CARD, INNER, TEXT, SUB, HEAD, DOCK;
    private static final Locale TR = new Locale("tr", "TR");

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView clock, date, dimLabel, pricePill;
    private TextView fuelBig, fuelL100, fuelPerKm, fuelNote;
    private TextView songTitle, songArtist;
    private ImageView art, playIcon;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            // Automatic day / night: rebuild with the other look at sunrise and sunset.
            if (DayNight.mode(act) == DayNight.AUTO && DayNight.isNight(act) != pal.night) {
                act.restartUi();
                return;
            }
            main.postDelayed(this, 15000);
        }
    };

    public ModernScreen(LauncherActivity act) {
        super(act);
        pal = DayNight.pal(act);
        CARD = pal.card;
        INNER = pal.inner;
        TEXT = pal.text;
        SUB = pal.sub;
        HEAD = pal.head;
        DOCK = pal.dock;
    }

    public static boolean enabled(android.content.Context c) { return Prefs.bool(c, P_MODERN, false); }

    @Override
    public String title() { return "MENU"; }

    private String s(String sr, String en) { return Mib.t(act, sr, en); }

    private int px(float v) { return Mib.px(act, v); }

    // ---- Building -------------------------------------------------------------------------------

    @Override
    protected View create(ViewGroup parent) {
        FrameLayout root = new FrameLayout(act);
        int w = Math.max(0, Math.min(WALLPAPERS.length - 1, Prefs.integer(act, P_WALLPAPER, 0)));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, pal.night
                ? new int[]{(Integer) WALLPAPERS[w][2], (Integer) WALLPAPERS[w][3], pal.bg}
                : new int[]{pal.skyTop, pal.skyBottom, pal.bg});
        root.setBackground(bg);

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        col.addView(header(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        col.addView(cards(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(236)));
        col.addView(dock(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(72)));
        return root;
    }

    private View header() {
        FrameLayout h = new FrameLayout(act);
        h.setPadding(px(16), px(10), px(26), px(8));

        LinearLayout cluster = new LinearLayout(act);
        cluster.setGravity(Gravity.TOP);
        cluster.addView(new GaugeCluster(act, pal), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout left = new LinearLayout(act);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        leftLp.leftMargin = px(22);
        leftLp.topMargin = px(14);
        cluster.addView(left, leftLp);
        clock = Mib.text(act, "", 60, Mib.regular(act), TEXT);
        date = Mib.text(act, "", 22, Mib.semibold(act), TEXT);
        TextView model = Mib.text(act, "DS 4", 15, Mib.semibold(act), SUB);
        model.setLetterSpacing(0.2f);
        left.addView(clock);
        LinearLayout.LayoutParams dl = wrap();
        dl.topMargin = px(6);
        left.addView(date, dl);
        LinearLayout.LayoutParams ml = wrap();
        ml.topMargin = px(10);
        left.addView(model, ml);
        h.addView(cluster, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.LEFT | Gravity.TOP));

        LinearLayout right = new LinearLayout(act);
        right.setGravity(Gravity.CENTER_VERTICAL);
        TextView modeLabel = Mib.text(act, modeText(), 18, Mib.semibold(act), TEXT);
        View mode = pill(modeLabel);
        mode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                DayNight.setMode(act, (DayNight.mode(act) + 1) % 3);
                act.restartUi();
            }
        });
        LinearLayout.LayoutParams mpl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(50));
        mpl.rightMargin = px(10);
        right.addView(mode, mpl);
        dimLabel = Mib.text(act, "", 18, Mib.semibold(act), TEXT);
        View dim = pill(dimLabel);
        dim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!NightDim.canDraw(act)) {
                    NightDim.request(act);
                    return;
                }
                NightDim.setOn(act, !NightDim.on(act));
                updateDim();
            }
        });
        right.addView(dim, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(50)));
        pricePill = Mib.text(act, "", 18, Mib.semibold(act), TEXT);
        View price = pill(pricePill);
        price.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { fuelMenu(); }
        });
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(50));
        pl.leftMargin = px(10);
        right.addView(price, pl);
        h.addView(right, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.RIGHT | Gravity.TOP));
        return h;
    }

    private View pill(TextView label) {
        FrameLayout p = new FrameLayout(act);
        GradientDrawable d = new GradientDrawable();
        d.setColor(pal.pill);
        d.setCornerRadius(px(25));
        p.setBackground(d);
        p.setPadding(px(18), 0, px(18), 0);
        p.addView(label, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        return p;
    }

    private String modeText() {
        int m = DayNight.mode(act);
        String n = pal.night ? s("Noć", "Night") : s("Dan", "Day");
        return m == DayNight.AUTO ? s("Auto · ", "Auto · ") + n : n;
    }

    private View cards() {
        LinearLayout row = new LinearLayout(act);
        row.setPadding(px(16), 0, px(16), px(12));
        row.addView(fuelCard(), cardLp(false));
        row.addView(mediaCard(), cardLp(true));
        row.addView(navCard(), cardLp(true));
        row.addView(phoneCard(), cardLp(true));
        return row;
    }

    private LinearLayout.LayoutParams cardLp(boolean gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        if (gap) lp.leftMargin = px(12);
        return lp;
    }

    private LinearLayout card(String title, int icon) {
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(CARD, 20));
        c.setPadding(px(18), px(16), px(18), px(16));
        FrameLayout head = new FrameLayout(act);
        head.addView(Mib.text(act, title, 16, Mib.semibold(act), HEAD), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL));
        head.addView(Mib.icon(act, icon, HEAD), new FrameLayout.LayoutParams(px(20), px(20),
                Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        c.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(24)));
        return c;
    }

    private static GradientDrawable roundD(int color, float r) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(r);
        return d;
    }

    private GradientDrawable round(int color, float radius) { return roundD(color, px(radius)); }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    // Fuel ------------------------------------------------------------------------------------------

    private View fuelCard() {
        LinearLayout c = card(s("Potrošnja goriva", "Fuel cost"), R.drawable.ui_ic_speedo);
        fuelBig = Mib.text(act, "—", 38, Mib.semibold(act), TEXT);
        LinearLayout.LayoutParams bl = wrap();
        bl.topMargin = px(10);
        c.addView(fuelBig, bl);
        c.addView(Mib.text(act, "TL / 100 km", 15, Mib.regular(act), SUB));

        LinearLayout pair = new LinearLayout(act);
        fuelL100 = Mib.text(act, "—", 20, Mib.semibold(act), TEXT);
        fuelPerKm = Mib.text(act, "—", 20, Mib.semibold(act), TEXT);
        pair.addView(stat(fuelL100, "L / 100 km"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        sl.leftMargin = px(8);
        pair.addView(stat(fuelPerKm, "TL / km"), sl);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        pl.topMargin = px(10);
        c.addView(pair, pl);

        fuelNote = Mib.text(act, "", 12, Mib.regular(act), SUB);
        LinearLayout.LayoutParams nl = wrap();
        nl.topMargin = px(6);
        c.addView(fuelNote, nl);
        c.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { fuelMenu(); }
        });
        return c;
    }

    private View stat(TextView value, String label) {
        LinearLayout b = new LinearLayout(act);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setBackground(round(INNER, 14));
        b.setPadding(px(10), px(4), px(8), px(4));
        b.addView(value);
        b.addView(Mib.text(act, label, 12, Mib.regular(act), SUB));
        return b;
    }

    private static String num(double v, int decimals) {
        return String.format(TR, "%." + decimals + "f", v);
    }

    private void updateFuel() {
        double l100 = FuelCost.litresPer100(act), price = FuelPrice.perLitre(act);
        double per100 = FuelCost.liraPer100(act), perKm = FuelCost.liraPerKm(act);
        fuelBig.setText(per100 > 0 ? num(per100, 0) + " TL" : "—");
        fuelL100.setText(l100 > 0 ? num(l100, 1) : "—");
        fuelPerKm.setText(perKm > 0 ? num(perKm, 2) : "—");
        pricePill.setText(price > 0 ? s("Dizel ", "Diesel ") + num(price, 2) + " TL" : s("Dizel: unesi cenu", "Diesel: set price"));
        String src;
        if (l100 <= 0) src = s("Dodirni: unesi prosek sa instrument table", "Tap: enter the average from the cluster");
        else if (price <= 0) src = s("Dodirni: unesi cenu goriva", "Tap: enter the fuel price");
        else {
            String ps = FuelPrice.source(act);
            src = "online".equals(ps) ? "Opet · " + FuelPrice.province(act)
                    : "manual".equals(ps) ? s("ručno uneta cena", "price typed in") : s("cena s poslednjeg točenja", "price from last fill-up");
            src += FuelCost.consumptionIsManual(act) ? s(" · prosek sa table", " · cluster average") : s(" · iz dnevnika točenja", " · from fuel log");
        }
        fuelNote.setText(src);
    }

    /** Fuel settings in one list: price, province, cluster average, refresh. */
    private void fuelMenu() {
        final String[] items = {
                s("Unesi cenu dizela (TL/L)", "Enter diesel price (TL/L)"),
                s("Koristi online cenu (Opet)", "Use the online price (Opet)"),
                s("Pokrajina (registarski broj)", "Province (plate code)"),
                s("Unesi prosečnu potrošnju sa table (L/100 km)", "Enter average consumption from the cluster (L/100 km)"),
                s("Računaj potrošnju iz dnevnika točenja", "Work out consumption from the fuel log"),
        };
        Dialogs.choose(act, s("Potrošnja goriva", "Fuel cost"), items, -1, new Dialogs.Chosen() {
            @Override
            public void onChosen(int i) {
                if (i == 0) {
                    double p = FuelPrice.perLitre(act);
                    Dialogs.number(act, items[0], "57,61", p > 0 ? num(p, 2) : "", new Dialogs.NumberEntered() {
                        public void onNumber(double v) {
                            if (v > 0) FuelPrice.setManual(act, v);
                            updateFuel();
                        }
                    });
                } else if (i == 1) {
                    FuelPrice.setManual(act, -1);
                    FuelPrice.refresh(act, true, fuelChanged);
                    Ui.toast(act, s("Preuzimam cenu…", "Fetching the price…"));
                    updateFuel();
                } else if (i == 2) {
                    Dialogs.text(act, items[2], "34", FuelPrice.province(act), new Dialogs.TextEntered() {
                        public void onText(String t) {
                            if (t != null && t.trim().matches("\\d{1,3}")) {
                                FuelPrice.setProvince(act, t.trim());
                                FuelPrice.refresh(act, true, fuelChanged);
                            }
                        }
                    });
                } else if (i == 3) {
                    double l = FuelCost.litresPer100(act);
                    Dialogs.number(act, items[3], "5,8", l > 0 ? num(l, 1) : "", new Dialogs.NumberEntered() {
                        public void onNumber(double v) {
                            if (v > 0 && v < 40) FuelCost.setManualConsumption(act, v);
                            updateFuel();
                        }
                    });
                } else if (i == 4) {
                    FuelCost.setManualConsumption(act, -1);
                    updateFuel();
                    if (FuelCost.litresPer100(act) <= 0) {
                        Ui.toast(act, s("Potrebna su dva puna točenja u Vozilo › Gorivo",
                                "Needs two full fill-ups in Vehicle › Fuel"));
                    }
                }
            }
        });
    }

    private final FuelPrice.Listener fuelChanged = new FuelPrice.Listener() {
        @Override
        public void onPrice() {
            if (isCreated()) updateFuel();
        }
    };

    // Media -----------------------------------------------------------------------------------------

    private View mediaCard() {
        LinearLayout c = card(s("Mediji", "Media"), R.drawable.ui_ic_note);
        LinearLayout info = new LinearLayout(act);
        info.setGravity(Gravity.CENTER_VERTICAL);
        art = new ImageView(act);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackground(round(0xFF2E2F38, 12));
        art.setClipToOutline(true);
        info.addView(art, new LinearLayout.LayoutParams(px(70), px(70)));
        LinearLayout text = new LinearLayout(act);
        text.setOrientation(LinearLayout.VERTICAL);
        songTitle = Mib.text(act, "", 19, Mib.semibold(act), TEXT);
        songArtist = Mib.text(act, "", 15, Mib.regular(act), SUB);
        text.addView(songTitle);
        LinearLayout.LayoutParams al = wrap();
        al.topMargin = px(4);
        text.addView(songArtist, al);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.leftMargin = px(12);
        info.addView(text, tl);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        il.topMargin = px(10);
        c.addView(info, il);
        info.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { act.show(LauncherActivity.MEDIA, true); }
        });

        LinearLayout keys = new LinearLayout(act);
        keys.setGravity(Gravity.CENTER_VERTICAL);
        keys.addView(roundKey(R.drawable.ic_prev, INNER, TEXT, new Runnable() {
            public void run() { MediaHub.get(act).previous(); }
        }), new LinearLayout.LayoutParams(0, px(52), 1f));
        playIcon = new ImageView(act);
        View play = roundKey(playIcon, pal.accent, pal.onAccent, new Runnable() {
            public void run() { MediaHub.get(act).playPause(); main.postDelayed(new Runnable() { public void run() { updateMedia(); } }, 400); }
        });
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, px(52), 1.3f);
        ml.leftMargin = ml.rightMargin = px(8);
        keys.addView(play, ml);
        keys.addView(roundKey(R.drawable.ic_next, INNER, TEXT, new Runnable() {
            public void run() { MediaHub.get(act).next(); }
        }), new LinearLayout.LayoutParams(0, px(52), 1f));
        c.addView(keys);
        return c;
    }

    private View roundKey(int icon, int bg, int fg, Runnable r) {
        ImageView iv = new ImageView(act);
        iv.setImageResource(icon);
        return roundKey(iv, bg, fg, r);
    }

    private View roundKey(ImageView iv, int bg, int fg, final Runnable r) {
        FrameLayout k = new FrameLayout(act);
        k.setBackground(round(bg, 26));
        iv.setImageTintList(android.content.res.ColorStateList.valueOf(fg));
        k.addView(iv, new FrameLayout.LayoutParams(px(26), px(26), Gravity.CENTER));
        k.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { r.run(); }
        });
        return k;
    }

    private void updateMedia() {
        MediaHub m = MediaHub.get(act);
        String t = m.hasTrackInfo() ? m.title() : null;
        songTitle.setText(Ui.isEmpty(t) ? s("Ništa ne svira", "Nothing playing") : t);
        String a = m.hasTrackInfo() ? m.artist() : null;
        songArtist.setText(a == null ? "" : a);
        Bitmap b = m.hasTrackInfo() ? m.art() : null;
        if (b != null) {
            art.setImageBitmap(b);
            art.setPadding(0, 0, 0, 0);
            art.setImageTintList(null);
        } else {
            art.setImageResource(R.drawable.ui_ic_note);
            art.setImageTintList(android.content.res.ColorStateList.valueOf(0xFF6E7079));
            art.setPadding(px(20), px(20), px(20), px(20));
            art.setScaleType(ImageView.ScaleType.FIT_CENTER);
        }
        if (b != null) art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        playIcon.setImageResource(m.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    @Override
    public void onMediaChanged() {
        main.post(new Runnable() {
            public void run() { if (isCreated()) updateMedia(); }
        });
    }

    // Navigation and phone ----------------------------------------------------------------------------

    private View navCard() {
        LinearLayout c = card(s("Navigacija", "Navigation"), R.drawable.ui_ic_nav);
        c.addView(row(R.drawable.ui_ic_home, s("Kuća", "Home"), null, new Runnable() {
            public void run() { goTo(Prefs.HOME_ADDR); }
        }), rowLp(10));
        c.addView(row(R.drawable.ui_ic_flag, s("Posao", "Work"), null, new Runnable() {
            public void run() { goTo(Prefs.WORK_ADDR); }
        }), rowLp(8));
        return c;
    }

    private void goTo(String key) {
        String saved = Prefs.str(act, key, null);
        if (Ui.isEmpty(saved)) {
            act.show(LauncherActivity.NAV, true);
            return;
        }
        String[] f = saved.split("\\|");
        NavHelper.navigateTo(act, f.length >= 3 ? f[1] + "," + f[2] : saved);
    }

    private View phoneCard() {
        LinearLayout c = card(s("Telefon", "Phone"), R.drawable.ui_ic_phone);
        c.addView(row(R.drawable.ui_ic_phone, s("Telefon", "Phone"), null, new Runnable() {
            public void run() { act.openPhone(); }
        }), rowLp(10));
        c.addView(row(R.drawable.ui_ic_appconnect_s, "Android Auto", null, new Runnable() {
            public void run() { act.openProjection(); }
        }), rowLp(8));
        return c;
    }

    private LinearLayout.LayoutParams rowLp(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = px(top);
        return lp;
    }

    private View row(int icon, String label, String sub, final Runnable r) {
        LinearLayout b = new LinearLayout(act);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setBackground(round(INNER, 16));
        b.setPadding(px(14), 0, px(10), 0);
        b.addView(Mib.icon(act, icon, pal.accent), new LinearLayout.LayoutParams(px(26), px(26)));
        LinearLayout t = new LinearLayout(act);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(Mib.text(act, label, 19, Mib.semibold(act), TEXT));
        if (sub != null) t.addView(Mib.text(act, sub, 13, Mib.regular(act), SUB));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.leftMargin = px(12);
        b.addView(t, tl);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { r.run(); }
        });
        return b;
    }

    // Dock ------------------------------------------------------------------------------------------

    private View dock() {
        LinearLayout d = new LinearLayout(act);
        d.setBackgroundColor(DOCK);
        d.setGravity(Gravity.CENTER);
        d.setPadding(px(16), 0, px(16), 0);
        dockKey(d, R.drawable.ui_ic_radio, new Runnable() { public void run() { act.openRadio(); } });
        dockKey(d, R.drawable.ui_ic_note, new Runnable() { public void run() { act.show(LauncherActivity.MEDIA, true); } });
        dockKey(d, R.drawable.ui_ic_nav, new Runnable() { public void run() { act.show(LauncherActivity.NAV, true); } });
        dockKey(d, R.drawable.ui_ic_phone, new Runnable() { public void run() { act.openPhone(); } });
        dockKey(d, R.drawable.ui_ic_appconnect_s, new Runnable() { public void run() { act.openProjection(); } });
        dockKey(d, R.drawable.ui_ic_car_s, new Runnable() { public void run() { act.show(LauncherActivity.VEHICLE, true); } });
        dockKey(d, R.drawable.ui_ic_apps, new Runnable() { public void run() { act.show(LauncherActivity.APPS, true); } });
        dockKey(d, R.drawable.ic_settings, new Runnable() { public void run() { act.show(LauncherActivity.SETTINGS, true); } });
        return d;
    }

    private void dockKey(LinearLayout d, int icon, final Runnable r) {
        FrameLayout k = new FrameLayout(act);
        k.setBackground(round(INNER, 16));
        ImageView iv = Mib.icon(act, icon, TEXT);
        k.addView(iv, new FrameLayout.LayoutParams(px(28), px(28), Gravity.CENTER));
        k.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { r.run(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(76), px(54));
        lp.leftMargin = lp.rightMargin = px(7);
        d.addView(k, lp);
    }

    // ---- Updates ------------------------------------------------------------------------------------

    private void updateClock() {
        Date now = new Date();
        boolean h24 = Prefs.use24h(act);
        clock.setText(new SimpleDateFormat(h24 ? "HH:mm" : "h:mm", TR).format(now));
        date.setText(new SimpleDateFormat("d MMMM EEEE", TR).format(now));
    }

    private void updateDim() {
        dimLabel.setText(NightDim.on(act) ? s("Zatamnjeno", "Dimmed") + " " + NightDim.level(act) + " %"
                : s("Zatamni", "Dim"));
    }

    @Override
    public void onShow() {
        main.removeCallbacks(tick);
        main.post(tick);
        MediaHub.get(act).addListener(this);
        updateMedia();
        updateDim();
        updateFuel();
        FuelPrice.refresh(act, false, fuelChanged);
    }

    @Override
    public void onHide() {
        main.removeCallbacks(tick);
        MediaHub.get(act).removeListener(this);
    }

    @Override
    public void onDestroy() {
        onHide();
    }
}
