package cn.edu.lostfound.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;

class HealthControllerTest {
  private JdbcTemplate jdbc;
  private RedisConnectionFactory redis;
  private RedisConnection connection;
  private HealthController controller;

  @BeforeEach void setup() {
    jdbc = mock(JdbcTemplate.class);
    redis = mock(RedisConnectionFactory.class);
    connection = mock(RedisConnection.class);
    controller = new HealthController(jdbc, redis);
  }

  @Test void livenessDoesNotTouchDependencies() {
    var response = controller.live();
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).isEqualTo(new HealthController.HealthStatus("UP"));
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    verify(jdbc, never()).queryForObject("SELECT 1", Integer.class);
    verify(redis, never()).getConnection();
  }

  @Test void readinessRequiresDatabaseAndRedisAndClosesConnection() {
    when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
    when(redis.getConnection()).thenReturn(connection);
    when(connection.ping()).thenReturn("PONG");

    var response = controller.ready();

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody()).isEqualTo(new HealthController.HealthStatus("UP"));
    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    verify(connection).close();
  }

  @Test void databaseFailureIsFixed503AndDoesNotContactRedis() {
    when(jdbc.queryForObject("SELECT 1", Integer.class))
        .thenThrow(new CannotGetJdbcConnectionException("synthetic private database endpoint"));

    var response = controller.ready();

    assertThat(response.getStatusCode().value()).isEqualTo(503);
    assertThat(response.getBody()).isEqualTo(new HealthController.HealthStatus("DOWN"));
    assertThat(response.toString()).doesNotContain("synthetic").doesNotContain("database endpoint");
    verify(redis, never()).getConnection();
  }

  @Test void redisFailureIsFixed503AndStillClosesConnection() {
    when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
    when(redis.getConnection()).thenReturn(connection);
    when(connection.ping()).thenThrow(new RedisConnectionFailureException("synthetic private redis endpoint"));

    var response = controller.ready();

    assertThat(response.getStatusCode().value()).isEqualTo(503);
    assertThat(response.getBody()).isEqualTo(new HealthController.HealthStatus("DOWN"));
    assertThat(response.toString()).doesNotContain("synthetic").doesNotContain("redis endpoint");
    verify(connection).close();
  }

  @Test void unexpectedProbeResponsesFailClosed() {
    when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(0);
    assertThat(controller.ready().getStatusCode().value()).isEqualTo(503);
    verify(redis, never()).getConnection();

    when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
    when(redis.getConnection()).thenReturn(connection);
    when(connection.ping()).thenReturn("unexpected");
    assertThat(controller.ready().getStatusCode().value()).isEqualTo(503);
    verify(connection).close();
  }
}
