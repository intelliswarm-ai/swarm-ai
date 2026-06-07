package ai.intelliswarm.swarmai.state.plan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.List;
import java.util.Objects;

/**
 * {@link Replanner} backed by a Spring AI {@link ChatClient}. Renders the
 * current plan via {@link PlanContextRenderer}, asks the LLM for amendments
 * shaped as {@link LlmAmendmentsDto}, then converts the parsed DTO into the
 * core {@link Replanner.Amendments} type.
 *
 * <p>Failure-tolerant by design — every failure mode (LLM call throws,
 * empty response, unparseable response, null DTO) returns
 * {@link Replanner.Amendments#none(String)} with a diagnostic rationale
 * rather than throwing. A replanner that crashes the loop is worse than one
 * that produces no amendments.
 *
 * <p>Defaults to a built-in system prompt suitable for general workflow
 * planning. Override {@link #LlmReplanner(ChatClient, PlanContextRenderer, String)}
 * for domain-specific tuning.
 */
public final class LlmReplanner implements Replanner {

    private static final Logger log = LoggerFactory.getLogger(LlmReplanner.class);

    /** Attribution used on entries this replanner emits. */
    public static final String PROPOSED_BY = "replanner:llm";

    /** Default system prompt — generic enough for most workflow domains. */
    public static final String DEFAULT_SYSTEM_PROMPT = """
            You are a workflow planning assistant. You will be shown:
              - the GOAL the workflow is pursuing
              - the CURRENT PLAN (entries grouped by status)
              - RECENT OBSERVATIONS gathered since the last replan

            Your job is to decide whether the plan needs amendments — extra steps,
            corrections, or replacements — to reach the goal. Be conservative:
            propose changes only when the current plan is genuinely insufficient
            or wrong. If the plan is fine as-is, return empty `newEntries` and
            `supersessions` and explain why in `rationale`.

            When proposing entries:
              - `summary` is a short imperative description (e.g. "verify backup").
              - `risk` is one of LOW, MEDIUM, HIGH, CRITICAL — pick the lowest
                that honestly reflects the action's reversibility and blast radius.
              - `rationale` says why this step is needed.

            When proposing supersessions:
              - `oldId` is the id of an existing entry (verbatim from the plan).
              - `replacementSummary` describes what should replace it.
              - `replacementRisk` is the new entry's risk tier.
              - `rationale` explains why the original was wrong.
            """;

    private final ChatClient chatClient;
    private final PlanContextRenderer renderer;
    private final String systemPrompt;
    private final BeanOutputConverter<LlmAmendmentsDto> outputConverter;

    public LlmReplanner(ChatClient chatClient) {
        this(chatClient, new PlanContextRenderer(), DEFAULT_SYSTEM_PROMPT);
    }

    public LlmReplanner(ChatClient chatClient,
                         PlanContextRenderer renderer,
                         String systemPrompt) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt");
        this.outputConverter = new BeanOutputConverter<>(LlmAmendmentsDto.class);
    }

    @Override
    public Amendments amend(EvolvingPlan plan, ReplanContext ctx) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(ctx, "ctx");

        String userPrompt = buildUserPrompt(ctx, plan);

        String raw;
        try {
            raw = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("LLM call failed in replanner: {}", e.getMessage());
            return Amendments.none("LLM call failed: " + e.getMessage());
        }
        if (raw == null || raw.isBlank()) {
            return Amendments.none("LLM returned empty response");
        }

        LlmAmendmentsDto dto;
        try {
            dto = outputConverter.convert(raw);
        } catch (Exception e) {
            log.warn("LLM output parse failed: {} — raw: {}", e.getMessage(), trimForLog(raw));
            return Amendments.none("LLM output parse failed: " + e.getMessage());
        }
        if (dto == null) {
            return Amendments.none("LLM output parsed to null");
        }

        return convert(dto);
    }

    private String buildUserPrompt(ReplanContext ctx, EvolvingPlan plan) {
        StringBuilder sb = new StringBuilder();

        String goal = ctx.goal() == null || ctx.goal().isBlank() ? "(unspecified)" : ctx.goal();
        sb.append("## Goal\n\n").append(goal).append("\n\n");

        String renderedPlan = renderer.render(plan);
        if (renderedPlan.isBlank()) {
            sb.append("## Current plan\n\n*(plan is empty)*\n\n");
        } else {
            sb.append(renderedPlan).append('\n');
        }

        List<String> observations = ctx.observations();
        if (!observations.isEmpty()) {
            sb.append("## Recent observations\n\n");
            for (String o : observations) {
                sb.append("- ").append(o).append('\n');
            }
            sb.append('\n');
        }

        sb.append("## Task\n\nDecide whether the plan needs amendments. ")
          .append("If it doesn't, return empty `newEntries` and `supersessions` ")
          .append("with a one-line `rationale` saying so.\n\n");

        sb.append(outputConverter.getFormat());

        return sb.toString();
    }

    private Amendments convert(LlmAmendmentsDto dto) {
        Amendments.Builder b = Amendments.builder();
        if (dto.rationale() != null && !dto.rationale().isBlank()) {
            b.rationale(dto.rationale());
        }

        if (dto.newEntries() != null) {
            for (LlmAmendmentsDto.NewEntry ne : dto.newEntries()) {
                if (ne == null) continue;
                if (ne.summary() == null || ne.summary().isBlank()) continue;
                PlanEntry entry = PlanEntry.builder()
                        .proposedBy(PROPOSED_BY)
                        .payload(firstNonBlank(ne.rationale(), ne.summary()))
                        .risk(parseRisk(ne.risk()))
                        .metadata("summary", ne.summary())
                        .build();
                b.addEntry(entry);
            }
        }

        if (dto.supersessions() != null) {
            for (LlmAmendmentsDto.Supersession s : dto.supersessions()) {
                if (s == null) continue;
                if (s.oldId() == null || s.oldId().isBlank()) continue;
                if (s.replacementSummary() == null || s.replacementSummary().isBlank()) continue;
                PlanEntry replacement = PlanEntry.builder()
                        .proposedBy(PROPOSED_BY)
                        .payload(firstNonBlank(s.rationale(), s.replacementSummary()))
                        .risk(parseRisk(s.replacementRisk()))
                        .supersedes(s.oldId())
                        .metadata("summary", s.replacementSummary())
                        .build();
                b.supersede(s.oldId(), replacement);
            }
        }

        return b.build();
    }

    private static Risk parseRisk(String s) {
        if (s == null) return Risk.UNKNOWN;
        try {
            return Risk.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Risk.UNKNOWN;
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b;
    }

    private static String trimForLog(String raw) {
        if (raw.length() <= 200) return raw;
        return raw.substring(0, 200) + "…";
    }
}
