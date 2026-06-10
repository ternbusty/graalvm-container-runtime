import org.gradle.api.tasks.Exec

plugins {
    application
    id("org.graalvm.buildtools.native") version "0.11.1"
}

group = "com.ternbusty"
version = "0.1.0-SNAPSHOT"

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

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
    jvmArgs("--enable-preview", "--enable-native-access=ALL-UNNAMED")
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
