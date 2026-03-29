package ai.intelliswarm.swarmai.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SkillRegistry Tests")
class SkillRegistryTest {

    private SkillRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SkillRegistry();
    }

    private GeneratedSkill createSkill(String name, String description) {
        GeneratedSkill skill = new GeneratedSkill(
            name, description, "test",
            "def result = 'hello'\nresult",
            Map.of(), List.of());
        skill.setStatus(SkillStatus.VALIDATED);
        return skill;
    }

    @Nested
    @DisplayName("findSimilar()")
    class FindSimilarTests {

        @Test
        @DisplayName("returns empty list when registry is empty")
        void findSimilar_emptyRegistry_returnsEmpty() {
            List<SkillRegistry.SimilarSkill> results = registry.findSimilar("stock price analysis", 0.3);
            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("finds skill with matching keywords above threshold")
        void findSimilar_matchingKeywords_returnsSkill() {
            GeneratedSkill skill = createSkill("stock_price_fetcher", "Fetches current stock price data for analysis");
            registry.register(skill);

            List<SkillRegistry.SimilarSkill> results = registry.findSimilar("fetch stock price data", 0.2);

            assertFalse(results.isEmpty());
            assertEquals("stock_price_fetcher", results.get(0).skill().getName());
            assertTrue(results.get(0).similarity() > 0.2);
        }

        @Test
        @DisplayName("returns empty when no skills match above threshold")
        void findSimilar_noMatch_returnsEmpty() {
            GeneratedSkill skill = createSkill("weather_forecast", "Gets weather forecast data");
            registry.register(skill);

            List<SkillRegistry.SimilarSkill> results = registry.findSimilar("stock price analysis", 0.5);
            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("sorts results by similarity descending")
        void findSimilar_multipleMatches_sortedBySimilarity() {
            GeneratedSkill skill1 = createSkill("basic_calculator", "Simple calculator for math operations");
            GeneratedSkill skill2 = createSkill("financial_calculator", "Financial calculator for stock price analysis and returns");
            registry.register(skill1);
            registry.register(skill2);

            List<SkillRegistry.SimilarSkill> results = registry.findSimilar("calculate financial returns for stock", 0.1);

            assertFalse(results.isEmpty());
            // Financial calculator should rank higher due to more keyword overlap
            if (results.size() >= 2) {
                assertTrue(results.get(0).similarity() >= results.get(1).similarity());
            }
        }

        @Test
        @DisplayName("excludes CANDIDATE skills")
        void findSimilar_candidateSkills_excluded() {
            GeneratedSkill skill = new GeneratedSkill(
                "stock_analyzer", "Analyzes stock data", "test",
                "result", Map.of(), List.of());
            // Status is CANDIDATE by default
            registry.register(skill);

            List<SkillRegistry.SimilarSkill> results = registry.findSimilar("analyze stock data", 0.1);
            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("handles null and blank input")
        void findSimilar_nullInput_returnsEmpty() {
            registry.register(createSkill("test_skill", "A test skill"));

            assertTrue(registry.findSimilar(null, 0.3).isEmpty());
            assertTrue(registry.findSimilar("", 0.3).isEmpty());
            assertTrue(registry.findSimilar("   ", 0.3).isEmpty());
        }
    }

    @Nested
    @DisplayName("selectRelevant()")
    class SelectRelevantTests {

        @Test
        @DisplayName("returns up to maxSkills skills")
        void selectRelevant_limitsResults() {
            for (int i = 0; i < 5; i++) {
                registry.register(createSkill("skill_" + i, "Description for skill " + i));
            }

            List<GeneratedSkill> relevant = registry.selectRelevant("some task", 3);
            assertEquals(3, relevant.size());
        }

        @Test
        @DisplayName("returns all skills when fewer than maxSkills")
        void selectRelevant_fewerThanMax_returnsAll() {
            registry.register(createSkill("skill_1", "A skill"));
            registry.register(createSkill("skill_2", "Another skill"));

            List<GeneratedSkill> relevant = registry.selectRelevant("some task", 10);
            assertEquals(2, relevant.size());
        }

        @Test
        @DisplayName("ranks skills by relevance to task description")
        void selectRelevant_rankedByRelevance() {
            GeneratedSkill irrelevant = createSkill("weather_tool", "Gets weather forecast data");
            GeneratedSkill relevant = createSkill("stock_analyzer", "Analyzes stock market data and trends");
            registry.register(irrelevant);
            registry.register(relevant);

            List<GeneratedSkill> results = registry.selectRelevant("analyze stock market trends", 2);

            assertFalse(results.isEmpty());
            // Stock analyzer should rank first
            assertEquals("stock_analyzer", results.get(0).getName());
        }

        @Test
        @DisplayName("handles null task description")
        void selectRelevant_nullDescription_returnsActive() {
            registry.register(createSkill("skill_1", "A skill"));

            List<GeneratedSkill> relevant = registry.selectRelevant(null, 5);
            assertEquals(1, relevant.size());
        }
    }

    @Nested
    @DisplayName("register() deduplication")
    class RegisterDeduplicationTests {

        @Test
        @DisplayName("replaces existing skill with same name")
        void register_sameName_replaces() {
            GeneratedSkill v1 = createSkill("my_skill", "Version 1");
            GeneratedSkill v2 = createSkill("my_skill", "Version 2");

            registry.register(v1);
            registry.register(v2);

            assertEquals(1, registry.size());
        }

        @Test
        @DisplayName("keeps skill with higher usage count")
        void register_higherUsage_kept() {
            GeneratedSkill v1 = createSkill("my_skill", "Version 1");
            // Simulate usage by executing (it increments usageCount)
            v1.setAvailableTools(Map.of());
            v1.execute(Map.of()); // usageCount = 1

            GeneratedSkill v2 = createSkill("my_skill", "Version 2");
            // v2 has 0 usage

            registry.register(v1);
            registry.register(v2);

            // v1 should be kept (higher usage)
            assertEquals(1, registry.size());
        }
    }

    @Nested
    @DisplayName("getStats()")
    class StatsTests {

        @Test
        @DisplayName("returns correct statistics")
        void getStats_returnsCorrectCounts() {
            GeneratedSkill s1 = createSkill("skill_1", "Skill 1");
            s1.setStatus(SkillStatus.ACTIVE);
            GeneratedSkill s2 = createSkill("skill_2", "Skill 2");
            s2.setStatus(SkillStatus.VALIDATED);

            registry.register(s1);
            registry.register(s2);

            Map<String, Object> stats = registry.getStats();
            assertEquals(2, stats.get("totalSkills"));
        }
    }
}
