plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

group = "com.openpasskey"
version = "0.2.1"

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

            pom {
                name.set("OPK ERC-681 SDK")
                description.set(
                    "Keyless, read-only Kotlin SDK for canonical ERC-681 ERC-20 and native-asset " +
                        "payment requests, CREATE2 receiver derivation, and payment observation."
                )
                url.set("https://github.com/zhangzhongnan928/ERC681-Terminal")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        name.set("Victor Zhang")
                        email.set("v@openpasskey.com")
                    }
                }
                scm {
                    url.set("https://github.com/zhangzhongnan928/ERC681-Terminal")
                    connection.set(
                        "scm:git:https://github.com/zhangzhongnan928/ERC681-Terminal.git"
                    )
                    developerConnection.set(
                        "scm:git:ssh://git@github.com/zhangzhongnan928/ERC681-Terminal.git"
                    )
                }
            }
        }
    }
    repositories {
        maven {
            name = "projectLocal"
            url = layout.buildDirectory.dir("repository").get().asFile.toURI()
        }
    }
}
