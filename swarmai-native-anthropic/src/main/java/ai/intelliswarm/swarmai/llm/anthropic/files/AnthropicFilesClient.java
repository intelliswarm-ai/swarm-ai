package ai.intelliswarm.swarmai.llm.anthropic.files;

import ai.intelliswarm.swarmai.api.PublicApi;
import ai.intelliswarm.swarmai.llm.anthropic.AnthropicClientException;
import ai.intelliswarm.swarmai.llm.anthropic.AnthropicConfig;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.beta.AnthropicBeta;
import com.anthropic.models.beta.files.FileDeleteParams;
import com.anthropic.models.beta.files.FileDownloadParams;
import com.anthropic.models.beta.files.FileListParams;
import com.anthropic.models.beta.files.FileMetadata;
import com.anthropic.models.beta.files.FileRetrieveMetadataParams;
import com.anthropic.models.beta.files.FileUploadParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Anthropic <a href="https://docs.claude.com/en/api/files">Files API</a> client.
 * Uploads files to your Anthropic workspace where they live until explicitly
 * deleted; downstream message calls can then reference the resulting
 * {@link AnthropicFile#id() file_id} instead of re-uploading base64'd content
 * on every request.
 *
 * <p>This wraps the SDK's beta surface
 * ({@code client.beta().files()}) — the Files API itself is in beta as of
 * 2026-Q1 and requires the {@code files-api-2025-04-14} beta header, which
 * this client sets automatically.
 *
 * <p>Typical lifecycle:
 * <pre>{@code
 * AnthropicFilesClient files = AnthropicFilesClient.fromEnv();
 * AnthropicFile uploaded = files.upload(Path.of("acme-10k.pdf"));
 * // uploaded.id() ≈ "file_abc123…" — pass this to subsequent message calls
 * try {
 *     for (AnthropicFile f : files.list()) {
 *         log.info("{} ({} bytes)", f.filename(), f.sizeBytes());
 *     }
 * } finally {
 *     files.delete(uploaded.id());
 * }
 * }</pre>
 *
 * <p>The current SwarmAI native-adapter substrate doesn't yet expose
 * {@code file_id} as a {@code DocumentBlock} variant — that integration
 * requires moving the messages call onto the beta surface and is a
 * future change. Until then this client is most useful for managing the
 * file lifecycle (upload, list, delete) ahead of caller code that does
 * the message dispatch via the raw SDK.
 */
@PublicApi(since = "1.0.24")
public final class AnthropicFilesClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicFilesClient.class);

    private final AnthropicClient client;

    AnthropicFilesClient(AnthropicClient client) {
        this.client = client;
    }

    public static AnthropicFilesClient from(AnthropicConfig config) {
        AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder();
        if (config.apiKey() != null) {
            b.apiKey(config.apiKey());
        } else {
            b.fromEnv();
        }
        if (config.baseUrl() != null) {
            b.baseUrl(config.baseUrl());
        }
        b.maxRetries(config.maxRetries());
        b.timeout(config.requestTimeout());
        return new AnthropicFilesClient(b.build());
    }

    public static AnthropicFilesClient fromEnv() {
        return new AnthropicFilesClient(AnthropicOkHttpClient.fromEnv());
    }

    /** Test seam — inject a pre-built SDK client (typically a Mockito mock). */
    public static AnthropicFilesClient withClient(AnthropicClient client) {
        return new AnthropicFilesClient(client);
    }

    // ---------- Public API ----------

    /** Upload a file from the local filesystem. */
    public AnthropicFile upload(Path path) {
        try {
            FileUploadParams params = FileUploadParams.builder()
                    .addBeta(AnthropicBeta.FILES_API_2025_04_14)
                    .file(path)
                    .build();
            return toAnthropicFile(client.beta().files().upload(params));
        } catch (RuntimeException e) {
            throw new AnthropicClientException(
                    "Anthropic file upload (" + path + ") failed: " + e.getMessage(),
                    e, null, null);
        }
    }

    /** Upload raw bytes — caller supplies the logical filename via the input. */
    public AnthropicFile upload(InputStream content) {
        try {
            FileUploadParams params = FileUploadParams.builder()
                    .addBeta(AnthropicBeta.FILES_API_2025_04_14)
                    .file(content)
                    .build();
            return toAnthropicFile(client.beta().files().upload(params));
        } catch (RuntimeException e) {
            throw new AnthropicClientException(
                    "Anthropic file upload failed: " + e.getMessage(), e, null, null);
        }
    }

    /** List every file in the caller's workspace (current page only). */
    public List<AnthropicFile> list() {
        try {
            FileListParams params = FileListParams.builder()
                    .addBeta(AnthropicBeta.FILES_API_2025_04_14)
                    .build();
            List<AnthropicFile> out = new ArrayList<>();
            for (FileMetadata md : client.beta().files().list(params).data()) {
                out.add(toAnthropicFile(md));
            }
            return out;
        } catch (RuntimeException e) {
            throw new AnthropicClientException(
                    "Anthropic file list failed: " + e.getMessage(), e, null, null);
        }
    }

    /** Re-fetch the server-side metadata for a single file. */
    public AnthropicFile metadata(String fileId) {
        try {
            FileRetrieveMetadataParams params = FileRetrieveMetadataParams.builder()
                    .fileId(fileId)
                    .addBeta(AnthropicBeta.FILES_API_2025_04_14)
                    .build();
            return toAnthropicFile(client.beta().files().retrieveMetadata(params));
        } catch (RuntimeException e) {
            throw new AnthropicClientException(
                    "Anthropic file metadata (" + fileId + ") failed: " + e.getMessage(),
                    e, null, null);
        }
    }

    /**
     * Download a file's bytes. Returns the raw payload; caller decides how
     * to interpret it (PDF, text, binary, …). The underlying HTTP body is
     * fully buffered before this method returns.
     */
    public byte[] download(String fileId) {
        FileDownloadParams params = FileDownloadParams.builder()
                .fileId(fileId)
                .addBeta(AnthropicBeta.FILES_API_2025_04_14)
                .build();
        try (com.anthropic.core.http.HttpResponse resp = client.beta().files().download(params);
             InputStream body = resp.body()) {
            return body.readAllBytes();
        } catch (IOException e) {
            throw new AnthropicClientException(
                    "Anthropic file download (" + fileId + ") failed: " + e.getMessage(),
                    e, null, null);
        } catch (RuntimeException e) {
            throw new AnthropicClientException(
                    "Anthropic file download (" + fileId + ") failed: " + e.getMessage(),
                    e, null, null);
        }
    }

    /** Convenience: download and write to disk. */
    public void download(String fileId, Path destination) {
        byte[] bytes = download(fileId);
        try {
            Files.write(destination, bytes);
        } catch (IOException e) {
            throw new AnthropicClientException(
                    "Anthropic file download write (" + destination + ") failed: " + e.getMessage(),
                    e, null, null);
        }
    }

    /** Permanently delete a file. Returns {@code true} when the server confirms. */
    public boolean delete(String fileId) {
        try {
            FileDeleteParams params = FileDeleteParams.builder()
                    .fileId(fileId)
                    .addBeta(AnthropicBeta.FILES_API_2025_04_14)
                    .build();
            client.beta().files().delete(params);
            return true;
        } catch (RuntimeException e) {
            log.warn("File delete {} failed: {}", fileId, e.getMessage());
            return false;
        }
    }

    // ---------- Translation ----------

    static AnthropicFile toAnthropicFile(FileMetadata md) {
        return new AnthropicFile(
                md.id(),
                md.filename(),
                md.mimeType(),
                md.sizeBytes(),
                md.createdAt().toInstant(),
                md.downloadable().orElse(false));
    }
}
