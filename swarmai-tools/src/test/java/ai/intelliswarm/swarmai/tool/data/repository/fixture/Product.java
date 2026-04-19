package ai.intelliswarm.swarmai.tool.data.repository.fixture;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** Test-only JPA entity isolated in its own package so @EnableJpaRepositories can scope to it. */
@Entity
public class Product {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String sku;
    private String name;
    private int stock;

    public Product() {}
    public Product(String sku, String name, int stock) {
        this.sku = sku;
        this.name = name;
        this.stock = stock;
    }

    public Long getId()     { return id; }
    public String getSku()  { return sku; }
    public String getName() { return name; }
    public int getStock()   { return stock; }
}
