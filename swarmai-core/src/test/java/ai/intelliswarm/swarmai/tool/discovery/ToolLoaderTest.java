package ai.intelliswarm.swarmai.tool.discovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the auto-load follow-up: ToolLoader + ToolLoadTool. */
class ToolLoaderTest {

    private static ToolCatalog catalogWith(String... names) {
        ToolCatalog cat = new ToolCatalog();
        for (String n : names) {
            cat.register(CatalogEntry.of(n, n + " description", List.of()));
        }
        return cat;
    }

    /** Stub callback whose function name matches its catalog entry. */
    private static ToolCallback stubCallback(String name) {
        return FunctionToolCallback.builder(name,
                        (java.util.function.Function<StubInput, String>) input -> "stub:" + name)
                .description("stub: " + name)
                .inputType(StubInput.class)
                .build();
    }

    public static class StubInput {
        public String dummy;
    }

    // =================================================================
    // LoadRequestStatus enum
    // =================================================================
    @Nested
    @DisplayName("LoadRequestStatus")
    class StatusEnum {

        @Test
        void isFailureClassification() {
            assertTrue(LoadRequestStatus.NOT_IN_CATALOG.isFailure());
            assertTrue(LoadRequestStatus.NO_CALLBACK_REGISTERED.isFailure());
            assertFalse(LoadRequestStatus.PENDING.isFailure());
            assertFalse(LoadRequestStatus.ALREADY_LOADED.isFailure());
        }
    }

    // =================================================================
    // ToolLoader
    // =================================================================
    @Nested
    @DisplayName("ToolLoader basics")
    class LoaderBasics {

        @Test
        void freshLoaderHasNothingLoadedOrPending() {
            ToolLoader loader = new ToolLoader(new ToolCatalog());
            assertEquals(0, loader.loadedCount());
            assertEquals(0, loader.pendingCount());
            assertTrue(loader.loadedCallbacks().isEmpty());
            assertTrue(loader.loadedNames().isEmpty());
        }

        @Test
        void registerCallbackRequiresCatalogEntry() {
            ToolCatalog cat = new ToolCatalog();
            ToolLoader loader = new ToolLoader(cat);
            assertThrows(IllegalArgumentException.class,
                    () -> loader.registerCallback("calculator", stubCallback("calculator")),
                    "supplier registration should fail when the tool isn't catalogued");

            cat.register(CatalogEntry.of("calculator", "do math"));
            assertDoesNotThrow(() -> loader.registerCallback("calculator", stubCallback("calculator")));
        }

        @Test
        void registerCallbackAcceptsBothFormsAndOverwrites() {
            ToolCatalog cat = catalogWith("calculator");
            ToolLoader loader = new ToolLoader(cat);

            loader.registerCallback("calculator", () -> stubCallback("v1"));
            loader.registerCallback("calculator", stubCallback("v2"));  // direct, not supplier
            // Latest registration wins
            loader.requestLoad("calculator");
            loader.applyPendingLoads();
            assertEquals("v2", loader.loadedCallbacks().get(0).getToolDefinition().name());
        }

        @Test
        void requestLoadKnownTool() {
            ToolCatalog cat = catalogWith("calculator");
            ToolLoader loader = new ToolLoader(cat);
            loader.registerCallback("calculator", stubCallback("calculator"));

            assertEquals(LoadRequestStatus.PENDING, loader.requestLoad("calculator"));
            assertTrue(loader.isPendingLoad("calculator"));
            assertFalse(loader.isLoaded("calculator"));
        }

        @Test
        void requestLoadUnknownToolReturnsNotInCatalog() {
            ToolLoader loader = new ToolLoader(new ToolCatalog());
            assertEquals(LoadRequestStatus.NOT_IN_CATALOG, loader.requestLoad("nope"));
        }

        @Test
        void requestLoadCatalogueWithoutSupplier() {
            ToolCatalog cat = catalogWith("calculator");
            ToolLoader loader = new ToolLoader(cat);
            // No registerCallback() call
            assertEquals(LoadRequestStatus.NO_CALLBACK_REGISTERED,
                    loader.requestLoad("calculator"));
        }

        @Test
        void requestLoadAlreadyLoaded() {
            ToolCatalog cat = catalogWith("calculator");
            ToolLoader loader = new ToolLoader(cat);
            loader.registerCallback("calculator", stubCallback("calculator"));

            loader.requestLoad("calculator");
            loader.applyPendingLoads();
            assertEquals(LoadRequestStatus.ALREADY_LOADED, loader.requestLoad("calculator"));
        }

        @Test
        void requestLoadSameToolTwiceWhilePendingDeduplicates() {
            ToolCatalog cat = catalogWith("calculator");
            ToolLoader loader = new ToolLoader(cat);
            loader.registerCallback("calculator", stubCallback("calculator"));

            assertEquals(LoadRequestStatus.PENDING, loader.requestLoad("calculator"));
            assertEquals(LoadRequestStatus.PENDING, loader.requestLoad("calculator"));
            assertEquals(1, loader.pendingCount());
        }

        @Test
        void applyPendingLoadsMaterialisesAndClears() {
            ToolCatalog cat = catalogWith("calculator", "current_time");
            ToolLoader loader = new ToolLoader(cat);
            loader.registerCallback("calculator", stubCallback("calculator"));
            loader.registerCallback("current_time", stubCallback("current_time"));

            loader.requestLoad("calculator");
            loader.requestLoad("current_time");
            assertEquals(2, loader.pendingCount());

            int loaded = loader.applyPendingLoads();
            assertEquals(2, loaded);
            assertEquals(0, loader.pendingCount());
            assertEquals(2, loader.loadedCount());
            assertTrue(loader.isLoaded("calculator"));
            assertTrue(loader.isLoaded("current_time"));
        }

        @Test
        void applyWithNoPendingReturnsZero() {
            ToolCatalog cat = catalogWith("calculator");
            ToolLoader loader = new ToolLoader(cat);
            loader.registerCallback("calculator", stubCallback("calculator"));
            assertEquals(0, loader.applyPendingLoads());
        }

        @Test
        void applyDoesNotReloadAlreadyLoaded() {
            ToolCatalog cat = catalogWith("calculator");
            ToolLoader loader = new ToolLoader(cat);

            AtomicInteger supplierCalls = new AtomicInteger();
            loader.registerCallback("calculator", () -> {
                supplierCalls.incrementAndGet();
                return stubCallback("calculator");
            });

            loader.requestLoad("calculator");
            loader.applyPendingLoads();
            assertEquals(1, supplierCalls.get());

            // Force a re-request — but loader marks it ALREADY_LOADED
            assertEquals(LoadRequestStatus.ALREADY_LOADED, loader.requestLoad("calculator"));
            // Even if pending somehow had it, supplier mustn't fire again
            assertEquals(1, supplierCalls.get());
        }

        @Test
        void unloadRemovesCallback() {
            ToolCatalog cat = catalogWith("calculator");
            ToolLoader loader = new ToolLoader(cat);
            loader.registerCallback("calculator", stubCallback("calculator"));

            loader.requestLoad("calculator");
            loader.applyPendingLoads();
            assertTrue(loader.isLoaded("calculator"));

            assertTrue(loader.unload("calculator").isPresent());
            assertFalse(loader.isLoaded("calculator"));
            assertTrue(loader.unload("calculator").isEmpty(),
                    "second unload returns empty");
        }

        @Test
        void resetClearsEverything() {
            ToolCatalog cat = catalogWith("a", "b", "c");
            ToolLoader loader = new ToolLoader(cat);
            for (String n : List.of("a", "b", "c")) {
                loader.registerCallback(n, stubCallback(n));
                loader.requestLoad(n);
            }
            loader.applyPendingLoads();
            assertEquals(3, loader.loadedCount());

            loader.reset();
            assertEquals(0, loader.loadedCount());
            assertEquals(0, loader.pendingCount());
        }

        @Test
        void loadedNamesPreserveLoadOrder() {
            ToolCatalog cat = catalogWith("c", "a", "b");
            ToolLoader loader = new ToolLoader(cat);
            for (String n : List.of("c", "a", "b")) {
                loader.registerCallback(n, stubCallback(n));
            }

            loader.requestLoad("a");
            loader.applyPendingLoads();
            loader.requestLoad("b");
            loader.applyPendingLoads();
            loader.requestLoad("c");
            loader.applyPendingLoads();

            assertEquals(List.of("a", "b", "c"), loader.loadedNames());
        }

        @Test
        void loadedCallbacksReturnsImmutableSnapshot() {
            ToolCatalog cat = catalogWith("a");
            ToolLoader loader = new ToolLoader(cat);
            loader.registerCallback("a", stubCallback("a"));
            loader.requestLoad("a");
            loader.applyPendingLoads();

            assertThrows(UnsupportedOperationException.class,
                    () -> loader.loadedCallbacks().clear());
        }

        @Test
        void catalogAccessor() {
            ToolCatalog cat = catalogWith("a");
            ToolLoader loader = new ToolLoader(cat);
            assertSame(cat, loader.catalog());
        }
    }

    // =================================================================
    // Concurrency
    // =================================================================
    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        @Test
        void parallelRequestLoadsAllSurvive() throws Exception {
            ToolCatalog cat = new ToolCatalog();
            for (int i = 0; i < 50; i++) {
                cat.register(CatalogEntry.of("tool_" + i, "t" + i));
            }
            ToolLoader loader = new ToolLoader(cat);
            for (int i = 0; i < 50; i++) {
                final int idx = i;
                loader.registerCallback("tool_" + idx, () -> stubCallback("tool_" + idx));
            }

            ExecutorService exec = Executors.newFixedThreadPool(8);
            CountDownLatch start = new CountDownLatch(1);
            for (int i = 0; i < 50; i++) {
                final int idx = i;
                exec.submit(() -> {
                    try {
                        start.await();
                        loader.requestLoad("tool_" + idx);
                    } catch (InterruptedException ignored) {}
                });
            }
            start.countDown();
            exec.shutdown();
            assertTrue(exec.awaitTermination(10, TimeUnit.SECONDS));

            int loaded = loader.applyPendingLoads();
            assertEquals(50, loaded, "every request should result in a loaded tool");
            assertEquals(50, loader.loadedCount());
            assertEquals(0, loader.pendingCount());
        }
    }

    // =================================================================
    // ToolLoadTool — agent-callable bridge
    // =================================================================
    @Nested
    @DisplayName("ToolLoadTool")
    class ToolBridge {

        @Test
        void handleLoadsKnownTool() {
            ToolCatalog cat = catalogWith("calculator");
            ToolLoader loader = new ToolLoader(cat);
            loader.registerCallback("calculator", stubCallback("calculator"));

            ToolLoadTool.ToolLoadInput in = new ToolLoadTool.ToolLoadInput();
            in.name = "calculator";

            String out = ToolLoadTool.handle(loader, in);
            assertTrue(out.startsWith("Tool 'calculator' loaded"), out);
            assertTrue(loader.isPendingLoad("calculator"));
        }

        @Test
        void handleAlreadyLoadedReturnsHint() {
            ToolCatalog cat = catalogWith("calculator");
            ToolLoader loader = new ToolLoader(cat);
            loader.registerCallback("calculator", stubCallback("calculator"));
            loader.requestLoad("calculator");
            loader.applyPendingLoads();

            ToolLoadTool.ToolLoadInput in = new ToolLoadTool.ToolLoadInput();
            in.name = "calculator";

            String out = ToolLoadTool.handle(loader, in);
            assertTrue(out.contains("already loaded"));
            assertTrue(out.contains("call it directly"));
        }

        @Test
        void handleNotInCatalogReturnsRecoveryHint() {
            ToolCatalog cat = catalogWith("calculator");
            ToolLoader loader = new ToolLoader(cat);

            ToolLoadTool.ToolLoadInput in = new ToolLoadTool.ToolLoadInput();
            in.name = "calculator2";

            String out = ToolLoadTool.handle(loader, in);
            assertTrue(out.startsWith("Error:"));
            assertTrue(out.contains("not in the catalog"));
            assertTrue(out.contains("tool_search"),
                    "should suggest using tool_search first: " + out);
        }

        @Test
        void handleNoCallbackRegisteredSuggestsAlternative() {
            ToolCatalog cat = catalogWith("calculator");
            ToolLoader loader = new ToolLoader(cat);
            // No callback registered

            ToolLoadTool.ToolLoadInput in = new ToolLoadTool.ToolLoadInput();
            in.name = "calculator";

            String out = ToolLoadTool.handle(loader, in);
            assertTrue(out.startsWith("Error:"));
            assertTrue(out.contains("no callback registered"));
            assertTrue(out.contains("Try a different tool"));
        }

        @Test
        void handleBlankNameRejected() {
            ToolLoader loader = new ToolLoader(new ToolCatalog());
            ToolLoadTool.ToolLoadInput in = new ToolLoadTool.ToolLoadInput();
            in.name = "  ";

            String out = ToolLoadTool.handle(loader, in);
            assertTrue(out.startsWith("Error:"));
        }

        @Test
        void handleNullInputRejected() {
            ToolLoader loader = new ToolLoader(new ToolCatalog());
            String out = ToolLoadTool.handle(loader, null);
            assertTrue(out.startsWith("Error:"));
        }

        @Test
        void buildCallbackHasExpectedFunctionName() {
            ToolLoader loader = new ToolLoader(new ToolCatalog());
            assertEquals("tool_load",
                    ToolLoadTool.callback(loader).getToolDefinition().name());
        }

        @Test
        void buildCallbackAcceptsCustomFunctionName() {
            ToolLoader loader = new ToolLoader(new ToolCatalog());
            assertEquals("install_tool",
                    ToolLoadTool.callback(loader, "install_tool").getToolDefinition().name());
        }
    }

    // =================================================================
    // End-to-end flow simulating the multi-turn orchestration
    // =================================================================
    @Nested
    @DisplayName("end-to-end flow")
    class EndToEnd {

        @Test
        void multiTurnSimulation() {
            // Catalog of 3 tools
            ToolCatalog cat = catalogWith("calculator", "current_time", "weather");
            ToolLoader loader = new ToolLoader(cat);
            loader.registerCallback("calculator", stubCallback("calculator"));
            loader.registerCallback("current_time", stubCallback("current_time"));
            loader.registerCallback("weather", stubCallback("weather"));

            // Turn 1: agent requests calculator + current_time
            assertEquals(LoadRequestStatus.PENDING, loader.requestLoad("calculator"));
            assertEquals(LoadRequestStatus.PENDING, loader.requestLoad("current_time"));

            int loaded1 = loader.applyPendingLoads();
            assertEquals(2, loaded1);

            // Harness now passes loadedCallbacks() to the next turn
            assertEquals(2, loader.loadedCallbacks().size());

            // Turn 2: agent uses what's loaded, no new request → applyPending
            // returns 0 → harness exits the loop
            int loaded2 = loader.applyPendingLoads();
            assertEquals(0, loaded2, "no new pending → exit signal for the orchestration loop");

            // Suppose the agent then requests weather mid-conversation
            loader.requestLoad("weather");
            int loaded3 = loader.applyPendingLoads();
            assertEquals(1, loaded3);
            assertEquals(3, loader.loadedCount());
        }
    }
}
