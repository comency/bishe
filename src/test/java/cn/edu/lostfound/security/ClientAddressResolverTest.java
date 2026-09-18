package cn.edu.lostfound.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientAddressResolverTest {
  @Test void untrustedPeerCannotSpoofForwardedAddress() {
    var resolver = new ClientAddressResolver("127.0.0.1");
    var request = request("192.0.2.10", "198.51.100.20");
    assertEquals("192.0.2.10", resolver.resolve(request));
  }

  @Test void trustedProxyUsesRightmostUntrustedAddress() {
    var resolver = new ClientAddressResolver("127.0.0.1,10.0.0.2");
    var request = request("127.0.0.1", "203.0.113.99, 198.51.100.7, 10.0.0.2");
    assertEquals("198.51.100.7", resolver.resolve(request));
  }

  @Test void malformedOrOversizedChainFailsClosedToDirectPeer() {
    var resolver = new ClientAddressResolver("127.0.0.1");
    assertEquals("127.0.0.1", resolver.resolve(request("127.0.0.1", "198.51.100.7, unknown")));
    String tooMany = String.join(",", java.util.Collections.nCopies(17, "198.51.100.7"));
    assertEquals("127.0.0.1", resolver.resolve(request("127.0.0.1", tooMany)));
  }

  @Test void ipv6LiteralsAreCanonicalizedWithoutDns() {
    var resolver = new ClientAddressResolver("::1");
    assertEquals("2001:db8:0:0:0:0:0:5", resolver.resolve(request("0:0:0:0:0:0:0:1", "2001:db8::5")));
  }

  @Test void invalidTrustedProxyConfigurationIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> new ClientAddressResolver("proxy.example"));
    assertThrows(IllegalArgumentException.class, () -> new ClientAddressResolver("127.0.0.1:8080"));
    assertThrows(IllegalArgumentException.class, () -> new ClientAddressResolver("999.0.0.1"));
  }

  @Test void missingRemoteAddressUsesStableFailClosedBucket() {
    var resolver = new ClientAddressResolver("");
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr(null);
    assertEquals("unknown", resolver.resolve(request));
  }

  @Test void allTrustedForwardedChainFallsBackToDirectPeer() {
    var resolver = new ClientAddressResolver("127.0.0.1,10.0.0.2");
    assertEquals("127.0.0.1", resolver.resolve(request("127.0.0.1", "10.0.0.2")));
  }

  private static MockHttpServletRequest request(String remote, String forwarded) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr(remote);
    request.addHeader("X-Forwarded-For", forwarded);
    return request;
  }
}
