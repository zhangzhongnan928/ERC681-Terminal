plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

group = "com.openpasskey"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

java {
    withSourcesJar()
}

dependencies {
    implementation(libs.gson)

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets {
    test {
        resources.srcDir("../../conformance")
    }
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "opk-erc681-sdk"
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "projectLocal"
            url = layout.buildDirectory.dir("repository").get().asFile.toURI()
        }
    }
}
