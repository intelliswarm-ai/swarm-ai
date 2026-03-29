package ai.intelliswarm.swarmai.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Persistent cache for scan results (e.g., nmap output).
 * <p>
 * Saves results as JSON files under {@code output/scan_results/} so that
 * subsequent workflow runs can skip re-scanning hosts that were already
 * scanned recently. Stale entries (older than the configured expiry) are
 * automatically evicted on read.
 */
public class ScanResultCache {

    private static final Logger logger = LoggerFactory.getLogger(ScanResultCache.class);
    private static final Path DEFAULT_CACHE_DIR = Paths.get("output", "scan_results");
    private static final Duration DEFAULT_EXPIRY = Duration.ofHours(4);

    private final Path cacheDir;
    private final Duration expiry;
    private final ObjectMapper objectMapper;
    private final Map<String, CachedScanResult> cache = new ConcurrentHashMap<>();

    /**
     * Immutable representation of a cached scan result.
     */
    public record CachedScanResult(
        String hostIp,
        List<String> openPorts,
        List<String> services,
        String rawOutput,
        String scanCommand,
        LocalDateTime timestamp
    ) {}

    /**
     * Create a cache with default directory ({@code output/scan_results/})
     * and default expiry (4 hours).
     */
    public ScanResultCache() {
        this(DEFAULT_CACHE_DIR, DEFAULT_EXPIRY);
    }

    /**
     * Create a cache with a custom directory and expiry duration.
     *
     * @param cacheDir directory to store/load JSON cache files
     * @param expiry   maximum age of a cached result before it is considered stale
     */
    public ScanResultCache(Path cacheDir, Duration expiry) {
        this.cacheDir = cacheDir;
        this.expiry = expiry;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    // ========================= Load =========================

    /**
     * Load all cached scan results from disk ({@code *.json} files in the cache directory).
     *
     * @return the number of entries successfully loaded
     */
    public int loadAll() {
        if (!Files.isDirectory(cacheDir)) {
            logger.debug("Scan cache directory does not exist: {}", cacheDir);
            return 0;
        }

        int loaded = 0;
        try (Stream<Path> files = Files.list(cacheDir)) {
            List<Path> jsonFiles = files
                .filter(p -> p.toString().endsWith(".json"))
                .collect(Collectors.toList());

            for (Path file : jsonFiles) {
                try {
                    CachedScanResult result = objectMapper.readValue(file.toFile(), CachedScanResult.class);
                    if (result.hostIp() != null && !isStale(result)) {
                        cache.put(result.hostIp(), result);
                        loaded++;
                    } else if (result.hostIp() != null) {
                        logger.debug("Skipping stale cache entry for {}", result.hostIp());
                    }
                } catch (IOException e) {
                    logger.warn("Failed to load scan cache file {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to list scan cache directory {}: {}", cacheDir, e.getMessage());
        }

        logger.debug("Loaded {} cached scan results from {}", loaded, cacheDir);
        return loaded;
    }

    // ========================= Save =========================

    /**
     * Save a single scan result, parsing open ports and services from the raw output.
     *
     * @param hostIp      the target host IP address
     * @param scanCommand the command that was executed (e.g., {@code nmap -sV ...})
     * @param rawOutput   the raw text output from the scan tool
     */
    public void save(String hostIp, String scanCommand, String rawOutput) {
        List<String> openPorts = extractPorts(rawOutput);
        List<String> services = extractServices(rawOutput);

        CachedScanResult result = new CachedScanResult(
            hostIp, openPorts, services, rawOutput, scanCommand, LocalDateTime.now()
        );

        cache.put(hostIp, result);

        // Persist to disk immediately
        try {
            Files.createDirectories(cacheDir);
            // Sanitize the IP for use as a filename (replace colons for IPv6)
            String filename = hostIp.replace(':', '_') + ".json";
            Path file = cacheDir.resolve(filename);
            objectMapper.writeValue(file.toFile(), result);
            logger.debug("Cached scan result for {} -> {}", hostIp, file);
        } catch (IOException e) {
            logger.warn("Failed to persist scan cache for {}: {}", hostIp, e.getMessage());
        }
    }

    /**
     * Save all in-memory cached results to disk.
     */
    public void saveAll() {
        if (cache.isEmpty()) return;

        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            logger.warn("Failed to create scan cache directory {}: {}", cacheDir, e.getMessage());
            return;
        }

        int saved = 0;
        for (CachedScanResult result : cache.values()) {
            if (!isStale(result)) {
                try {
                    String filename = result.hostIp().replace(':', '_') + ".json";
                    Path file = cacheDir.resolve(filename);
                    objectMapper.writeValue(file.toFile(), result);
                    saved++;
                } catch (IOException e) {
                    logger.warn("Failed to persist scan cache for {}: {}", result.hostIp(), e.getMessage());
                }
            }
        }
        logger.info("Saved {} scan cache entries to {}", saved, cacheDir);
    }

    // ========================= Get =========================

    /**
     * Get the cached scan result for a host, if present and not stale.
     *
     * @param hostIp the target host IP address
     * @return the cached result, or empty if not found or stale
     */
    public Optional<CachedScanResult> get(String hostIp) {
        CachedScanResult result = cache.get(hostIp);
        if (result == null) return Optional.empty();
        if (isStale(result)) {
            cache.remove(hostIp);
            return Optional.empty();
        }
        return Optional.of(result);
    }

    /**
     * Get all non-stale cached results.
     *
     * @return unmodifiable map of hostIp to CachedScanResult
     */
    public Map<String, CachedScanResult> getAll() {
        cache.values().removeIf(this::isStale);
        return Collections.unmodifiableMap(cache);
    }

    // ========================= Staleness =========================

    /**
     * Check whether a cached result has exceeded the expiry duration.
     *
     * @param result the cached scan result to check
     * @return true if the result is stale and should not be used
     */
    public boolean isStale(CachedScanResult result) {
        return Duration.between(result.timestamp(), LocalDateTime.now()).compareTo(expiry) > 0;
    }

    // ========================= Context Generation =========================

    /**
     * Generate a human-readable context string for injection into agent prompts.
     * Returns null if the cache is empty (all entries stale or no entries at all).
     *
     * @return context string describing cached scan results, or null
     */
    public String toContextString() {
        // Remove stale entries first
        cache.values().removeIf(this::isStale);

        if (cache.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("CACHED SCAN RESULTS FROM PREVIOUS RUNS (skip re-scanning these unless verifying changes):\n");

        for (CachedScanResult r : cache.values()) {
            sb.append("- ").append(r.hostIp()).append(": ");
            if (r.openPorts().isEmpty()) {
                sb.append("no open ports");
            } else {
                sb.append("ports ").append(String.join(",", r.openPorts()));
                if (!r.services().isEmpty()) {
                    sb.append(" (").append(String.join(", ", r.services())).append(")");
                }
            }
            sb.append(" [scanned ")
              .append(r.timestamp().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
              .append("]\n");
        }

        return sb.toString();
    }

    // ========================= Parsing Helpers =========================

    /**
     * Extract open port numbers from nmap-style output.
     * Matches patterns like {@code 80/tcp   open  http}.
     *
     * @param output raw scan output text
     * @return list of port number strings
     */
    static List<String> extractPorts(String output) {
        List<String> ports = new ArrayList<>();
        if (output == null) return ports;
        Pattern p = Pattern.compile("(\\d+)/tcp\\s+open");
        Matcher m = p.matcher(output);
        while (m.find()) {
            String port = m.group(1);
            if (!ports.contains(port)) {
                ports.add(port);
            }
        }
        return ports;
    }

    /**
     * Extract service names from nmap-style output.
     * Matches patterns like {@code 80/tcp   open  http   lighttpd}.
     *
     * @param output raw scan output text
     * @return list of service name strings
     */
    static List<String> extractServices(String output) {
        List<String> services = new ArrayList<>();
        if (output == null) return services;
        Pattern p = Pattern.compile("\\d+/tcp\\s+open\\s+(\\S+)");
        Matcher m = p.matcher(output);
        while (m.find()) {
            String service = m.group(1);
            if (!services.contains(service)) {
                services.add(service);
            }
        }
        return services;
    }
}
