package ai.intelliswarm.swarmai.tool.data.repository;

import ai.intelliswarm.swarmai.tool.data.repository.fixture.Product;
import ai.intelliswarm.swarmai.tool.data.repository.fixture.ProductRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for SpringDataRepositoryTool — boots a real Spring context with JPA
 * and H2 and verifies the tool discovers / introspects / invokes a real JpaRepository.
 *
 * The entity and repository live in the {@code .fixture} subpackage so @EnableJpaRepositories
 * can scope exactly to them (avoids conflicts with other test-class nested Repository interfaces).
 *
 * Tagged {@code integration} — slow context boot, excluded from default {@code mvn test}.
 */
@Tag("integration")
@DataJpaTest
@EnableJpaRepositories(basePackageClasses = ProductRepository.class)
@EntityScan(basePackageClasses = Product.class)
@AutoConfigureTestDatabase
@DisplayName("SpringDataRepositoryTool Integration Tests")
class SpringDataRepositoryToolIntegrationTest {

    @Configuration
    @ComponentScan(basePackageClasses = SpringDataRepositoryTool.class)
    static class TestConfig { }

    @Autowired ApplicationContext ctx;
    @Autowired ProductRepository repo;

    private SpringDataRepositoryTool tool;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void seed() {
        repo.deleteAll();
        repo.save(new Product("SKU-1", "Blue Shirt",  42));
        repo.save(new Product("SKU-2", "Red Shirt",    3));
        repo.save(new Product("SKU-3", "Green Hat",    0));
        tool = new SpringDataRepositoryTool(ctx);
    }

    @Test
    @DisplayName("list_repositories: finds the ProductRepository bean in a real Spring context")
    void listRepositoriesReal() {
        Object out = tool.execute(Map.of("operation", "list_repositories"));
        String s = out.toString();
        assertTrue(s.contains("productRepository"), "Expected 'productRepository'. Got:\n" + s);
        assertTrue(s.contains("ProductRepository"), s);
        // Entity type comes from RepositoryFactoryInformation — only real Spring Data sets this up.
        assertTrue(s.contains("Product"), "Expected entity 'Product'. Got:\n" + s);
    }

    @Test
    @DisplayName("list_methods: enumerates custom finders + inherited JpaRepository reads, hides writes")
    void listMethodsReal() {
        Object out = tool.execute(Map.of(
            "operation", "list_methods",
            "repository", "productRepository"));
        String s = out.toString();
        assertTrue(s.contains("findBySku"));
        assertTrue(s.contains("countByStockLessThan"));
        assertTrue(s.contains("findByNameContainingIgnoreCase"));
        assertTrue(s.contains("findAll"), "Inherited JpaRepository reads should appear");
        assertFalse(s.contains("saveAll"), "saveAll should be hidden. Got:\n" + s);
        assertFalse(s.contains("deleteAll"), "deleteAll should be hidden. Got:\n" + s);
    }

    @Test
    @DisplayName("invoke: findBySku returns the seeded Blue Shirt row")
    void invokeFindBySku() throws Exception {
        Object out = tool.execute(Map.of(
            "operation", "invoke",
            "repository", "productRepository",
            "method", "findBySku",
            "args", List.of("SKU-1")));

        JsonNode arr = mapper.readTree(out.toString());
        assertTrue(arr.isArray(), "Expected JSON array. Got:\n" + out);
        assertEquals(1, arr.size());
        assertEquals("Blue Shirt", arr.get(0).path("name").asText());
        assertEquals(42, arr.get(0).path("stock").asInt());
    }

    @Test
    @DisplayName("invoke: countByStockLessThan counts Green Hat + Red Shirt under 10")
    void invokeCountReal() throws Exception {
        Object out = tool.execute(Map.of(
            "operation", "invoke",
            "repository", "productRepository",
            "method", "countByStockLessThan",
            "args", List.of(10)));
        assertEquals(2, mapper.readTree(out.toString()).asInt());
    }

    @Test
    @DisplayName("invoke: findByNameContainingIgnoreCase returns both Shirt rows")
    void invokeSubstringSearch() throws Exception {
        Object out = tool.execute(Map.of(
            "operation", "invoke",
            "repository", "productRepository",
            "method", "findByNameContainingIgnoreCase",
            "args", List.of("shirt")));

        JsonNode arr = mapper.readTree(out.toString());
        assertEquals(2, arr.size(), "Expected 2 matches. Got:\n" + out);
        List<String> names = new java.util.ArrayList<>();
        arr.forEach(n -> names.add(n.path("name").asText()));
        assertTrue(names.contains("Blue Shirt"));
        assertTrue(names.contains("Red Shirt"));
    }

    @Test
    @DisplayName("invoke: findAll returns all 3 seeded rows as JSON array")
    void invokeFindAllReal() throws Exception {
        Object out = tool.execute(Map.of(
            "operation", "invoke",
            "repository", "productRepository",
            "method", "findAll"));

        JsonNode arr = mapper.readTree(out.toString());
        assertEquals(3, arr.size(), "Expected 3 rows. Got:\n" + out);
    }

    @Test
    @DisplayName("invoke: deleteAll is refused by default but runs when allow_writes=true")
    void writeGuardReal() {
        Object refused = tool.execute(Map.of(
            "operation", "invoke",
            "repository", "productRepository",
            "method", "deleteAll",
            "args", List.of()));
        assertTrue(refused.toString().contains("allow_writes=true"));

        Object done = tool.execute(Map.of(
            "operation", "invoke",
            "repository", "productRepository",
            "method", "deleteAll",
            "args", List.of(),
            "allow_writes", true));
        assertEquals("null", done.toString(), "deleteAll returns void");
        assertEquals(0, repo.count(), "Database should actually be empty now");
    }
}
