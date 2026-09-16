package cn.edu.lostfound.verification;

import cn.edu.lostfound.audit.AuditApi;
import cn.edu.lostfound.common.BusinessException;
import cn.edu.lostfound.config.CampusProperties;
import cn.edu.lostfound.security.UserContext;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static cn.edu.lostfound.verification.VerificationDtos.*;

@Service
@Transactional(isolation=Isolation.READ_COMMITTED)
public class VerificationService {
  private final VerificationStore store;
  private final VerificationApi api;
  private final AuditApi audit;
  private final CampusProperties campus;
  private final Clock clock;
  public VerificationService(VerificationStore store,VerificationApi api,AuditApi audit,
      CampusProperties campus,Clock clock) {
    this.store=store;this.api=api;this.audit=audit;this.campus=campus;this.clock=clock;
  }

  @Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED)
  public MyVerification mine(int page,int pageSize) {
    validatePage(page,pageSize);
    return mine(loginId(),page,pageSize,clock.instant());
  }
  public MyVerification submit(SubmitRequest request) {
    Long userId=loginId();
    var q=store.lock(userId).orElseThrow(()->notFound());
    api.checkEnvironment(q);
    version(q,request.expectedVersion());
    Instant now=clock.instant();
    String from=VerificationApi.effectiveStatus(q,now);
    if(!Set.of("UNVERIFIED","REJECTED","EXPIRED").contains(from)) throw conflict("当前状态不允许提交；待审不能重提，撤销后需先允许重核");
    Long applicationId=store.insertApplication(q,request,now);
    store.submit(q,applicationId,now);
    audit.verification("VERIFICATION_SUBMITTED",userId,UserContext.admin(),userId,applicationId,
        from,"PENDING",q.version()+1,null);
    return mine(userId,1,10,now);
  }

  @Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED)
  public Page<AdminSummary> list(Status status,String keyword,Long userId,int page,int pageSize) {
    requireAdmin();validatePage(page,pageSize);
    if(keyword!=null&&keyword.length()>80) throw invalid("关键词不能超过80个字符");
    if(userId!=null&&userId<1) throw invalid("账号ID必须为正整数");
    Instant now=clock.instant();
    String statusName=status==null?null:status.name();
    return new Page<>(store.list(statusName,keyword,userId,now,page,pageSize).stream()
        .map(q->adminSummary(q,now)).toList(),store.listCount(statusName,keyword,userId,now),page,pageSize);
  }
  @Transactional(readOnly=true,isolation=Isolation.READ_COMMITTED)
  public AdminVerification detail(Long userId,int page,int pageSize) {
    requireAdmin();validatePage(page,pageSize);validateUserId(userId);
    return adminDetail(userId,page,pageSize,clock.instant());
  }
  public AdminVerification review(Long userId,ReviewRequest request) {
    validateReview(request);
    var q=lockAdminTarget(userId);
    version(q,request.expectedVersion());
    if(!"PENDING".equals(q.status())||!Objects.equals(q.currentApplicationId(),request.applicationId())) {
      throw conflict("申请已变化或不再待审，请刷新当前申请");
    }
    var application=store.application(request.applicationId()).orElseThrow(VerificationService::notFound);
    if(!application.userId().equals(userId)||!"PENDING".equals(application.status())||application.isTest()!=campus.testMode()) {
      throw conflict("当前申请不允许审核");
    }
    Instant now=clock.instant();
    boolean approved=request.decision()==Decision.APPROVED;
    Instant expiry=null;
    if(approved) {
      try { expiry=request.validThrough().plusDays(1).atStartOfDay(campus.zoneId()).toInstant(); }
      catch(DateTimeException ex) { throw invalid("有效截止日期超出支持范围"); }
      if(!now.isBefore(expiry)) throw invalid("有效截止日期已过期，不能通过认证");
    }
    String status=approved?"VERIFIED":"REJECTED";
    String reason=approved?null:VerificationStore.clean(request.reason());
    store.reviewApplication(application.id(),UserContext.id(),request,now);
    store.transition(q,status,expiry,reason,now);
    audit.verification(approved?"VERIFICATION_APPROVED":"VERIFICATION_REJECTED",UserContext.id(),true,
        userId,application.id(),"PENDING",status,q.version()+1,reason);
    return adminDetail(userId,1,10,now);
  }
  public AdminVerification revoke(Long userId,VersionReasonRequest request) {
    var q=lockAdminTarget(userId);version(q,request.expectedVersion());
    Instant now=clock.instant();
    if(!"VERIFIED".equals(VerificationApi.effectiveStatus(q,now))) throw conflict("仅当前有效的认证可以撤销");
    String reason=VerificationStore.clean(request.reason());
    store.transition(q,"REVOKED",q.expiresAt(),reason,now);
    audit.verification("VERIFICATION_REVOKED",UserContext.id(),true,userId,q.currentApplicationId(),
        "VERIFIED","REVOKED",q.version()+1,reason);
    return adminDetail(userId,1,10,now);
  }
  public AdminVerification reopen(Long userId,VersionReasonRequest request) {
    var q=lockAdminTarget(userId);version(q,request.expectedVersion());
    if(!"REVOKED".equals(q.status())) throw conflict("仅已撤销账号可以允许重新核验");
    Instant now=clock.instant();String reason=VerificationStore.clean(request.reason());
    store.transition(q,"UNVERIFIED",null,reason,now);
    audit.verification("VERIFICATION_REOPENED",UserContext.id(),true,userId,q.currentApplicationId(),
        "REVOKED","UNVERIFIED",q.version()+1,reason);
    return adminDetail(userId,1,10,now);
  }

  private VerificationStore.Qualification lockAdminTarget(Long userId) {
    Long actor=requireAdmin();validateUserId(userId);
    if(actor.equals(userId)) throw new BusinessException(403,"FORBIDDEN","管理员不能审核或变更自己的校园认证");
    VerificationStore.Qualification target=null;
    for(Long id:new TreeSet<>(Set.of(actor,userId))) {
      var q=store.lock(id).orElseThrow(VerificationService::notFound);
      api.checkEnvironment(q);
      if(id.equals(actor)&&!"ADMIN".equals(q.role())) throw new BusinessException(403,"FORBIDDEN","需要管理员权限");
      if(id.equals(userId)) target=q;
    }
    return target;
  }
  private Long requireAdmin() {
    Long id=loginId();
    if(!UserContext.admin()) throw new BusinessException(403,"FORBIDDEN","需要管理员权限");
    var actor=api.required(id);
    if(!"ADMIN".equals(actor.role())) throw new BusinessException(403,"FORBIDDEN","需要管理员权限");
    api.checkEnvironment(actor);
    return id;
  }
  private static Long loginId() {
    if(UserContext.id()==null) throw new BusinessException(401,"AUTH_REQUIRED","请先登录");
    return UserContext.id();
  }
  private MyVerification mine(Long userId,int page,int pageSize,Instant now) {
    var q=api.required(userId);
    return new MyVerification(api.summary(q,now),store.application(q.currentApplicationId()).map(this::application).orElse(null),
        new Page<>(store.history(userId,page,pageSize).stream().map(this::application).toList(),store.historyCount(userId),page,pageSize));
  }
  private AdminVerification adminDetail(Long userId,int page,int pageSize,Instant now) {
    var q=api.required(userId);
    return new AdminVerification(adminSummary(q,now),store.application(q.currentApplicationId()).map(this::adminApplication).orElse(null),
        new Page<>(store.history(userId,page,pageSize).stream().map(this::adminApplication).toList(),store.historyCount(userId),page,pageSize));
  }
  private AdminSummary adminSummary(VerificationStore.Qualification q,Instant now) {
    var summary=api.summary(q,now);
    return new AdminSummary(summary.userId(),summary.campusId(),summary.status(),summary.version(),summary.expiresAt(),
        summary.validThrough(),summary.isTest(),summary.reason(),q.username(),q.realName(),q.currentApplicationId(),q.applicationVersion());
  }
  private Application application(VerificationStore.ApplicationRow a) {
    return new Application(a.id(),a.applicationVersion(),a.status(),a.realName(),a.studentNumber(),a.statement(),
        api.offset(a.submittedAt()),api.offset(a.reviewedAt()),a.validThrough(),a.reason());
  }
  private AdminApplication adminApplication(VerificationStore.ApplicationRow a) {
    return new AdminApplication(a.id(),a.applicationVersion(),a.status(),a.realName(),a.studentNumber(),a.statement(),
        api.offset(a.submittedAt()),api.offset(a.reviewedAt()),a.validThrough(),a.reason(),a.method(),a.evidenceSummary(),a.internalNote(),a.reviewerId());
  }
  private static void version(VerificationStore.Qualification q,Long expected) {
    if(expected==null||expected<0) throw invalid("请提供当前资格版本");
    if(expected!=q.version()) throw new BusinessException(409,"VERSION_CONFLICT","记录已变化，请刷新后重试");
  }
  private static void validatePage(int page,int pageSize) {
    if(page<1||pageSize<1||pageSize>50) throw invalid("页码至少为1，每页数量为1至50");
  }
  private static void validateUserId(Long id) { if(id==null||id<1) throw invalid("账号ID必须为正整数"); }
  private static void validateReview(ReviewRequest request) {
    if(request.decision()==null) throw invalid("请选择通过或驳回");
    if(request.decision()==Decision.APPROVED) {
      if(request.method()==null||VerificationStore.clean(request.evidenceSummary())==null||request.validThrough()==null) {
        throw invalid("通过必须填写核验方式、依据摘要和有效截止日期");
      }
      if(request.reason()!=null) throw invalid("通过分支不接受驳回原因");
      if(request.validThrough().getYear()<1000||request.validThrough().getYear()>9998) throw invalid("有效截止日期超出支持范围");
    } else {
      if(VerificationStore.clean(request.reason())==null) throw invalid("驳回必须填写申请人可见的原因");
      if(request.method()!=null||request.evidenceSummary()!=null||request.validThrough()!=null) throw invalid("驳回分支不接受通过字段");
    }
  }
  private static BusinessException invalid(String message) { return new BusinessException(400,"VALIDATION_ERROR",message); }
  private static BusinessException conflict(String message) { return new BusinessException(409,"STATE_CONFLICT",message); }
  private static BusinessException notFound() { return new BusinessException(404,"NOT_ACCESSIBLE","账号认证记录不存在"); }
}
