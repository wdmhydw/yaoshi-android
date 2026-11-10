package com.k2fsa.sherpa.onnx;

/** Android-only loader for the unmodified v2.0.1 upstream Java bindings. */
final class LibraryLoader {
    private static boolean loaded;
    static synchronized void maybeLoad() {
        if (!loaded) { System.loadLibrary("sherpa-onnx-jni"); loaded = true; }
    }
}
