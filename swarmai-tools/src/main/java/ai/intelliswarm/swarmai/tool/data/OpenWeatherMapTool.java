package ai.intelliswarm.swarmai.tool.data;

import ai.intelliswarm.swarmai.tool.base.BaseTool;
import ai.intelliswarm.swarmai.tool.base.ToolRequirements;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenWeatherMap Tool — current weather & 5-day forecast by city or lat/lon.
 *
 * Requires a free OPENWEATHER_API_KEY from https://openweathermap.org/api.
 * Free tier allows 60 calls/min, 1,000,000 calls/month.
 *
 * Operations:
 *   - current  : current conditions (default)
 *   - forecast : 5-day / 3-hour forecast
 *
 * Units:
 *   - metric (default)   : °C, m/s
 *   - imperial           : °F, mph
 *   - standard           : K, m/s
 */
@Component
public class OpenWeatherMapTool implements BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(OpenWeatherMapTool.class);
    private static final String API_BASE = "https://api.openweathermap.org/data/2.5";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${swarmai.tools.openweather.api-key:}")
    private String apiKey = "";

    public OpenWeatherMapTool() {
        this(new RestTemplate(), new ObjectMapper());
    }

    OpenWeatherMapTool(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Override public String getFunctionName() { return "weather"; }

    @Override
    public String getDescription() {
        return "Get current weather or a 5-day / 3-hour forecast for any city (e.g. 'London') or " +
               "coordinate pair (lat/lon). Supports metric, imperial, and standard (Kelvin) units. " +
               "Requires OPENWEATHER_API_KEY.";
    }

    @Override
    public Object execute(Map<String, Object> parameters) {
        String effectiveKey = pickKey(parameters);
        if (effectiveKey == null || effectiveKey.isBlank()) {
            return "Error: OpenWeatherMap API key not configured. Set OPENWEATHER_API_KEY env var " +
                   "or swarmai.tools.openweather.api-key property.";
        }

        String operation = asString(parameters.getOrDefault("operation", "current")).toLowerCase();
        String location = resolveLocation(parameters);
        if (location == null) {
            return "Error: provide either 'city' or both 'lat' and 'lon' parameters.";
        }
        String units = asString(parameters.getOrDefault("units", "metric")).toLowerCase();
        if (!List.of("metric", "imperial", "standard").contains(units)) {
            return "Error: 'units' must be 'metric', 'imperial', or 'standard'.";
        }

        logger.info("OpenWeatherMapTool: op={} location={} units={}", operation, location, units);

        try {
            return switch (operation) {
                case "current"  -> fetchCurrent(effectiveKey, location, units);
                case "forecast" -> fetchForecast(effectiveKey, location, units);
                default -> "Error: unknown operation '" + operation + "'. Use 'current' or 'forecast'.";
            };
        } catch (HttpClientErrorException.NotFound e) {
            return "Error: location not found — '" + location + "'. Try a different city name or provide lat/lon.";
        } catch (HttpClientErrorException.Unauthorized e) {
            return "Error: OpenWeatherMap rejected the API key (401). " +
                   "Ensure OPENWEATHER_API_KEY is active — new keys take up to 2 hours to become valid.";
        } catch (RestClientException e) {
            logger.warn("OpenWeatherMapTool network error: {}", e.getMessage());
            return "Error: OpenWeatherMap request failed — " + e.getMessage();
        } catch (Exception e) {
            logger.error("OpenWeatherMapTool unexpected error", e);
            return "Error: " + e.getMessage();
        }
    }

    // ---------- operations ----------

    private String fetchCurrent(String key, String location, String units) throws Exception {
        URI uri = URI.create(API_BASE + "/weather?" + location + "&units=" + units + "&appid=" + enc(key));
        ResponseEntity<String> response = restTemplate.exchange(
            uri, HttpMethod.GET, new HttpEntity<>(null), String.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            return "Error: OpenWeatherMap returned HTTP " + response.getStatusCode().value();
        }
        return formatCurrent(response.getBody(), units);
    }

    private String fetchForecast(String key, String location, String units) throws Exception {
        URI uri = URI.create(API_BASE + "/forecast?" + location + "&units=" + units + "&appid=" + enc(key));
        ResponseEntity<String> response = restTemplate.exchange(
            uri, HttpMethod.GET, new HttpEntity<>(null), String.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            return "Error: OpenWeatherMap returned HTTP " + response.getStatusCode().value();
        }
        return formatForecast(response.getBody(), units);
    }

    // ---------- formatting ----------

    private String formatCurrent(String json, String units) throws Exception {
        JsonNode n = objectMapper.readTree(json);
        String city = n.path("name").asText("unknown");
        String country = n.path("sys").path("country").asText("");
        JsonNode main = n.path("main");
        JsonNode wind = n.path("wind");
        JsonNode weather = n.path("weather").isArray() && n.path("weather").size() > 0
            ? n.path("weather").get(0) : null;

        String tempUnit = tempUnit(units);
        String windUnit = windUnit(units);

        StringBuilder out = new StringBuilder();
        out.append("**Weather in ").append(city);
        if (!country.isBlank()) out.append(", ").append(country);
        out.append("**\n\n");
        if (weather != null) {
            out.append("Conditions: ").append(cap(weather.path("description").asText()))
               .append(" (").append(weather.path("main").asText()).append(")\n");
        }
        out.append(String.format("Temperature: %.1f%s (feels like %.1f%s)%n",
            main.path("temp").asDouble(), tempUnit, main.path("feels_like").asDouble(), tempUnit));
        out.append("Humidity: ").append(main.path("humidity").asInt()).append("%\n");
        out.append(String.format("Wind: %.1f %s%n", wind.path("speed").asDouble(), windUnit));
        long sunriseUnix = n.path("sys").path("sunrise").asLong();
        long sunsetUnix = n.path("sys").path("sunset").asLong();
        if (sunriseUnix > 0 && sunsetUnix > 0) {
            int tzOffset = n.path("timezone").asInt(0);
            out.append("Sunrise: ").append(localTime(sunriseUnix, tzOffset))
               .append("   Sunset: ").append(localTime(sunsetUnix, tzOffset)).append('\n');
        }
        return out.toString().trim();
    }

    private String formatForecast(String json, String units) throws Exception {
        JsonNode n = objectMapper.readTree(json);
        String city = n.path("city").path("name").asText("unknown");
        String country = n.path("city").path("country").asText("");
        String tempUnit = tempUnit(units);

        StringBuilder out = new StringBuilder();
        out.append("**5-day forecast for ").append(city);
        if (!country.isBlank()) out.append(", ").append(country);
        out.append("**\n\n");

        // Group forecast entries by calendar day and compute daily min/max + a representative condition.
        JsonNode list = n.path("list");
        if (!list.isArray() || list.isEmpty()) return "No forecast data returned.";

        // Preserve insertion order across days.
        LinkedHashMap<String, double[]> dayRanges = new LinkedHashMap<>();
        LinkedHashMap<String, String> dayCondition = new LinkedHashMap<>();
        DateTimeFormatter dayFmt = DateTimeFormatter.ofPattern("EEE MMM d");

        for (JsonNode slot : list) {
            long dt = slot.path("dt").asLong();
            String day = dayFmt.format(Instant.ofEpochSecond(dt).atOffset(ZoneOffset.UTC).toLocalDate());
            double t = slot.path("main").path("temp").asDouble();
            double[] minMax = dayRanges.computeIfAbsent(day, k -> new double[]{t, t});
            minMax[0] = Math.min(minMax[0], t);
            minMax[1] = Math.max(minMax[1], t);
            // Prefer the midday entry's "description" as the day's representative weather.
            int hour = Instant.ofEpochSecond(dt).atOffset(ZoneOffset.UTC).getHour();
            if (hour == 12 || !dayCondition.containsKey(day)) {
                JsonNode w = slot.path("weather").isArray() && slot.path("weather").size() > 0
                    ? slot.path("weather").get(0) : null;
                if (w != null) dayCondition.put(day, cap(w.path("description").asText()));
            }
        }

        for (String day : dayRanges.keySet()) {
            double[] r = dayRanges.get(day);
            String cond = dayCondition.getOrDefault(day, "–");
            out.append(String.format("• %s — %s, %.1f%s … %.1f%s%n", day, cond, r[0], tempUnit, r[1], tempUnit));
        }
        return out.toString().trim();
    }

    // ---------- helpers ----------

    private String resolveLocation(Map<String, Object> parameters) {
        String city = asString(parameters.get("city"));
        if (city != null && !city.isBlank()) return "q=" + enc(city);
        Object lat = parameters.get("lat");
        Object lon = parameters.get("lon");
        if (lat != null && lon != null) {
            try {
                double la = Double.parseDouble(lat.toString());
                double lo = Double.parseDouble(lon.toString());
                if (la < -90 || la > 90 || lo < -180 || lo > 180) return null;
                return "lat=" + la + "&lon=" + lo;
            } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private String pickKey(Map<String, Object> parameters) {
        String explicit = asString(parameters.get("api_key"));
        if (explicit != null && !explicit.isBlank()) return explicit;
        if (apiKey != null && !apiKey.isBlank()) return apiKey;
        String env = System.getenv("OPENWEATHER_API_KEY");
        return env != null && !env.isBlank() ? env : null;
    }

    private static String tempUnit(String units) {
        return switch (units) { case "imperial" -> "°F"; case "standard" -> "K"; default -> "°C"; };
    }

    private static String windUnit(String units) {
        return "imperial".equals(units) ? "mph" : "m/s";
    }

    private static String localTime(long unix, int tzOffsetSec) {
        return DateTimeFormatter.ofPattern("HH:mm")
            .format(Instant.ofEpochSecond(unix).atOffset(ZoneOffset.ofTotalSeconds(tzOffsetSec)));
    }

    private static String cap(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private static String asString(Object v) { return v == null ? null : v.toString(); }

    // ---------- metadata ----------

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new HashMap<>();

        Map<String, Object> operation = new HashMap<>();
        operation.put("type", "string");
        operation.put("enum", List.of("current", "forecast"));
        operation.put("description", "'current' (default) or 'forecast' (5-day / 3-hour).");
        props.put("operation", operation);

        Map<String, Object> city = new HashMap<>();
        city.put("type", "string");
        city.put("description", "City name (optionally with country code, e.g. 'London,GB').");
        props.put("city", city);

        Map<String, Object> lat = new HashMap<>();
        lat.put("type", "number");
        lat.put("description", "Latitude (-90..90). Must be paired with 'lon'.");
        props.put("lat", lat);

        Map<String, Object> lon = new HashMap<>();
        lon.put("type", "number");
        lon.put("description", "Longitude (-180..180). Must be paired with 'lat'.");
        props.put("lon", lon);

        Map<String, Object> units = new HashMap<>();
        units.put("type", "string");
        units.put("enum", List.of("metric", "imperial", "standard"));
        units.put("description", "Units system. Default: 'metric' (°C, m/s).");
        props.put("units", units);

        schema.put("properties", props);
        schema.put("required", new String[]{});
        return schema;
    }

    @Override public boolean isAsync() { return false; }
    @Override public boolean isCacheable() { return false; } // weather is live data
    @Override public String getCategory() { return "data"; }
    @Override public List<String> getTags() { return List.of("weather", "forecast", "climate", "api"); }

    @Override
    public String getTriggerWhen() {
        return "User asks about current weather, temperature, humidity, wind, or a multi-day forecast " +
               "for a specific city or location.";
    }

    @Override
    public String getAvoidWhen() {
        return "User asks about historical weather (this is a live/forecast tool) or climate statistics.";
    }

    @Override
    public ToolRequirements getRequirements() {
        return ToolRequirements.builder().env("OPENWEATHER_API_KEY").build();
    }

    @Override
    public Map<String, Object> getOutputSchema() {
        return Map.of("type", "markdown",
            "description", "Markdown-formatted conditions or a daily-grouped 5-day forecast.");
    }

    @Override
    public String smokeTest() {
        String key = pickKey(Map.of());
        if (key == null || key.isBlank()) return "OPENWEATHER_API_KEY not configured";
        try {
            Object result = execute(Map.of("city", "London", "operation", "current"));
            String s = result == null ? "" : result.toString();
            return s.startsWith("Error") ? "OpenWeatherMap unreachable: " + s : null;
        } catch (Exception e) {
            return "OpenWeatherMap unreachable: " + e.getMessage();
        }
    }

    public record Request(String operation, String city, Double lat, Double lon, String units) {}
}
