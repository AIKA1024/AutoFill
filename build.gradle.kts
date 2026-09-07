plugins {
    alias(libs.plugins.agp.app) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
