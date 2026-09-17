package cn.edu.lostfound.config;

import cn.edu.lostfound.identity.AccountDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Driver contracts run without MySQL, Redis, Docker or a candidate process. */
class HttpDatabaseOutageContractTest {
  @Test void updateIncludesRequiredNullableContact() throws Exception {
    String body=new HttpDatabaseOutageTest().updateBody("Recovered 1",0);
    var mapper=new ObjectMapper();var json=mapper.readTree(body);
    assertThat(json.has("contact")).isTrue();assertThat(json.path("contact").isNull()).isTrue();
    assertThat(mapper.readValue(body,AccountDtos.UpdateMe.class)).isEqualTo(new AccountDtos.UpdateMe("Recovered 1",null,0L));
  }
  @Test void primitiveStatusListAcceptsExactExpectedResponse() {
    HttpDatabaseOutageTest.assertExpectedStatus(200,200);
    HttpDatabaseOutageTest.assertExpectedStatus(503,200,503);
  }
  @Test void unexpectedStatusNeverPassesRecovery() {
    assertThatThrownBy(()->HttpDatabaseOutageTest.assertExpectedStatus(500,200,503)).isInstanceOf(AssertionError.class);
    assertThatThrownBy(()->HttpDatabaseOutageTest.assertExpectedStatus(400,200)).isInstanceOf(AssertionError.class);
  }
}
