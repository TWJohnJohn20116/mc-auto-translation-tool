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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
    /**
     * Guards the {@link #process} and {@link #shutdownHook} fields and the two
     * request/cancel state flags. It is deliberately separate from the instance
     * monitor: a request on the Minecraft render thread (F8 toggle or saving
     * settings) must never wait for a startup that is downloading a model or
     * polling the health endpoint for up to 90 seconds. No blocking wait may ever
     * be performed while this lock is held.
     */
    private final Object lifecycleLock = new Object();
    /**
     * Current request generation. Claiming a startup increments it, so every claim
     * gets a value that is never reused, and {@link #close()} invalidates the claim
     * in progress by incrementing it again. Because a token is never handed out
     * twice, a retiring startup can only ever clear its own claim.
     */
    private final AtomicLong requestGeneration = new AtomicLong();
    /** Token of the startup currently in progress, or -1L when none is. */
    private final AtomicLong activeStartupToken = new AtomicLong(-1L);
    /**
     * Set while a request must be abandoned and its child process reaped, either
     * because {@link #close()} cancelled the startup or because another start
     * replaced it. It is an atomic instead of a plain field because it is read by
     * the startup thread, the close thread and the shutdown hook.
     */
    private final AtomicBoolean stopRequested = new AtomicBoolean();

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

    private void ensureRunning() throws Exception {
        if (isRunning()) {
            return;
        }
        final long token;
        Process stale;
        synchronized (lifecycleLock) {
            // A close() may have completed while this thread waited for the lock.
            // Observe it before starting another process.
            if (stopRequested.get()) {
                throw new IOException("离线模型已停止");
            }
            // Claim the startup so close() and the shutdown hook can interrupt it.
            // Two workers of one session must never run two servers at once, and a
            // second claim while one is in progress must not block the caller.
            if (activeStartupToken.get() >= 0L) {
                throw new IOException("离线模型正在启动中");
            }
            long now = System.currentTimeMillis();
            if (nextStartupAttemptAt > now && !startupFailureMessage.isEmpty()) {
                throw new IOException(startupFailureMessage);
            }
            // Claiming the startup starts a new generation, so a previous startup
            // that is still winding down can never clear this claim.
            token = requestGeneration.incrementAndGet();
            activeStartupToken.set(token);
            stale = dropProcess();
        }
        if (stale != null) {
            // Handing the child to the reaper starts a thread; keep it off the lock.
            stopProcessInBackground(stale);
        }
        try {
            startServer(token);
        } catch (Exception failure) {
            // Every failure path must stop whatever startServer left behind, including
            // the child process when the failure is an interrupt.
            stopProcessInBackgroundIfAny();
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw failure;
            }
            throw failure instanceof IOException
                    ? (IOException) failure
                    : new IOException(safeMessage(failure), failure);
        } finally {
            synchronized (lifecycleLock) {
                // Retire this claim. The token is unique per startup, so this can only
                // ever clear the claim this call installed and never a newer one.
                activeStartupToken.compareAndSet(token, -1L);
            }
        }
    }

    private boolean isRunning() {
        synchronized (lifecycleLock) {
            Process child = process;
            return child != null && child.isAlive() && localApi != null;
        }
    }

    /** Starts the server once. Never holds {@link #lifecycleLock} across a blocking wait. */
    private void startServer(long token) throws Exception {
        requireCurrentStartup(token);
        Files.createDirectories(root);
        Path server = ensureEngine();
        Path model = ensureModel();
        // A close() during a multi-hundred-megabyte download must not be turned into
        // an automatic engine repair or into a five-minute retry backoff.
        requireCurrentStartup(token);
        try {
            startServer(server, model, false, token);
        } catch (OfflineProcessExitedException firstFailure) {
            stopProcessInBackgroundIfAny();
            status = "离线引擎启动失败，正在使用兼容模式重试";
            try {
                startServer(server, model, true, token);
                return;
            } catch (Exception compatibilityFailure) {
                stopProcessInBackgroundIfAny();
                if (isCancelledStartup(compatibilityFailure, token)) {
                    throw compatibilityFailure;
                }
                if (autoDownload && !engineRepairAttempted) {
                    engineRepairAttempted = true;
                    status = "离线引擎启动失败，正在自动修复";
                    deleteTree(engineInstallDirectory());
                    server = ensureEngine();
                    try {
                        startServer(server, model, true, token);
                        return;
                    } catch (Exception repairFailure) {
                        stopProcessInBackgroundIfAny();
                        if (isCancelledStartup(repairFailure, token)) {
                            throw repairFailure;
                        }
                        throw delayStartupRetries(repairFailure);
                    }
                }
                throw delayStartupRetries(compatibilityFailure);
            }
        } catch (Exception startupFailure) {
            stopProcessInBackgroundIfAny();
            if (isCancelledStartup(startupFailure, token)) {
                throw startupFailure;
            }
            throw delayStartupRetries(startupFailure);
        }
    }

    /** True when a startup failed because it was cancelled rather than because of a real fault. */
    private boolean isCancelledStartup(Exception failure, long token) {
        return failure instanceof InterruptedException || !isCurrentStartup(token);
    }

    private void startServer(Path server, Path model, boolean conservativeFileAccess, long token)
            throws Exception {
        // A close() or a replaced startup may have landed between two attempts.
        requireCurrentStartup(token);
        int port = reserveLoopbackPort();
        Path log = root.resolve("llama-server.log");
        long logStart = Files.isRegularFile(log) ? Files.size(log) : 0L;
        Path nativeModel = OfflineProcessSupport.prepareModelPathForNativeProcess(
                model, modelSha256);
        int processors = Runtime.getRuntime().availableProcessors();
        // The model shares the machine with Minecraft's render thread. Two inference
        // threads are enough for this small model and avoid sustained frame drops on
        // legacy clients when a busy lobby exposes many labels at once.
        int threads = Math.max(1, Math.min(2, processors / 2));
        status = "正在启动离线模型";
        List<String> command = new ArrayList<String>(Arrays.asList(
                server.toString(),
                "-m", nativeModel.toString(),
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
        Process child;
        synchronized (lifecycleLock) {
            // Closing the window between the check above and the assignment means a
            // close() that already handed the old process off must win the race: the
            // freshly started child is handed straight back to the reaper instead.
            requireCurrentStartup(token);
            try {
                child = builder.start();
            } catch (IOException startFailure) {
                throw new IOException(
                        OfflineProcessSupport.describeProcessStartFailure(startFailure), startFailure);
            }
            process = child;
            registerShutdownHook();
        }
        try {
            waitUntilHealthy(port, child, 90_000L, log, logStart);
        } catch (Exception startupFailure) {
            stopProcessInBackgroundIfAny();
            throw startupFailure;
        }
        OpenAiChatTranslationProvider api = new OpenAiChatTranslationProvider(
                "http://127.0.0.1:" + port + "/v1/chat/completions",
                "", "universal-translator-local", "offline-loopback",
                new org.universaltranslator.core.net.HttpJsonClient(1_000, 15_000));
        synchronized (lifecycleLock) {
            // Same race for the ready hand-off. A close() may have happened while the
            // model was loading, in which case the child is reaped instead of adopted.
            if (!isCurrentStartup(token) || process != child || !child.isAlive()) {
                throw new IOException("离线模型启动已被取消");
            }
            localApi = api;
            nextStartupAttemptAt = 0L;
            startupFailureMessage = "";
            status = "离线模型已就绪";
        }
    }

    private boolean isCurrentStartup(long token) {
        return requestGeneration.get() == token && activeStartupToken.get() == token;
    }

    /** Fails an obsolete startup as soon as it notices that it was cancelled. */
    private void requireCurrentStartup(long token) throws IOException {
        if (!isCurrentStartup(token) || stopRequested.get()) {
            throw new IOException("离线模型启动已被取消");
        }
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
        Path installed = engineInstallDirectory(asset);
        if (Files.isDirectory(installed)) {
            try {
                return SafeArchiveExtractor.findServer(installed);
            } catch (IOException ignored) {
                deleteTree(installed);
            }
        }
        if (!autoDownload) {
            throw new IOException("离线引擎未安装，且自动下载已关闭");
        }
        status = "正在下载离线引擎（约 " + Math.max(1L, asset.size / 1_000_000L) + " MB）";
        Path archive = engineRoot.resolve(asset.archiveName);
        VerifiedDownloader.download(asset.downloadSources(), archive, asset.size, asset.sha256,
                progressListener("正在下载离线引擎"));
        status = "离线引擎下载并校验完成";
        Path staging = installed.resolveSibling("installing");
        deleteTree(staging);
        Files.createDirectories(staging);
        status = "正在安装离线引擎";
        try {
            SafeArchiveExtractor.extract(archive, staging);
            SafeArchiveExtractor.findServer(staging);
            Files.createDirectories(installed.getParent());
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

    private Path engineInstallDirectory() throws IOException {
        return engineInstallDirectory(OfflineEngineAsset.current());
    }

    private Path engineInstallDirectory(OfflineEngineAsset asset) throws IOException {
        if (asset.platformId.startsWith("android-")) {
            return OfflineProcessSupport.androidEngineInstallDirectory(
                    root, "b9637-" + asset.platformId).resolve("installed");
        }
        return engineRoot(asset).resolve("installed");
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
            throw new IOException("离线模型未安装，且自动下载已关闭");
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
            // A close() or a replaced startup interrupts this poll so the wait can
            // never hold a closing render thread hostage for the full timeout.
            if (Thread.interrupted()) {
                throw new InterruptedException("Offline model startup was cancelled");
            }
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
        String detail = OfflineProcessSupport.readNewLogTail(log, logStart);
        throw new IOException(OfflineProcessSupport.describeStartupTimeout(detail), lastFailure);
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

    /**
     * Stops the model without ever waiting for a startup to finish. It is called
     * from the Minecraft render/tick thread, so it must not block on a download or
     * on the 90-second health poll. Only short state mutations happen here; the
     * child process is handed to the background reaper.
     */
    @Override
    public void close() {
        Process child;
        synchronized (lifecycleLock) {
            // Bump the request generation and clear the claim so the startup in
            // progress sees itself as obsolete, then free the slot so a later start
            // is not blocked by a startup that is still winding down. Everything
            // before its next wait is already abandoned by the generation bump; the
            // wait itself is interrupted below. stopRequested keeps the provider
            // stopped until a session explicitly starts it again.
            requestGeneration.incrementAndGet();
            stopRequested.set(true);
            activeStartupToken.set(-1L);
            child = dropProcess();
        }
        if (child != null) {
            stopProcessInBackground(child);
        }
        status = "离线模型已停止";
    }

    /** Stops the child process in the background. Never blocks on the lifecycle lock. */
    private void stopProcessInBackgroundIfAny() {
        Process child;
        synchronized (lifecycleLock) {
            child = dropProcess();
        }
        if (child != null) {
            stopProcessInBackground(child);
        }
    }

    /** Must be called while holding {@link #lifecycleLock}; never blocks. */
    private Process dropProcess() {
        localApi = null;
        return detachProcess(true);
    }

    private void registerShutdownHook() {
        if (shutdownHook != null) {
            return;
        }
        Thread hook = new Thread(new Runnable() {
            @Override
            public void run() {
                closeProcessForShutdown();
            }
        }, "universal-translator-offline-shutdown");
        try {
            Runtime.getRuntime().addShutdownHook(hook);
            shutdownHook = hook;
        } catch (IllegalStateException | SecurityException shuttingDown) {
            // The JVM is already stopping. Do not allow a newly-started model
            // process to survive after Minecraft exits.
            closeProcessForShutdown();
        }
    }

    /**
     * Quitting the game must not stall JVM shutdown behind a startup that is still
     * downloading or polling. The child is terminated without any wait, so the hook
     * returns immediately and the child still cannot survive Minecraft's exit. Only
     * the short state mutation runs on the lifecycle lock, so the hook can never
     * deadlock against a startup that is holding it.
     */
    private void closeProcessForShutdown() {
        Process child;
        synchronized (lifecycleLock) {
            child = dropProcess();
        }
        if (child != null) {
            // The JVM may halt before a background reaper could escalate, so the
            // forced termination is issued here instead of being waited for.
            child.destroy();
            child.destroyForcibly();
        }
    }

    private Process detachProcess(boolean unregisterHook) {
        Thread hook = shutdownHook;
        shutdownHook = null;
        if (unregisterHook && hook != null && hook != Thread.currentThread()) {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException | SecurityException ignored) {
                // JVM shutdown has already started; the hook may be running.
            }
        }
        Process child = process;
        process = null;
        return child;
    }

    private static void stopProcessInBackground(final Process child) {
        child.destroy();
        if (!child.isAlive()) {
            return;
        }
        Thread reaper = new Thread(new Runnable() {
            @Override
            public void run() {
                stopProcess(child);
            }
        }, "universal-translator-offline-process-reaper");
        try {
            reaper.setDaemon(true);
            reaper.start();
        } catch (RuntimeException unableToStartReaper) {
            child.destroyForcibly();
        }
    }

    private static void stopProcess(Process child) {
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
