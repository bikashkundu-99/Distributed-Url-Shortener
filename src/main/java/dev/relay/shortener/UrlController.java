package dev.relay.shortener;

import dev.relay.shortener.ApiModels.CreateUrlRequest;
import dev.relay.shortener.ApiModels.CreatedUrl;
import dev.relay.shortener.ApiModels.ErrorBody;
import dev.relay.shortener.ApiModels.UrlStats;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.view.RedirectView;
import java.util.Map;

@RestController
public class UrlController {
    private final UrlService service;
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    public UrlController(UrlService service, JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.service = service; this.jdbc = jdbc; this.redis = redis;
    }

    @PostMapping("/api/urls")
    public ResponseEntity<CreatedUrl> create(@Valid @RequestBody CreateUrlRequest body, HttpServletRequest request) {
        String base = request.getScheme() + "://" + request.getHeader("Host");
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(body.url(), body.customAlias(), base));
    }

    @GetMapping("/api/urls/{code}/stats")
    public UrlStats stats(@PathVariable String code) { return service.stats(code); }

    @GetMapping("/api/health")
    public Map<String, String> health() { jdbc.queryForObject("SELECT 1", Integer.class); redis.opsForValue().get("health:probe"); return Map.of("status", "ok"); }

    @GetMapping("/{code}")
    public RedirectView redirect(@PathVariable String code, HttpServletRequest request) {
        String ip = request.getHeader("X-Real-IP");
        if (ip == null) ip = request.getRemoteAddr();
        RedirectView view = new RedirectView(service.resolve(code, ip, request.getHeader("Referer")));
        view.setStatusCode(HttpStatus.TEMPORARY_REDIRECT);
        view.setExposeModelAttributes(false);
        return view;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorBody> statusError(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(new ErrorBody(e.getReason()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> validationError(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream().findFirst().map(x -> x.getDefaultMessage()).orElse("invalid request");
        return ResponseEntity.badRequest().body(new ErrorBody(message));
    }
}
