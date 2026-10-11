package dev.relay.shortener;

import dev.relay.shortener.ApiModels.ClickEvent;
import dev.relay.shortener.ApiModels.CountryClicks;
import dev.relay.shortener.ApiModels.CreatedUrl;
import dev.relay.shortener.ApiModels.UrlStats;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.jdbc.core.ConnectionCallback;

import java.net.URI;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.http.HttpStatus.*;

@Service
public class UrlService {
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final DistributedIdGenerator ids;
    private final SchemaInitializer bloom;
    private final KafkaTemplate<String, ClickEvent> kafka;
    private final GeoIpResolver geo;
    private final RelayProperties properties;

    public UrlService(JdbcTemplate jdbc, StringRedisTemplate redis, DistributedIdGenerator ids,
                      SchemaInitializer bloom, KafkaTemplate<String, ClickEvent> kafka,
                      GeoIpResolver geo, RelayProperties properties) {
        this.jdbc = jdbc; this.redis = redis; this.ids = ids; this.bloom = bloom;
        this.kafka = kafka; this.geo = geo; this.properties = properties;
    }

    @Transactional
    public CreatedUrl create(String inputUrl, String alias, String baseUrl) {
        final String target;
        try {
            URI uri = URI.create(inputUrl.trim()).normalize();
            if (!uri.isAbsolute() || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https")) || uri.getHost() == null) throw new IllegalArgumentException();
            target = uri.toString();
        } catch (Exception e) { throw new ResponseStatusException(BAD_REQUEST, "url must be a valid http or https URL"); }

        // Serialize creation of the same destination across both API replicas.
        // The lock is transaction-scoped, so a concurrent request sees the first insert.
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1, target);
                statement.execute();
            }
            return null;
        });
        List<String> existing = jdbc.query("SELECT code FROM urls WHERE target_url = ? ORDER BY created_at, id LIMIT 1",
                (rs, row) -> rs.getString(1), target);
        if (!existing.isEmpty()) {
            String existingCode = existing.getFirst();
            redis.opsForValue().set("url:" + existingCode, target, Duration.ofSeconds(properties.cacheTtlSeconds()));
            return new CreatedUrl(existingCode, baseUrl + "/" + existingCode, target, true);
        }

        long id = ids.nextId();
        String code = alias == null || alias.isBlank() ? DistributedIdGenerator.base62(id) : alias;
        try {
            // Set bits first. A failed insert only creates a harmless Bloom false positive.
            bloom.addToBloom(code);
            jdbc.update("INSERT INTO urls(id, code, target_url) VALUES (?, ?, ?)", id, code, target);
        } catch (DuplicateKeyException e) { throw new ResponseStatusException(CONFLICT, "That alias is already in use"); }
        redis.opsForValue().set("url:" + code, target, Duration.ofSeconds(properties.cacheTtlSeconds()));
        return new CreatedUrl(code, baseUrl + "/" + code, target, false);
    }

    public String resolve(String code, String ip, String referrer) {
        if (code.length() > 64) throw new ResponseStatusException(NOT_FOUND, "Short URL not found");
        String key = "url:" + code;
        String target = redis.opsForValue().get(key);
        if (target == null) {
            if (!bloom.mightContain(code)) throw new ResponseStatusException(NOT_FOUND, "Short URL not found");
            List<String> rows = jdbc.query("SELECT target_url FROM urls WHERE code = ?", (rs, row) -> rs.getString(1), code);
            if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Short URL not found");
            target = rows.getFirst();
            redis.opsForValue().set(key, target, Duration.ofSeconds(properties.cacheTtlSeconds()));
        }
        String host = "";
        try { if (referrer != null) host = URI.create(referrer).getHost(); } catch (Exception ignored) { }
        ClickEvent event = new ClickEvent(UUID.randomUUID().toString(), code, Instant.now(), geo.country(ip), host == null ? "" : host);
        kafka.send(properties.kafkaTopic(), code, event).whenComplete((result, error) -> {
            if (error != null) org.slf4j.LoggerFactory.getLogger(UrlService.class).warn("Could not publish click for {}", code, error);
        });
        return target;
    }

    public UrlStats stats(String code) {
        List<UrlStats> url = jdbc.query("SELECT code, target_url, created_at FROM urls WHERE code = ?",
                (rs, row) -> new UrlStats(rs.getString("code"), rs.getString("target_url"), rs.getTimestamp("created_at").toInstant(), 0, List.of()), code);
        if (url.isEmpty()) throw new ResponseStatusException(NOT_FOUND, "Short URL not found");
        Long clicks = jdbc.queryForObject("SELECT count(*) FROM click_analytics WHERE code = ?", Long.class, code);
        List<CountryClicks> countries = jdbc.query("SELECT country, count(*) clicks FROM click_analytics WHERE code = ? GROUP BY country ORDER BY clicks DESC",
                (rs, row) -> new CountryClicks(rs.getString("country"), rs.getLong("clicks")), code);
        UrlStats base = url.getFirst();
        return new UrlStats(base.code(), base.targetUrl(), base.createdAt(), clicks == null ? 0 : clicks, countries);
    }
}
