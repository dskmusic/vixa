import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Claves de API de los recursos online: se leen de local.properties (api.pixabay=…) o de variables de entorno
// (VIXA_API_PIXABAY=…). Ese archivo NO se sube a Git. Sin clave, la app compila igual y la pide en Ajustes.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun apiKey(name: String): String = (localProps.getProperty("api.$name") ?: System.getenv("VIXA_API_" + name.uppercase()) ?: "").trim()

// Versión: version.properties (versionName=1.2.8). Se puede forzar al compilar con -PversionName=1.2.9 (la Action lo hace).
// El versionCode sale del número (1.2.8 → 10208): crece siempre que la versión crece, y así las actualizaciones se instalan encima.
val versionProps = Properties().apply {
    val f = rootProject.file("version.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val appVersionName: String = ((findProperty("versionName") as String?) ?: versionProps.getProperty("versionName") ?: "1.0.0").trim()
val appVersionCode: Int = appVersionName.split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }.let { (it.getOrElse(0) { 0 } * 10000 + it.getOrElse(1) { 0 } * 100 + it.getOrElse(2) { 0 }).coerceAtLeast(1) }

// Firma de release: con las variables KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS y KEY_PASSWORD (las pone la Action de GitHub)
// se firma con tu keystore; sin ellas (compilación local) se usa la firma de depuración, útil para pruebas.
val releaseKeystore: String? = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }

android {
    namespace = "com.dskmusic.vixa"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dskmusic.vixa"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        // Los runtimes de IA (sherpa-onnx, ONNX Runtime, MediaPipe) traen 4 ABI: solo se empaqueta arm64-v8a (los móviles).
        // Para probar en un emulador x86_64 vuelve a añadir "x86_64" aquí y quita "lib/x86_64/**" de packaging.jniLibs.excludes.
        ndk { abiFilters += setOf("arm64-v8a") }
        buildConfigField("String", "KEY_PIXABAY", "\"${apiKey("pixabay")}\"")
        buildConfigField("String", "KEY_PEXELS", "\"${apiKey("pexels")}\"")
        buildConfigField("String", "KEY_UNSPLASH", "\"${apiKey("unsplash")}\"")
        buildConfigField("String", "KEY_WALLHAVEN", "\"${apiKey("wallhaven")}\"")
        buildConfigField("String", "KEY_COVERR", "\"${apiKey("coverr")}\"")
        buildConfigField("String", "KEY_GIPHY", "\"${apiKey("giphy")}\"")
        buildConfigField("String", "KEY_KLIPY", "\"${apiKey("klipy")}\"")
        buildConfigField("String", "KEY_JAMENDO", "\"${apiKey("jamendo")}\"")
        buildConfigField("String", "KEY_FREESOUND", "\"${apiKey("freesound")}\"")
    }

    // Solo los idiomas de la app: las librerías de AndroidX y Google traen decenas de traducciones.
    androidResources {
        localeFilters += setOf("es", "en")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    // giflossy (gifsicle con --lossy): biblioteca nativa para comprimir los GIF. Requiere NDK y CMake 3.22.1 (SDK Manager).
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        // Debug sin minificación; release con R8 (código y recursos recortados). Reglas en proguard-rules.pro.
        // La variante debug (Run ▶ de Android Studio) lleva otro nombre de paquete (.debug) y se instala a la vez que la release.
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    // Bytecode Java/Kotlin 17. No se fija jvmToolchain para no exigir un JDK 17 instalado:
    // el JDK con el que Android Studio ejecuta Gradle (JBR 21) compila a nivel 17.
    // Con Kotlin integrado en AGP 9, jvmTarget toma por defecto este targetCompatibility.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        warningsAsErrors = false
        // Media3 marca casi toda la API de edición como @UnstableApi (opt-in comprobado por lint).
        disable += setOf("UnsafeOptInUsageError", "MissingPermission")
    }

    packaging {
        // Solo arm64-v8a y x86_64. El AAR de sherpa-onnx trae para x86 un libonnxruntime.so propio que choca con el de
        // ONNX Runtime; al descartar x86 y armeabi-v7a no llega a empaquetarse (pickFirst por si el fusionador lo ve antes).
        jniLibs {
            excludes += setOf("lib/x86/**", "lib/armeabi-v7a/**", "lib/x86_64/**")
            pickFirsts += setOf("lib/x86/libonnxruntime.so")
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/INDEX.LIST",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
            )
        }
    }
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "androidx.compose.animation.ExperimentalAnimationApi",
            "androidx.compose.ui.ExperimentalComposeUiApi",
            "androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi",
            "androidx.media3.common.util.UnstableApi",
            "androidx.media3.common.util.ExperimentalApi",
            "kotlinx.coroutines.ExperimentalCoroutinesApi",
            "kotlinx.coroutines.FlowPreview",
            "kotlinx.serialization.ExperimentalSerializationApi",
            "coil3.annotation.ExperimentalCoilApi",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3.adaptive)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.inspector)
    implementation(libs.androidx.media3.inspector.frame)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)

    implementation(libs.androidx.emoji2.emojipicker)

    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.video)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // IA en el dispositivo. sherpa-onnx 1.13.8 (la 2.x quitó espeak-ng, que necesitan las voces Piper) con
    // ONNX Runtime enlazado estáticamente: no choca con el libonnxruntime.so de Maven.
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"))
    implementation(libs.onnxruntime.android)
    implementation(libs.mediapipe.tasks.vision)
    // Segmentación de sujetos de Google (ML Kit, modelo descargado por Google Play services; no va en el APK).
    implementation(libs.mlkit.subject)
    implementation(libs.commons.compress)
}
