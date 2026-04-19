package ai.intelliswarm.swarmai.tool.data.repository.fixture;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Test-only repository. Lives in the fixture package so @EnableJpaRepositories can scope to it. */
public interface ProductRepository extends JpaRepository<Product, Long> {
    List<Product> findBySku(String sku);
    long countByStockLessThan(int stock);
    List<Product> findByNameContainingIgnoreCase(String fragment);
}
