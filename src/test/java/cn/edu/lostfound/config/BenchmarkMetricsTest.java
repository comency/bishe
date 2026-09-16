package cn.edu.lostfound.config;

import java.util.*;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BenchmarkMetricsTest {
  @Test void usesNearestRankAndIncludesErrorsInDenominator() {
    var result = BenchmarkMetrics.summarize(LongStream.rangeClosed(1,100).boxed().toList(), 125, 1_000_000_000L);
    assertThat(result).containsEntry("p95",95L).containsEntry("p99",99L).containsEntry("errors",25)
        .containsEntry("errorRate",.2).containsEntry("successfulRequestsPerSecond",100.0).containsEntry("passed",false);
  }
  @Test void emptyOrAllFailedIsNeverAPass() {
    assertThat(BenchmarkMetrics.summarize(List.of(),0,1)).containsEntry("passed",false).containsEntry("p95",null);
    assertThat(BenchmarkMetrics.summarize(List.of(),20,1)).containsEntry("errorRate",1.0).containsEntry("passed",false);
  }
  @Test void thresholdIsInclusiveAndInputUnchanged() {
    var values = new ArrayList<>(List.of(800_000L,10L));
    assertThat(BenchmarkMetrics.summarize(values,2,1)).containsEntry("passed",true);
    assertThat(values).containsExactly(800_000L,10L);
    assertThat(BenchmarkMetrics.summarize(List.of(800_001L),1,1)).containsEntry("passed",false);
  }
  @Test void invalidMeasurementsFailClosed() {
    assertThatThrownBy(() -> BenchmarkMetrics.summarize(List.of(1L),0,1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> BenchmarkMetrics.summarize(List.of(-1L),1,1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> BenchmarkMetrics.summarize(List.of(),0,0)).isInstanceOf(IllegalArgumentException.class);
  }
}
