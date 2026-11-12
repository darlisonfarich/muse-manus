// gadget-link (docs/gadget-link.md): o celular no papel de gadget Muse.
// Módulo JVM puro, sem nada do Mika, para poder ser extraído depois. Usa java.security /
// javax.crypto (iguais na JVM e no Android) e, só no passo 4, o OkHttp do WebSocket com o Muse.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
