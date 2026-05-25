# swarmai-rag

Retrieval-Augmented Generation primitives for the SwarmAI framework.

This module fills a gap in `swarmai-tools`: there was a read-only `SemanticSearchTool`
but no way for an agent to **download a PDF**, **chunk and embed it**, or **write into the
vector store**. swarmai-rag adds those, plus three additional academic-paper search tools.

## What's inside

| Class | Purpose |
|---|---|
| `LuceneVectorStore` | Spring AI `VectorStore` implementation backed by Apache Lucene's `KnnFloatVectorField`. Persists to disk. |
| `RagIngestTool` (`rag_ingest`) | Read a local PDF/TXT/MD, chunk via `TokenTextSplitter`, embed via the project's `EmbeddingModel`, write into the `VectorStore`. |
| `RagSearchTool` (`rag_search`) | Citation-aware top-K retrieval over the `VectorStore`. |
| `PdfDownloadTool` (`pdf_download`) | HTTP GET a binary file to a local directory (used before `rag_ingest`). |
| `SemanticScholarTool` (`semantic_scholar_search`) | Semantic Scholar Graph API, no key required. |
| `OpenAlexTool` (`openalex_search`) | OpenAlex (~240M works), no key required; honors `OPENALEX_MAILTO`. |
| `PubMedTool` (`pubmed_search`) | NCBI E-utilities for biomedical literature; honors `NCBI_API_KEY`. |

## Auto-configuration

`SwarmaiRagAutoConfiguration` creates a `LuceneVectorStore` bean automatically when:

- an `EmbeddingModel` bean is present (e.g. Spring AI OpenAI / Ollama starter on the classpath), and
- no other `VectorStore` bean is defined.

It then registers all six tools as Spring beans, picked up by SwarmAI's tool registry.

To use Chroma / PGVector / Pinecone instead of Lucene, define your own `VectorStore` bean —
the module will pick it up and skip the Lucene default.

## Configuration

```yaml
swarmai:
  rag:
    index-path: ./.swarmai-rag-index   # where Lucene persists
    papers-dir: ./papers               # where pdf_download saves files
```

## A typical research-agent loop

1. `arxiv_search` / `semantic_scholar_search` / `openalex_search` / `pubmed_search` — find papers
2. `pdf_download` — save the PDFs to `./papers/`
3. `rag_ingest path=... source=arxiv:2401.12345` — chunk, embed, index
4. `rag_search query="..."` — pull evidence with citations
