package dev.relay.shortener;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DistributedIdGenerator {
    private static final long BLOCK_SIZE = 4096;
    private static final char[] BASE62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private final JdbcTemplate jdbc;
    private long next;
    private long end;

    public DistributedIdGenerator(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public synchronized long nextId() {
        if (next >= end) {
            Long block = jdbc.queryForObject("SELECT nextval('url_id_block_seq')", Long.class);
            next = block * BLOCK_SIZE;
            end = next + BLOCK_SIZE;
        }
        return next++;
    }

    public static String base62(long value) {
        if (value == 0) return "0";
        char[] buffer = new char[11];
        int pos = buffer.length;
        while (value > 0) {
            buffer[--pos] = BASE62[(int) (value % 62)];
            value /= 62;
        }
        return new String(buffer, pos, buffer.length - pos);
    }
}
