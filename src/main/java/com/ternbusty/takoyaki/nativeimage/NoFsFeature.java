package com.ternbusty.takoyaki.nativeimage;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization;

/**
 * Quarkus-style attempt to strip the {@code java.nio.file.FileSystem} chain
 * from the image. The Phase 3 Fs sweep removed every application-level call
 * to {@code java.nio.file.*}, but the framework still landed in the image
 * because {@code com.oracle.svm.core.jdk.Target_java_nio_file_FileSystems}
 * runs {@code FileSystems.getDefault()} at build time and snapshots the
 * default {@code LinuxFileSystem} into the heap.
 *
 * <p>This feature pushes the relevant classes to run-time initialization so
 * the build-time snapshot does not include their static state. The classes
 * are still reachable, but the instances move out of the image heap.
 *
 * <p>If anything in SubstrateVM truly requires {@code FileSystems.getDefault()}
 * to be ready at build time (e.g. for resource lookup against {@code jrt:/})
 * the build will refuse and we will know exactly what to substitute or leave
 * alone.
 */
public final class NoFsFeature implements Feature {

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        // We CANNOT push java.io.UnixFileSystem, sun.nio.fs.LinuxFileSystem
        // or java.nio.file.FileSystems to runtime init: SubstrateVM itself
        // snapshots their instances into the image heap (Target_java_io_File
        // and Target_java_nio_file_FileSystems), and marking them runtime-only
        // contradicts that and the build refuses.
        //
        // What we CAN try is the optional providers that SVM doesn't strictly
        // need for itself:
        forceRuntimeInit("jdk.internal.jrtfs.JrtFileSystemProvider");
        forceRuntimeInit("jdk.nio.zipfs.ZipFileSystemProvider");
    }

    private static void forceRuntimeInit(String fqcn) {
        try {
            Class<?> c = Class.forName(fqcn, false, NoFsFeature.class.getClassLoader());
            RuntimeClassInitialization.initializeAtRunTime(c);
        } catch (ClassNotFoundException ignored) {
            // Not on this JDK build; just skip.
        }
    }
}
