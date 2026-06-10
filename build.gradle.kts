import org.gradle.api.tasks.Exec

plugins {
    application
    jacoco
    id("org.graalvm.buildtools.native") version "0.11.1"
}

group = "com.ternbusty"
// x-release-please-start-version
version = "0.1.0"
// x-release-please-end

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation("info.picocli:picocli:4.7.7")
    annotationProcessor("info.picocli:picocli-codegen:4.7.7")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.19.0")
    compileOnly("org.graalvm.sdk:nativeimage:25.0.2")

    testImplementation(platform("org.junit:junit-bom:6.1.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:5.20.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.20.0")
}

application {
    mainClass = "com.ternbusty.takoyaki.Main"
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(
        listOf(
            "-Aproject=${project.group}/${project.name}",
            "--enable-preview",
        ),
    )
    options.release = 25
}

tasks.named<JavaExec>("run") {
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--enable-preview")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    // -Xshare:off keeps Mockito's bytecode manipulation happy on recent JDKs;
    // ByteBuddyAgent attach needs to be unconditionally allowed in tests.
    jvmArgs(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED",
        "-XX:+EnableDynamicAgentLoading",
        "-Xshare:off",
    )
    // Contest tests live under com.ternbusty.takoyaki.contest and drive the
    // real takoyaki binary (needs TAKOYAKI_BIN set and Linux). They run via
    // the separate `contestTest` task — not as part of normal `./gradlew test`.
    exclude("com/ternbusty/takoyaki/contest/**")
    finalizedBy("jacocoTestReport")
}

// Integration tests modelled on youki's tests/contest. Each subpackage covers
// one OCI feature (cgroups, hooks, kill, lifecycle, ...). They invoke the
// already-built takoyaki binary against test bundles laid down under @TempDir.
// Skip locally if TAKOYAKI_BIN isn't set; CI sets it after the native build.
val contestTest by tasks.registering(Test::class) {
    description = "Run contest-style integration tests against the real takoyaki binary."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    jvmArgs(
        "--enable-preview",
        "--enable-native-access=ALL-UNNAMED",
        "-XX:+EnableDynamicAgentLoading",
        "-Xshare:off",
    )
    // Only the contest package.
    include("com/ternbusty/takoyaki/contest/**")
    // Propagate the binary path to the test JVM so the harness can find it.
    System.getenv("TAKOYAKI_BIN")?.let { environment("TAKOYAKI_BIN", it) }
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn("test")
    reports {
        xml.required = true
        html.required = true
    }
}

val bootstrapDir = layout.projectDirectory.dir("src/main/c/bootstrap")
val bootstrapBuildDir = layout.buildDirectory.dir("bootstrap")

val buildBootstrap by tasks.registering(Exec::class) {
    val outDir = bootstrapBuildDir.get().asFile
    doFirst { outDir.mkdirs() }
    workingDir = bootstrapDir.asFile
    inputs.dir(bootstrapDir)
    outputs.dir(bootstrapBuildDir)
    commandLine(
        "sh", "-c",
        "gcc -c -fPIC -Wall -Wextra -O2 bootstrap.c -o ${outDir.absolutePath}/bootstrap.o " +
            "&& ar rcs ${outDir.absolutePath}/libbootstrap.a ${outDir.absolutePath}/bootstrap.o",
    )
}

// Pass -Pquick to gradle for a fast (-Ob) development build.
// Without -Pquick, a fully optimized image is produced.
val isQuick = providers.gradleProperty("quick").isPresent

graalvmNative {
    binaries {
        named("main") {
            imageName = "takoyaki"
            mainClass = "com.ternbusty.takoyaki.Main"
            quickBuild = isQuick
            buildArgs.addAll(
                "--no-fallback",
                "-H:+UnlockExperimentalVMOptions",
                "-H:+ForeignAPISupport",
                "-H:NativeLinkerOption=${bootstrapBuildDir.get().asFile.absolutePath}/libbootstrap.a",
                "-H:NativeLinkerOption=-rdynamic",
                "-H:NativeLinkerOption=-Wl,--whole-archive,${bootstrapBuildDir.get().asFile.absolutePath}/libbootstrap.a,--no-whole-archive",
                "-H:NativeLinkerOption=-Wl,--push-state,--no-as-needed,-l:libseccomp.so.2,--pop-state",
                "--features=com.ternbusty.takoyaki.nativeimage.ForeignFeature",
                "--enable-native-access=ALL-UNNAMED",
                "--enable-preview",
            )
        }
    }
}

tasks.named("nativeCompile") {
    dependsOn(buildBootstrap)
}
