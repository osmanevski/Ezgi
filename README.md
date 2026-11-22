# Ezgi

Sade, reklamsız bir Android müzik çalar. Telefondaki şarkıları çalar, YouTube Music'te arayıp
şarkı indirir. Sunucu yok, hesap yok; her şey telefonda.

*A simple, ad-free Android music player: plays local music and downloads songs from YouTube Music
on the phone itself. No server, no account. UI in Turkish.*

## Özellikler

- Telefondaki şarkılar: arama, sıralama (son eklenen, ad, sanatçı, albüm, özel sıra), sürükleyerek sıra
- Çalma listeleri, karışık / tekrar, ekran kapalıyken çalma, bildirim ve kilit ekranı kontrolleri
- YouTube Music araması ve indirme ([NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor));
  şarkılar `Müzik/Ezgi` klasörüne iner, uygulama silinse de kalır
- Ekolayzer: sürüklenebilir eğri ve hazır ayarlar
- Uygulama içi güncelleme: açılışta bu deponun son sürümüne bakar

## Kurulum

[Sürümler](https://github.com/osmanevski/Ezgi/releases/latest) sayfasından APK'yı indir, dokun,
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
