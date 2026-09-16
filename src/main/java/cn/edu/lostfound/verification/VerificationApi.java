package cn.edu.lostfound.verification;

import cn.edu.lostfound.common.BusinessException;
import cn.edu.lostfound.config.CampusProperties;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Public module boundary. Eligibility is always read from current database facts, never a token/cache. */
@Service
public class VerificationApi {
  private final VerificationStore store;
  private final CampusProperties campus;
  private final Clock clock;
  private final EntityManager entityManager;
  public VerificationApi(VerificationStore store,CampusProperties campus,Clock clock,EntityManager entityManager) {
    this.store=store;this.campus=campus;this.clock=clock;this.entityManager=entityManager;
  }

  @Transactional(propagation=Propagation.MANDATORY)
  public void initialize(Long userId) { store.initialize(userId,campus.campusId(),clock.instant()); }

  @Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED)
  public VerificationDtos.VerificationSummary summary(Long userId) { return summary(required(userId),clock.instant()); }

  @Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED)
  public void requireEligible(Long userId) {
    var q=store.find(userId).orElseThrow(VerificationApi::ineligible);
    checkEligible(q,clock.instant());
  }

  @Transactional(propagation=Propagation.MANDATORY)
  public void lockEligible(Long userId) {
    lockEligible(userId,false);
  }

  @Transactional(propagation=Propagation.MANDATORY)
  public void lockCounterpartEligible(Long userId) {
    lockEligible(userId,true);
  }

  /** Non-throwing projection avoids marking a read transaction rollback-only. */
  @Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED)
  public boolean eligible(Long userId) {
    var q=store.find(userId);
    if(q.isEmpty())return false;
    try {checkEligible(q.get(),clock.instant());return true;}
    catch(BusinessException e){if(e.getHttpStatus()==403)return false;throw e;}
  }

  private void lockEligible(Long userId,boolean counterpart) {
    var q=store.lock(userId).orElseThrow(()->counterpart?counterpartIneligible():ineligible());
    checkParticipant(q,clock.instant(),counterpart);
    EligibilityCommitGuard guard=null;
    for(var synchronization:TransactionSynchronizationManager.getSynchronizations()) {
      if(synchronization instanceof EligibilityCommitGuard existing) { guard=existing;break; }
    }
    if(guard==null) {
      guard=new EligibilityCommitGuard();
      TransactionSynchronizationManager.registerSynchronization(guard);
    }
    guard.userIds.add(userId);
    if(counterpart&&!guard.actorIds.contains(userId))guard.counterpartIds.add(userId);
    if(!counterpart){guard.actorIds.add(userId);guard.counterpartIds.remove(userId);}
  }

  private void checkParticipant(VerificationStore.Qualification q,Instant now,boolean counterpart) {
    try {checkEligible(q,now);} catch(BusinessException e) {
      if(counterpart&&e.getHttpStatus()==403)throw counterpartIneligible();
      throw e;
    }
  }

  @Transactional(propagation=Propagation.MANDATORY)
  public void lockForAccount(Long userId) {
    store.lock(userId).orElseThrow(VerificationApi::ineligible);
  }

  VerificationStore.Qualification required(Long userId) {
    return store.find(userId).orElseThrow(()->new BusinessException(404,"NOT_ACCESSIBLE","账号认证记录不存在"));
  }
  void checkEnvironment(VerificationStore.Qualification q) {
    if(!campus.campusId().equals(q.campusId())||campus.testMode()!=q.isTest()) {
      throw new BusinessException(403,"VERIFICATION_REQUIRED","账号所属校园或测试环境不匹配，请联系支持人员");
    }
  }
  void checkEligible(VerificationStore.Qualification q,Instant now) {
    checkEnvironment(q);
    if(!"VERIFIED".equals(effectiveStatus(q,now))) throw ineligible();
  }
  static String effectiveStatus(VerificationStore.Qualification q,Instant now) {
    if("VERIFIED".equals(q.status())&&(q.expiresAt()==null||!now.isBefore(q.expiresAt()))) return "EXPIRED";
    return q.status();
  }
  VerificationDtos.VerificationSummary summary(VerificationStore.Qualification q,Instant now) {
    return new VerificationDtos.VerificationSummary(q.userId(),q.campusId(),effectiveStatus(q,now),q.version(),
        offset(q.expiresAt()),validThrough(q.expiresAt()),q.isTest(),q.reason());
  }
  OffsetDateTime offset(Instant instant) { return instant==null?null:instant.atZone(campus.zoneId()).toOffsetDateTime(); }
  LocalDate validThrough(Instant expiry) { return expiry==null?null:expiry.atZone(campus.zoneId()).toLocalDate().minusDays(1); }
  private static BusinessException ineligible() {
    return new BusinessException(403,"VERIFICATION_REQUIRED","当前校园身份未认证或已失效，请先完成人工核验");
  }
  private static BusinessException counterpartIneligible() {
    return new BusinessException(409,"COUNTERPART_INELIGIBLE","对方当前不具备交接资格，请等待重新核验");
  }
  private final class EligibilityCommitGuard implements TransactionSynchronization {
    private final TreeSet<Long> userIds=new TreeSet<>();
    private final TreeSet<Long> counterpartIds=new TreeSet<>();
    private final TreeSet<Long> actorIds=new TreeSet<>();
    @Override public void beforeCommit(boolean readOnly) {
      // Flush first, then obtain current locked facts, then take the final time sample.
      // No external I/O, extra business writes, or waits may follow this eligibility boundary.
      entityManager.flush();
      List<VerificationStore.Qualification> qualifications=new ArrayList<>();
      for(Long id:userIds) qualifications.add(store.lock(id).orElseThrow(()->counterpartIds.contains(id)?counterpartIneligible():ineligible()));
      Instant now=clock.instant();
      for(var q:qualifications) checkParticipant(q,now,counterpartIds.contains(q.userId()));
    }
  }
}
