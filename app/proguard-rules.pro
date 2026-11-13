# Reglas de R8 para el build release.
# Las librerías de AndroidX, Media3, Coil, OkHttp, kotlinx.serialization y ML Kit traen sus propias reglas (consumer rules).
# Aquí solo lo que usa JNI o reflexión desde código nativo, que R8 no puede ver.

# --- Runtimes de IA con código nativo (JNI busca clases y métodos por nombre)
-keep class ai.onnxruntime.** { *; }
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn ai.onnxruntime.**
-dontwarn com.k2fsa.sherpa.onnx.**
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
-dontwarn javax.lang.model.**
-dontwarn autovalue.shaded.**
-dontwarn com.google.auto.value.**

# --- Los modelos serializados (proyectos .json y ajustes) se leen por nombre de campo
-keepclassmembers class com.dskmusic.vixa.model.** { *; }
-keepclassmembers class com.dskmusic.vixa.data.** { *; }
-keep,includedescriptorclasses class com.dskmusic.vixa.**$$serializer { *; }
-keepclassmembers class com.dskmusic.vixa.** {
    *** Companion;
}
-keepclasseswithmembers class com.dskmusic.vixa.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- commons-compress: dependencias opcionales que no se empaquetan
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn org.apache.commons.compress.**

# --- Trazas legibles en el registro de errores
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
