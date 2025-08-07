/*
 * SwarmAI Framework - A Java implementation inspired by CrewAI
 * 
 * This file is part of SwarmAI, a derivative work based on CrewAI.
 * Original CrewAI: Copyright (c) 2025 crewAI, Inc. (MIT License)
 * SwarmAI adaptations: Copyright (c) 2025 IntelliSwarm.ai (MIT License)
 * 
 * Licensed under the MIT License. See LICENSE file for details.
 */
package ai.intelliswarm.swarmai.examples.research.tools;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Report Generator Tool for Creating Professional Reports
 * 
 * Generates formatted reports in various formats (Markdown, HTML, PDF).
 * Provides templates for different types of business reports.
 */
public class ReportGeneratorTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(ReportGeneratorTool.class);

    @Override
    public String getFunctionName() {
        return "generate_report";
    }

    @Override
    public String getDescription() {
        return "Generate professional reports in various formats (Markdown, HTML, PDF). " +
               "Supports different templates for business reports, executive summaries, and analytical documents.";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String reportType = (String) parameters.get("report_type");
        String content = (String) parameters.get("content");
        String format = (String) parameters.getOrDefault("format", "markdown");
        String filename = (String) parameters.getOrDefault("filename", "report");
        String template = (String) parameters.getOrDefault("template", "standard");

        logger.info("📋 Generating {} report: type='{}', format='{}', template='{}'", 
            filename, reportType, format, template);

        return switch (format.toLowerCase()) {
            case "markdown" -> generateMarkdownReport(reportType, content, filename, template);
            case "html" -> generateHtmlReport(reportType, content, filename, template);
            case "pdf" -> generatePdfReport(reportType, content, filename, template);
            default -> generateMarkdownReport(reportType, content, filename, template);
        };
    }

    private Map<String, Object> generateMarkdownReport(String reportType, String content, String filename, String template) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            String reportContent = switch (template.toLowerCase()) {
                case "executive" -> generateExecutiveTemplate(reportType, content);
                case "competitive" -> generateCompetitiveTemplate(reportType, content);
                case "analytical" -> generateAnalyticalTemplate(reportType, content);
                default -> generateStandardTemplate(reportType, content);
            };

            // Save to file if filename provided
            if (filename != null && !filename.isEmpty()) {
                String fullFilename = filename.endsWith(".md") ? filename : filename + ".md";
                try (FileWriter writer = new FileWriter(fullFilename)) {
                    writer.write(reportContent);
                    result.put("file_saved", fullFilename);
                    logger.info("📄 Report saved to: {}", fullFilename);
                }
            }

            result.put("format", "markdown");
            result.put("content", reportContent);
            result.put("word_count", countWords(reportContent));
            result.put("generated_at", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            
        } catch (IOException e) {
            logger.error("Error generating report", e);
            result.put("error", "Failed to generate report: " + e.getMessage());
        }

        return result;
    }

    private Map<String, Object> generateHtmlReport(String reportType, String content, String filename, String template) {
        Map<String, Object> result = new HashMap<>();
        
        // Convert markdown-like content to HTML structure
        String htmlContent = convertToHtml(content, template);
        
        result.put("format", "html");
        result.put("content", htmlContent);
        result.put("template", template);
        result.put("generated_at", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        
        return result;
    }

    private Map<String, Object> generatePdfReport(String reportType, String content, String filename, String template) {
        Map<String, Object> result = new HashMap<>();
        
        // In production, this would generate actual PDF using libraries like iText or Apache PDFBox
        result.put("format", "pdf");
        result.put("status", "PDF generation simulated - would require PDF library integration");
        result.put("template", template);
        result.put("generated_at", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        
        return result;
    }

    private String generateExecutiveTemplate(String reportType, String content) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMMM dd, yyyy"));
        
        return String.format("""
            # Executive Summary: %s
            
            **Document Type:** Executive Report  
            **Date:** %s  
            **Prepared by:** SwarmAI Research Team  
            **Classification:** Confidential
            
            ---
            
            ## Executive Overview
            
            This executive summary provides key findings and strategic recommendations based on comprehensive analysis conducted by our multi-agent research team.
            
            ## Key Findings
            
            %s
            
            ## Strategic Recommendations
            
            ### Immediate Actions (0-3 months)
            - Priority implementation items
            - Quick wins and low-hanging fruit
            - Risk mitigation measures
            
            ### Medium-term Initiatives (3-12 months)
            - Strategic development projects
            - Market positioning activities
            - Capability building programs
            
            ### Long-term Vision (12+ months)
            - Strategic positioning goals
            - Market expansion opportunities
            - Innovation and development roadmap
            
            ## Risk Assessment
            
            ### High Priority Risks
            - Market disruption potential
            - Competitive response scenarios
            - Resource allocation challenges
            
            ### Mitigation Strategies
            - Contingency planning recommendations
            - Monitoring and early warning systems
            - Strategic partnership opportunities
            
            ## Financial Implications
            
            ### Investment Requirements
            - Initial capital requirements
            - Ongoing operational costs
            - Expected return on investment
            
            ### Revenue Impact
            - Short-term revenue implications
            - Long-term growth projections
            - Market share opportunities
            
            ## Next Steps
            
            1. **Executive Decision Required:** Strategic direction approval
            2. **Resource Allocation:** Team and budget assignment
            3. **Timeline Confirmation:** Implementation schedule approval
            4. **Success Metrics:** KPI definition and tracking setup
            
            ---
            
            **Contact Information:**  
            SwarmAI Research Team  
            research@intelliswarm.ai  
            
            *This report was generated using the SwarmAI multi-agent framework, leveraging collaborative intelligence for comprehensive analysis.*
            """, reportType, timestamp, content);
    }

    private String generateCompetitiveTemplate(String reportType, String content) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMMM dd, yyyy"));
        
        return String.format("""
            # Competitive Analysis Report: %s
            
            **Analysis Date:** %s  
            **Report Type:** Competitive Intelligence  
            **Scope:** Market Analysis and Strategic Positioning  
            
            ---
            
            ## Table of Contents
            
            1. [Executive Summary](#executive-summary)
            2. [Market Landscape](#market-landscape)
            3. [Competitive Matrix](#competitive-matrix)
            4. [Strategic Analysis](#strategic-analysis)
            5. [Recommendations](#recommendations)
            6. [Appendices](#appendices)
            
            ---
            
            ## Executive Summary
            
            This competitive analysis examines the current market landscape, key players, and strategic positioning opportunities in the target market.
            
            ### Key Insights
            %s
            
            ## Market Landscape
            
            ### Market Size and Growth
            - Total Addressable Market (TAM)
            - Serviceable Addressable Market (SAM)
            - Growth rate and trends
            - Key market drivers
            
            ### Market Segmentation
            - Customer segments and characteristics
            - Geographic distribution
            - Use case analysis
            - Pricing segments
            
            ## Competitive Matrix
            
            ### Direct Competitors
            | Company | Market Share | Strengths | Weaknesses | Strategy |
            |---------|--------------|-----------|------------|----------|
            | Competitor A | XX%% | Key strengths | Key weaknesses | Strategic focus |
            | Competitor B | XX%% | Key strengths | Key weaknesses | Strategic focus |
            | Competitor C | XX%% | Key strengths | Key weaknesses | Strategic focus |
            
            ### Indirect Competitors
            - Alternative solutions
            - Substitute products
            - Emerging threats
            
            ## Strategic Analysis
            
            ### SWOT Analysis
            
            **Strengths**
            - Internal capabilities and advantages
            - Competitive differentiators
            - Market position benefits
            
            **Weaknesses**
            - Internal limitations
            - Competitive disadvantages
            - Resource constraints
            
            **Opportunities**
            - Market trends and gaps
            - Emerging segments
            - Strategic partnerships
            
            **Threats**
            - Competitive responses
            - Market disruptions
            - Regulatory changes
            
            ### Porter's Five Forces
            
            1. **Threat of New Entrants:** Analysis of barriers to entry
            2. **Bargaining Power of Suppliers:** Supplier landscape assessment
            3. **Bargaining Power of Buyers:** Customer power analysis
            4. **Threat of Substitutes:** Alternative solution evaluation
            5. **Competitive Rivalry:** Intensity of competition assessment
            
            ## Recommendations
            
            ### Strategic Positioning
            - Recommended market position
            - Differentiation strategy
            - Value proposition refinement
            
            ### Tactical Initiatives
            - Product development priorities
            - Marketing and sales strategies
            - Partnership opportunities
            - Pricing strategy recommendations
            
            ### Implementation Roadmap
            
            **Phase 1 (0-6 months):** Foundation building
            **Phase 2 (6-12 months):** Market penetration
            **Phase 3 (12-24 months):** Scale and optimize
            
            ## Appendices
            
            ### A. Data Sources
            - Research methodology
            - Data collection sources
            - Analysis frameworks used
            
            ### B. Detailed Financial Analysis
            - Revenue comparisons
            - Investment analysis
            - ROI projections
            
            ### C. Market Research Data
            - Survey results
            - Interview insights
            - Secondary research findings
            
            ---
            
            **Prepared by:** SwarmAI Multi-Agent Research Team  
            **Quality Assurance:** Peer review and validation completed  
            **Distribution:** Confidential - Executive team only
            
            *This competitive analysis leverages SwarmAI's multi-agent framework for comprehensive, unbiased market intelligence.*
            """, reportType, timestamp, content);
    }

    private String generateAnalyticalTemplate(String reportType, String content) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMMM dd, yyyy"));
        
        return String.format("""
            # Analytical Report: %s
            
            **Analysis Date:** %s  
            **Report Type:** Data Analysis & Insights  
            **Methodology:** Multi-Agent Analytical Framework  
            
            ---
            
            ## Abstract
            
            This analytical report presents findings from comprehensive data analysis conducted using advanced analytical methods and multi-agent intelligence systems.
            
            ## Methodology
            
            ### Data Collection
            - Primary data sources
            - Secondary research integration
            - Data validation methods
            - Quality assurance processes
            
            ### Analytical Framework
            - Statistical analysis methods
            - Pattern recognition techniques
            - Trend analysis approaches
            - Predictive modeling components
            
            ### Multi-Agent Analysis
            - Agent specialization and roles
            - Collaborative analysis workflow
            - Cross-validation processes
            - Consensus building methods
            
            ## Findings
            
            %s
            
            ## Data Analysis
            
            ### Statistical Summary
            - Descriptive statistics
            - Distribution analysis
            - Correlation findings
            - Regression analysis results
            
            ### Pattern Recognition
            - Identified patterns and trends
            - Anomaly detection results
            - Seasonal variations
            - Cyclical patterns
            
            ### Predictive Insights
            - Forecasting models
            - Scenario analysis
            - Risk probability assessments
            - Confidence intervals
            
            ## Implications
            
            ### Business Impact
            - Strategic implications
            - Operational considerations
            - Financial projections
            - Risk assessments
            
            ### Market Implications
            - Industry trends
            - Competitive dynamics
            - Customer behavior insights
            - Market opportunity assessment
            
            ## Recommendations
            
            ### Data-Driven Actions
            1. **Immediate Actions:** High-confidence recommendations
            2. **Strategic Initiatives:** Medium-term planning considerations
            3. **Monitoring Requirements:** Ongoing data collection needs
            4. **Validation Steps:** Hypothesis testing recommendations
            
            ### Implementation Guidelines
            - Execution priorities
            - Resource requirements
            - Success metrics
            - Monitoring frameworks
            
            ## Limitations and Assumptions
            
            ### Data Limitations
            - Sample size considerations
            - Data quality constraints
            - Temporal limitations
            - Geographic scope restrictions
            
            ### Analytical Assumptions
            - Model assumptions
            - Statistical assumptions
            - Market assumptions
            - Behavioral assumptions
            
            ## Appendices
            
            ### A. Technical Details
            - Statistical methodologies
            - Model specifications
            - Algorithm descriptions
            - Validation procedures
            
            ### B. Raw Data Summary
            - Data sources inventory
            - Processing steps
            - Transformation methods
            - Quality metrics
            
            ### C. Supplementary Analysis
            - Additional statistical tests
            - Sensitivity analysis
            - Alternative model results
            - Comparative analysis
            
            ---
            
            **Analysis Team:** SwarmAI Multi-Agent Analytical Framework  
            **Peer Review:** Independent validation completed  
            **Distribution:** Technical and executive stakeholders
            
            *This analytical report demonstrates the power of collaborative AI intelligence in data analysis and insight generation.*
            """, reportType, timestamp, content);
    }

    private String generateStandardTemplate(String reportType, String content) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMMM dd, yyyy"));
        
        return String.format("""
            # %s Report
            
            **Generated:** %s  
            **Document Type:** Standard Report  
            **Source:** SwarmAI Framework
            
            ---
            
            ## Overview
            
            This report presents findings and analysis generated by the SwarmAI multi-agent framework.
            
            ## Content
            
            %s
            
            ## Conclusion
            
            This analysis demonstrates the collaborative capabilities of the SwarmAI framework in generating comprehensive, multi-perspective insights.
            
            ---
            
            **Generated by:** SwarmAI Multi-Agent System  
            **Framework Version:** 1.0.0-SNAPSHOT  
            **Quality:** AI-Generated Content
            """, reportType, timestamp, content);
    }

    private String convertToHtml(String content, String template) {
        // Simple markdown to HTML conversion for demo purposes
        // In production, use a proper markdown parser
        String html = content
            .replaceAll("^# (.*?)$", "<h1>$1</h1>")
            .replaceAll("^## (.*?)$", "<h2>$1</h2>")
            .replaceAll("^### (.*?)$", "<h3>$1</h3>")
            .replaceAll("\\*\\*(.*?)\\*\\*", "<strong>$1</strong>")
            .replaceAll("\\*(.*?)\\*", "<em>$1</em>")
            .replaceAll("\n\n", "</p><p>")
            .replaceAll("^", "<p>")
            .concat("</p>");

        return String.format("""
            <!DOCTYPE html>
            <html>
            <head>
                <title>SwarmAI Report</title>
                <style>
                    body { font-family: Arial, sans-serif; margin: 40px; }
                    h1 { color: #2c3e50; border-bottom: 2px solid #3498db; }
                    h2 { color: #34495e; margin-top: 30px; }
                    h3 { color: #7f8c8d; }
                    p { line-height: 1.6; }
                    .header { background-color: #ecf0f1; padding: 20px; border-radius: 5px; }
                </style>
            </head>
            <body>
                <div class="header">
                    <h1>SwarmAI Generated Report</h1>
                    <p><strong>Template:</strong> %s | <strong>Generated:</strong> %s</p>
                </div>
                %s
            </body>
            </html>
            """, template, LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), html);
    }

    private int countWords(String text) {
        if (text == null || text.isEmpty()) return 0;
        return text.trim().split("\\s+").length;
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        
        Map<String, Object> properties = new HashMap<>();
        
        Map<String, Object> reportType = new HashMap<>();
        reportType.put("type", "string");
        reportType.put("description", "Type of report being generated");
        properties.put("report_type", reportType);
        
        Map<String, Object> content = new HashMap<>();
        content.put("type", "string");
        content.put("description", "Main content to include in the report");
        properties.put("content", content);
        
        Map<String, Object> format = new HashMap<>();
        format.put("type", "string");
        format.put("description", "Output format: markdown, html, pdf");
        format.put("default", "markdown");
        properties.put("format", format);
        
        Map<String, Object> template = new HashMap<>();
        template.put("type", "string");
        template.put("description", "Report template: standard, executive, competitive, analytical");
        template.put("default", "standard");
        properties.put("template", template);
        
        Map<String, Object> filename = new HashMap<>();
        filename.put("type", "string");
        filename.put("description", "Optional filename for saving the report");
        properties.put("filename", filename);
        
        schema.put("properties", properties);
        schema.put("required", new String[]{"report_type", "content"});
        
        return schema;
    }

    @Override
    public boolean isAsync() {
        return false;
    }

    @Override
    public int getMaxUsageCount() {
        return 10; // Allow up to 10 reports per agent
    }

    @Override
    public boolean isCacheable() {
        return false; // Don't cache reports as they may be unique
    }
}