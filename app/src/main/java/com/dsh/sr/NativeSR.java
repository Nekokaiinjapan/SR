package com.dsh.sr;

/** Thin JNI bridge. All the heavy lifting lives in native/srjni.cpp. */
public final class NativeSR {

    static {
        System.loadLibrary("srjni");
    }

    private NativeSR() {}

    /** Load a .param/.bin pair. Returns false on failure (see {@link #lastError()}). */
    public static native boolean load(String paramPath, String binPath,
                                      int scale, int threads, boolean vulkan);

    /** ARGB in -> ARGB out, scaled by the model's factor. Null on failure. */
    public static native int[] upscale(int[] argb, int width, int height, int tile);

    public static native String lastError();

    /** Runs a synthetic image through the model and returns a one-line report. */
    public static native String diag(String paramPath, String binPath,
                                     int scale, boolean vulkan);

    /** Probes the already-loaded net (cheap, no second Vulkan instance). */
    public static native String probe();
}
