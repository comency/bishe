package cn.edu.lostfound.config;

import java.util.*;

/** Test-only nearest-rank percentiles. Failed calls must remain in the error denominator. */
final class BenchmarkMetrics {
  private BenchmarkMetrics() {}
  static Map<String,Object> summarize(List<Long> successfulMicros, int attempts, long wallNanos) {
    if (attempts < successfulMicros.size() || attempts < 0 || wallNanos <= 0 || successfulMicros.stream().anyMatch(n -> n < 0))
      throw new IllegalArgumentException("Invalid benchmark measurements");
    var sorted = new ArrayList<>(successfulMicros); Collections.sort(sorted);
    var result = new LinkedHashMap<String,Object>();
    result.put("attempts", attempts); result.put("successes", sorted.size()); result.put("errors", attempts - sorted.size());
    result.put("errorRate", attempts == 0 ? 0.0 : (attempts - sorted.size()) / (double) attempts);
    result.put("successfulRequestsPerSecond", sorted.size() / (wallNanos / 1e9));
    result.put("latencyUnit", "microseconds");
    result.put("p50", rank(sorted, .50)); result.put("p95", rank(sorted, .95)); result.put("p99", rank(sorted, .99));
    result.put("max", sorted.isEmpty() ? null : sorted.getLast());
    result.put("passed", !sorted.isEmpty() && attempts == sorted.size() && rank(sorted, .95) <= 800_000);
    return result;
  }
  private static Long rank(List<Long> sorted, double fraction) {
    return sorted.isEmpty() ? null : sorted.get((int)Math.ceil(sorted.size() * fraction) - 1);
  }
}
