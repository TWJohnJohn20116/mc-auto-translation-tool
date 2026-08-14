package org.universaltranslator.core.provider;

import org.universaltranslator.core.TranslationProvider;
import org.universaltranslator.core.TranslationRequest;
import org.universaltranslator.core.TranslationProviderStatus;
import org.universaltranslator.core.OfflineModel;
import org.universaltranslator.core.offline.OfflineEngineAsset;
import org.universaltranslator.core.offline.OfflineProcessSupport;
import org.universaltranslator.core.offline.SafeArchiveExtractor;
import org.universaltranslator.core.offline.VerifiedDownloader;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Fully local provider using a loopback-only llama.cpp child process. */
public final class LlamaCppOfflineProvider
        implements TranslationProvider, TranslationProviderStatus, AutoCloseable {
    private static final long STARTUP_FAILURE_RETRY_MILLIS = 5L * 60L * 1000L;
    public static final String DEFAULT_MODEL_ID = OfflineModel.LITE.modelId();
    public static final String DEFAULT_MODEL_FILE = OfflineModel.LITE.modelFile();
    public static final URI DEFAULT_MODEL_URI = URI.create(
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/"
                    + "872f8a96064a1242ac3a3359cad77c3042548405/" + DEFAULT_MODEL_FILE);
    public static final URI DEFAULT_MODEL_CHINA_URI = URI.create(
            "https://modelscope.cn/models/qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/master/"
                    + DEFAULT_MODEL_FILE);
    public static final long DEFAULT_MODEL_SIZE = OfflineModel.LITE.expectedBytes();
    public static final String DEFAULT_MODEL_SHA256 =
            "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db";
    public static final String QUALITY_MODEL_ID = OfflineModel.QUALITY.modelId();
    public static final String QUALITY_MODEL_FILE = OfflineModel.QUALITY.modelFile();
    public static final URI QUALITY_MODEL_URI = URI.create(
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/"
                    + "62a8d092b0a1047016f3edbd0fde387598727aa5/" + QUALITY_MODEL_FILE);
    public static final URI QUALITY_MODEL_CHINA_URI = URI.create(
            "https://modelscope.cn/models/qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/master/"
                    + QUALITY_MODEL_FILE);
    public static final long QUALITY_MODEL_SIZE = OfflineModel.QUALITY.expectedBytes();
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
    private volatile String progressStage = "";
    private volatile int progressPercent = -1;
    private volatile long nextStartupAttemptAt;
    private volatile String startupFailureMessage = "";
    private boolean engineRepairAttempted;

    public LlamaCppOfflineProvider(Path root, boolean autoDownload) {
        this(root, autoDownload, DEFAULT_MODEL_ID, DEFAULT_MODEL_FILE,
                DEFAULT_MODEL_CHINA_URI, DEFAULT_MODEL_URI, DEFAULT_MODEL_SIZE, DEFAULT_MODEL_SHA256);
    }

    public static LlamaCppOfflineProvider forModel(Path root, boolean autoDownload, String selection) {
        return forModel(root, autoDownload, OfflineModel.fromConfig(selection));
    }

    public static LlamaCppOfflineProvider forModel(
            Path root, boolean autoDownload, OfflineModel selection) {
        OfflineModel normalized = selection == null ? OfflineModel.LITE : selection;
        if (normalized == OfflineModel.LITE) {
            return new LlamaCppOfflineProvider(root, autoDownload);
        }
        if (normalized == OfflineModel.QUALITY) {
            return new LlamaCppOfflineProvider(root, autoDownload, QUALITY_MODEL_ID, QUALITY_MODEL_FILE,
                    QUALITY_MODEL_CHINA_URI, QUALITY_MODEL_URI, QUALITY_MODEL_SIZE, QUALITY_MODEL_SHA256);
        }
        throw new IllegalArgumentException("Unsupported offline model: " + normalized);
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
        long now = System.currentTimeMillis();
        if (nextStartupAttemptAt > now && !startupFailureMessage.isEmpty()) {
            throw new IOException(startupFailureMessage);
        }
        closeProcess();
        Files.createDirectories(root);
        Path server = ensureEngine();
        Path model = ensureModel();
        try {
            startServer(server, model, false);
        } catch (OfflineProcessExitedException firstFailure) {
            closeProcess();
            status = "离线引擎启动失败，正在使用兼容模式重试";
            try {
                startServer(server, model, true);
                return;
            } catch (Exception compatibilityFailure) {
                closeProcess();
                if (autoDownload && !engineRepairAttempted) {
                    engineRepairAttempted = true;
                    status = "离线引擎启动失败，正在自动修复";
                    deleteTree(engineInstallDirectory());
                    server = ensureEngine();
                    try {
                        startServer(server, model, true);
                        return;
                    } catch (Exception repairFailure) {
                        closeProcess();
                        throw delayStartupRetries(repairFailure);
                    }
                }
                throw delayStartupRetries(compatibilityFailure);
            }
        } catch (Exception startupFailure) {
            closeProcess();
            throw delayStartupRetries(startupFailure);
        }
    }

    private void startServer(Path server, Path model, boolean conservativeFileAccess)
            throws Exception {
        int port = reserveLoopbackPort();
        Path log = root.resolve("llama-server.log");
        long logStart = Files.isRegularFile(log) ? Files.size(log) : 0L;
        int processors = Runtime.getRuntime().availableProcessors();
        // The model shares the machine with Minecraft's render thread. Two inference
        // threads are enough for this small model and avoid sustained frame drops on
        // legacy clients when a busy lobby exposes many labels at once.
        int threads = Math.max(1, Math.min(2, processors / 2));
        status = "正在启动离线模型";
        List<String> command = new ArrayList<String>(Arrays.asList(
                server.toString(),
                "-m", model.toString(),
                "--host", "127.0.0.1",
                "--port", Integer.toString(port),
                "--alias", "universal-translator-local",
                "--ctx-size", "1024",
                "--parallel", "1",
                "--threads", Integer.toString(threads),
                "--threads-batch", Integer.toString(threads)));
        OfflineProcessSupport.appendStableModelLoadingArguments(command, conservativeFileAccess);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(server.getParent().toFile());
        OfflineProcessSupport.configureLibraryPath(builder, server.getParent());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        process = builder.start();
        registerShutdownHook();
        try {
            waitUntilHealthy(port, process, 90_000L, log, logStart);
        } catch (Exception startupFailure) {
            closeProcess();
            throw startupFailure;
        }
        localApi = new OpenAiChatTranslationProvider(
                "http://127.0.0.1:" + port + "/v1/chat/completions",
                "", "universal-translator-local", "offline-loopback",
                new org.universaltranslator.core.net.HttpJsonClient(1_000, 15_000));
        nextStartupAttemptAt = 0L;
        startupFailureMessage = "";
        status = "离线模型已就绪";
    }

    private IOException delayStartupRetries(Exception failure) {
        String message = safeMessage(failure) + "；已暂停自动重试 5 分钟";
        startupFailureMessage = message;
        nextStartupAttemptAt = System.currentTimeMillis() + STARTUP_FAILURE_RETRY_MILLIS;
        return new IOException(message, failure);
    }

    private Path ensureEngine() throws IOException {
        OfflineEngineAsset asset = OfflineEngineAsset.current();
        Path engineRoot = engineRoot(asset);
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
        VerifiedDownloader.download(asset.downloadSources(), archive, asset.size, asset.sha256,
                progressListener("正在下载离线引擎"));
        status = "离线引擎下载并校验完成";
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

    private Path engineInstallDirectory() {
        return engineRoot(OfflineEngineAsset.current()).resolve("installed");
    }

    private Path engineRoot(OfflineEngineAsset asset) {
        return root.resolve("engines").resolve("b9637-" + asset.platformId);
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
        Path downloaded = VerifiedDownloader.download(
                sources, model, modelSize, modelSha256, progressListener("正在下载离线模型"));
        status = "离线模型下载并校验完成";
        return downloaded;
    }

    private VerifiedDownloader.ProgressListener progressListener(final String stage) {
        progressStage = stage;
        progressPercent = -1;
        return new VerifiedDownloader.ProgressListener() {
            @Override
            public void onProgress(long downloadedBytes, long totalBytes) {
                if (totalBytes <= 0L) {
                    return;
                }
                int percent = (int) Math.min(100L, downloadedBytes * 100L / totalBytes);
                int previous = progressPercent;
                if (!stage.equals(progressStage) || previous < 0
                        || percent < previous || percent >= 100 || percent >= previous + 5) {
                    progressStage = stage;
                    progressPercent = percent;
                    status = stage + "：" + percent + "%（"
                            + downloadedBytes / 1_000_000L + "/"
                            + totalBytes / 1_000_000L + " MB）";
                }
            }
        };
    }

    private static void waitUntilHealthy(
            int port,
            Process child,
            long timeoutMillis,
            Path log,
            long logStart
    ) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        IOException lastFailure = null;
        while (System.currentTimeMillis() < deadline) {
            if (!child.isAlive()) {
                int exitCode = child.exitValue();
                String detail = OfflineProcessSupport.readNewLogTail(log, logStart);
                throw new OfflineProcessExitedException(
                        OfflineProcessSupport.describeStartupExit(exitCode, detail));
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
        return singleLine.length() <= 160 ? singleLine : singleLine.substring(0, 157) + "...";
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
        localApi = null;
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

    private static final class OfflineProcessExitedException extends IOException {
        private OfflineProcessExitedException(String message) {
            super(message);
        }
    }
}
