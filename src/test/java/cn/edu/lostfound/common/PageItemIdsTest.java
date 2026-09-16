package cn.edu.lostfound.common;

import java.util.*;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PageItemIdsTest {
  @Test void boundedCopyIsDetachedDeduplicatedAndOrdered(){
    var source=new ArrayList<>(List.of(3L,1L,3L));var ids=PageItemIds.copyOf(source);source.clear();
    assertThat(ids).containsExactly(3L,1L);assertThatThrownBy(()->ids.add(2L)).isInstanceOf(UnsupportedOperationException.class);
  }
  @Test void emptyAndMaximumPageAccepted(){
    assertThat(PageItemIds.copyOf(List.of())).isEmpty();
    assertThat(PageItemIds.copyOf(LongStream.rangeClosed(1,50).boxed().toList())).hasSize(50);
  }
  @Test void invalidOrUnboundedIdsRefused(){
    for(var ids:List.of(List.of(0L),List.of(-1L),Arrays.asList(1L,null),LongStream.rangeClosed(1,51).boxed().toList()))
      assertThatThrownBy(()->PageItemIds.copyOf(ids)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->PageItemIds.copyOf(null)).isInstanceOf(IllegalArgumentException.class);
  }
}
