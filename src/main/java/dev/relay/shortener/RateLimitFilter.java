package dev.relay.shortener;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

@Component
public class RateLimitFilter extends OncePerRequestFilter {
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]) end; return n", Long.class);
    private final StringRedisTemplate redis;
    private final RelayProperties properties;

    public RateLimitFilter(StringRedisTemplate redis, RelayProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.equals("/") || path.startsWith("/docs") || path.startsWith("/swagger") || path.equals("/favicon.ico")) {
            chain.doFilter(request, response);
            return;
        }
        String ip = request.getHeader("X-Real-IP");
        if (ip == null || ip.isBlank()) ip = request.getRemoteAddr();
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ip.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
            Long count = redis.execute(SCRIPT, List.of("rate:" + digest), "60");
            if (count != null && count > properties.rateLimitPerMinute()) {
                response.setStatus(429);
                response.setHeader("Retry-After", "60");
                return;
            }
        } catch (Exception e) {
            response.setStatus(503);
            response.getWriter().write("rate limiter unavailable");
            return;
        }
        chain.doFilter(request, response);
    }
}
