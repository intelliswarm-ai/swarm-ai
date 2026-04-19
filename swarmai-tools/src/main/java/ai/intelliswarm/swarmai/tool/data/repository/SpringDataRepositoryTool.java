package ai.intelliswarm.swarmai.tool.data.repository;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.PermissionLevel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.core.support.RepositoryFactoryInformation;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SpringDataRepositoryTool — reflectively expose Spring Data {@code Repository} beans
 * (the {@code JpaRepository} / {@code CrudRepository} interfaces every Spring Boot shop
 * already writes) as agent-callable tools.
 *
 * <p>Three operations:
 * <ul>
 *   <li>{@code list_repositories} — enumerate every {@link Repository} bean in the context,
 *       with their interface and entity type.</li>
 *   <li>{@code list_methods} — show the callable methods on one repository. By default only
 *       safe read methods (find/count/exists/get/read + inherited CrudRepository reads).</li>
 *   <li>{@code invoke} — call a method with JSON-serializable args. Args are coerced from
 *       JSON via Jackson to match the method's parameter types.</li>
 * </ul>
 *
 * <p>Permission level is {@code DANGEROUS} — this tool exposes the entire domain layer,
 * which almost certainly contains sensitive data. Gate with permission modes per tenant /
 * agent as you would any SQL tool. Write methods ({@code save}, {@code delete}, {@code update})
 * are refused by default; set {@code allow_writes=true} in a call to opt in.
 */
@Component
public class SpringDataRepositoryTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(SpringDataRepositoryTool.class);

    private static final Set<String> SAFE_PREFIXES = Set.of(
        "find", "get", "read", "count", "exists", "query", "search", "stream"
    );
    private static final Set<String> WRITE_PREFIXES = Set.of(
        "save", "delete", "remove", "update", "insert", "put"
    );
    /** Methods inherited from Object that we hide from the agent. */
    private static final Set<String> OBJECT_METHODS = Set.of(
        "equals", "hashCode", "toString", "getClass", "wait", "notify", "notifyAll", "clone", "finalize"
    );

    private final ObjectProvider<ApplicationContext> applicationContextProvider;
    private final ObjectMapper objectMapper;

    @Autowired
    public SpringDataRepositoryTool(ObjectProvider<ApplicationContext> applicationContextProvider) {
        this.applicationContextProvider = applicationContextProvider;
        this.objectMapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    // Test-friendly: let tests pass a concrete ApplicationContext directly.
    SpringDataRepositoryTool(ApplicationContext applicationContext) {
        this.applicationContextProvider = new ObjectProvider<>() {
            @Override public ApplicationContext getObject() { return applicationContext; }
            @Override public ApplicationContext getObject(Object... args) { return applicationContext; }
            @Override public ApplicationContext getIfAvailable() { return applicationContext; }
            @Override public ApplicationContext getIfUnique() { return applicationContext; }
        };
        this.objectMapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    @Override public String getFunctionName() { return "repo_query"; }

    @Override
    public String getDescription() {
        return "Query the application's Spring Data repositories (JpaRepository, CrudRepository, etc.). " +
               "operation='list_repositories' enumerates available repositories; 'list_methods' shows " +
               "callable methods on one; 'invoke' runs a method with JSON args. Write methods are refused " +
               "unless allow_writes=true. Permission: DANGEROUS — exposes the full domain layer.";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        ApplicationContext ctx = applicationContextProvider.getIfAvailable();
        if (ctx == null) {
            return "Error: no ApplicationContext available. This tool must run inside a Spring context.";
        }
        String operation = asString(parameters.getOrDefault("operation", "list_repositories")).toLowerCase();
        try {
            return switch (operation) {
                case "list_repositories" -> listRepositories(ctx);
                case "list_methods"      -> listMethods(ctx, parameters);
                case "invoke"            -> invoke(ctx, parameters);
                default -> "Error: unknown operation '" + operation +
                           "'. Use 'list_repositories', 'list_methods', or 'invoke'.";
            };
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            logger.error("SpringDataRepositoryTool error", e);
            return "Error: " + e.getClass().getSimpleName() + " — " + e.getMessage();
        }
    }

    // ---------- operations ----------

    private String listRepositories(ApplicationContext ctx) {
        Map<String, Repository> beans = ctx.getBeansOfType(Repository.class);
        if (beans.isEmpty()) return "No Spring Data Repository beans in this context.";

        StringBuilder out = new StringBuilder();
        out.append("Spring Data repositories (" + beans.size() + "):\n\n");
        beans.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> {
                Class<?> iface = findRepositoryInterface(ctx, e.getKey(), e.getValue());
                Class<?> entity = findEntityType(ctx, e.getKey());
                out.append("• **").append(e.getKey()).append("**\n");
                out.append("  interface: ").append(iface == null ? "?" : iface.getName()).append('\n');
                if (entity != null) out.append("  entity:    ").append(entity.getName()).append('\n');
            });
        return out.toString().trim();
    }

    private String listMethods(ApplicationContext ctx, Map<String, Object> parameters) {
        String beanName = requireString(parameters, "repository");
        Repository<?, ?> repo = lookupRepository(ctx, beanName);
        String resolvedName = resolveBeanName(ctx, beanName, repo);
        boolean allowWrites = parseBool(parameters, "allow_writes", false);

        Class<?> iface = findRepositoryInterface(ctx, resolvedName, repo);
        if (iface == null) return "Error: could not determine interface for bean '" + beanName + "'.";

        List<Method> methods = safeMethods(iface, allowWrites);
        if (methods.isEmpty()) {
            return "No " + (allowWrites ? "" : "safe ") + "methods found on " + iface.getSimpleName() + ".";
        }

        StringBuilder out = new StringBuilder();
        out.append("Methods on **").append(beanName).append("** (`")
           .append(iface.getName()).append("`):\n\n");
        for (Method m : methods) {
            out.append("• **").append(m.getName()).append("**(");
            Parameter[] params = m.getParameters();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) out.append(", ");
                out.append(params[i].getName()).append(": ")
                   .append(friendlyTypeName(params[i].getType()));
            }
            out.append(") → ").append(friendlyTypeName(m.getReturnType())).append('\n');
        }
        return out.toString().trim();
    }

    private String invoke(ApplicationContext ctx, Map<String, Object> parameters) throws Exception {
        String beanName = requireString(parameters, "repository");
        String methodName = requireString(parameters, "method");
        Repository<?, ?> repo = lookupRepository(ctx, beanName);
        String resolvedName = resolveBeanName(ctx, beanName, repo);
        boolean allowWrites = parseBool(parameters, "allow_writes", false);

        Class<?> iface = findRepositoryInterface(ctx, resolvedName, repo);
        if (iface == null) return "Error: could not determine interface for bean '" + beanName + "'.";

        if (!allowWrites && isWriteMethod(methodName)) {
            return "Error: method '" + methodName + "' looks like a write (save/delete/update/remove). " +
                   "Set allow_writes=true to run it — think twice, this is irreversible.";
        }

        @SuppressWarnings("unchecked")
        List<Object> rawArgs = parameters.get("args") instanceof List
            ? (List<Object>) parameters.get("args")
            : List.of();

        Method chosen = pickMethod(iface, methodName, rawArgs.size(), allowWrites);
        if (chosen == null) {
            return "Error: no method '" + methodName + "' on " + iface.getSimpleName() +
                   " with " + rawArgs.size() + " argument(s)" +
                   (allowWrites ? "" : " (among safe methods)") + ".";
        }

        // Coerce raw args → typed args via Jackson. Pageable/Sort/PageRequest get special handling.
        Object[] typedArgs = coerceArgs(chosen, rawArgs, parameters);

        logger.info("SpringDataRepositoryTool invoke: {}#{}({})", iface.getSimpleName(), methodName, rawArgs);
        Object result = chosen.invoke(repo, typedArgs);

        return formatResult(result);
    }

    // ---------- helpers: reflection & lookup ----------

    @SuppressWarnings("unchecked")
    private Repository<?, ?> lookupRepository(ApplicationContext ctx, String beanName) {
        Object bean;
        if (ctx.containsBean(beanName)) {
            bean = ctx.getBean(beanName);
        } else {
            // Allow lookup by simple class name for agent convenience.
            Map<String, Repository> all = ctx.getBeansOfType(Repository.class);
            bean = all.entrySet().stream()
                .filter(e -> {
                    Class<?> iface = findRepositoryInterface(ctx, e.getKey(), e.getValue());
                    return iface != null && iface.getSimpleName().equalsIgnoreCase(beanName);
                })
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                    "No Repository bean named or class '" + beanName + "'. " +
                    "Call list_repositories to see available ones."));
        }
        if (!(bean instanceof Repository<?, ?> r)) {
            throw new IllegalArgumentException("Bean '" + beanName + "' is not a Spring Data Repository.");
        }
        return r;
    }

    private Class<?> findRepositoryInterface(ApplicationContext ctx, String beanName, Object bean) {
        // Preferred path: Spring Data's RepositoryFactoryInformation tells us exactly which
        // user-declared interface this bean was created for (UserRepository / ProductRepository / etc.).
        // Only proxies created by Spring Data expose this; raw JDK proxies in unit tests do not.
        if (beanName != null) {
            try {
                String factoryBeanName = "&" + beanName;
                if (ctx.containsBean(factoryBeanName)) {
                    Object fb = ctx.getBean(factoryBeanName);
                    if (fb instanceof RepositoryFactoryInformation<?, ?> rfi) {
                        Class<?> declared = rfi.getRepositoryInformation().getRepositoryInterface();
                        if (declared != null) return declared;
                    }
                }
            } catch (Exception ignored) { /* fall through to reflection */ }
        }
        // Fallback: walk interfaces and pick the first user-owned one that extends Repository.
        Class<?>[] ifaces = AopUtils.isAopProxy(bean)
            ? AopUtils.getTargetClass(bean).getInterfaces()
            : bean.getClass().getInterfaces();
        if (ifaces.length == 0) ifaces = bean.getClass().getInterfaces();
        Class<?> firstRepoMatch = null;
        for (Class<?> i : ifaces) {
            if (!Repository.class.isAssignableFrom(i) || i.equals(Repository.class)) continue;
            if (firstRepoMatch == null) firstRepoMatch = i;
            // Prefer user-defined interfaces over Spring Data's own internal base interfaces.
            String pkg = i.getPackageName();
            if (!pkg.startsWith("org.springframework.data")) return i;
        }
        return firstRepoMatch;
    }

    /** Resolve the real Spring bean name so {@code &beanName} lookup works even when the user
     *  passed a simple class name. Returns null if we can't tell. */
    private String resolveBeanName(ApplicationContext ctx, String inputName, Object bean) {
        if (ctx.containsBean(inputName)) return inputName;
        Map<String, Repository> all = ctx.getBeansOfType(Repository.class);
        for (Map.Entry<String, Repository> e : all.entrySet()) {
            if (e.getValue() == bean) return e.getKey();
        }
        return null;
    }

    private Class<?> findEntityType(ApplicationContext ctx, String beanName) {
        String factoryBeanName = "&" + beanName;
        try {
            if (ctx.containsBean(factoryBeanName)) {
                Object fb = ctx.getBean(factoryBeanName);
                if (fb instanceof RepositoryFactoryInformation<?, ?> rfi) {
                    return rfi.getRepositoryInformation().getDomainType();
                }
            }
        } catch (Exception ignored) { /* best-effort */ }
        return null;
    }

    private List<Method> safeMethods(Class<?> iface, boolean allowWrites) {
        List<Method> out = new ArrayList<>();
        for (Method m : iface.getMethods()) {
            if (OBJECT_METHODS.contains(m.getName())) continue;
            if (m.isSynthetic() || m.isBridge()) continue;
            if (!allowWrites && !isSafeReadMethod(m.getName())) continue;
            out.add(m);
        }
        out.sort(Comparator.comparing(Method::getName).thenComparingInt(Method::getParameterCount));
        // Dedupe overloads with identical printable signatures
        Set<String> seen = new HashSet<>();
        List<Method> deduped = new ArrayList<>();
        for (Method m : out) {
            String sig = m.getName() + "(" + m.getParameterCount() + ")";
            if (seen.add(sig)) deduped.add(m);
        }
        return deduped;
    }

    private Method pickMethod(Class<?> iface, String name, int arity, boolean allowWrites) {
        for (Method m : iface.getMethods()) {
            if (!m.getName().equals(name)) continue;
            if (m.getParameterCount() != arity) continue;
            if (!allowWrites && isWriteMethod(m.getName())) continue;
            return m;
        }
        // Fallback: allow the agent to call Pageable-accepting overloads with one fewer arg by injecting a default pageable.
        for (Method m : iface.getMethods()) {
            if (!m.getName().equals(name)) continue;
            if (m.getParameterCount() == arity + 1 && m.getParameterTypes()[m.getParameterCount() - 1].equals(Pageable.class)) {
                if (!allowWrites && isWriteMethod(m.getName())) continue;
                return m;
            }
        }
        return null;
    }

    private boolean isSafeReadMethod(String name) {
        String lower = name.toLowerCase();
        for (String p : SAFE_PREFIXES) if (lower.startsWith(p)) return true;
        return false;
    }

    private boolean isWriteMethod(String name) {
        String lower = name.toLowerCase();
        for (String p : WRITE_PREFIXES) if (lower.startsWith(p)) return true;
        return false;
    }

    // ---------- helpers: arg coercion & result formatting ----------

    private Object[] coerceArgs(Method m, List<Object> rawArgs, Map<String, Object> parameters) throws Exception {
        Class<?>[] paramTypes = m.getParameterTypes();
        Object[] typed = new Object[paramTypes.length];
        int rawIndex = 0;
        for (int i = 0; i < paramTypes.length; i++) {
            Class<?> t = paramTypes[i];
            if (Pageable.class.isAssignableFrom(t)) {
                typed[i] = buildPageable(parameters);
            } else if (Sort.class.isAssignableFrom(t)) {
                typed[i] = buildSort(parameters);
            } else if (rawIndex < rawArgs.size()) {
                Object raw = rawArgs.get(rawIndex++);
                typed[i] = raw == null ? null : objectMapper.convertValue(raw, t);
            } else {
                typed[i] = null;
            }
        }
        return typed;
    }

    private Pageable buildPageable(Map<String, Object> parameters) {
        int page = parseInt(parameters, "page", 0, 0, Integer.MAX_VALUE);
        int size = parseInt(parameters, "size", 20, 1, 1000);
        Sort sort = buildSort(parameters);
        return sort == null ? PageRequest.of(page, size) : PageRequest.of(page, size, sort);
    }

    private Sort buildSort(Map<String, Object> parameters) {
        Object raw = parameters.get("sort");
        if (raw == null) return null;
        List<Sort.Order> orders = new ArrayList<>();
        if (raw instanceof String s && !s.isBlank()) {
            // Format: "field,dir;field2,dir"
            for (String clause : s.split(";")) {
                String[] parts = clause.trim().split(",");
                if (parts.length == 0 || parts[0].isBlank()) continue;
                Sort.Direction dir = parts.length > 1 && parts[1].trim().equalsIgnoreCase("desc")
                    ? Sort.Direction.DESC : Sort.Direction.ASC;
                orders.add(new Sort.Order(dir, parts[0].trim()));
            }
        } else if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> untyped) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) untyped;
                    String prop = String.valueOf(m.get("property"));
                    Object dirObj = m.get("direction");
                    String d = dirObj == null ? "ASC" : String.valueOf(dirObj);
                    orders.add(new Sort.Order(Sort.Direction.fromString(d), prop));
                }
            }
        }
        return orders.isEmpty() ? null : Sort.by(orders);
    }

    private String formatResult(Object result) throws Exception {
        if (result == null) return "null";
        if (result instanceof Optional<?> opt) {
            return opt.isPresent() ? objectMapper.writeValueAsString(opt.get()) : "(empty)";
        }
        if (result instanceof Page<?> page) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("page", page.getNumber());
            summary.put("size", page.getSize());
            summary.put("totalElements", page.getTotalElements());
            summary.put("totalPages", page.getTotalPages());
            summary.put("content", page.getContent());
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary);
        }
        if (result instanceof Iterable<?> it) {
            List<Object> list = new ArrayList<>();
            it.forEach(list::add);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(list);
        }
        if (result.getClass().isArray()) {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        }
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
    }

    // ---------- small helpers ----------

    private static String friendlyTypeName(Class<?> c) {
        if (c == null) return "void";
        if (c.isArray()) return friendlyTypeName(c.getComponentType()) + "[]";
        return c.getSimpleName();
    }

    private static String asString(Object v) { return v == null ? null : v.toString(); }

    private static String requireString(Map<String, Object> parameters, String key) {
        String v = asString(parameters.get(key));
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("'" + key + "' parameter is required.");
        }
        return v;
    }

    private static boolean parseBool(Map<String, Object> parameters, String key, boolean def) {
        Object raw = parameters.get(key);
        if (raw instanceof Boolean b) return b;
        if (raw == null) return def;
        return Boolean.parseBoolean(raw.toString());
    }

    private static int parseInt(Map<String, Object> parameters, String key, int def, int min, int max) {
        Object raw = parameters.get(key);
        if (raw == null) return def;
        try {
            int n = raw instanceof Number ? ((Number) raw).intValue() : Integer.parseInt(raw.toString().trim());
            return Math.max(min, Math.min(max, n));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ---------- metadata ----------

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();

        Map<String, Object> operation = new HashMap<>();
        operation.put("type", "string");
        operation.put("enum", List.of("list_repositories", "list_methods", "invoke"));
        operation.put("description", "Which meta-operation to perform. Default: 'list_repositories'.");
        props.put("operation", operation);

        addStringProp(props, "repository", "Bean name (or simple interface name) of the repository.");
        addStringProp(props, "method", "Method name to invoke (e.g. 'findByEmail').");

        Map<String, Object> args = new HashMap<>();
        args.put("type", "array");
        args.put("description", "JSON-serializable argument list matching the method signature.");
        props.put("args", args);

        Map<String, Object> allowWrites = new HashMap<>();
        allowWrites.put("type", "boolean");
        allowWrites.put("description", "Opt-in to invoke save/delete/update methods. Default false.");
        props.put("allow_writes", allowWrites);

        Map<String, Object> page = new HashMap<>();
        page.put("type", "integer");
        page.put("description", "Pageable: page index (0-based). Used when the method accepts Pageable.");
        props.put("page", page);

        Map<String, Object> size = new HashMap<>();
        size.put("type", "integer");
        size.put("description", "Pageable: page size (1..1000). Default 20.");
        props.put("size", size);

        addStringProp(props, "sort", "Sort spec: 'field,dir;field2,dir' (e.g. 'createdAt,desc').");

        schema.put("properties", props);
        schema.put("required", new String[]{});
        return schema;
    }

    private static void addStringProp(Map<String, Object> props, String name, String desc) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", "string");
        m.put("description", desc);
        props.put(name, m);
    }

    @Override public boolean isAsync() { return false; }
    @Override public boolean isCacheable() { return false; }
    @Override public String getCategory() { return "data"; }
    @Override public List<String> getTags() { return List.of("spring-data", "jpa", "repository", "domain"); }
    @Override public PermissionLevel getPermissionLevel() { return PermissionLevel.DANGEROUS; }

    @Override
    public String getTriggerWhen() {
        return "User asks about domain objects that live in the application's database — customers, " +
               "orders, products, users, etc. — and the answer is a read query against a Spring Data " +
               "repository. Prefer this over raw SQL for type safety.";
    }

    @Override
    public String getAvoidWhen() {
        return "Data is outside the application's JPA domain (external SaaS, logs, files) — use the " +
               "specific tool (http_request, database_query, s3_object) instead.";
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return Map.of("type", "markdown",
            "description", "Repo/method listings as markdown; invoke results as pretty-printed JSON.");
    }

    @Override
    public String smokeTest() {
        ApplicationContext ctx = applicationContextProvider.getIfAvailable();
        if (ctx == null) return "no ApplicationContext available";
        return null; // healthy even with zero repositories — the tool just lists nothing
    }

    public record Request(String operation, String repository, String method, List<Object> args,
                          Boolean allow_writes, Integer page, Integer size, String sort) {}
}
