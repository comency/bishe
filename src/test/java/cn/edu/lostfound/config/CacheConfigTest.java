package cn.edu.lostfound.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.edu.lostfound.entity.Item;
import cn.edu.lostfound.entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.bytecode.internal.bytebuddy.ByteBuddyState;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.proxy.pojo.bytebuddy.ByteBuddyProxyFactory;
import org.hibernate.proxy.pojo.bytebuddy.ByteBuddyProxyHelper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.util.ReflectionTestUtils;

class CacheConfigTest {
  private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

  @Test
  void cacheHitPreservesHttpJsonDatesAndPublisherIdWithoutCachingAccountDetails() throws Exception {
    User publisher = new User("student", "private-password-hash", "Student", "USER");
    ReflectionTestUtils.setField(publisher, "id", 7L);
    publisher.setPhone("private-phone-number");
    Item item = new Item(publisher, "校园卡", "图书馆捡到校园卡", "FOUND", "证件", "图书馆",
        LocalDate.of(2026, 9, 15));
    ReflectionTestUtils.setField(item, "id", 42L);
    ReflectionTestUtils.setField(item, "createdAt", LocalDateTime.of(2026, 9, 15, 10, 30));
    item.setStatus("APPROVED");
    List<Item> result = List.of(item);
    AtomicReference<byte[]> stored = new AtomicReference<>();
    var cache = cacheWithStoredValue(stored);

    cache.put("校园卡:FOUND", result);
    List<?> cached = cache.get("校园卡:FOUND", List.class);

    assertThat(cached).hasSize(1);
    assertThat(cached.getFirst()).isInstanceOf(Item.class);
    assertThat(((Item) cached.getFirst()).getPublisherId()).isEqualTo(7L);
    assertThat(objectMapper.writeValueAsString(cached))
        .isEqualTo(objectMapper.writeValueAsString(result));
    assertThat(new String(stored.get(), StandardCharsets.UTF_8))
        .contains("\"publisher\":{\"id\":7}")
        .doesNotContain("student", "private-password-hash", "private-phone-number", "@class");
    // Cache-specific mixins must not change the API's publisher privacy rules.
    assertThat(objectMapper.writeValueAsString(item))
        .contains("\"publisherId\":7")
        .doesNotContain("\"publisher\":");
  }

  @Test
  void cachesEmptySearchResults() {
    var cache = cacheWithStoredValue(new AtomicReference<>());

    cache.put(":", List.of());

    assertThat(cache.get(":", List.class)).isEmpty();
  }

  @Test
  void detachedHibernatePublisherProxyRoundTripsWithoutInitialization() throws Exception {
    var proxyFactory = new ByteBuddyProxyFactory(new ByteBuddyProxyHelper(new ByteBuddyState()));
    proxyFactory.postInstantiate(User.class.getName(), User.class, Set.of(HibernateProxy.class),
        User.class.getMethod("getId"), null, null);
    HibernateProxy proxy = proxyFactory.getProxy(17L, null);
    User publisher = (User) proxy;
    assertThat(ReflectionTestUtils.getField(publisher, "id")).isNull();
    assertThat(proxy.getHibernateLazyInitializer().isUninitialized()).isTrue();
    Item item = new Item(publisher, "钥匙", "宿舍楼拾到钥匙", "FOUND", "其他", "宿舍楼",
        LocalDate.of(2026, 9, 15));
    ReflectionTestUtils.setField(item, "id", 50L);
    List<Item> result = List.of(item);
    String originalJson = objectMapper.writeValueAsString(result);
    var cache = cacheWithStoredValue(new AtomicReference<>());

    cache.put("钥匙:FOUND", result);
    List<?> cached = cache.get("钥匙:FOUND", List.class);

    assertThat(((Item) cached.getFirst()).getPublisherId()).isEqualTo(17L);
    assertThat(objectMapper.writeValueAsString(cached)).isEqualTo(originalJson);
    assertThat(proxy.getHibernateLazyInitializer().isUninitialized()).isTrue();
  }

  @Test
  void cachingOnlyNeedsThePublisherIdAndSupportsMissingOptionalFields() {
    User publisher = new User("not-loaded", "not-loaded", null, "USER") {
      @Override
      public Long getId() {
        return 9L;
      }

      @Override
      public String getUsername() {
        throw new AssertionError("The cache must not initialize publisher details");
      }
    };
    Item item = new Item(publisher, "雨伞", "黑色雨伞", "LOST", null, null, null);
    var cache = cacheWithStoredValue(new AtomicReference<>());

    cache.put(":LOST", List.of(item));
    Item cached = (Item) cache.get(":LOST", List.class).getFirst();

    assertThat(cached.getPublisherId()).isEqualTo(9L);
    assertThat(cached.getOccurredAt()).isNull();
    assertThat(cached.getCategory()).isNull();
    assertThat(cached.getLocation()).isNull();
  }

  private org.springframework.cache.Cache cacheWithStoredValue(AtomicReference<byte[]> stored) {
    RedisCacheWriter writer = mock(RedisCacheWriter.class);
    doAnswer(invocation -> {
      stored.set(invocation.getArgument(2));
      return null;
    }).when(writer).put(eq("itemSearch"), any(byte[].class), any(byte[].class), any());
    when(writer.get(eq("itemSearch"), any(byte[].class)))
        .thenAnswer(invocation -> stored.get());
    var builder = RedisCacheManager.builder(writer);
    new CacheConfig().itemSearchCacheCustomizer(objectMapper).customize(builder);
    RedisCacheManager manager = builder.build();
    manager.afterPropertiesSet();
    return manager.getCache("itemSearch");
  }
}
