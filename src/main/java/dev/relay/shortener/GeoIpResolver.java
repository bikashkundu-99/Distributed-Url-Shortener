package dev.relay.shortener;

import com.maxmind.geoip2.DatabaseReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.io.File;
import java.net.InetAddress;

@Component
public class GeoIpResolver {
    private static final Logger log = LoggerFactory.getLogger(GeoIpResolver.class);
    private final DatabaseReader reader;

    public GeoIpResolver(RelayProperties properties) {
        DatabaseReader loaded = null;
        String path = properties.geoipDbPath();
        if (path != null && !path.isBlank() && new File(path).isFile()) {
            try { loaded = new DatabaseReader.Builder(new File(path)).build(); }
            catch (Exception e) { log.warn("Could not open GeoIP database; country will be unknown", e); }
        }
        reader = loaded;
    }

    public String country(String ip) {
        if (reader == null || ip == null || ip.isBlank()) return "unknown";
        try { return reader.country(InetAddress.getByName(ip)).getCountry().getIsoCode().toLowerCase(); }
        catch (Exception ignored) { return "unknown"; }
    }

    @PreDestroy
    public void close() throws Exception { if (reader != null) reader.close(); }
}
