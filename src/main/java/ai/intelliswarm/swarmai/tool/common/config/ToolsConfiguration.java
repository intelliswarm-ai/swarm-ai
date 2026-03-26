/*
 * SwarmAI Framework - A Java implementation inspired by CrewAI
 *
 * This file is part of SwarmAI, a derivative work based on CrewAI.
 * Original CrewAI: Copyright (c) 2025 crewAI, Inc. (MIT License)
 * SwarmAI adaptations: Copyright (c) 2025 IntelliSwarm.ai (MIT License)
 *
 * Licensed under the MIT License. See LICENSE file for details.
 */
package ai.intelliswarm.swarmai.tool.common.config;

import ai.intelliswarm.swarmai.tool.common.CalculatorTool;
import ai.intelliswarm.swarmai.tool.common.FileReadTool;
import ai.intelliswarm.swarmai.tool.common.FileWriteTool;
import ai.intelliswarm.swarmai.tool.common.DirectoryReadTool;
import ai.intelliswarm.swarmai.tool.common.WebScrapeTool;
import ai.intelliswarm.swarmai.tool.common.HttpRequestTool;
import ai.intelliswarm.swarmai.tool.common.JSONTransformTool;
import ai.intelliswarm.swarmai.tool.common.XMLParseTool;
import ai.intelliswarm.swarmai.tool.common.EmailTool;
import ai.intelliswarm.swarmai.tool.common.SlackWebhookTool;
import ai.intelliswarm.swarmai.tool.common.ShellCommandTool;
import ai.intelliswarm.swarmai.tool.common.PDFReadTool;
import ai.intelliswarm.swarmai.tool.common.CSVAnalysisTool;
import ai.intelliswarm.swarmai.tool.common.CodeExecutionTool;
import ai.intelliswarm.swarmai.tool.common.DatabaseQueryTool;
import ai.intelliswarm.swarmai.tool.common.SemanticSearchTool;
import ai.intelliswarm.swarmai.tool.common.DataAnalysisTool;
import ai.intelliswarm.swarmai.tool.common.ReportGeneratorTool;
import ai.intelliswarm.swarmai.tool.common.SECFilingsTool;
import ai.intelliswarm.swarmai.tool.common.SimulatedWebSearchTool;
import ai.intelliswarm.swarmai.tool.common.WebSearchTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Unified Configuration for All SwarmAI Tools
 *
 * This configuration class registers all shared tools as Spring beans
 * and Spring AI function beans for use across all examples and workflows.
 */
@Configuration
public class ToolsConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(ToolsConfiguration.class);

    // ==================== Tool Beans ====================

    @Bean
    public CalculatorTool calculatorTool() {
        logger.info("Registering CalculatorTool as Spring bean");
        return new CalculatorTool();
    }

    @Bean
    public WebSearchTool webSearchTool() {
        logger.info("Registering WebSearchTool as Spring bean");
        return new WebSearchTool();
    }

    @Bean
    public SECFilingsTool secFilingsTool() {
        logger.info("Registering SECFilingsTool as Spring bean");
        return new SECFilingsTool();
    }

    @Bean
    public DataAnalysisTool dataAnalysisTool() {
        logger.info("Registering DataAnalysisTool as Spring bean");
        return new DataAnalysisTool();
    }

    @Bean
    public ReportGeneratorTool reportGeneratorTool() {
        logger.info("Registering ReportGeneratorTool as Spring bean");
        return new ReportGeneratorTool();
    }

    @Bean
    public SimulatedWebSearchTool simulatedWebSearchTool() {
        logger.info("Registering SimulatedWebSearchTool as Spring bean");
        return new SimulatedWebSearchTool();
    }

    @Bean
    public FileReadTool fileReadTool() {
        logger.info("Registering FileReadTool as Spring bean");
        return new FileReadTool();
    }

    @Bean
    public FileWriteTool fileWriteTool() {
        logger.info("Registering FileWriteTool as Spring bean");
        return new FileWriteTool();
    }

    @Bean
    public DirectoryReadTool directoryReadTool() {
        logger.info("Registering DirectoryReadTool as Spring bean");
        return new DirectoryReadTool();
    }

    @Bean
    public WebScrapeTool webScrapeTool() {
        logger.info("Registering WebScrapeTool as Spring bean");
        return new WebScrapeTool();
    }

    @Bean
    public HttpRequestTool httpRequestTool() {
        logger.info("Registering HttpRequestTool as Spring bean");
        return new HttpRequestTool();
    }

    @Bean
    public JSONTransformTool jsonTransformTool() {
        logger.info("Registering JSONTransformTool as Spring bean");
        return new JSONTransformTool();
    }

    @Bean
    public PDFReadTool pdfReadTool() {
        logger.info("Registering PDFReadTool as Spring bean");
        return new PDFReadTool();
    }

    @Bean
    public CSVAnalysisTool csvAnalysisTool() {
        logger.info("Registering CSVAnalysisTool as Spring bean");
        return new CSVAnalysisTool();
    }

    @Bean
    public CodeExecutionTool codeExecutionTool() {
        logger.info("Registering CodeExecutionTool as Spring bean");
        return new CodeExecutionTool();
    }

    @Bean
    public XMLParseTool xmlParseTool() {
        logger.info("Registering XMLParseTool as Spring bean");
        return new XMLParseTool();
    }

    @Bean
    public ShellCommandTool shellCommandTool() {
        logger.info("Registering ShellCommandTool as Spring bean");
        return new ShellCommandTool();
    }

    // ==================== Spring AI Function Beans ====================

    @Bean
    @Description("Performs mathematical calculations for financial analysis like P/E ratios, growth rates, and valuation metrics")
    public Function<CalculatorTool.Request, String> calculator() {
        logger.info("Registering calculator function for Spring AI");
        CalculatorTool tool = calculatorTool();
        return request -> (String) tool.execute(Map.of("expression", request.expression()));
    }

    @Bean
    @Description("Searches the web for stock news, market analysis, and financial information")
    public Function<WebSearchTool.Request, String> web_search() {
        logger.info("Registering web_search function for Spring AI");
        WebSearchTool tool = webSearchTool();
        return request -> (String) tool.execute(Map.of("query", request.query()));
    }

    @Bean
    @Description("Analyzes SEC filings (10-K, 10-Q) for company financial data and regulatory information")
    public Function<SECFilingsTool.Request, String> sec_filings() {
        logger.info("Registering sec_filings function for Spring AI");
        SECFilingsTool tool = secFilingsTool();
        return request -> (String) tool.execute(Map.of("input", request.input()));
    }

    @Bean
    @Description("Analyze market data and competitor metrics to identify patterns, trends, and insights")
    public Function<DataAnalysisRequest, DataAnalysisResponse> data_analysis() {
        logger.info("Registering data_analysis function for Spring AI");
        DataAnalysisTool tool = dataAnalysisTool();

        return request -> {
            try {
                Map<String, Object> parameters = Map.of(
                    "data", request.data(),
                    "analysis_type", request.analysisType() != null ? request.analysisType() : "general",
                    "include_charts", request.includeCharts() != null ? request.includeCharts() : false
                );

                Object result = tool.execute(parameters);

                if (result instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> resultMap = (Map<String, Object>) result;
                    return new DataAnalysisResponse(
                        (String) resultMap.get("analysis_type"),
                        (String) resultMap.get("results"),
                        (String) resultMap.get("summary")
                    );
                }

                return new DataAnalysisResponse("general", result.toString(), "Analysis completed");
            } catch (Exception e) {
                logger.error("Error executing data analysis: {}", e.getMessage(), e);
                return new DataAnalysisResponse("error", "Analysis failed: " + e.getMessage(), "Error occurred");
            }
        };
    }

    @Bean
    @Description("Generate professional reports from research data and analysis results")
    public Function<ReportGeneratorRequest, ReportGeneratorResponse> report_generator() {
        logger.info("Registering report_generator function for Spring AI");
        ReportGeneratorTool tool = reportGeneratorTool();

        return request -> {
            try {
                Map<String, Object> parameters = Map.of(
                    "content", request.content(),
                    "report_type", request.reportType() != null ? request.reportType() : "executive",
                    "format", request.format() != null ? request.format() : "markdown"
                );

                Object result = tool.execute(parameters);

                if (result instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> resultMap = (Map<String, Object>) result;
                    return new ReportGeneratorResponse(
                        (String) resultMap.get("report_type"),
                        (String) resultMap.get("format"),
                        (String) resultMap.get("content"),
                        (String) resultMap.get("file_path")
                    );
                }

                return new ReportGeneratorResponse("executive", "markdown", result.toString(), null);
            } catch (Exception e) {
                logger.error("Error executing report generation: {}", e.getMessage(), e);
                return new ReportGeneratorResponse("error", "text", "Report generation failed: " + e.getMessage(), null);
            }
        };
    }

    @Bean
    @Description("Read file contents from the filesystem. Supports text, JSON, CSV, YAML, and XML formats with optional line range.")
    public Function<FileReadTool.Request, String> file_read() {
        logger.info("Registering file_read function for Spring AI");
        FileReadTool tool = fileReadTool();
        return request -> {
            Map<String, Object> params = new HashMap<>();
            params.put("path", request.path());
            if (request.format() != null) params.put("format", request.format());
            if (request.offset() != null) params.put("offset", request.offset());
            if (request.limit() != null) params.put("limit", request.limit());
            return tool.execute(params).toString();
        };
    }

    @Bean
    @Description("Parse XML documents and extract data via XPath queries, element listing, or text extraction.")
    public Function<XMLParseTool.Request, String> xml_parse() {
        logger.info("Registering xml_parse function for Spring AI");
        XMLParseTool tool = xmlParseTool();
        return request -> {
            Map<String, Object> params = new HashMap<>();
            if (request.xmlContent() != null) params.put("xml_content", request.xmlContent());
            if (request.path() != null) params.put("path", request.path());
            if (request.operation() != null) params.put("operation", request.operation());
            if (request.xpath() != null) params.put("xpath", request.xpath());
            return tool.execute(params).toString();
        };
    }

    @Bean
    @Description("Execute whitelisted shell commands for system introspection (ls, cat, grep, git, ps, df, etc.).")
    public Function<ShellCommandTool.Request, String> shell_command() {
        logger.info("Registering shell_command function for Spring AI");
        ShellCommandTool tool = shellCommandTool();
        return request -> {
            Map<String, Object> params = new HashMap<>();
            params.put("command", request.command());
            if (request.timeout() != null) params.put("timeout", request.timeout());
            return tool.execute(params).toString();
        };
    }

    @Bean
    @Description("Extract text and metadata from PDF files with page range support.")
    public Function<PDFReadTool.Request, String> pdf_read() {
        logger.info("Registering pdf_read function for Spring AI");
        PDFReadTool tool = pdfReadTool();
        return request -> {
            Map<String, Object> params = new HashMap<>();
            params.put("path", request.path());
            if (request.startPage() != null) params.put("start_page", request.startPage());
            if (request.endPage() != null) params.put("end_page", request.endPage());
            return tool.execute(params).toString();
        };
    }

    @Bean
    @Description("Read and analyze CSV/TSV data. Supports describe, stats, head, filter, and count operations.")
    public Function<CSVAnalysisTool.Request, String> csv_analysis() {
        logger.info("Registering csv_analysis function for Spring AI");
        CSVAnalysisTool tool = csvAnalysisTool();
        return request -> {
            Map<String, Object> params = new HashMap<>();
            if (request.path() != null) params.put("path", request.path());
            if (request.csvContent() != null) params.put("csv_content", request.csvContent());
            if (request.operation() != null) params.put("operation", request.operation());
            if (request.column() != null) params.put("column", request.column());
            if (request.value() != null) params.put("value", request.value());
            if (request.rows() != null) params.put("rows", request.rows());
            return tool.execute(params).toString();
        };
    }

    @Bean
    @Description("Execute code snippets in javascript or shell with timeout and safety restrictions.")
    public Function<CodeExecutionTool.Request, String> code_execution() {
        logger.info("Registering code_execution function for Spring AI");
        CodeExecutionTool tool = codeExecutionTool();
        return request -> {
            Map<String, Object> params = new HashMap<>();
            params.put("code", request.code());
            if (request.language() != null) params.put("language", request.language());
            if (request.timeout() != null) params.put("timeout", request.timeout());
            return tool.execute(params).toString();
        };
    }

    @Bean
    @Description("Parse, query, extract, and transform JSON data. Supports dot-notation paths, flattening, and CSV conversion.")
    public Function<JSONTransformTool.Request, String> json_transform() {
        logger.info("Registering json_transform function for Spring AI");
        JSONTransformTool tool = jsonTransformTool();
        return request -> {
            Map<String, Object> params = new HashMap<>();
            params.put("json", request.json());
            if (request.operation() != null) params.put("operation", request.operation());
            if (request.path() != null) params.put("path", request.path());
            return tool.execute(params).toString();
        };
    }

    @Bean
    @Description("Make HTTP requests to REST APIs. Supports GET, POST, PUT, DELETE, PATCH with custom headers and auth.")
    public Function<HttpRequestTool.Request, String> http_request() {
        logger.info("Registering http_request function for Spring AI");
        HttpRequestTool tool = httpRequestTool();
        return request -> {
            Map<String, Object> params = new HashMap<>();
            params.put("url", request.url());
            if (request.method() != null) params.put("method", request.method());
            if (request.body() != null) params.put("body", request.body());
            if (request.headers() != null) params.put("headers", request.headers());
            if (request.authToken() != null) params.put("auth_token", request.authToken());
            return tool.execute(params).toString();
        };
    }

    @Bean
    @Description("Fetch a web page URL and extract clean, structured content including title, text, headings, links, and tables.")
    public Function<WebScrapeTool.Request, String> web_scrape() {
        logger.info("Registering web_scrape function for Spring AI");
        WebScrapeTool tool = webScrapeTool();
        return request -> {
            Map<String, Object> params = new HashMap<>();
            params.put("url", request.url());
            if (request.selector() != null) params.put("selector", request.selector());
            if (request.includeLinks() != null) params.put("include_links", request.includeLinks());
            if (request.includeTables() != null) params.put("include_tables", request.includeTables());
            return tool.execute(params).toString();
        };
    }

    @Bean
    @Description("List files and directories with optional glob pattern filtering. Supports recursive search.")
    public Function<DirectoryReadTool.Request, String> directory_read() {
        logger.info("Registering directory_read function for Spring AI");
        DirectoryReadTool tool = directoryReadTool();
        return request -> {
            Map<String, Object> params = new HashMap<>();
            params.put("path", request.path() != null ? request.path() : ".");
            if (request.pattern() != null) params.put("pattern", request.pattern());
            if (request.recursive() != null) params.put("recursive", request.recursive());
            if (request.maxResults() != null) params.put("max_results", request.maxResults());
            return tool.execute(params).toString();
        };
    }

    @Bean
    @Description("Write content to a file on the filesystem. Supports overwrite, append, and create modes.")
    public Function<FileWriteTool.Request, String> file_write() {
        logger.info("Registering file_write function for Spring AI");
        FileWriteTool tool = fileWriteTool();
        return request -> {
            Map<String, Object> params = new HashMap<>();
            params.put("path", request.path());
            params.put("content", request.content());
            if (request.mode() != null) params.put("mode", request.mode());
            return tool.execute(params).toString();
        };
    }

    // ==================== Request/Response Records ====================

    public record DataAnalysisRequest(
        String data,
        String analysisType,
        Boolean includeCharts
    ) {}

    public record DataAnalysisResponse(
        String analysisType,
        String results,
        String summary
    ) {}

    public record ReportGeneratorRequest(
        String content,
        String reportType,
        String format
    ) {}

    public record ReportGeneratorResponse(
        String reportType,
        String format,
        String content,
        String filePath
    ) {}
}
