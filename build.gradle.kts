import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.jvm.tasks.Jar

plugins {
    `java-library`
    id("com.vanniktech.maven.publish") version "0.37.0"
}

group = "io.github.barrrssss"
version = "0.1.1"

java {
    // Gradle 9 and the publishing plugin run on JDK 17 or newer.
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

// Compile against the Java 11 API, not merely to Java 11 bytecode. This prevents
// accidental use of APIs introduced after Java 11 when the build runs on a newer JDK.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
    options.encoding = "UTF-8"
}

tasks.withType<Jar>().configureEach {
    from(rootProject.file("LICENSE")) {
        into("META-INF")
    }
    from(rootProject.file("NOTICE")) {
        into("META-INF")
    }
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Automatic-Module-Name" to "io.github.barrrssss.erasure16",
            "Implementation-Title" to "Erasure16",
            "Implementation-Version" to project.version,
        )
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Test-only. The published library has no runtime dependencies.
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = TestExceptionFormat.FULL
    }

    // The benchmark is opt-in and excluded from ordinary build/check runs.
    providers.systemProperty("erasure16.bench").orNull?.let { value ->
        systemProperty("erasure16.bench", value)
        maxHeapSize = "2g"
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    pom {
        name.set("Erasure16")
        description.set(
            "Systematic Reed-Solomon erasure coding over GF(2^16) with a Cauchy generator matrix."
        )
        inceptionYear.set("2026")
        url.set("https://github.com/barrrssss/erasure16-java")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("barrrssss")
                name.set("barrrssss")
                url.set("https://github.com/barrrssss")
            }
        }
        scm {
            connection.set("scm:git:https://github.com/barrrssss/erasure16-java.git")
            developerConnection.set("scm:git:ssh://git@github.com/barrrssss/erasure16-java.git")
            url.set("https://github.com/barrrssss/erasure16-java")
        }
        issueManagement {
            system.set("GitHub")
            url.set("https://github.com/barrrssss/erasure16-java/issues")
        }
    }
}
