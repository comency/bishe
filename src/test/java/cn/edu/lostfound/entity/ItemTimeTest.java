package cn.edu.lostfound.entity;

import java.time.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;

class ItemTimeTest {
  private final ZoneId legacyZone=ZoneId.of("Asia/Shanghai");
  private Item item(){return new Item(new User("synthetic","unused","Synthetic","USER"),"Synthetic","Synthetic","LOST",null,null,null);}
  private Instant read(Item item,String name){return name.equals("createdAt")?item.createdInstant(legacyZone):item.updatedInstant(legacyZone);}
  @ParameterizedTest @ValueSource(strings={"createdAt","updatedAt"})
  void missingLegacyAndUtcValuesStayNull(String field){
    var item=item();ReflectionTestUtils.setField(item,field,null);
    assertThat(read(item,field)).isNull();
  }
  @ParameterizedTest @ValueSource(strings={"createdAt","updatedAt"})
  void knownLegacyValueUsesExplicitZoneAndPreservesMicroseconds(String field){
    var item=item();ReflectionTestUtils.setField(item,field,LocalDateTime.parse("2026-01-02T03:04:05.123456"));
    assertThat(read(item,field)).isEqualTo(Instant.parse("2026-01-01T19:04:05.123456Z"));
  }
  @ParameterizedTest @ValueSource(strings={"createdAt","updatedAt"})
  void authoritativeUtcWinsOverDifferentLegacyValue(String field){
    var item=item();var utc=Instant.parse("2026-09-16T04:00:00.123456Z");ReflectionTestUtils.setField(item,field+"Utc",utc);
    ReflectionTestUtils.setField(item,field,LocalDateTime.parse("2020-01-01T00:00:00"));assertThat(read(item,field)).isEqualTo(utc);
  }
  @ParameterizedTest @ValueSource(strings={"createdAt","updatedAt"})
  void utcValueDoesNotRequireLegacyColumn(String field){
    var item=item();var utc=Instant.parse("2026-09-16T04:00:00Z");ReflectionTestUtils.setField(item,field+"Utc",utc);
    ReflectionTestUtils.setField(item,field,null);assertThat(read(item,field)).isEqualTo(utc);
  }
}
