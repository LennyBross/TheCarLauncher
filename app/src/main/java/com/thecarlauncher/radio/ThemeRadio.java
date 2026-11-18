package com.thecarlauncher.radio;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import com.thecarlauncher.util.Prefs;
import com.thecarlauncher.media.MediaHub;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;

/** Main-thread adapter for the three themed FM pages. Existing OEM services own the audio route. */
public final class ThemeRadio {
    private static ThemeRadio instance;
    private final Context app;
    private final RadioController radio;
    private boolean hcnClaimed;
    private String note = "";
    private String artKey = "";
    private Bitmap lastArt;
    private String artData = "";

    private ThemeRadio(Context c) {
        app = c.getApplicationContext();
        radio = RadioController.get(app);
    }
    public static synchronized ThemeRadio get(Context c) {
        if (instance == null) instance = new ThemeRadio(c);
        return instance;
    }
    private String backend() {
        if (!Prefs.bool(app, Prefs.RADIO_INTERNAL, true)) return "";
        if (radio.available()) return "native";
        if (HcnRadio.available()) return "hcn";
        return "";
    }
    private static boolean valid(int khz) { return khz >= 87500 && khz <= 108000; }
    private int hcnKhz() {
        int[] i = HcnRadio.info();
        if (i == null) return 0;
        int f = i[0];
        if (f >= 8750 && f <= 10800) f *= 10;
        return valid(f) ? f : 0;
    }
    public JSONObject snapshot() {
        JSONObject o = new JSONObject();
        try {
            String b = backend();
            boolean n = b.equals("native"), h = b.equals("hcn");
            int[] hi = h ? HcnRadio.info() : null;
            int khz = n ? radio.freq() * 10 : h ? hcnKhz() : 0;
            String name = n ? radio.ps() : "";
            JSONArray presets = new JSONArray(), stations = new JSONArray();
            if (n) {
                for (int k = 0; k < RadioController.PRESETS; k++) {
                    RadioController.Station s = radio.preset(k);
                    presets.put(s == null ? JSONObject.NULL : new JSONObject().put("khz",s.freq*10).put("name",s.name));
                }
                for (RadioController.Station s : radio.stations()) {
                    if (valid(s.freq*10)) stations.put(new JSONObject().put("khz",s.freq*10).put("name",s.name));
                }
            } else if (h) {
                String saved = Prefs.str(app,"theme_radio_hcn_presets","");
                String[] list = saved.split(",",-1);
                for (int k=0;k<18;k++) {
                    int f=0;
                    try { if(k<list.length) f=Integer.parseInt(list[k]); } catch(Exception ignored) {}
                    presets.put(valid(f)?new JSONObject().put("khz",f).put("name",""):JSONObject.NULL);
                }
            }
            o.put("ok",!b.isEmpty()).put("backend",b).put("khz",khz).put("name",name)
                .put("text",n ? radio.rt() : "").put("stereo",n ? radio.stereo() : hi != null && hi[1]==1)
                .put("active",n ? radio.isActive() : h && hcnClaimed).put("confirmed",n)
                .put("scanning",n && radio.scanning()).put("canScan",n)
                .put("presets",presets).put("stations",stations).put("note",note)
                .put("error",b.isEmpty() ? (Prefs.bool(app,Prefs.RADIO_INTERNAL,true) ? "FM servisine erişilemedi. Cihaz raporu gerekli." : "Dahili radyo ayarlarda kapalı.") : "");
            if (n && !name.isEmpty()) {
                Bitmap bm = RadioLogos.get(app).logo(name);
                if (!name.equals(artKey) || bm != lastArt) {
                    artKey=name; lastArt=bm; artData="";
                    if(bm!=null){
                        ByteArrayOutputStream out=new ByteArrayOutputStream();
                        Bitmap scaled = Bitmap.createScaledBitmap(bm,128,128,true);
                        scaled.compress(Bitmap.CompressFormat.PNG,100,out);
                        artData="data:image/png;base64,"+Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP);
                    }
                }
                o.put("art",artData);
            }
        } catch (Exception e) { try { o.put("ok",false).put("error","FM: "+e.getClass().getSimpleName()); } catch(Exception ignored) {} }
        return o;
    }
    private boolean startHcn() {
        if (hcnClaimed) return true;
        MediaHub.get(app).onRadioStarting();
        hcnClaimed=HcnRadio.play();
        if (!hcnClaimed) note="FM başlatma komutu desteklenmiyor.";
        return hcnClaimed;
    }
    public void action(String action,int v) {
        String b=backend(); note="";
        if(b.isEmpty()) return;
        if(b.equals("native")){
            switch(action){
                case "play": if(!radio.start())note="FM başlatılamadı.";break;
                case "stop": radio.stop();break;
                case "tune": if(valid(v)&&radio.start()){radio.stopScan();radio.tune(v/10);}break;
                case "next": if(radio.start()){radio.stopScan();radio.seekUp();}break;
                case "prev": if(radio.start()){radio.stopScan();radio.seekDown();}break;
                case "stepUp": if(radio.start()){radio.stopScan();radio.stepUp();}break;
                case "stepDown": if(radio.start()){radio.stopScan();radio.stepDown();}break;
                case "scan": if(radio.scanning())radio.stopScan();else if(radio.start())radio.startScan();break;
                case "save": if(v>=0&&v<18&&radio.isActive())radio.savePreset(v);break;
                case "recall": if(v>=0&&v<18&&radio.preset(v)!=null&&radio.start()){radio.stopScan();radio.recallPreset(v);}break;
                default:break;
            }
            return;
        }
        switch(action){
            case "play": startHcn();break;
            case "stop": if(HcnRadio.stop())hcnClaimed=false;else note="FM durdurma komutu desteklenmiyor.";break;
            case "tune": if(valid(v)&&startHcn()&&!HcnRadio.tune(v))note="Frekans komutu başarısız.";break;
            case "next": case "prev": case "stepUp": case "stepDown":
                if(startHcn()){if(action.startsWith("step"))HcnRadio.step(action.equals("stepUp"));else HcnRadio.seek(action.equals("next"));}break;
            case "save": if(v>=0&&v<18&&valid(hcnKhz())){
                String[] old=Prefs.str(app,"theme_radio_hcn_presets","").split(",",-1);
                StringBuilder out=new StringBuilder();
                for(int k=0;k<18;k++){if(k>0)out.append(',');out.append(k==v?String.valueOf(hcnKhz()):k<old.length?old[k]:"");}
                Prefs.put(app,"theme_radio_hcn_presets",out.toString());
            }break;
            case "recall": if(v>=0&&v<18){
                String[] old=Prefs.str(app,"theme_radio_hcn_presets","").split(",",-1);
                try{int f=Integer.parseInt(old[v]);if(valid(f)&&startHcn()&&!HcnRadio.tune(f))note="Frekans komutu başarısız.";}catch(Exception ignored){}
            }break;
            default:break;
        }
    }
}
