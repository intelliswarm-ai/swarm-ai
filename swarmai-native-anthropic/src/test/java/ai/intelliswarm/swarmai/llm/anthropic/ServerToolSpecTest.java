package ai.intelliswarm.swarmai.llm.anthropic;

import com.anthropic.models.messages.ToolUnion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ServerToolSpecTest {

    @Test
    void webSearchUnrestrictedHasNoDomainFilters() {
        var spec = ServerToolSpec.WebSearch.unrestricted();
        assertThat(spec.allowedDomains()).isEmpty();
        assertThat(spec.blockedDomains()).isEmpty();
        assertThat(spec.maxUses()).isNull();
    }

    @Test
    void webSearchAllowOnlyCarriesAllowlist() {
        var spec = ServerToolSpec.WebSearch.allowOnly(List.of("sec.gov", "edgar.sec.gov"));
        assertThat(spec.allowedDomains()).containsExactly("sec.gov", "edgar.sec.gov");
    }

    @Test
    void webSearchMapsToSdkWebSearchTool() {
        var spec = ServerToolSpec.WebSearch.allowOnly(List.of("sec.gov"));
        ToolUnion union = WireMapper.toServerToolUnion(spec);
        assertThat(union.webSearchTool20260209()).isPresent();
        var sdk = union.webSearchTool20260209().get();
        assertThat(sdk.allowedDomains()).hasValueSatisfying(d ->
                assertThat(d).containsExactly("sec.gov"));
    }

    @Test
    void webFetchUnrestrictedMapsToSdkWebFetchTool() {
        ToolUnion union = WireMapper.toServerToolUnion(ServerToolSpec.WebFetch.unrestricted());
        assertThat(union.webFetchTool20260209()).isPresent();
    }

    @Test
    void codeExecutionMapsToSdkCodeExecutionTool() {
        ToolUnion union = WireMapper.toServerToolUnion(new ServerToolSpec.CodeExecution());
        assertThat(union.codeExecutionTool20260120()).isPresent();
    }

    @Test
    void memoryMapsToSdkMemoryTool() {
        ToolUnion union = WireMapper.toServerToolUnion(new ServerToolSpec.Memory());
        assertThat(union.memoryTool20250818()).isPresent();
    }

    @Test
    void requestBuilderAcceptsServerTools() {
        AnthropicRequest req = AnthropicRequest.builder()
                .model("claude-opus-4-7")
                .message(AnthropicMessage.userText("Search SEC for ACME"))
                .serverTool(ServerToolSpec.WebSearch.allowOnly(List.of("sec.gov")))
                .serverTool(new ServerToolSpec.CodeExecution())
                .build();
        assertThat(req.serverTools()).hasSize(2);
        assertThat(req.serverTools().get(0)).isInstanceOf(ServerToolSpec.WebSearch.class);
        assertThat(req.serverTools().get(1)).isInstanceOf(ServerToolSpec.CodeExecution.class);
    }

    @Test
    void requestBuilderListVariant() {
        AnthropicRequest req = AnthropicRequest.builder()
                .model("claude-opus-4-7")
                .message(AnthropicMessage.userText("hi"))
                .serverTools(List.of(
                        ServerToolSpec.WebSearch.unrestricted(),
                        ServerToolSpec.WebFetch.unrestricted(),
                        new ServerToolSpec.Memory()))
                .build();
        assertThat(req.serverTools()).hasSize(3);
    }
}
