# Código próprio: manter tudo (componentes, reflexão do Shizuku, protocolo).
-keep class io.github.jacobhortencio.watchbridge.** { *; }
# Shizuku usa reflexão em newProcess.
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.**
# Guava traz anotações opcionais que não existem no Android.
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn afu.org.checkerframework.**
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn sun.misc.Unsafe
