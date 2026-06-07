package ai.intelliswarm.swarmai.llm.anthropic.batch;

import ai.intelliswarm.swarmai.agent.llm.CachePolicy;
import ai.intelliswarm.swarmai.agent.llm.DocumentBlock;
import ai.intelliswarm.swarmai.api.PublicApi;
import ai.intelliswarm.swarmai.llm.anthropic.AnthropicClientException;
import ai.intelliswarm.swarmai.llm.anthropic.AnthropicConfig;
import ai.intelliswarm.swarmai.llm.anthropic.AnthropicMessage;
import ai.intelliswarm.swarmai.llm.anthropic.AnthropicNativeClient;
import ai.intelliswarm.swarmai.llm.anthropic.AnthropicRequest;
import ai.intelliswarm.swarmai.llm.anthropic.AnthropicResponse;
import ai.intelliswarm.swarmai.llm.anthropic.AnthropicRole;
import ai.intelliswarm.swarmai.llm.anthropic.ServerToolSpec;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ToolChoice;
import com.anthropic.models.messages.ToolChoiceAny;
import com.anthropic.models.messages.ToolChoiceAuto;
import com.anthropic.models.messages.ToolChoiceNone;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.batches.MessageBatch;
import com.anthropic.models.messages.batches.MessageBatchIndividualResponse;
import com.anthropic.models.messages.batches.MessageBatchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * Anthropic <a href="https://docs.claude.com/en/api/messages-batches">Message
 * Batches</a> client. Submits up to 100,000 messages or 256MB per batch and
 * processes them asynchronously at 50% of the standard request price.
 *
 * <p>Typical flow:
 * <pre>{@code
 * AnthropicBatchClient batch = AnthropicBatchClient.fromEnv();
 * BatchHandle h = batch.submit(items);
 * while (h.processingStatus() != BatchProcessingStatus.ENDED) {
 *     Thread.sleep(60_000);
 *     h = batch.status(h.id());
 * }
 * for (BatchResult r : batch.results(h.id())) {
 *     switch (r.outcome()) {
 *         case BatchItemOutcome.Succeeded ok -> handle(r.customId(), ok.response());
 *         case BatchItemOutcome.Errored err -> log.warn("{} failed: {}", r.customId(), err.message());
 *         case BatchItemOutcome.Canceled c  -> log.info("{} cancelled", r.customId());
 *         case BatchItemOutcome.Expired  e  -> log.warn("{} expired before processing", r.customId());
 *     }
 * }
 * }</pre>
 *
 * <p>The per-item translation mirrors {@link AnthropicNativeClient}'s
 * single-shot path: caching breakpoints, extended thinking (enabled or
 * adaptive), citations, server-side tools, and tool-choice settings all
 * propagate through.
 */
@PublicApi(since = "1.0.24")
public final class AnthropicBatchClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicBatchClient.class);

    private final AnthropicClient client;

    AnthropicBatchClient(AnthropicClient client) {
        this.client = client;
    }

    public static AnthropicBatchClient from(AnthropicConfig config) {
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
        return new AnthropicBatchClient(b.build());
    }

    public static AnthropicBatchClient fromEnv() {
        return new AnthropicBatchClient(AnthropicOkHttpClient.fromEnv());
    }

    /** Test seam — inject a pre-built SDK client (typically a Mockito mock). */
    public static AnthropicBatchClient withClient(AnthropicClient client) {
        return new AnthropicBatchClient(client);
    }

    // ---------- Public API ----------

    /**
     * Submit a batch. Anthropic returns immediately with a {@link BatchHandle}
     * whose {@link BatchHandle#processingStatus()} is {@link
     * BatchProcessingStatus#IN_PROGRESS}. Poll {@link #status(String)} until
     * it transitions to {@link BatchProcessingStatus#ENDED}.
     */
    public BatchHandle submit(List<BatchItem> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("batch must contain at least one item");
        }
        BatchCreateParams.Builder b = BatchCreateParams.builder();
        for (BatchItem item : items) {
            b.addRequest(toBatchCreateRequest(item));
        }
        try {
            MessageBatch batch = client.messages().batches().create(b.build());
            return toHandle(batch);
        } catch (RuntimeException e) {
            throw new AnthropicClientException(
                    "Anthropic batch submit failed: " + e.getMessage(), e, null, null);
        }
    }

    /** Re-fetch the server-side status of a previously-submitted batch. */
    public BatchHandle status(String batchId) {
        try {
            return toHandle(client.messages().batches().retrieve(batchId));
        } catch (RuntimeException e) {
            throw new AnthropicClientException(
                    "Anthropic batch retrieve failed: " + e.getMessage(), e, null, null);
        }
    }

    /**
     * Request cancellation of a batch. Items already running continue to
     * completion; items not yet dispatched move to the {@code canceled}
     * bucket. Status transitions {@code IN_PROGRESS → CANCELING → ENDED}.
     */
    public BatchHandle cancel(String batchId) {
        try {
            return toHandle(client.messages().batches().cancel(batchId));
        } catch (RuntimeException e) {
            throw new AnthropicClientException(
                    "Anthropic batch cancel failed: " + e.getMessage(), e, null, null);
        }
    }

    /**
     * Download the per-item outcomes of an ended batch. The SDK exposes this
     * as a JSON-lines stream backed by a {@link com.anthropic.core.http.StreamResponse}
     * (AutoCloseable) — we consume the entire stream inside try-with-resources
     * to guarantee the underlying HTTP body is fully drained before close,
     * then return a materialised list for ergonomic access.
     *
     * <p>For batches with tens of thousands of responses where materialising
     * the full list would burn memory, see {@link #resultsForEach(String, java.util.function.Consumer)}.
     */
    public List<BatchResult> results(String batchId) {
        List<BatchResult> out = new ArrayList<>();
        resultsForEach(batchId, out::add);
        return out;
    }

    /**
     * Streaming variant — invokes {@code consumer} once per result without
     * buffering. The underlying {@link com.anthropic.core.http.StreamResponse}
     * is opened, drained, and closed within this call, so callers don't need
     * to manage lifecycle.
     */
    public void resultsForEach(String batchId, java.util.function.Consumer<BatchResult> consumer) {
        try (com.anthropic.core.http.StreamResponse<MessageBatchIndividualResponse> stream =
                     client.messages().batches().resultsStreaming(batchId)) {
            Iterator<MessageBatchIndividualResponse> it = stream.stream().iterator();
            while (it.hasNext()) {
                consumer.accept(toBatchResult(it.next()));
            }
        } catch (RuntimeException e) {
            throw new AnthropicClientException(
                    "Anthropic batch results failed: " + e.getMessage(), e, null, null);
        }
    }

    /** Permanently delete a finished batch's records server-side. */
    public boolean delete(String batchId) {
        try {
            client.messages().batches().delete(batchId);
            return true;
        } catch (RuntimeException e) {
            log.warn("Batch delete {} failed: {}", batchId, e.getMessage());
            return false;
        }
    }

    // ---------- Translation: handle + result ----------

    static BatchHandle toHandle(MessageBatch sdk) {
        com.anthropic.models.messages.batches.MessageBatchRequestCounts c = sdk.requestCounts();
        BatchRequestCounts counts = new BatchRequestCounts(
                c.processing(), c.succeeded(), c.errored(), c.canceled(), c.expired());
        BatchProcessingStatus status = switch (sdk.processingStatus().known()) {
            case IN_PROGRESS -> BatchProcessingStatus.IN_PROGRESS;
            case CANCELING -> BatchProcessingStatus.CANCELING;
            case ENDED -> BatchProcessingStatus.ENDED;
        };
        return new BatchHandle(
                sdk.id(),
                status,
                counts,
                sdk.createdAt().toInstant(),
                sdk.endedAt().map(java.time.OffsetDateTime::toInstant),
                sdk.expiresAt().toInstant(),
                sdk.resultsUrl());
    }

    private BatchResult toBatchResult(MessageBatchIndividualResponse sdk) {
        MessageBatchResult result = sdk.result();
        BatchItemOutcome outcome;
        if (result.isSucceeded()) {
            Message msg = result.asSucceeded().message();
            // Pass null for request docs because batch results don't carry the
            // original request — citation locations are still resolvable via
            // the offsets returned by the model itself.
            AnthropicResponse resp = new AnthropicResponse(
                    msg.id(),
                    msg.model().asString(),
                    ai.intelliswarm.swarmai.llm.anthropic.WireMapper.fromStopReason(msg.stopReason()),
                    ai.intelliswarm.swarmai.llm.anthropic.WireMapper.fromContentBlocks(msg.content(), List.of()),
                    ai.intelliswarm.swarmai.llm.anthropic.WireMapper.fromUsage(msg.usage()));
            outcome = new BatchItemOutcome.Succeeded(resp);
        } else if (result.isErrored()) {
            com.anthropic.models.ErrorResponse er = result.asErrored().error();
            outcome = new BatchItemOutcome.Errored(
                    er.error()._json().toString(),
                    er.error().toString());
        } else if (result.isCanceled()) {
            outcome = new BatchItemOutcome.Canceled();
        } else {
            outcome = new BatchItemOutcome.Expired();
        }
        return new BatchResult(sdk.customId(), outcome);
    }

    // ---------- Translation: AnthropicRequest → batch sub-request ----------
    //
    // MAINTAIN: keep field-for-field in sync with AnthropicNativeClient.toSdkParams().
    // The two SDK builders (MessageCreateParams.Builder and BatchCreateParams.Request.Params.Builder)
    // have parity but no shared Kotlin interface — duplication is the lesser evil
    // vs. reflection or codegen.

    static BatchCreateParams.Request toBatchCreateRequest(BatchItem item) {
        AnthropicRequest req = item.request();
        BatchCreateParams.Request.Params.Builder b = BatchCreateParams.Request.Params.builder()
                .model(Model.of(req.model()))
                .maxTokens(req.maxOutputTokens());

        if (req.temperature() != null) {
            b.temperature(req.temperature());
        }

        if (req.system() != null) {
            Optional<CacheControlEphemeral> sysCache =
                    ai.intelliswarm.swarmai.llm.anthropic.WireMapper.cacheFor(req.cachePolicy(), "system");
            if (sysCache.isPresent()) {
                TextBlockParam.Builder tb = TextBlockParam.builder().text(req.system());
                sysCache.ifPresent(tb::cacheControl);
                b.systemOfTextBlockParams(List.of(tb.build()));
            } else {
                b.system(req.system());
            }
        }

        if (!req.tools().isEmpty() || !req.serverTools().isEmpty()) {
            Optional<CacheControlEphemeral> toolsCache =
                    ai.intelliswarm.swarmai.llm.anthropic.WireMapper.cacheFor(req.cachePolicy(), "tools");
            List<ToolUnion> sdkTools =
                    new ArrayList<>(req.tools().size() + req.serverTools().size());
            for (ServerToolSpec st : req.serverTools()) {
                sdkTools.add(ai.intelliswarm.swarmai.llm.anthropic.WireMapper.toServerToolUnion(st));
            }
            for (int i = 0; i < req.tools().size(); i++) {
                Optional<CacheControlEphemeral> cc =
                        (i == req.tools().size() - 1) ? toolsCache : Optional.empty();
                sdkTools.add(ai.intelliswarm.swarmai.llm.anthropic.WireMapper
                        .toToolUnion(req.tools().get(i), cc));
            }
            b.tools(sdkTools);
        }

        switch (req.toolChoice()) {
            case AUTO -> b.toolChoice(ToolChoice.ofAuto(
                    ToolChoiceAuto.builder()
                            .disableParallelToolUse(req.disableParallelToolUse())
                            .build()));
            case ANY -> b.toolChoice(ToolChoice.ofAny(
                    ToolChoiceAny.builder()
                            .disableParallelToolUse(req.disableParallelToolUse())
                            .build()));
            case NONE -> b.toolChoice(ToolChoice.ofNone(ToolChoiceNone.builder().build()));
        }

        Optional<CacheControlEphemeral> docsCache =
                ai.intelliswarm.swarmai.llm.anthropic.WireMapper.cacheFor(req.cachePolicy(), "documents");
        boolean documentsAttached = false;
        for (int i = 0; i < req.messages().size(); i++) {
            AnthropicMessage msg = req.messages().get(i);
            List<DocumentBlock> docsForThis;
            Optional<CacheControlEphemeral> lastDocCache;
            if (!documentsAttached && msg.role() == AnthropicRole.USER && !req.documents().isEmpty()) {
                docsForThis = req.documents();
                lastDocCache = docsCache;
                documentsAttached = true;
            } else {
                docsForThis = List.of();
                lastDocCache = Optional.empty();
            }
            Optional<CacheControlEphemeral> msgCache =
                    cacheForMessage(req.cachePolicy(), i, req.messages().size());
            MessageParam mp = ai.intelliswarm.swarmai.llm.anthropic.WireMapper
                    .toMessageParam(msg, docsForThis, lastDocCache, msgCache);
            b.addMessage(mp);
        }

        ai.intelliswarm.swarmai.llm.anthropic.WireMapper.toThinkingConfig(req.thinkingBudget())
                .ifPresent(b::thinking);
        ai.intelliswarm.swarmai.llm.anthropic.WireMapper.toOutputConfig(req.thinkingBudget())
                .ifPresent(b::outputConfig);

        return BatchCreateParams.Request.builder()
                .customId(item.customId())
                .params(b.build())
                .build();
    }

    private static Optional<CacheControlEphemeral> cacheForMessage(
            CachePolicy policy, int index, int total) {
        for (ai.intelliswarm.swarmai.agent.llm.CacheBreakpoint bp : policy.breakpoints()) {
            int resolved = ai.intelliswarm.swarmai.llm.anthropic.WireMapper
                    .resolveMessageIndex(bp.slot(), total);
            if (resolved == index) {
                return Optional.of(ai.intelliswarm.swarmai.llm.anthropic.WireMapper
                        .toCacheControl(bp.tier()));
            }
        }
        return Optional.empty();
    }
}
