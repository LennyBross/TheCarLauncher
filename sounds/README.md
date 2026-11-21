# CataraPatara ses paketleri

Bu klasördeki her `.zip` CataraPatara'ya otomatik iner: uygulama açıldığında ve motor sesi
başladığında GitHub'daki listeye bakar, yeni ya da değişmiş paketleri indirir. Uygulamayı
güncellemek gerekmez — buraya bir zip eklemek ya da değiştirmek yeter.

**Bu repo herkese açık.** Buraya yalnızca özgün (kendi ürettiğiniz) ya da kullanım izni olan
sesler konur. Üretici, YouTube vb. kayıtlar buraya konmaz; onlar uygulamada "+ Ses paketi ekle"
ile cihaza elle yüklenir.

## Zip içeriği

```
pack.json
sound.wav      (16 bit PCM, mono ya da stereo, herhangi bir örnekleme hızı)
```

## pack.json

```json
{
  "name": "Jaguar V8 Supercharged (Prototype)",
  "sub": "kısa açıklama",
  "file": "sound.wav",
  "idle": 720,            // rölanti devri
  "redline": 6500,        // devir sınırı
  "maxRate": 1.8,         // en fazla hız/perde kaydırma oranı
  "grain": 0.07,          // tane uzunluğu (sn), 0.06–0.10
  "idleBank": [[t, rpm, kazanç], ...],   // rölanti / boşta
  "onBank":   [[t, rpm, kazanç], ...],   // gaz basılıyken
  "offBank":  [[t, rpm, kazanç], ...],   // gazdan ayak çekilmişken
  "bankGain": {"idle": 1, "on": 1, "off": 0.9}
}
```

- `t`: wav içindeki saniye; `rpm`: o anda motorun devri; `kazanç`: ses düzeyi düzeltmesi (1 = aynen).
- Her bankta en az birkaç nokta olmalı; ne kadar sık (ör. her 25–100 ms) o kadar akıcı.
- Uygulama istenen devre en yakın noktalardan kısa taneler alıp perdesini kaydırarak harmanlar.
- Dosya adı paketin kimliğidir: aynı adla yeniden yüklenen zip eskisinin yerine geçer.

## Yeni format: 4 döngü paketi (`"type": "loops"`)

Teknik: [markeasting/engine-audio](https://github.com/markeasting/engine-audio) (MIT) — CataraPatara'da Java'ya yeniden yazıldı
(`catarapatara/.../engine/LoopEngine.java`). OBD'den gelen gerçek devir ve gaz pedalıyla en doğal sonucu veren format budur.

Zip içinde `pack.json` ve 16-bit WAV dosyaları (mono ya da stereo):

```json
{
  "type": "loops",
  "name": "Örnek V8",
  "sub": "4 döngü",
  "idle": 850,
  "redline": 6500,
  "pitchFactor": 0.2,
  "crossfade": [3000, 6500],
  "layers": {
    "on_low":   { "file": "on_low.wav",   "rpm": 1000, "volume": 0.5 },
    "off_low":  { "file": "off_low.wav",  "rpm": 1000, "volume": 0.5 },
    "on_high":  { "file": "on_high.wav",  "rpm": 5000, "volume": 0.5 },
    "off_high": { "file": "off_high.wav", "rpm": 5000, "volume": 0.5 },
    "limiter":  { "file": "limiter.wav",  "rpm": 0,    "volume": 0.4 }
  }
}
```

- `on_*` gazda (yük altında), `off_*` gaz kesikken kaydedilmiş, **sabit devirde, sorunsuz döngü yapan** kısa kayıtlar (1–4 sn).
- `rpm`: döngünün kaydedildiği devir. Ses, aracın devrine `cents = (devir − rpm) × pitchFactor` kadar perdelenir.
- `crossfade`: düşük ↔ yüksek döngü geçişinin devir aralığı. Gaz pedalı on ↔ off karışımını belirler.
- Sadece `on_low` zorunlu; eksik katmanların yerine diğerleri kullanılır.
- Sadece kendi kaydın ya da açık lisanslı sesler. Oyunlardan çıkarılmış ses bankaları (ör. dosya adında `{GUID}` olanlar) kullanılamaz.
