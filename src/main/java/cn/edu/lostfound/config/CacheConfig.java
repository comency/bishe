package cn.edu.lostfound.config;

import cn.edu.lostfound.entity.Item;
import cn.edu.lostfound.entity.User;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIncludeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

@Configuration
@EnableCaching
public class CacheConfig {
  @Bean
  RedisCacheManagerBuilderCustomizer itemSearchCacheCustomizer(ObjectMapper objectMapper) {
    // Keep the HTTP representation unchanged while preserving publisherId on cache hits.
    ObjectMapper cacheMapper = objectMapper.copy()
        .addMixIn(Item.class, CachedItem.class)
        .addMixIn(User.class, CachedPublisher.class);
    var listType = cacheMapper.getTypeFactory().constructCollectionType(List.class, Item.class);
    var serializer = new Jackson2JsonRedisSerializer<List<Item>>(cacheMapper, listType);
    RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
        // Avoid reading values written with the old JDK serialization format.
        .prefixCacheNameWith("json-v1:")
        .serializeValuesWith(SerializationPair.fromSerializer(serializer));
    return builder -> builder.withCacheConfiguration("itemSearch", config);
  }

  @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
      getterVisibility = JsonAutoDetect.Visibility.NONE,
      isGetterVisibility = JsonAutoDetect.Visibility.NONE)
  abstract static class CachedItem {
    @JsonIgnore(false)
    abstract User getPublisher();

    @JsonIgnore
    abstract Long getPublisherId();
  }

  // A lazy JPA publisher can provide its ID without loading private account details.
  @JsonIncludeProperties("id")
  abstract static class CachedPublisher {}
}
