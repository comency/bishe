package cn.edu.lostfound.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Trusts X-Forwarded-For only when the direct peer is an explicitly configured proxy. */
@Component
public class ClientAddressResolver {
  private static final int MAX_TRUSTED_PROXIES = 8;
  private static final int MAX_FORWARDED_HOPS = 16;
  private final Set<String> trustedProxies;

  public ClientAddressResolver(@Value("${app.proxy.trusted-addresses:}") String configured) {
    trustedProxies = parseTrusted(configured);
  }

  public String resolve(HttpServletRequest request) {
    String remote = parseLiteral(request.getRemoteAddr());
    if (remote == null) return "unknown";
    if (!trustedProxies.contains(remote)) return remote;

    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded == null || forwarded.isBlank()) return remote;
    String[] hops = forwarded.split(",", -1);
    if (hops.length > MAX_FORWARDED_HOPS) return remote;
    for (int index = hops.length - 1; index >= 0; index--) {
      String candidate = parseLiteral(hops[index].trim());
      if (candidate == null) return remote;
      if (!trustedProxies.contains(candidate)) return candidate;
    }
    return remote;
  }

  static Set<String> parseTrusted(String configured) {
    if (configured == null || configured.isBlank()) return Set.of();
    String[] values = configured.split(",", -1);
    if (values.length > MAX_TRUSTED_PROXIES) {
      throw new IllegalArgumentException("At most eight trusted proxy addresses are allowed");
    }
    Set<String> parsed = new LinkedHashSet<>();
    for (String value : values) {
      String address = parseLiteral(value.trim());
      if (address == null) throw new IllegalArgumentException("Trusted proxy addresses must be IP literals");
      parsed.add(address);
    }
    return Set.copyOf(parsed);
  }

  static String parseLiteral(String value) {
    if (value == null || value.isBlank() || value.contains("%") || value.startsWith("[") || value.endsWith("]")) {
      return null;
    }
    boolean ipv4 = value.matches("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}");
    boolean ipv6 = value.indexOf(':') >= 0 && value.matches("[0-9a-fA-F:.]+");
    if (!ipv4 && !ipv6) return null;
    if (ipv4 && Arrays.stream(value.split("\\.")).mapToInt(Integer::parseInt).anyMatch(part -> part > 255)) {
      return null;
    }
    try {
      InetAddress address = InetAddress.getByName(value);
      if ((ipv4 && address.getAddress().length != 4) || (ipv6 && address.getAddress().length != 16)) return null;
      return address.getHostAddress();
    } catch (UnknownHostException | NumberFormatException failure) {
      return null;
    }
  }
}
