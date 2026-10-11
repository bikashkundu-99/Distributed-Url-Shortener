package dev.relay.shortener;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Component
public class SchemaInitializer implements InitializingBean {
    public static final String BLOOM_KEY = "url:bloom:v1";
    public static final String BLOOM_READY = "url:bloom:ready:v1";
    public static final int BLOOM_BITS = 1 << 22;
    private static final int HASH_COUNT = 5;
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    public SchemaInitializer(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    @Override
    public void afterPropertiesSet() {
        jdbc.execute("CREATE SEQUENCE IF NOT EXISTS url_id_block_seq START 1");
        jdbc.execute("CREATE TABLE IF NOT EXISTS urls (id BIGINT PRIMARY KEY, code TEXT UNIQUE NOT NULL, target_url TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now())");
        jdbc.execute("CREATE TABLE IF NOT EXISTS click_analytics (event_id UUID PRIMARY KEY, code TEXT NOT NULL, clicked_at TIMESTAMPTZ NOT NULL, country TEXT NOT NULL DEFAULT 'unknown', referrer_host TEXT NOT NULL DEFAULT '')");
        jdbc.execute("CREATE INDEX IF NOT EXISTS click_analytics_code_time_idx ON click_analytics(code, clicked_at DESC)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS click_analytics_country_idx ON click_analytics(country)");

        List<String> codes = jdbc.queryForList("SELECT code FROM urls", String.class);
        for (String code : codes) addToBloom(code);
        redis.opsForValue().set(BLOOM_READY, "1");
    }

    public void addToBloom(String code) {
        for (long bit : indices(code)) redis.opsForValue().setBit(BLOOM_KEY, bit, true);
    }

    public boolean mightContain(String code) {
        if (!"1".equals(redis.opsForValue().get(BLOOM_READY)) || Boolean.FALSE.equals(redis.hasKey(BLOOM_KEY))) return true;
        for (long bit : indices(code)) {
            if (!Boolean.TRUE.equals(redis.opsForValue().getBit(BLOOM_KEY, bit))) return false;
        }
        return true;
    }

    private static long[] indices(String code) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.wrap(hash);
            long first = buffer.getLong() & Long.MAX_VALUE;
            long second = (buffer.getLong() & Long.MAX_VALUE) | 1L;
            long[] result = new long[HASH_COUNT];
            for (int i = 0; i < HASH_COUNT; i++) result[i] = Long.remainderUnsigned(first + i * second, BLOOM_BITS);
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
