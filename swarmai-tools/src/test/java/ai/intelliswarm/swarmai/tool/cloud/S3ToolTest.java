package ai.intelliswarm.swarmai.tool.cloud;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("S3Tool Unit Tests")
class S3ToolTest {

    private S3Client s3;
    private S3Tool tool;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);
        tool = new S3Tool(() -> s3);
    }

    @Test void functionName() { assertEquals("s3_object", tool.getFunctionName()); }

    @Test void writePermission() {
        assertEquals(ai.intelliswarm.swarmai.tool.base.PermissionLevel.WORKSPACE_WRITE,
            tool.getPermissionLevel());
    }

    // ===== Common validation =====

    @Test
    @DisplayName("bucket is required")
    void bucketRequired() {
        Object out = tool.execute(Map.of("operation", "list"));
        assertTrue(out.toString().startsWith("Error"));
    }

    @Test
    @DisplayName("unknown operation → helpful error")
    void unknownOp() {
        Object out = tool.execute(Map.of("operation", "rename", "bucket", "b1"));
        assertTrue(out.toString().contains("unknown operation"));
    }

    // ===== list =====

    @Test
    @DisplayName("list: formats keys with size and last-modified, and 'of total' count")
    void listFormat() {
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(
            ListObjectsV2Response.builder()
                .keyCount(2)
                .isTruncated(false)
                .contents(
                    S3Object.builder().key("docs/a.txt").size(1024L).lastModified(Instant.parse("2026-01-01T00:00:00Z")).build(),
                    S3Object.builder().key("docs/b.txt").size(2048L).lastModified(Instant.parse("2026-01-02T00:00:00Z")).build()
                )
                .build());

        Object out = tool.execute(Map.of("operation", "list", "bucket", "my-bucket", "prefix", "docs/"));

        String s = out.toString();
        assertTrue(s.contains("s3://my-bucket/docs/"));
        assertTrue(s.contains("docs/a.txt"));
        assertTrue(s.contains("1024 B"));
        assertTrue(s.contains("docs/b.txt"));
        assertTrue(s.contains("2 of 2"));
    }

    @Test
    @DisplayName("list: empty contents → clean message")
    void listEmpty() {
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(
            ListObjectsV2Response.builder().keyCount(0).contents(List.of()).build());

        Object out = tool.execute(Map.of("operation", "list", "bucket", "my-bucket"));
        assertTrue(out.toString().contains("No objects"));
    }

    @Test
    @DisplayName("list: truncation hint when isTruncated=true")
    void listTruncated() {
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(
            ListObjectsV2Response.builder()
                .keyCount(1).isTruncated(true)
                .contents(S3Object.builder().key("a").size(1L).lastModified(Instant.now()).build())
                .build());

        Object out = tool.execute(Map.of("operation", "list", "bucket", "b", "max_keys", 1));
        assertTrue(out.toString().contains("truncated"));
    }

    @Test
    @DisplayName("list: max_keys is passed and clamped to 1000")
    void listMaxKeysClamped() {
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(
            ListObjectsV2Response.builder().keyCount(0).build());

        tool.execute(Map.of("operation", "list", "bucket", "b", "max_keys", 5000));

        ArgumentCaptor<ListObjectsV2Request> captor = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(s3).listObjectsV2(captor.capture());
        assertEquals(1000, captor.getValue().maxKeys());
    }

    // ===== get =====

    @Test
    @DisplayName("get: reads small object, surfaces size + content type")
    void getFormat() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(
            HeadObjectResponse.builder().contentLength(11L).contentType("text/plain").build());
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(
            ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), "hello world".getBytes()));

        Object out = tool.execute(Map.of("operation", "get", "bucket", "b", "key", "greetings.txt"));

        String s = out.toString();
        assertTrue(s.contains("s3://b/greetings.txt"));
        assertTrue(s.contains("11 B"));
        assertTrue(s.contains("text/plain"));
        assertTrue(s.contains("hello world"));
    }

    @Test
    @DisplayName("get: refuses objects larger than 1 MiB")
    void getSizeCap() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(
            HeadObjectResponse.builder().contentLength(10_000_000L).contentType("text/plain").build());

        Object out = tool.execute(Map.of("operation", "get", "bucket", "b", "key", "big.txt"));

        assertTrue(out.toString().contains("> 1048576 cap"));
    }

    @Test
    @DisplayName("get: NoSuchKey → clean 'not found' message")
    void getMissing() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(
            NoSuchKeyException.builder().message("not here").build());

        Object out = tool.execute(Map.of("operation", "get", "bucket", "b", "key", "missing.txt"));
        assertTrue(out.toString().contains("not found"));
    }

    @Test
    @DisplayName("get: missing key → error, no API call")
    void getRequiresKey() {
        Object out = tool.execute(Map.of("operation", "get", "bucket", "b"));
        assertTrue(out.toString().startsWith("Error"));
    }

    // ===== put =====

    @Test
    @DisplayName("put: sends key + content-type and returns byte count")
    void putFormat() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(
            PutObjectResponse.builder().eTag("etag1").build());

        Object out = tool.execute(Map.of(
            "operation", "put",
            "bucket", "b",
            "key", "report.json",
            "content", "{\"ok\":true}",
            "content_type", "application/json"));

        assertTrue(out.toString().contains("Wrote 11 bytes to s3://b/report.json"), out.toString());

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(captor.capture(), any(RequestBody.class));
        assertEquals("b", captor.getValue().bucket());
        assertEquals("report.json", captor.getValue().key());
        assertEquals("application/json", captor.getValue().contentType());
    }

    @Test
    @DisplayName("put: missing key or content → error")
    void putRequiresFields() {
        Object out1 = tool.execute(Map.of("operation", "put", "bucket", "b", "content", "x"));
        assertTrue(out1.toString().startsWith("Error"));
        Object out2 = tool.execute(Map.of("operation", "put", "bucket", "b", "key", "k"));
        assertTrue(out2.toString().startsWith("Error"));
    }

    // ===== head =====

    @Test
    @DisplayName("head: includes size, content type, etag, last-modified, user metadata")
    void headFormat() {
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(
            HeadObjectResponse.builder()
                .contentLength(42L)
                .contentType("image/png")
                .eTag("\"abc\"")
                .lastModified(Instant.parse("2026-01-01T00:00:00Z"))
                .metadata(Map.of("owner", "jane"))
                .build());

        Object out = tool.execute(Map.of("operation", "head", "bucket", "b", "key", "x.png"));

        String s = out.toString();
        assertTrue(s.contains("Size:          42 B"));
        assertTrue(s.contains("Content-Type:  image/png"));
        assertTrue(s.contains("ETag:          \"abc\""));
        assertTrue(s.contains("owner: jane"));
    }

    // ===== delete =====

    @Test
    @DisplayName("delete: confirms path")
    void deleteFormat() {
        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenReturn(DeleteObjectResponse.builder().build());

        Object out = tool.execute(Map.of("operation", "delete", "bucket", "b", "key", "old.txt"));

        assertTrue(out.toString().contains("Deleted s3://b/old.txt"));
    }

    // ===== Error surfaces =====

    @Test
    @DisplayName("NoSuchBucket → friendly error")
    void noBucket() {
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenThrow(
            NoSuchBucketException.builder().message("missing").build());

        Object out = tool.execute(Map.of("operation", "list", "bucket", "ghost"));
        assertTrue(out.toString().contains("does not exist"));
    }

    @Test
    @DisplayName("S3Exception → HTTP code + error message surfaced cleanly")
    void s3Exception() {
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenThrow(
            (S3Exception) S3Exception.builder()
                .statusCode(403)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied").errorMessage("no access").build())
                .message("forbidden")
                .build());

        Object out = tool.execute(Map.of("operation", "list", "bucket", "b"));
        assertTrue(out.toString().contains("403"));
        assertTrue(out.toString().contains("AccessDenied"));
    }
}
