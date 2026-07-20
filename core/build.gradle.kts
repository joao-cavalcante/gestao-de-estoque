plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    jvm()
    // androidTarget() entra na Fase 4 (app coletor), quando o Android SDK
    // estiver configurado no ambiente de build.

    sourceSets {
        commonMain.dependencies {
            // regras de negócio puras (resolução de código de barras,
            // cubagem, validações de conferência) - sem dependência de
            // framework, pra poder compilar igual pra JVM e Android depois.
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
