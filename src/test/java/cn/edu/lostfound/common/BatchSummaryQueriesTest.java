package cn.edu.lostfound.common;

import cn.edu.lostfound.audit.AuditApi;
import cn.edu.lostfound.claim.*;
import cn.edu.lostfound.identity.AccountApi;
import cn.edu.lostfound.media.MediaService;
import cn.edu.lostfound.verification.VerificationApi;
import java.time.Clock;
import java.util.*;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BatchSummaryQueriesTest {
  final JdbcTemplate jdbc=mock(JdbcTemplate.class);
  final MediaService media=new MediaService(jdbc,mock(VerificationApi.class),mock(AccountApi.class),Clock.systemUTC(),
      mock(PlatformTransactionManager.class),".local/unused-summary-unit",12000000,8192,24);
  final ClaimApi claims=new ClaimApi(mock(ClaimStore.class),mock(AuditApi.class),Clock.systemUTC(),jdbc);
  @Test void fiftyIdsIssueExactlyTwoParameterizedQueries(){
    when(jdbc.query(any(PreparedStatementCreator.class),any(RowMapper.class))).thenReturn(List.of());
    var ids=LongStream.rangeClosed(1,50).boxed().toList();
    assertThat(media.imagesForItems(ids)).isEmpty();assertThat(claims.summariesForItems(ids,7)).isEmpty();
    var capture=ArgumentCaptor.forClass(PreparedStatementCreator.class);
    verify(jdbc,times(2)).query(capture.capture(),any(RowMapper.class));verifyNoMoreInteractions(jdbc);
    var sql=capture.getAllValues().stream().map(p->((SqlProvider)p).getSql()).toList();
    assertThat(sql.getFirst()).contains("IN (", "m.lifecycle='BOUND'", "ORDER BY i.item_id,i.display_order").doesNotContain("storage_key");
    assertThat(sql.getLast()).contains("applicant_id=?", "CASE WHEN applicant_id=? THEN id ELSE NULL END").doesNotContain("evidence","contact");
    assertThat(sql.getFirst().chars().filter(c->c=='?').count()).isEqualTo(50);
    assertThat(sql.getLast().chars().filter(c->c=='?').count()).isEqualTo(52);
  }
  @Test void emptyPagesNeverQuery(){
    assertThat(media.imagesForItems(List.of())).isEmpty();assertThat(claims.summariesForItems(List.of(),7)).isEmpty();verifyNoInteractions(jdbc);
  }
  @Test void invalidBatchesAndActorsFailBeforeQuery(){
    var ids=LongStream.rangeClosed(1,51).boxed().toList();
    assertThatThrownBy(()->media.imagesForItems(ids)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->claims.summariesForItems(ids,7)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->claims.summariesForItems(List.of(1L),0)).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(jdbc);
  }
}
