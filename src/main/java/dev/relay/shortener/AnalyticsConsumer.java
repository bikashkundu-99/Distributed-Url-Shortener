package dev.relay.shortener;

import dev.relay.shortener.ApiModels.ClickEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;

@Component
@ConditionalOnProperty(name = "relay.analytics.enabled", havingValue = "true")
public class AnalyticsConsumer {
    private final JdbcTemplate jdbc;
    public AnalyticsConsumer(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @KafkaListener(topics = "${relay.kafka-topic:url-clicks}")
    public void onClick(ClickEvent event, Acknowledgment acknowledgment) {
        jdbc.update("INSERT INTO click_analytics(event_id, code, clicked_at, country, referrer_host) VALUES (?::uuid, ?, ?, ?, ?) ON CONFLICT(event_id) DO NOTHING",
                event.eventId(), event.code(), Timestamp.from(event.clickedAt()), event.country(), event.referrerHost());
        acknowledgment.acknowledge();
    }
}
