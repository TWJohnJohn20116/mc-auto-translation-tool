package org.universaltranslator.core.offline;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Pinned llama.cpp CPU builds for the desktop platforms supported by the mod. */
public final class OfflineEngineAsset {
    private static final String RELEASE = "b9637";
    private static final String BASE = "https://github.com/ggml-org/llama.cpp/releases/download/"
            + RELEASE + "/";
    private static final String ACCELERATED_BASE = "https://gh-proxy.com/" + BASE;

    public final String platformId;
    public final String archiveName;
    public final URI uri;
    public final URI acceleratedUri;
    public final long size;
    public final String sha256;

    private OfflineEngineAsset(String platformId, String archiveName, long size, String sha256) {
        this.platformId = platformId;
        this.archiveName = archiveName;
        this.uri = URI.create(BASE + archiveName);
        this.acceleratedUri = URI.create(ACCELERATED_BASE + archiveName);
        this.size = size;
        this.sha256 = sha256;
    }

    public static OfflineEngineAsset current() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm64 = arch.contains("aarch64") || arch.contains("arm64");
        boolean x64 = arch.contains("x86_64") || arch.contains("amd64") || arch.contains("x64");
        if (os.contains("mac") && arm64) {
            return asset("macos-arm64", "llama-b9637-bin-macos-arm64.tar.gz", 10_586_927L,
                    "72a93f3e68c31de3e438d462669aad1fcdb423b995e9c41033cc7d27a9a3ac69");
        }
        if (os.contains("mac") && x64) {
            return asset("macos-x64", "llama-b9637-bin-macos-x64.tar.gz", 10_877_158L,
                    "71743f8db0958e7c266cceb7add7b16aa418a964667e471094aa6ae65b9c8298");
        }
        if ((os.contains("linux") || os.contains("unix")) && arm64) {
            return asset("linux-arm64", "llama-b9637-bin-ubuntu-arm64.tar.gz", 12_528_190L,
                    "211d9e9ee738698beb7ca271be82661ae2b5da3fbb489cf7d9e4e6ed601be106");
        }
        if ((os.contains("linux") || os.contains("unix")) && x64) {
            return asset("linux-x64", "llama-b9637-bin-ubuntu-x64.tar.gz", 15_512_345L,
                    "a50ee14f021a9d8e92e30f622f7e3be1318ee1125bb9a9ba8d2025388df48743");
        }
        if (os.contains("win") && arm64) {
            return asset("windows-arm64", "llama-b9637-bin-win-cpu-arm64.zip", 10_846_442L,
                    "db1d3f4c13c08b693f539e100bf6d3a435148b0ffc186b044fdd65d490cc6df7");
        }
        if (os.contains("win") && x64) {
            return asset("windows-x64", "llama-b9637-bin-win-cpu-x64.zip", 16_906_751L,
                    "f7783c2b8c007f95e710ac40f26a24861a80b603b0b739fc54d7c926a4716c1e");
        }
        throw new IllegalStateException("Offline translation is not packaged for " + os + " / " + arch);
    }

    /** A checksum-pinned acceleration source followed by the official GitHub release. */
    public List<URI> downloadSources() {
        return Arrays.asList(acceleratedUri, uri);
    }

    private static OfflineEngineAsset asset(String id, String name, long size, String sha256) {
        return new OfflineEngineAsset(id, name, size, sha256);
    }
}
