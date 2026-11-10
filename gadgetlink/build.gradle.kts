// gadget-link (docs/gadget-link.md): o celular no papel de gadget Muse.
// Módulo JVM puro, sem nada do Mika, para poder ser extraído depois. Usa só
// java.security / javax.crypto, que existem igual na JVM e no Android (Conscrypt).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
