package ai.intelliswarm.swarmai.rag.config;

import ai.intelliswarm.swarmai.rag.store.LuceneVectorStore;
import ai.intelliswarm.swarmai.rag.tool.OpenAlexTool;
import ai.intelliswarm.swarmai.rag.tool.PdfDownloadTool;
import ai.intelliswarm.swarmai.rag.tool.PubMedTool;
import ai.intelliswarm.swarmai.rag.tool.RagIngestTool;
import ai.intelliswarm.swarmai.rag.tool.RagSearchTool;
import ai.intelliswarm.swarmai.rag.tool.SemanticScholarTool;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Auto-wires the swarmai-rag module:
 *
 * <ul>
 *     <li>A {@link LuceneVectorStore} bean rooted at {@code swarmai.rag.index-path}
 *         (default {@code ./.swarmai-rag-index}) — only created if no other
 *         {@link VectorStore} bean exists and an {@link EmbeddingModel} is available.</li>
 *     <li>The RAG and paper-source tools as Spring beans so swarm-ai's tool registry
 *         picks them up automatically.</li>
 * </ul>
 *
 * <p>To skip the bundled Lucene store and supply your own {@link VectorStore} (Chroma,
 * PGVector, Pinecone), just define that bean in your application — the
 * {@code @ConditionalOnMissingBean} guard will step aside.
 */
@AutoConfiguration
@AutoConfigureAfter(name = {
    // Run after the provider-specific embedding autoconfigs so @ConditionalOnBean(EmbeddingModel.class)
    // reliably sees the EmbeddingModel bean when one is present.
    "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration",
    "org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration"
})
@ConditionalOnClass(VectorStore.class)
public class SwarmaiRagAutoConfiguration {

    @Bean
    @ConditionalOnBean(EmbeddingModel.class)
    @ConditionalOnMissingBean(VectorStore.class)
    public LuceneVectorStore swarmaiRagLuceneVectorStore(
            EmbeddingModel embeddingModel,
            @Value("${swarmai.rag.index-path:./.swarmai-rag-index}") String indexPath) {
        Path path = Paths.get(indexPath).toAbsolutePath().normalize();
        path.toFile().mkdirs();
        return new LuceneVectorStore(path, embeddingModel);
    }

    @Bean
    @ConditionalOnMissingBean
    public RagIngestTool ragIngestTool(VectorStore vectorStore) {
        return new RagIngestTool(vectorStore);
    }

    @Bean
    @ConditionalOnMissingBean
    public RagSearchTool ragSearchTool(VectorStore vectorStore) {
        return new RagSearchTool(vectorStore);
    }

    @Bean
    @ConditionalOnMissingBean
    public PdfDownloadTool pdfDownloadTool(
            @Value("${swarmai.rag.papers-dir:./papers}") String papersDir) {
        return new PdfDownloadTool(
                java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .connectTimeout(java.time.Duration.ofSeconds(15))
                    .build(),
                Paths.get(papersDir).toAbsolutePath().normalize());
    }

    @Bean
    @ConditionalOnMissingBean
    public SemanticScholarTool semanticScholarTool() {
        return new SemanticScholarTool();
    }

    @Bean
    @ConditionalOnMissingBean
    public OpenAlexTool openAlexTool() {
        return new OpenAlexTool();
    }

    @Bean
    @ConditionalOnMissingBean
    public PubMedTool pubMedTool() {
        return new PubMedTool();
    }
}
