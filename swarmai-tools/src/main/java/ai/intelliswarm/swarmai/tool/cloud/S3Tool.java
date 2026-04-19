package ai.intelliswarm.swarmai.tool.cloud;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import ai.intelliswarm.swarmai.tool.base.ToolRequirements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * AWS S3 Tool — list objects, read a text object, write a text object, delete an object, head metadata.
 *
 * <p>Credentials use the AWS default credential chain (env vars, profile, IAM role, etc.).
 * Region comes from {@code AWS_REGION} env var or the {@code swarmai.tools.s3.region}
 * property, with fallback to the SDK's default provider.
 *
 * <p>Optional {@code swarmai.tools.s3.endpoint-override} lets tests point at LocalStack
 * / MinIO. When set, {@code path-style-access} is automatically enabled.
 *
 * <p>Permission level = {@code WORKSPACE_WRITE} because put/delete mutate buckets.
 */
@Component
public class S3Tool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(S3Tool.class);
    private static final int DEFAULT_MAX_KEYS = 50;
    private static final int MAX_MAX_KEYS = 1000;
    private static final int READ_MAX_BYTES = 1_048_576; // 1 MiB — prevent gigantic reads in agent context

    @Value("${swarmai.tools.s3.region:}")
    private String region = "";

    @Value("${swarmai.tools.s3.endpoint-override:}")
    private String endpointOverride = "";

    private final Supplier<S3Client> clientFactory;

    public S3Tool() {
        this(null); // lazy — client is built on first use from resolved region
    }

    /**
     * Test-friendly constructor. Passing a non-null supplier bypasses region/endpoint resolution
     * and returns your client directly (useful for LocalStack / mocked S3 in tests).
     */
    S3Tool(Supplier<S3Client> clientFactory) {
        this.clientFactory = clientFactory;
    }

    @Override public String getFunctionName() { return "s3_object"; }

    @Override
    public String getDescription() {
        return "Work with AWS S3: list objects under a prefix, read a small text object (≤1MiB), " +
               "write a text object, head metadata, or delete. Uses the AWS default credential " +
               "chain (env vars, profile, IAM role). Region defaults to AWS_REGION.";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String operation = asString(parameters.getOrDefault("operation", "list")).toLowerCase();
        String bucket = asString(parameters.get("bucket"));
        if (bucket == null || bucket.isBlank()) {
            return "Error: 'bucket' parameter is required.";
        }
        logger.info("S3Tool: op={} bucket={}", operation, bucket);

        try (S3Client s3 = buildClient()) {
            return switch (operation) {
                case "list"   -> list(s3, bucket, parameters);
                case "get"    -> get(s3, bucket, parameters);
                case "put"    -> put(s3, bucket, parameters);
                case "head"   -> head(s3, bucket, parameters);
                case "delete" -> delete(s3, bucket, parameters);
                default -> "Error: unknown operation '" + operation +
                           "'. Use 'list', 'get', 'put', 'head', or 'delete'.";
            };
        } catch (NoSuchBucketException e) {
            return "Error: S3 bucket '" + bucket + "' does not exist.";
        } catch (NoSuchKeyException e) {
            return "Error: S3 object not found in '" + bucket + "'.";
        } catch (S3Exception e) {
            return "Error: S3 returned " + e.statusCode() + " " + e.awsErrorDetails().errorCode() +
                   " — " + e.awsErrorDetails().errorMessage();
        } catch (Exception e) {
            logger.error("S3Tool unexpected error", e);
            return "Error: " + e.getClass().getSimpleName() + " — " + e.getMessage();
        }
    }

    // ---------- operations ----------

    private String list(S3Client s3, String bucket, Map<String, Object> parameters) {
        String prefix = asString(parameters.getOrDefault("prefix", ""));
        int maxKeys = parseInt(parameters, "max_keys", DEFAULT_MAX_KEYS, 1, MAX_MAX_KEYS);

        ListObjectsV2Request.Builder req = ListObjectsV2Request.builder()
            .bucket(bucket).maxKeys(maxKeys);
        if (prefix != null && !prefix.isBlank()) req.prefix(prefix);

        ListObjectsV2Response resp = s3.listObjectsV2(req.build());
        List<S3Object> items = resp.contents();

        if (items.isEmpty()) {
            return "No objects in s3://" + bucket + (prefix == null || prefix.isBlank() ? "" : "/" + prefix);
        }
        StringBuilder out = new StringBuilder();
        out.append("Objects in s3://").append(bucket);
        if (prefix != null && !prefix.isBlank()) out.append("/").append(prefix);
        out.append(" (").append(items.size()).append(" of ")
           .append(resp.keyCount()).append("):\n\n");
        for (S3Object o : items) {
            out.append(String.format("• %-60s  %10d B  %s%n",
                truncate(o.key(), 60), o.size(), o.lastModified()));
        }
        if (Boolean.TRUE.equals(resp.isTruncated())) {
            out.append("\n… results truncated. Increase 'max_keys' (current=").append(maxKeys).append(").\n");
        }
        return out.toString().trim();
    }

    private String get(S3Client s3, String bucket, Map<String, Object> parameters) {
        String key = asString(parameters.get("key"));
        if (key == null || key.isBlank()) return "Error: 'key' is required for operation='get'.";

        // HEAD first to enforce the size cap cheaply — spares bandwidth on giant objects.
        HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        long size = head.contentLength() == null ? 0L : head.contentLength();
        if (size > READ_MAX_BYTES) {
            return "Error: object is " + size + " bytes (> " + READ_MAX_BYTES + " cap). " +
                   "Use a more specific prefix, or pre-process with a script.";
        }

        ResponseBytes<GetObjectResponse> resp = s3.getObjectAsBytes(
            GetObjectRequest.builder().bucket(bucket).key(key).build());
        String charsetName = asString(parameters.getOrDefault("charset", "UTF-8"));
        Charset charset;
        try { charset = Charset.forName(charsetName); }
        catch (Exception e) { charset = StandardCharsets.UTF_8; }

        String contentType = head.contentType() == null ? "application/octet-stream" : head.contentType();
        String body = resp.asString(charset);
        return "s3://" + bucket + "/" + key + " (" + size + " B, " + contentType + ")\n\n" + body;
    }

    private String put(S3Client s3, String bucket, Map<String, Object> parameters) {
        String key = asString(parameters.get("key"));
        String content = asString(parameters.get("content"));
        if (key == null || key.isBlank() || content == null) {
            return "Error: 'key' and 'content' are required for operation='put'.";
        }
        String contentType = asString(parameters.getOrDefault("content_type", "text/plain; charset=utf-8"));

        PutObjectRequest req = PutObjectRequest.builder()
            .bucket(bucket).key(key).contentType(contentType).build();
        s3.putObject(req, RequestBody.fromString(content, StandardCharsets.UTF_8));
        return "Wrote " + content.getBytes(StandardCharsets.UTF_8).length + " bytes to s3://" + bucket + "/" + key;
    }

    private String head(S3Client s3, String bucket, Map<String, Object> parameters) {
        String key = asString(parameters.get("key"));
        if (key == null || key.isBlank()) return "Error: 'key' is required for operation='head'.";

        HeadObjectResponse h = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        StringBuilder out = new StringBuilder();
        out.append("**s3://").append(bucket).append("/").append(key).append("**\n");
        out.append("Size:          ").append(h.contentLength()).append(" B\n");
        out.append("Content-Type:  ").append(h.contentType() == null ? "(none)" : h.contentType()).append('\n');
        out.append("ETag:          ").append(h.eTag()).append('\n');
        out.append("Last-Modified: ").append(h.lastModified()).append('\n');
        if (h.metadata() != null && !h.metadata().isEmpty()) {
            out.append("User metadata:\n");
            h.metadata().forEach((k, v) -> out.append("  ").append(k).append(": ").append(v).append('\n'));
        }
        return out.toString().trim();
    }

    private String delete(S3Client s3, String bucket, Map<String, Object> parameters) {
        String key = asString(parameters.get("key"));
        if (key == null || key.isBlank()) return "Error: 'key' is required for operation='delete'.";

        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        return "Deleted s3://" + bucket + "/" + key;
    }

    // ---------- client wiring ----------

    private S3Client buildClient() {
        if (clientFactory != null) return clientFactory.get();
        S3ClientBuilder builder = S3Client.builder();
        String effRegion = firstNonBlank(region, System.getenv("AWS_REGION"), System.getenv("AWS_DEFAULT_REGION"));
        if (effRegion != null) builder.region(Region.of(effRegion));
        String ep = firstNonBlank(endpointOverride, System.getenv("AWS_ENDPOINT_URL_S3"));
        if (ep != null && !ep.isBlank()) {
            builder.endpointOverride(URI.create(ep));
            builder.forcePathStyle(true);
        }
        return builder.build();
    }

    // ---------- helpers ----------

    private static int parseInt(Map<String, Object> parameters, String key, int def, int min, int max) {
        Object raw = parameters.get(key);
        if (raw == null) return def;
        try {
            int n = raw instanceof Number ? ((Number) raw).intValue() : Integer.parseInt(raw.toString().trim());
            return Math.max(min, Math.min(max, n));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static String asString(Object v) { return v == null ? null : v.toString(); }
    private static String truncate(String s, int n) { return s == null || s.length() <= n ? s : s.substring(0, n - 1) + "…"; }

    // ---------- metadata ----------

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();

        Map<String, Object> operation = new HashMap<>();
        operation.put("type", "string");
        operation.put("enum", List.of("list", "get", "put", "head", "delete"));
        operation.put("description", "S3 operation. Default: 'list'.");
        props.put("operation", operation);

        addStringProp(props, "bucket", "S3 bucket name (required).");
        addStringProp(props, "key", "Object key (required for get/put/head/delete).");
        addStringProp(props, "prefix", "Prefix filter for 'list'.");
        addStringProp(props, "content", "Text body for 'put'. Binary not supported here.");
        addStringProp(props, "content_type", "Content-Type for 'put'. Default: 'text/plain; charset=utf-8'.");
        addStringProp(props, "charset", "Charset for decoding 'get'. Default: UTF-8.");

        Map<String, Object> maxKeys = new HashMap<>();
        maxKeys.put("type", "integer");
        maxKeys.put("description", "Max keys for 'list' (1–" + MAX_MAX_KEYS + "). Default: " + DEFAULT_MAX_KEYS + ".");
        props.put("max_keys", maxKeys);

        schema.put("properties", props);
        schema.put("required", new String[]{"bucket"});
        return schema;
    }

    private static void addStringProp(Map<String, Object> props, String name, String desc) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", "string");
        m.put("description", desc);
        props.put(name, m);
    }

    @Override public boolean isAsync() { return false; }
    @Override public boolean isCacheable() { return false; }
    @Override public String getCategory() { return "cloud"; }
    @Override public List<String> getTags() { return List.of("aws", "s3", "storage", "cloud"); }
    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.WORKSPACE_WRITE; }

    @Override
    public String getTriggerWhen() {
        return "User references an AWS S3 bucket: list objects under a prefix, read a known object, " +
               "write results somewhere, or delete a file.";
    }

    @Override
    public String getAvoidWhen() {
        return "Storage is local filesystem (use file_read/file_write) or a different cloud (GCS/Azure Blob).";
    }

    @Override
    public ToolRequirements getRequirements() {
        return ToolRequirements.builder().env("AWS_REGION").build();
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return Map.of("type", "markdown",
            "description", "Object listing, file contents, head metadata, or post-action confirmation.");
    }

    @Override
    public String smokeTest() {
        // Minimal probe: instantiate a client. We don't list a real bucket since we don't
        // know which one the user has. If instantiation fails (e.g. region unresolvable),
        // surface it here.
        try (S3Client s3 = buildClient()) {
            if (s3 == null) return "S3 client could not be created";
            return null;
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            return "S3 client build failed: " + e.getMessage();
        }
    }

    public record Request(String operation, String bucket, String key, String prefix, String content,
                          String content_type, String charset, Integer max_keys) {}
}
