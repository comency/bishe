package cn.edu.lostfound.config;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HttpBenchmarkFixtureTest {
  @Test void distributionAndActorIsolationCoverAllTwentyUsers() {
    int images=0,claims=0,accepted=0;var states=new HashSet<String>();var actors=new HashSet<Integer>();
    for(int id=1;id<=10000;id++) {
      images+=HttpLoadBenchmarkTest.imageCount(id);
      if(HttpLoadBenchmarkTest.hasClaims(id)) {
        claims+=2;states.add(HttpLoadBenchmarkTest.claimState(id));
        if(HttpLoadBenchmarkTest.claimState(id).equals("ACCEPTED"))accepted++;
        int first=HttpLoadBenchmarkTest.applicant(id,1),second=HttpLoadBenchmarkTest.applicant(id,2);
        actors.add(first);actors.add(second);
        assertThat(first).isNotEqualTo(second).isNotEqualTo(1+(id-1)/500);
        assertThat(second).isNotEqualTo(1+(id-1)/500);
        for(int actor=1;actor<=20;actor++) {
          Long actual=HttpLoadBenchmarkTest.ownClaim(id,actor);
          if(actor==first)assertThat(actual).isEqualTo(id*2L-1);
          else if(actor==second)assertThat(actual).isEqualTo(id*2L);
          else assertThat(actual).isNull();
        }
      } else for(int actor=1;actor<=20;actor++)assertThat(HttpLoadBenchmarkTest.ownClaim(id,actor)).isNull();
    }
    assertThat(images).isEqualTo(15000);assertThat(claims).isEqualTo(8000);assertThat(accepted).isEqualTo(1000);
    assertThat(states).containsExactlyInAnyOrder("APPLIED","ACCEPTED","REJECTED","CANCELLED");assertThat(actors).hasSize(20);
  }
}
