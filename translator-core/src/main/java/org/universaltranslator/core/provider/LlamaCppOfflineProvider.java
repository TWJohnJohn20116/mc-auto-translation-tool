package org.universaltranslator.core.provider;

import org.universaltranslator.core.TranslationProvider;
import org.universaltranslator.core.TranslationRequest;
import org.universaltranslator.core.TranslationProviderStatus;
import org.universaltranslator.core.offline.OfflineEngineAsset;
import org.universaltranslator.core.offline.SafeArchiveExtractor;
import org.universaltranslator.core.offline.VerifiedDownloader;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Fully local provider using a loopback-only llama.cpp child process. */
public final class LlamaCppOfflineProvider
        implements TranslationProvider, TranslationProviderStatus, AutoCloseable {
    public static final String DEFAULT_MODEL_ID = "qwen2.5-0.5b-instruct-q4-k-m";
    public static final String DEFAULT_MODEL_FILE = "qwen2.5-0.5b-instruct-q4_k_m.gguf";
    public static final URI DEFAULT_MODEL_URI = URI.create(
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/"
                    + "872f8a96064a1242ac3a3359cad77c3042548405/" + DEFAULT_MODEL_FILE);
    public static final URI DEFAULT_MODEL_CHINA_URI = URI.create(
            "https://modelscope.cn/models/qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/master/"
                    + DEFAULT_MODEL_FILE);
    public static final long DEFAULT_MODEL_SIZE = 491_400_032L;
    public static final String DEFAULT_MODEL_SHA256 =
            "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db";
    public static final String QUALITY_MODEL_ID = "qwen2.5-1.5b-instruct-q4-k-m";
    public static final String QUALITY_MODEL_FILE = "qwen2.5-1.5b-instruct-q4_k_m.gguf";
    public static final URI QUALITY_MODEL_URI = URI.create(
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/"
                    + "62a8d092b0a1047016f3edbd0fde387598727aa5/" + QUALITY_MODEL_FILE);
    public static final URI QUALITY_MODEL_CHINA_URI = URI.create(
            "https://modelscope.cn/models/qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/master/"
                    + QUALITY_MODEL_FILE);
    public static final long QUALITY_MODEL_SIZE = 1_117_320_736L;
    public static final String QUALITY_MODEL_SHA256 =
            "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e";

    private final Path root;
    private final boolean autoDownload;
    private final String modelId;
    private final String modelFile;
    private final URI modelUri;
    private final URI modelChinaUri;
    private final long modelSize;
    private final String modelSha256;
    private volatile String status = "等待首次离线翻译";
    private volatile Process process;
    private volatile OpenAiChatTranslationProvider localApi;
    private volatile Thread shutdownHook;

    public LlamaCppOfflineProvider(Path root, boolean autoDownload) {
        this(root, autoDownload, DEFAULT_MODEL_ID, DEFAULT_MODEL_FILE,
                DEFAULT_MODEL_CHINA_URI, DEFAULT_MODEL_URI, DEFAULT_MODEL_SIZE, DEFAULT_MODEL_SHA256);
    }

    public static LlamaCppOfflineProvider forModel(Path root, boolean autoDownload, String selection) {
        String normalized = selection == null ? "lite" : selection.trim().toLowerCase(java.util.Locale.ROOT);
        if ("lite".equals(normalized) || DEFAULT_MODEL_ID.equals(normalized)) {
            return new LlamaCppOfflineProvider(root, autoDownload);
        }
        if ("quality".equals(normalized) || QUALITY_MODEL_ID.equals(normalized)) {
            return new LlamaCppOfflineProvider(root, autoDownload, QUALITY_MODEL_ID, QUALITY_MODEL_FILE,
                    QUALITY_MODEL_CHINA_URI, QUALITY_MODEL_URI, QUALITY_MODEL_SIZE, QUALITY_MODEL_SHA256);
        }
        throw new IllegalArgumentException("Unsupported offline model: " + selection);
    }

    public LlamaCppOfflineProvider(
            Path root,
            boolean autoDownload,
            String modelId,
            String modelFile,
            URI modelUri,
            long modelSize,
            String modelSha256
    ) {
        this(root, autoDownload, modelId, modelFile, null, modelUri, modelSize, modelSha256);
    }

    private LlamaCppOfflineProvider(
            Path root,
            boolean autoDownload,
            String modelId,
            String modelFile,
            URI modelChinaUri,
            URI modelUri,
            long modelSize,
            String modelSha256
    ) {
        if (root == null) {
            throw new IllegalArgumentException("Offline model directory is required");
        }
        this.root = root.toAbsolutePath().normalize();
        this.autoDownload = autoDownload;
        this.modelId = requireSimpleName("model id", modelId);
        this.modelFile = requireSimpleName("model file", modelFile);
        this.modelChinaUri = modelChinaUri;
        this.modelUri = modelUri;
        this.modelSize = modelSize;
        this.modelSha256 = modelSha256;
    }

    @Override
    public String id() {
        return "offline-llama:" + modelId;
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public String translate(TranslationRequest request) throws Exception {
        try {
            ensureRunning();
            OpenAiChatTranslationProvider api = localApi;
            if (api == null) {
                throw new IllegalStateException("Offline translation process is not ready");
            }
            status = "离线模型运行中";
            return api.translate(request);
        } catch (Exception error) {
            status = "离线翻译失败：" + safeMessage(error);
            throw error;
        }
    }

    private synchronized void ensureRunning() throws Exception {
        if (process != null && process.isAlive() && localApi != null) {
            return;
        }
        closeProcess();
        Files.createDirectories(root);
        Path server = ensureEngine();
        Path model = ensureModel();
        int port = reserveLoopbackPort();
        Path log = root.resolve("llama-server.log");
        int processors = Runtime.getRuntime().availableProcessors();
        // The model shares the machine with Minecraft's render thread. Two inference
        // threads are enough for this small model and avoid sustained frame drops on
        // legacy clients when a busy lobby exposes many labels at once.
        int threads = Math.max(1, Math.min(2, processors / 2));
        status = "正在启动离线模型";
        ProcessBuilder builder = new ProcessBuilder(
                server.toString(),
                "-m", model.toString(),
                "--host", "127.0.0.1",
                "--port", Integer.toString(port),
                "--alias", "universal-translator-local",
                "--ctx-size", "1024",
                "--parallel", "1",
                "--threads", Integer.toString(threads),
                "--threads-batch", Integer.toString(threads));
        builder.directory(server.getParent().toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        process = builder.start();
        registerShutdownHook();
        try {
            waitUntilHealthy(port, process, 90_000L);
        } catch (Exception startupFailure) {
            closeProcess();
            throw startupFailure;
        }
        localApi = new OpenAiChatTranslationProvider(
                "http://127.0.0.1:" + port + "/v1/chat/completions",
                "", "universal-translator-local", "offline-loopback",
                new org.universaltranslator.core.net.HttpJsonClient(1_000, 15_000));
        status = "离线模型已就绪";
    }

    private Path ensureEngine() throws IOException {
        OfflineEngineAsset asset = OfflineEngineAsset.current();
        Path engineRoot = root.resolve("engines").resolve("b9637-" + asset.platformId);
        Path installed = engineRoot.resolve("installed");
        if (Files.isDirectory(installed)) {
            try {
                return SafeArchiveExtractor.findServer(installed);
            } catch (IOException ignored) {
                deleteTree(installed);
            }
        }
        if (!autoDownload) {
            throw new IOException("Offline engine is not installed and automatic download is disabled");
        }
        status = "正在下载离线引擎（约 " + Math.max(1L, asset.size / 1_000_000L) + " MB）";
        Path archive = engineRoot.resolve(asset.archiveName);
        VerifiedDownloader.download(asset.downloadSources(), archive, asset.size, asset.sha256);
        Path staging = engineRoot.resolve("installing");
        deleteTree(staging);
        Files.createDirectories(staging);
        status = "正在安装离线引擎";
        try {
            SafeArchiveExtractor.extract(archive, staging);
            SafeArchiveExtractor.findServer(staging);
            Files.createDirectories(engineRoot);
            try {
                Files.move(staging, installed, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveUnsupported) {
                Files.move(staging, installed, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            deleteTree(staging);
            throw exception;
        }
        return SafeArchiveExtractor.findServer(installed);
    }

    private Path ensureModel() throws IOException {
        Path model = root.resolve("models").resolve(modelId).resolve(modelFile);
        if (Files.isRegularFile(model)
                && Files.size(model) == modelSize
                && modelSha256.equalsIgnoreCase(VerifiedDownloader.sha256(model))) {
            return model;
        }
        if (!autoDownload) {
            throw new IOException("Offline model is not installed and automatic download is disabled");
        }
        status = "正在下载离线模型（国内源优先，约 "
                + Math.max(1L, modelSize / 1_000_000L) + " MB）";
        List<URI> sources = modelChinaUri == null
                ? java.util.Collections.singletonList(modelUri)
                : Arrays.asList(modelChinaUri, modelUri);
        return VerifiedDownloader.download(sources, model, modelSize, modelSha256);
    }

    private static void waitUntilHealthy(int port, Process child, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        IOException lastFailure = null;
        while (System.currentTimeMillis() < deadline) {
            if (!child.isAlive()) {
                throw new IOException("Offline translation process exited during startup; see llama-server.log");
            }
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) URI.create(
                        "http://127.0.0.1:" + port + "/health").toURL().openConnection();
                connection.setConnectTimeout(500);
                connection.setReadTimeout(1000);
                int response = connection.getResponseCode();
                if (response >= 200 && response < 300) {
                    return;
                }
            } catch (IOException exception) {
                lastFailure = exception;
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            Thread.sleep(250L);
        }
        throw new IOException("Offline model did not become ready within 90 seconds", lastFailure);
    }

    private static int reserveLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1,
                java.net.InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String requireSimpleName(String label, String value) {
        if (value == null || value.trim().isEmpty()
                || value.contains("/") || value.contains("\\") || value.contains("..")) {
            throw new IllegalArgumentException("Invalid " + label);
        }
        return value.trim();
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return error.getClass().getSimpleName();
        }
        String singleLine = message.replace('\n', ' ').replace('\r', ' ').trim();
        return singleLine.length() <= 96 ? singleLine : singleLine.substring(0, 93) + "...";
    }

    @Override
    public synchronized void close() {
        localApi = null;
        closeProcess();
        status = "离线模型已停止";
    }

    private synchronized void registerShutdownHook() {
        if (shutdownHook != null) {
            return;
        }
        Thread hook = new Thread(new Runnable() {
            @Override
            public void run() {
                closeProcess(false);
            }
        }, "universal-translator-offline-shutdown");
        try {
            Runtime.getRuntime().addShutdownHook(hook);
            shutdownHook = hook;
        } catch (IllegalStateException shuttingDown) {
            // The JVM is already stopping. Do not allow a newly-started model
            // process to survive after Minecraft exits.
            closeProcess(false);
        }
    }

    private synchronized void closeProcess() {
        closeProcess(true);
    }

    private synchronized void closeProcess(boolean unregisterHook) {
        Thread hook = shutdownHook;
        shutdownHook = null;
        if (unregisterHook && hook != null && hook != Thread.currentThread()) {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignored) {
                // JVM shutdown has already started; the hook may be running.
            }
        }
        Process child = process;
        process = null;
        if (child == null) {
            return;
        }
        child.destroy();
        try {
            if (!child.waitFor(2L, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                child.waitFor(2L, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            child.destroyForcibly();
        }
    }
}
