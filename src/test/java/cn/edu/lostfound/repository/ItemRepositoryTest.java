package cn.edu.lostfound.repository;

import static org.assertj.core.api.Assertions.assertThat;
import cn.edu.lostfound.entity.Item;
import cn.edu.lostfound.entity.User;
import java.sql.Connection;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.JdbcSettings;
import org.hibernate.cfg.SchemaToolingSettings;
import org.hibernate.dialect.MySQLDialect;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

class ItemRepositoryTest {
  @Test
  void repositoryQueriesResolvePersistentPublisherAssociationWithoutJdbc() throws Exception {
    NoJdbcConnections connections = new NoJdbcConnections();
    StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
        .applySetting(JdbcSettings.ALLOW_METADATA_ON_BOOT, false)
        .applySetting(JdbcSettings.DIALECT, MySQLDialect.class.getName())
        .applySetting(SchemaToolingSettings.HBM2DDL_AUTO, "none")
        .addService(ConnectionProvider.class, connections)
        .build();
    try (SessionFactory sessionFactory = new MetadataSources(registry)
        .addAnnotatedClass(User.class)
        .addAnnotatedClass(Item.class)
        .buildMetadata()
        .buildSessionFactory();
        Session session = sessionFactory.openSession()) {
      // Construct the real Spring Data repository just as application startup
      // does. A mocked repository would miss derived-property path failures.
      ItemRepository repository = new JpaRepositoryFactory(session).getRepository(ItemRepository.class);
      assertThat(repository).isNotNull();
      assertThat(sessionFactory.getMetamodel().entity(Item.class).getAttributes())
          .extracting(attribute -> attribute.getName())
          .contains("publisher")
          .doesNotContain("publisherId");
    } finally {
      StandardServiceRegistryBuilder.destroy(registry);
    }
    assertThat(connections.attempts).isZero();
  }

  private static final class NoJdbcConnections implements ConnectionProvider {
    private int attempts;

    @Override
    public Connection getConnection() {
      attempts++;
      throw new AssertionError("This query-validation test must never obtain a JDBC connection");
    }

    @Override
    public void closeConnection(Connection connection) {
      throw new AssertionError("No JDBC connection should exist");
    }

    @Override
    public boolean supportsAggressiveRelease() { return false; }

    @Override
    public boolean isUnwrappableAs(Class<?> type) { return type.isInstance(this); }

    @Override
    public <T> T unwrap(Class<T> type) { return type.cast(this); }
  }
}
