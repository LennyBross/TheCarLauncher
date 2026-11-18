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
