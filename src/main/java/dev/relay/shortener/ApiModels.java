package dev.relay.shortener;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class ApiModels {
    private ApiModels() { }

    public record CreateUrlRequest(@NotBlank @Size(max = 2048) String url,
                                   @Pattern(regexp = "^[A-Za-z0-9_-]{3,32}$", message = "alias must be 3-32 letters, digits, _ or -") String customAlias) { }
    public record CreatedUrl(String code, String shortUrl, String targetUrl, boolean alreadyExists) { }
    public record CountryClicks(String country, long clicks) { }
    public record UrlStats(String code, String targetUrl, Instant createdAt, long clicks, List<CountryClicks> countries) { }
    public record ClickEvent(String eventId, String code, Instant clickedAt, String country, String referrerHost) { }
    public record ErrorBody(String detail) { }
}
