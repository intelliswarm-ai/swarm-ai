package ai.intelliswarm.swarmai.task;

import ai.intelliswarm.swarmai.agent.Agent;
import ai.intelliswarm.swarmai.base.BaseSwarmTest;
import ai.intelliswarm.swarmai.base.MockChatClientFactory;
import ai.intelliswarm.swarmai.task.output.OutputFormat;
import ai.intelliswarm.swarmai.task.output.TaskOutput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises typed structured output: {@code Task.builder().outputType(MyType.class)}
 * and {@code TaskOutput.as(MyType.class)}. Covers:
 * <ul>
 *   <li>builder + getter wiring on Task</li>
 *   <li>auto-flip of OutputFormat to JSON when outputType is set</li>
 *   <li>{@code TaskOutput.as(Class)} type-safe accessor + fallback parse path</li>
 *   <li>end-to-end: agent receives JSON, auto-parses, attaches typed instance</li>
 *   <li>code-fence stripping (```json … ``` and bare ``` … ``` wrappers)</li>
 *   <li>parse failure ⇒ null typed output but task still succeeds</li>
 * </ul>
 */
@DisplayName("Typed Structured Output")
class TypedOutputTest extends BaseSwarmTest {

    /** Test target type — the kind of small DTO callers will declare. */
    public static class Invoice {
        public String vendor;
        public double total;
        public String currency;

        public Invoice() {} // Jackson needs no-arg constructor

        public Invoice(String vendor, double total, String currency) {
            this.vendor = vendor;
            this.total = total;
            this.currency = currency;
        }
    }

    public static class City {
        public String name;
        public int population;

        public City() {}
    }

    @Nested
    @DisplayName("Task.builder().outputType()")
    class BuilderTests {

        @Test
        @DisplayName("stores the declared outputType")
        void outputType_isStoredOnTask() {
            Task task = Task.builder()
                    .description("extract invoice")
                    .agent(createAgent())
                    .outputType(Invoice.class)
                    .build();

            assertEquals(Invoice.class, task.getOutputType());
        }

        @Test
        @DisplayName("auto-flips OutputFormat to JSON when outputType is set without explicit format")
        void outputType_flipsFormatToJson() {
            Task task = Task.builder()
                    .description("extract")
                    .agent(createAgent())
                    .outputType(Invoice.class)
                    .build();

            assertEquals(OutputFormat.JSON, task.getOutputFormat(),
                    "outputType should default outputFormat to JSON");
        }

        @Test
        @DisplayName("respects explicit outputFormat even when outputType is set")
        void outputType_respectsExplicitFormat() {
            Task task = Task.builder()
                    .description("extract")
                    .agent(createAgent())
                    .outputType(Invoice.class)
                    .outputFormat(OutputFormat.MARKDOWN)
                    .build();

            assertEquals(OutputFormat.MARKDOWN, task.getOutputFormat());
        }

        @Test
        @DisplayName("returns null outputType when not set (back-compat)")
        void outputType_isNullByDefault() {
            Task task = Task.builder()
                    .description("plain task")
                    .agent(createAgent())
                    .build();

            assertNull(task.getOutputType());
        }
    }

    @Nested
    @DisplayName("TaskOutput.as()")
    class AsTests {

        @Test
        @DisplayName("returns the pre-attached typed instance without re-parsing")
        void as_returnsAttachedTypedInstance() {
            Invoice attached = new Invoice("ACME", 199.99, "USD");
            TaskOutput out = TaskOutput.builder()
                    .taskId("t1")
                    .rawOutput("{\"vendor\":\"ACME\",\"total\":199.99,\"currency\":\"USD\"}")
                    .typedOutput(attached)
                    .build();

            Invoice retrieved = out.as(Invoice.class);

            assertSame(attached, retrieved,
                    "as() should return the attached instance, not re-parse");
        }

        @Test
        @DisplayName("falls back to parsing rawOutput when no typed instance was attached")
        void as_fallsBackToRawParse() {
            TaskOutput out = TaskOutput.builder()
                    .taskId("t2")
                    .rawOutput("{\"vendor\":\"Initech\",\"total\":42.0,\"currency\":\"EUR\"}")
                    .format(OutputFormat.JSON)
                    .build();

            Invoice parsed = out.as(Invoice.class);

            assertEquals("Initech", parsed.vendor);
            assertEquals(42.0, parsed.total);
            assertEquals("EUR", parsed.currency);
        }

        @Test
        @DisplayName("throws ClassCastException when the attached type does not match the requested type")
        void as_throwsOnTypeMismatch() {
            TaskOutput out = TaskOutput.builder()
                    .taskId("t3")
                    .rawOutput("{}")
                    .typedOutput(new Invoice("X", 1.0, "USD"))
                    .build();

            ClassCastException thrown = assertThrows(ClassCastException.class,
                    () -> out.as(City.class));
            assertTrue(thrown.getMessage().contains("Invoice"));
            assertTrue(thrown.getMessage().contains("City"));
        }

        @Test
        @DisplayName("getTypedOutput() returns the attached object, untyped")
        void getTypedOutput_returnsObject() {
            Invoice attached = new Invoice("ACME", 1.0, "USD");
            TaskOutput out = TaskOutput.builder()
                    .taskId("t4")
                    .rawOutput("{}")
                    .typedOutput(attached)
                    .build();

            assertSame(attached, out.getTypedOutput());
        }
    }

    @Nested
    @DisplayName("End-to-end: Agent.executeTask with outputType")
    class EndToEndTests {

        @Test
        @DisplayName("auto-parses a clean JSON response into the declared type")
        void execute_autoParsesPlainJson() {
            String response = "{\"vendor\":\"ACME\",\"total\":199.99,\"currency\":\"USD\"}";
            ChatClient mockClient = MockChatClientFactory.withResponse(response);

            Agent agent = Agent.builder()
                    .role("Extractor")
                    .goal("extract invoice fields")
                    .backstory("I produce JSON")
                    .chatClient(mockClient)
                    .build();
            Task task = Task.builder()
                    .description("Extract invoice from text")
                    .agent(agent)
                    .outputType(Invoice.class)
                    .build();

            TaskOutput out = task.execute(Collections.emptyList());

            assertNotNull(out.getTypedOutput(),
                    "typedOutput should be populated when LLM returns parseable JSON");
            Invoice parsed = out.as(Invoice.class);
            assertEquals("ACME", parsed.vendor);
            assertEquals(199.99, parsed.total, 1e-6);
            assertEquals("USD", parsed.currency);
        }

        @Test
        @DisplayName("strips ```json fences before parsing (most common LLM wrapper)")
        void execute_stripsJsonCodeFences() {
            String response = "```json\n{\"vendor\":\"Globex\",\"total\":50.0,\"currency\":\"GBP\"}\n```";
            ChatClient mockClient = MockChatClientFactory.withResponse(response);

            Agent agent = Agent.builder()
                    .role("Extractor").goal("extract").backstory("json")
                    .chatClient(mockClient).build();
            Task task = Task.builder()
                    .description("extract")
                    .agent(agent)
                    .outputType(Invoice.class)
                    .build();

            TaskOutput out = task.execute(Collections.emptyList());

            Invoice parsed = out.as(Invoice.class);
            assertEquals("Globex", parsed.vendor);
            assertEquals(50.0, parsed.total, 1e-6);
        }

        @Test
        @DisplayName("strips bare ``` fences (no language tag) before parsing")
        void execute_stripsBareCodeFences() {
            String response = "```\n{\"vendor\":\"Initech\",\"total\":1.0,\"currency\":\"USD\"}\n```";
            ChatClient mockClient = MockChatClientFactory.withResponse(response);

            Agent agent = Agent.builder()
                    .role("Extractor").goal("g").backstory("b")
                    .chatClient(mockClient).build();
            Task task = Task.builder()
                    .description("extract")
                    .agent(agent)
                    .outputType(Invoice.class)
                    .build();

            TaskOutput out = task.execute(Collections.emptyList());

            Invoice parsed = out.as(Invoice.class);
            assertEquals("Initech", parsed.vendor);
        }

        @Test
        @DisplayName("returns null typedOutput on parse failure but does NOT fail the task")
        void execute_parseFailureLeavesTypedOutputNullButTaskSucceeds() {
            String response = "Sorry, I cannot extract that.";
            ChatClient mockClient = MockChatClientFactory.withResponse(response);

            Agent agent = Agent.builder()
                    .role("Extractor").goal("g").backstory("b")
                    .chatClient(mockClient).build();
            Task task = Task.builder()
                    .description("extract")
                    .agent(agent)
                    .outputType(Invoice.class)
                    .build();

            TaskOutput out = task.execute(Collections.emptyList());

            assertTrue(out.isSuccessful(), "Task itself should still be marked successful");
            assertNotNull(out.getRawOutput(), "Raw output is preserved");
            assertNull(out.getTypedOutput(), "typedOutput is null on parse failure");
        }

        @Test
        @DisplayName("does not auto-parse when no outputType is declared (back-compat)")
        void execute_skipsParsingWhenNoOutputType() {
            String response = "{\"vendor\":\"X\",\"total\":1.0,\"currency\":\"USD\"}";
            ChatClient mockClient = MockChatClientFactory.withResponse(response);

            Agent agent = Agent.builder()
                    .role("R").goal("g").backstory("b")
                    .chatClient(mockClient).build();
            Task task = Task.builder()
                    .description("plain")
                    .agent(agent)
                    // no outputType
                    .build();

            TaskOutput out = task.execute(Collections.emptyList());

            assertNull(out.getTypedOutput(),
                    "Without outputType, typedOutput should remain null even if the response is valid JSON");
        }
    }

    // ============================================================================
    // Enterprise shapes — exercising the typed-output mechanism against the kinds
    // of records SwarmAI's primary audience (Java/Spring enterprise + financial)
    // is most likely to declare. Each test pairs a realistic record shape with a
    // realistic LLM response and asserts the round-trip works end-to-end.
    // ============================================================================

    /** Banking — loan origination. Nested employment + credit-score sub-records. */
    public record EmploymentInfo(String employer, String role, int yearsEmployed, double annualIncome) {}
    public record LoanApplication(
            String applicationId,
            String applicantName,
            String applicantEmail,
            double requestedAmount,
            String currency,
            int termMonths,
            EmploymentInfo employment,
            int creditScore,
            String purpose) {}

    /** Capital markets — trade execution shape used by FIX-adjacent agents. */
    public record TradeOrder(
            String orderId,
            String ticker,
            String side,                 // "BUY" | "SELL"
            long quantity,
            double limitPrice,
            String currency,
            String venue,
            String clientId,
            String settlementDate) {}

    /** Compliance — security audit finding with severity + CVE refs. */
    public record AuditFinding(
            String findingId,
            String severity,             // "LOW" | "MEDIUM" | "HIGH" | "CRITICAL"
            String component,
            String description,
            List<String> cveIds,
            String remediation) {}

    public record AuditReport(
            String reportId,
            String auditedSystem,
            String auditedAt,
            int findingsCount,
            List<AuditFinding> findings) {}

    /** KYC — onboarding shape with verification status. Insurance / banking / fintech. */
    public record KycDocument(String type, String number, String issuingCountry, boolean verified) {}
    public record KycCheck(
            String customerId,
            String legalName,
            String dateOfBirth,
            String residenceCountry,
            String status,                 // "PENDING" | "APPROVED" | "REJECTED" | "ESCALATED"
            String riskRating,             // "LOW" | "MEDIUM" | "HIGH"
            List<KycDocument> documents,
            List<String> sanctionsFlags) {}

    /** Procurement — purchase order shape that a Spring Data JPA app would persist. */
    public record PoLineItem(String sku, String description, int quantity, double unitPrice, double extendedTotal) {}
    public record PurchaseOrder(
            String poNumber,
            String vendorName,
            String requesterId,
            String approverEmail,
            String costCenter,
            String currency,
            double subtotal,
            double tax,
            double total,
            List<PoLineItem> lines) {}

    @Nested
    @DisplayName("Enterprise shapes")
    class EnterpriseShapesTests {

        @Test
        @DisplayName("LoanApplication: nested EmploymentInfo parses end-to-end")
        void loanApplication_extractsCleanly() {
            String response = """
                    {
                      "applicationId": "LOAN-2026-00451",
                      "applicantName": "Maria Schwartz",
                      "applicantEmail": "maria@example.test",
                      "requestedAmount": 285000.00,
                      "currency": "EUR",
                      "termMonths": 360,
                      "employment": {
                        "employer": "Globex Engineering",
                        "role": "Principal Engineer",
                        "yearsEmployed": 7,
                        "annualIncome": 142000.00
                      },
                      "creditScore": 781,
                      "purpose": "PRIMARY_RESIDENCE"
                    }""";
            ChatClient mockClient = MockChatClientFactory.withResponse(response);

            Agent loanAgent = Agent.builder()
                    .role("Loan Officer Assistant").goal("extract loan applications")
                    .backstory("banking domain extractor")
                    .chatClient(mockClient).build();
            Task task = Task.builder()
                    .description("Extract loan application from intake form")
                    .agent(loanAgent)
                    .outputType(LoanApplication.class)
                    .build();

            LoanApplication parsed = task.execute(Collections.emptyList()).as(LoanApplication.class);

            assertEquals("LOAN-2026-00451", parsed.applicationId());
            assertEquals("Maria Schwartz", parsed.applicantName());
            assertEquals(285000.00, parsed.requestedAmount(), 1e-6);
            assertEquals("EUR", parsed.currency());
            assertEquals(360, parsed.termMonths());
            assertNotNull(parsed.employment(), "Nested EmploymentInfo must be parsed");
            assertEquals("Globex Engineering", parsed.employment().employer());
            assertEquals(7, parsed.employment().yearsEmployed());
            assertEquals(142000.00, parsed.employment().annualIncome(), 1e-6);
            assertEquals(781, parsed.creditScore());
        }

        @Test
        @DisplayName("TradeOrder: capital-markets shape with side enum and limit price")
        void tradeOrder_extractsCleanly() {
            String response = """
                    {
                      "orderId": "ORD-2026-04-28-99812",
                      "ticker": "AAPL",
                      "side": "BUY",
                      "quantity": 1500,
                      "limitPrice": 187.45,
                      "currency": "USD",
                      "venue": "NASDAQ",
                      "clientId": "CLI-7821",
                      "settlementDate": "2026-04-30"
                    }""";
            ChatClient mockClient = MockChatClientFactory.withResponse(response);

            Agent traderAgent = Agent.builder()
                    .role("Trade Desk Assistant").goal("g").backstory("b")
                    .chatClient(mockClient).build();
            Task task = Task.builder()
                    .description("Convert trader voice instruction into a structured order")
                    .agent(traderAgent)
                    .outputType(TradeOrder.class)
                    .build();

            TradeOrder order = task.execute(Collections.emptyList()).as(TradeOrder.class);

            assertEquals("AAPL", order.ticker());
            assertEquals("BUY", order.side(), "side must be BUY or SELL — never lower-case");
            assertEquals(1500L, order.quantity());
            assertEquals(187.45, order.limitPrice(), 1e-6);
            assertEquals("NASDAQ", order.venue());
            assertEquals("2026-04-30", order.settlementDate());
        }

        @Test
        @DisplayName("AuditReport: list of findings with severity + CVE list parses")
        void auditReport_extractsListOfFindings() {
            String response = """
                    {
                      "reportId": "AUDIT-2026-Q2-014",
                      "auditedSystem": "payments-api",
                      "auditedAt": "2026-04-28T09:14:00Z",
                      "findingsCount": 2,
                      "findings": [
                        {
                          "findingId": "F-001",
                          "severity": "HIGH",
                          "component": "auth-service",
                          "description": "JWT signature verification disabled in DEV profile leaks into PROD",
                          "cveIds": ["CVE-2026-12345"],
                          "remediation": "Remove DEV profile bypass; enforce signature verification globally"
                        },
                        {
                          "findingId": "F-002",
                          "severity": "MEDIUM",
                          "component": "billing-service",
                          "description": "Outdated Jackson dependency",
                          "cveIds": ["CVE-2025-9999", "CVE-2025-9998"],
                          "remediation": "Upgrade to com.fasterxml.jackson.core:jackson-databind:2.18.x"
                        }
                      ]
                    }""";
            ChatClient mockClient = MockChatClientFactory.withResponse(response);

            Agent auditorAgent = Agent.builder()
                    .role("Security Auditor").goal("g").backstory("b")
                    .chatClient(mockClient).build();
            Task task = Task.builder()
                    .description("Summarize the security audit into a structured report")
                    .agent(auditorAgent)
                    .outputType(AuditReport.class)
                    .build();

            AuditReport report = task.execute(Collections.emptyList()).as(AuditReport.class);

            assertEquals("payments-api", report.auditedSystem());
            assertEquals(2, report.findingsCount());
            assertNotNull(report.findings());
            assertEquals(2, report.findings().size());
            assertEquals("HIGH", report.findings().get(0).severity());
            assertEquals("auth-service", report.findings().get(0).component());
            assertEquals(List.of("CVE-2026-12345"), report.findings().get(0).cveIds());
            assertEquals(2, report.findings().get(1).cveIds().size());
        }

        @Test
        @DisplayName("KycCheck: KYC onboarding with documents list + sanctions flags")
        void kycCheck_extractsMultiLevelNested() {
            String response = """
                    {
                      "customerId": "CUST-008812",
                      "legalName": "Aoife Ó Briain",
                      "dateOfBirth": "1989-03-12",
                      "residenceCountry": "IE",
                      "status": "APPROVED",
                      "riskRating": "LOW",
                      "documents": [
                        {"type": "PASSPORT", "number": "PA1234567", "issuingCountry": "IE", "verified": true},
                        {"type": "PROOF_OF_ADDRESS", "number": "UB-09887", "issuingCountry": "IE", "verified": true}
                      ],
                      "sanctionsFlags": []
                    }""";
            ChatClient mockClient = MockChatClientFactory.withResponse(response);

            Agent kycAgent = Agent.builder()
                    .role("KYC Analyst").goal("g").backstory("b")
                    .chatClient(mockClient).build();
            Task task = Task.builder()
                    .description("Run KYC check and return structured outcome")
                    .agent(kycAgent)
                    .outputType(KycCheck.class)
                    .build();

            KycCheck kyc = task.execute(Collections.emptyList()).as(KycCheck.class);

            assertEquals("CUST-008812", kyc.customerId());
            assertEquals("APPROVED", kyc.status());
            assertEquals("LOW", kyc.riskRating());
            assertEquals(2, kyc.documents().size());
            assertEquals("PASSPORT", kyc.documents().get(0).type());
            assertTrue(kyc.documents().get(0).verified());
            assertTrue(kyc.sanctionsFlags().isEmpty(),
                    "Empty list should round-trip cleanly, not become null");
        }

        @Test
        @DisplayName("PurchaseOrder: SAP-style PO with line items and totals")
        void purchaseOrder_extractsCleanly() {
            String response = """
                    {
                      "poNumber": "PO-2026-04-0451",
                      "vendorName": "Initech Office Supplies",
                      "requesterId": "user-9921",
                      "approverEmail": "ops-lead@example.test",
                      "costCenter": "ENG-INFRA-Q2",
                      "currency": "USD",
                      "subtotal": 487.50,
                      "tax": 39.00,
                      "total": 526.50,
                      "lines": [
                        {"sku": "SKU-1001", "description": "Standing desk", "quantity": 1, "unitPrice": 350.00, "extendedTotal": 350.00},
                        {"sku": "SKU-1102", "description": "Monitor arm", "quantity": 2, "unitPrice": 68.75, "extendedTotal": 137.50}
                      ]
                    }""";
            ChatClient mockClient = MockChatClientFactory.withResponse(response);

            Agent procurementAgent = Agent.builder()
                    .role("Procurement Bot").goal("g").backstory("b")
                    .chatClient(mockClient).build();
            Task task = Task.builder()
                    .description("Convert procurement request email into structured PurchaseOrder")
                    .agent(procurementAgent)
                    .outputType(PurchaseOrder.class)
                    .build();

            PurchaseOrder po = task.execute(Collections.emptyList()).as(PurchaseOrder.class);

            assertEquals("PO-2026-04-0451", po.poNumber());
            assertEquals(2, po.lines().size());
            assertEquals(526.50, po.total(), 1e-6);
            assertEquals(po.subtotal() + po.tax(), po.total(), 0.01,
                    "subtotal + tax must reconcile to total");
            assertEquals("SKU-1001", po.lines().get(0).sku());
            assertEquals(2, po.lines().get(1).quantity());
            assertEquals(137.50, po.lines().get(1).extendedTotal(), 1e-6);
        }

        @Test
        @DisplayName("Enterprise: parse failure on a domain shape leaves rawOutput intact for human review")
        void enterprise_parseFailureKeepsRawOutputForHumanReview() {
            // Realistic failure: model returned prose instead of JSON because the
            // input was unparseable. Compliance audit trails depend on this raw text
            // surviving so a human can review what happened.
            String response = "I cannot extract a loan application from the provided document because " +
                              "the applicant's name is redacted. Please escalate to a human reviewer.";
            ChatClient mockClient = MockChatClientFactory.withResponse(response);

            Agent loanAgent = Agent.builder()
                    .role("Loan Officer Assistant").goal("g").backstory("b")
                    .chatClient(mockClient).build();
            Task task = Task.builder()
                    .description("Extract loan application")
                    .agent(loanAgent)
                    .outputType(LoanApplication.class)
                    .build();

            TaskOutput out = task.execute(Collections.emptyList());

            assertNull(out.getTypedOutput(),
                    "On parse failure typedOutput is null — caller knows to escalate");
            assertNotNull(out.getRawOutput());
            assertTrue(out.getRawOutput().contains("escalate"),
                    "Raw model output is preserved verbatim for human review / audit");
        }
    }
}
