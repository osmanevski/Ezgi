# Ezgi

Sade, reklamsız bir Android müzik çalar. Telefondaki şarkıları çalar, YouTube Music'te arayıp
şarkı indirir. Sunucu yok, hesap yok; her şey telefonda.

*A simple, ad-free Android music player: plays local music and downloads songs from YouTube Music
on the phone itself. No server, no account. UI in Turkish.*

## Özellikler

- Telefondaki şarkılar: arama, sıralama (son eklenen, ad, sanatçı, albüm, özel sıra), sürükleyerek sıra, silme
- Çalma listeleri, karışık / tekrar, ekran kapalıyken çalma, bildirim ve kilit ekranı kontrolleri
- YouTube Music araması: dokununca indirmeden çalar, ↓ ile indirir ([NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor));
  şarkılar `Müzik/Ezgi` klasörüne iner, uygulama silinse de kalır
- Ekolayzer: sürüklenebilir eğri ve hazır ayarlar
- Şarkı sözleri, zamanlı (çalan satır vurgulu; [LRCLIB](https://lrclib.net))
- Uygulama içi güncelleme: açılışta bu deponun son sürümüne bakar

## Dinleme geçmişi ve gizlilik

Ezgi dinlemeleri ve indirmeleri telefonda kaydeder (yıl sonu özeti, akıllı mix için). İlk açılışta
sorar: izin verilirse bu kayıtlar `osmanevski.com/ezgi` sunucusuna da gider. Giden yalnız şarkı adı,
sanatçı, ne kadar dinlendiği ve zaman; kimlik telefonda üretilen rastgele bir kurulum kimliği.
Telefon bilgisi, konum, hesap yok; sunucu IP adresi saklamaz. Ayarlar'dan kapatılabilir.
Sunucu kodu: [`sunucu/`](sunucu/).

## Kurulum

[Son sürümü indir](https://github.com/osmanevski/Ezgi/releases/latest/download/Ezgi.apk), dokun,
"bilinmeyen kaynaklardan yükleme" iznini ver. Sonraki sürümler uygulamanın içinden gelir.

## Derleme ve yayın

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :app:assembleRelease          # imzalı APK için imza/keystore.properties gerekir
./yayinla.sh 1.1 "Neler değişti"        # sürüm + APK + GitHub sürümü
```

İmza anahtarı (`imza/`) depoda değil. Güncellemelerin telefondakinin üstüne kurulabilmesi için
her sürüm aynı anahtarla imzalanmalı.

Kotlin, Jetpack Compose, Media3 (ExoPlayer).
