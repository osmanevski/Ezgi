# NewPipeExtractor (README'deki kurallar): YouTube imza çözümü Rhino ile çalışıyor.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
# Rhino'nun masaüstü bağımlılıkları Android'de yok.
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
# YouTube istekleri protobuf-lite mesajlarıyla kuruluyor; yansımayla okunan alanlar silinmesin.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keep class org.schabi.newpipe.extractor.services.youtube.** { *; }
