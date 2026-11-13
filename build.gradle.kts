// Archivo de build de nivel superior.
// AGP 9 integra Kotlin: no se aplica el plugin org.jetbrains.kotlin.android.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
