package ai.intelliswarm.swarmai.tool.data.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.data.repository.CrudRepository;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("SpringDataRepositoryTool Unit Tests")
class SpringDataRepositoryToolTest {

    // ---------- domain + repository test doubles ----------

    public record User(Long id, String email, String name) {}

    public interface UserRepository extends CrudRepository<User, Long> {
        List<User> findByEmail(String email);
        long countByName(String name);
        boolean existsByEmail(String email);
        List<User> findAllByOrderByNameAsc();
        // A write method we'll use to verify the refuse-by-default guard.
        void deleteByEmail(String email);
    }

    /**
     * Build a JDK proxy that implements UserRepository. The handler dispatches each call
     * to a map of canned responses or a simple Java Predicate.
     */
    private static UserRepository buildFakeRepo() {
        User alice = new User(1L, "alice@example.com", "Alice");
        User bob   = new User(2L, "bob@example.com",   "Bob");
        List<User> all = List.of(alice, bob);

        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "findByEmail"               -> all.stream().filter(u -> u.email().equals(args[0])).toList();
            case "findAllByOrderByNameAsc"   -> all;
            case "countByName"               -> all.stream().filter(u -> u.name().equals(args[0])).count();
            case "existsByEmail"             -> all.stream().anyMatch(u -> u.email().equals(args[0]));
            case "findById"                  -> all.stream().filter(u -> u.id().equals(args[0])).findFirst();
            case "findAll"                   -> all;
            case "count"                     -> (long) all.size();
            case "existsById"                -> all.stream().anyMatch(u -> u.id().equals(args[0]));
            case "deleteByEmail", "deleteById", "delete", "deleteAll", "deleteAllById",
                 "save", "saveAll"           -> null;
            // Object methods
            case "toString"                  -> "FakeUserRepository";
            case "hashCode"                  -> System.identityHashCode(proxy);
            case "equals"                    -> proxy == args[0];
            default -> throw new UnsupportedOperationException("Unexpected " + method);
        };
        return (UserRepository) Proxy.newProxyInstance(
            SpringDataRepositoryToolTest.class.getClassLoader(),
            new Class<?>[]{UserRepository.class},
            handler);
    }

    private ApplicationContext ctx;
    private SpringDataRepositoryTool tool;
    private UserRepository fakeRepo;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        ctx = mock(ApplicationContext.class);
        fakeRepo = buildFakeRepo();
        mapper = new ObjectMapper();

        when(ctx.getBeansOfType(org.springframework.data.repository.Repository.class))
            .thenReturn(Map.of("userRepository", fakeRepo));
        when(ctx.containsBean("userRepository")).thenReturn(true);
        when(ctx.getBean("userRepository")).thenReturn(fakeRepo);

        tool = new SpringDataRepositoryTool(ctx);
    }

    // ===== interface =====

    @Test void functionName() { assertEquals("repo_query", tool.getFunctionName()); }

    @Test void dangerousPermission() {
        assertEquals(ai.intelliswarm.swarmai.tool.base.PermissionLevel.DANGEROUS,
            tool.getPermissionLevel());
    }

    // ===== list_repositories =====

    @Test
    @DisplayName("list_repositories: shows bean name + interface")
    void listRepositories() {
        Object out = tool.execute(Map.of("operation", "list_repositories"));
        String s = out.toString();
        assertTrue(s.contains("userRepository"));
        assertTrue(s.contains("UserRepository"), s);
        assertTrue(s.contains("1"), "Should indicate 1 repo");
    }

    @Test
    @DisplayName("list_repositories: empty context is handled cleanly")
    void listRepositoriesEmpty() {
        ApplicationContext emptyCtx = mock(ApplicationContext.class);
        when(emptyCtx.getBeansOfType(org.springframework.data.repository.Repository.class))
            .thenReturn(Map.of());
        SpringDataRepositoryTool t = new SpringDataRepositoryTool(emptyCtx);
        Object out = t.execute(Map.of("operation", "list_repositories"));
        assertTrue(out.toString().contains("No Spring Data Repository"));
    }

    // ===== list_methods =====

    @Test
    @DisplayName("list_methods: only safe reads by default; deleteByEmail is hidden")
    void listSafeMethods() {
        Object out = tool.execute(Map.of("operation", "list_methods", "repository", "userRepository"));
        String s = out.toString();
        // Safe method shows up
        assertTrue(s.contains("findByEmail"));
        assertTrue(s.contains("countByName"));
        assertTrue(s.contains("existsByEmail"));
        assertTrue(s.contains("findAll"));
        // Writes are hidden
        assertFalse(s.contains("deleteByEmail"), s);
        assertFalse(s.contains("save"), s);
    }

    @Test
    @DisplayName("list_methods with allow_writes=true exposes delete/save")
    void listMethodsIncludeWrites() {
        Object out = tool.execute(Map.of(
            "operation", "list_methods",
            "repository", "userRepository",
            "allow_writes", true));
        String s = out.toString();
        assertTrue(s.contains("deleteByEmail"), s);
    }

    @Test
    @DisplayName("list_methods: bean can be looked up by simple class name too")
    void listMethodsByClassName() {
        Object out = tool.execute(Map.of("operation", "list_methods", "repository", "UserRepository"));
        // This matches either by bean name OR interface simple name. The mock has bean name
        // 'userRepository' — not exactly 'UserRepository'. The ctx.containsBean("UserRepository")
        // returns false, so the tool falls through to scanning getBeansOfType() and matching by
        // interface name. That path must work.
        assertFalse(out.toString().startsWith("Error"), out.toString());
        assertTrue(out.toString().contains("findByEmail"));
    }

    @Test
    @DisplayName("list_methods: unknown repository name → clear error with hint")
    void listMethodsUnknown() {
        Object out = tool.execute(Map.of("operation", "list_methods", "repository", "ghostRepository"));
        assertTrue(out.toString().startsWith("Error"));
        assertTrue(out.toString().contains("list_repositories"));
    }

    @Test
    @DisplayName("list_methods: missing 'repository' → error")
    void listMethodsMissingRepo() {
        Object out = tool.execute(Map.of("operation", "list_methods"));
        assertTrue(out.toString().startsWith("Error"));
    }

    // ===== invoke =====

    @Test
    @DisplayName("invoke: findByEmail with real arg returns Alice as JSON list")
    void invokeFindByEmail() throws Exception {
        Object out = tool.execute(Map.of(
            "operation", "invoke",
            "repository", "userRepository",
            "method", "findByEmail",
            "args", List.of("alice@example.com")));

        String s = out.toString();
        // Output is pretty-printed JSON
        JsonNode node = mapper.readTree(s);
        assertTrue(node.isArray());
        assertEquals(1, node.size());
        assertEquals("Alice", node.get(0).path("name").asText());
        assertEquals("alice@example.com", node.get(0).path("email").asText());
    }

    @Test
    @DisplayName("invoke: countByName returns a primitive long, serialized as a number")
    void invokeCount() throws Exception {
        Object out = tool.execute(Map.of(
            "operation", "invoke",
            "repository", "userRepository",
            "method", "countByName",
            "args", List.of("Alice")));
        JsonNode node = mapper.readTree(out.toString());
        assertEquals(1, node.asInt());
    }

    @Test
    @DisplayName("invoke: existsByEmail returns a boolean")
    void invokeExists() throws Exception {
        Object out = tool.execute(Map.of(
            "operation", "invoke",
            "repository", "userRepository",
            "method", "existsByEmail",
            "args", List.of("nobody@example.com")));
        assertEquals("false", out.toString());
    }

    @Test
    @DisplayName("invoke: findById returns an Optional — 'empty' if not present")
    void invokeFindByIdEmpty() {
        Object out = tool.execute(Map.of(
            "operation", "invoke",
            "repository", "userRepository",
            "method", "findById",
            "args", List.of(99)));
        assertEquals("(empty)", out.toString());
    }

    @Test
    @DisplayName("invoke: findById unwraps Optional.get() when present")
    void invokeFindByIdPresent() throws Exception {
        Object out = tool.execute(Map.of(
            "operation", "invoke",
            "repository", "userRepository",
            "method", "findById",
            "args", List.of(1)));
        JsonNode node = mapper.readTree(out.toString());
        assertEquals("Alice", node.path("name").asText());
    }

    @Test
    @DisplayName("invoke: integer 'id' is coerced from JSON to Long via Jackson")
    void invokeArgCoercion() throws Exception {
        Object out = tool.execute(Map.of(
            "operation", "invoke",
            "repository", "userRepository",
            "method", "findById",
            "args", List.of(1))); // Integer, not Long — must be coerced
        JsonNode node = mapper.readTree(out.toString());
        assertEquals("Alice", node.path("name").asText());
    }

    @Test
    @DisplayName("invoke: write method refused without allow_writes")
    void invokeWriteRefused() {
        Object out = tool.execute(Map.of(
            "operation", "invoke",
            "repository", "userRepository",
            "method", "deleteByEmail",
            "args", List.of("alice@example.com")));
        assertTrue(out.toString().contains("write"), out.toString());
        assertTrue(out.toString().contains("allow_writes=true"));
    }

    @Test
    @DisplayName("invoke: write method allowed with allow_writes=true")
    void invokeWriteAllowed() {
        Object out = tool.execute(Map.of(
            "operation", "invoke",
            "repository", "userRepository",
            "method", "deleteByEmail",
            "args", List.of("alice@example.com"),
            "allow_writes", true));
        // deleteByEmail returns void → formatter returns "null"
        assertEquals("null", out.toString());
    }

    @Test
    @DisplayName("invoke: wrong arity → error, no method invocation")
    void invokeWrongArity() {
        Object out = tool.execute(Map.of(
            "operation", "invoke",
            "repository", "userRepository",
            "method", "findByEmail",
            "args", List.of())); // missing the email arg
        assertTrue(out.toString().startsWith("Error"));
        assertTrue(out.toString().contains("0 argument"), out.toString());
    }

    @Test
    @DisplayName("invoke: unknown method name → helpful error")
    void invokeUnknownMethod() {
        Object out = tool.execute(Map.of(
            "operation", "invoke",
            "repository", "userRepository",
            "method", "findByFavoriteColor",
            "args", List.of("blue")));
        assertTrue(out.toString().startsWith("Error"));
        assertTrue(out.toString().contains("no method"));
    }

    @Test
    @DisplayName("invoke: findAll Iterable is serialized as a JSON array")
    void invokeFindAll() throws Exception {
        Object out = tool.execute(Map.of(
            "operation", "invoke",
            "repository", "userRepository",
            "method", "findAll"));
        JsonNode node = mapper.readTree(out.toString());
        assertTrue(node.isArray());
        assertEquals(2, node.size());
    }

    @Test
    @DisplayName("unknown operation → helpful error")
    void unknownOp() {
        Object out = tool.execute(Map.of("operation", "exec"));
        assertTrue(out.toString().contains("unknown operation"));
    }
}
