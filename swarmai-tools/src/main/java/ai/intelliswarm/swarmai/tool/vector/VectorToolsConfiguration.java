package ai.intelliswarm.swarmai.tool.vector;

import ai.intelliswarm.swarmai.tool.common.config.SpringAiToolBindingSupport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.function.Function;

/**
 * Spring AI function-bean registrations for the {@code vector} tool category:
 * Pinecone (later: Qdrant, Weaviate, Milvus, pgvector).
 */
@Configuration
public class VectorToolsConfiguration {

    @Bean
    @Description("Query, upsert, delete, or inspect a Pinecone vector index. Vectors must be pre-embedded.")
    public Function<PineconeVectorTool.Request, String> pinecone(PineconeVectorTool tool) {
        return SpringAiToolBindingSupport.bind(tool);
    }
}
