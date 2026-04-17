package ai.intelliswarm.swarmai.selfimproving.config;

import ai.intelliswarm.swarmai.process.ProcessType;
import ai.intelliswarm.swarmai.selfimproving.ledger.LedgerStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfImprovementAutoConfigurationTest {

    @Test
    void evolutionMatchRequiresSameWorkflowShapeAndConfiguredProcess() {
        LedgerStore.StoredEvolution evolution = new LedgerStore.StoredEvolution(
                "swarm-1",
                "PROCESS_TYPE_CHANGE",
                "independent tasks are parallelizable",
                "{\"processType\":\"SEQUENTIAL\",\"taskCount\":3,\"maxDependencyDepth\":0,\"agentCount\":2,\"configuration\":{}}",
                "{\"processType\":\"SEQUENTIAL\",\"taskCount\":3,\"maxDependencyDepth\":0,\"agentCount\":2,\"configuration\":{\"processType\":\"PARALLEL\"}}",
                Instant.now()
        );

        assertTrue(SelfImprovementAutoConfiguration.evolutionMatchesCurrentWorkflow(
                evolution, ProcessType.SEQUENTIAL, 3, 0));
        assertFalse(SelfImprovementAutoConfiguration.evolutionMatchesCurrentWorkflow(
                evolution, ProcessType.HIERARCHICAL, 3, 0));
        assertFalse(SelfImprovementAutoConfiguration.evolutionMatchesCurrentWorkflow(
                evolution, ProcessType.SEQUENTIAL, 4, 0));
        assertFalse(SelfImprovementAutoConfiguration.evolutionMatchesCurrentWorkflow(
                evolution, ProcessType.SEQUENTIAL, 3, 1));
    }

    @Test
    void evolutionMatchRejectsMalformedJson() {
        LedgerStore.StoredEvolution malformed = new LedgerStore.StoredEvolution(
                "swarm-2",
                "PROCESS_TYPE_CHANGE",
                "bad payload",
                "{bad-json",
                "{\"configuration\":{\"processType\":\"PARALLEL\"}}",
                Instant.now()
        );

        assertFalse(SelfImprovementAutoConfiguration.evolutionMatchesCurrentWorkflow(
                malformed, ProcessType.SEQUENTIAL, 2, 0));
    }
}
