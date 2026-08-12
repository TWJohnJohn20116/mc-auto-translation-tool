package org.universaltranslator.bundle;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Manifest;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.discovery.DirectoryModCandidateFinder;
import net.fabricmc.loader.impl.discovery.ModCandidateImpl;
import net.fabricmc.loader.impl.discovery.ModDiscoverer;
import net.fabricmc.loader.impl.discovery.ModResolver;
import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.game.patch.GameTransformer;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.launch.MappingConfiguration;
import net.fabricmc.loader.impl.metadata.BuiltinModMetadata;
import net.fabricmc.loader.impl.metadata.DependencyOverrides;
import net.fabricmc.loader.impl.metadata.VersionOverrides;
import net.fabricmc.loader.impl.util.Arguments;

/** Exercises Fabric Loader's real nested-JAR discovery and dependency solver. */
public final class BundleResolutionVerifier {
    private BundleResolutionVerifier() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: <bundle.jar> <minecraft-version>...");
        }

        Path bundle = Path.of(args[0]).toAbsolutePath();
        FabricLauncherBase.setLauncher(new ClientLauncher());

        for (int i = 1; i < args.length; i++) {
            verify(bundle, args[i]);
        }
    }

    private static void verify(Path bundle, String minecraftVersion) throws Exception {
        Path workDir = Files.createTempDirectory("fabric-bundle-resolution-");
        Path modsDir = Files.createDirectory(workDir.resolve("mods"));
        Files.copy(bundle, modsDir.resolve(bundle.getFileName()));

        try {
            FabricLoaderImpl loader = FabricLoaderImpl.INSTANCE;
            loader.setGameProvider(new StubGameProvider(minecraftVersion, workDir));

            VersionOverrides versionOverrides = new VersionOverrides();
            DependencyOverrides dependencyOverrides = new DependencyOverrides(workDir);
            ModDiscoverer discoverer = new ModDiscoverer(versionOverrides, dependencyOverrides);
            discoverer.addCandidateFinder(new DirectoryModCandidateFinder(modsDir, false));

            Map<String, Set<ModCandidateImpl>> envDisabledMods = new java.util.HashMap<>();
            List<ModCandidateImpl> discovered = discoverer.discoverMods(loader, envDisabledMods);
            List<ModCandidateImpl> selected = ModResolver.resolve(discovered, EnvType.CLIENT, envDisabledMods);

            ModCandidateImpl implementation = selected.stream()
                    .filter(candidate -> candidate.getId().equals("universal_translator"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No implementation selected for " + minecraftVersion));

            String localPath = implementation.getLocalPath();
            String expected = minecraftVersion.equals("1.21.11")
                    ? "universal-translator-1.21.11.jar"
                    : "universal-translator-1.21.4-1.21.5.jar";
            if (localPath == null || !localPath.endsWith(expected)) {
                throw new AssertionError("Selected " + localPath + " for " + minecraftVersion
                        + "; expected " + expected);
            }

            System.out.println("Fabric Loader selected " + expected + " for Minecraft " + minecraftVersion);
        } finally {
            deleteTree(workDir);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            });
        }
    }

    private static final class StubGameProvider implements GameProvider {
        private final String minecraftVersion;
        private final Path launchDirectory;
        private final Arguments arguments = new Arguments();

        private StubGameProvider(String minecraftVersion, Path launchDirectory) {
            this.minecraftVersion = minecraftVersion;
            this.launchDirectory = launchDirectory;
        }

        @Override public String getGameId() { return "minecraft"; }
        @Override public String getGameName() { return "Minecraft"; }
        @Override public String getRawGameVersion() { return minecraftVersion; }
        @Override public String getNormalizedGameVersion() { return minecraftVersion; }
        @Override public String getEntrypoint() { return "net.minecraft.client.main.Main"; }
        @Override public Path getLaunchDirectory() { return launchDirectory; }
        @Override public boolean requiresUrlClassLoader() { return false; }
        @Override public Set<BuiltinTransform> getBuiltinTransforms(String className) { return Collections.emptySet(); }
        @Override public boolean isEnabled() { return true; }
        @Override public boolean locateGame(FabricLauncher launcher, String[] args) { return true; }
        @Override public void initialize(FabricLauncher launcher) { }
        @Override public GameTransformer getEntrypointTransformer() { return new GameTransformer(); }
        @Override public void unlockClassPath(FabricLauncher launcher) { }
        @Override public void launch(ClassLoader loader) { }
        @Override public Arguments getArguments() { return arguments; }
        @Override public String[] getLaunchArguments(boolean sanitize) { return new String[0]; }

        @Override
        public Collection<BuiltinMod> getBuiltinMods() {
            return List.of(
                    builtin("minecraft", minecraftVersion),
                    builtin("fabricloader", "0.19.3"),
                    builtin("fabric-api", "1.0.0")
            );
        }

        private BuiltinMod builtin(String id, String version) {
            ModMetadata metadata = new BuiltinModMetadata.Builder(id, version).setName(id).build();
            return new BuiltinMod(Collections.emptyList(), metadata);
        }
    }

    private static final class ClientLauncher implements FabricLauncher {
        @Override public MappingConfiguration getMappingConfiguration() { return null; }
        @Override public void addToClassPath(Path path, String... allowedPrefixes) { }
        @Override public void setAllowedPrefixes(Path path, String... prefixes) { }
        @Override public void setValidParentClassPath(Collection<Path> paths) { }
        @Override public EnvType getEnvironmentType() { return EnvType.CLIENT; }
        @Override public boolean isClassLoaded(String name) { return false; }
        @Override public Class<?> loadIntoTarget(String name) throws ClassNotFoundException { return Class.forName(name); }
        @Override public InputStream getResourceAsStream(String name) { return ClassLoader.getSystemResourceAsStream(name); }
        @Override public ClassLoader getTargetClassLoader() { return ClassLoader.getSystemClassLoader(); }
        @Override public byte[] getClassByteArray(String name, boolean runTransformers) throws IOException {
            throw new IOException("Class bytes are not needed by this verifier");
        }
        @Override public Manifest getManifest(Path originPath) { return null; }
        @Override public boolean isDevelopment() { return false; }
        @Override public String getEntrypoint() { return "net.minecraft.client.main.Main"; }
        @Override public List<Path> getClassPath() { return Collections.emptyList(); }
    }
}
