package org.universaltranslator.core;

/**
 * The single source of the version string this mod reports in outgoing HTTP requests.
 *
 * <p>The core has no build-generated version resource: {@code translator-core} has no
 * {@code processResources} task and no manifest version, so this value cannot be derived at build
 * time. It must be updated by hand together with {@code gradle.properties}' {@code mod_version};
 * the release checklist in {@code AGENTS.md} lists this step.
 *
 * <p>It deliberately lives in exactly one place. When the two call sites each carried their own
 * copy, one of them silently drifted to {@code 1.1} while the released version was {@code 1.3.11},
 * so every model download announced a version three releases old.
 */
public final class UserAgent {
    /** Sent as the {@code User-Agent} header by every outgoing request the core makes. */
    public static final String VALUE = "MCAutoTranslationTool/1.4";

    private UserAgent() {
    }
}
