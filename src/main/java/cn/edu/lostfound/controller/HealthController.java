package cn.edu.lostfound.controller;

import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Minimal deployment probes: fixed responses, no endpoint or exception details. */
@RestController
public class HealthController {
  private final JdbcTemplate jdbc;
  private final RedisConnectionFactory redis;

  public HealthController(JdbcTemplate jdbc, RedisConnectionFactory redis) {
    this.jdbc = jdbc;
    this.redis = redis;
  }

  public record HealthStatus(String status) { }

  @GetMapping("/api/health/live")
  public ResponseEntity<HealthStatus> live() {
    return response(200, "UP");
  }

  @GetMapping("/api/health/ready")
  public ResponseEntity<HealthStatus> ready() {
    try {
      Integer database = jdbc.queryForObject("SELECT 1", Integer.class);
      if (!Integer.valueOf(1).equals(database)) return response(503, "DOWN");
      try (var connection = redis.getConnection()) {
        if (!"PONG".equalsIgnoreCase(connection.ping())) return response(503, "DOWN");
      }
      return response(200, "UP");
    } catch (RuntimeException failure) {
      return response(503, "DOWN");
    }
  }

  private static ResponseEntity<HealthStatus> response(int status, String value) {
    return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new HealthStatus(value));
  }
}
