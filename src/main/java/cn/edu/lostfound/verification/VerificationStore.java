package cn.edu.lostfound.verification;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** JDBC lock reads deliberately bypass JPA's first-level cache. All dates in these tables are UTC. */
@Repository
public class VerificationStore {
  private final JdbcTemplate jdbc;
  public VerificationStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }

  record Qualification(Long userId, String campusId, String status, Long currentApplicationId,
      long applicationVersion, Instant expiresAt, String reason, long version, boolean isTest,
      String username, String role, String realName) {}
  record ApplicationRow(Long id, Long userId, long applicationVersion, String status,
      String realName, String studentNumber, String statement, Instant submittedAt,
      Instant reviewedAt, LocalDate validThrough, String reason, String method,
      String evidenceSummary, String internalNote, Long reviewerId, boolean isTest) {}

  private static final String SUMMARY_SELECT = """
      SELECT v.*,u.is_test,u.username,u.role,a.real_name FROM campus_verifications v
      JOIN users u ON u.id=v.user_id
      LEFT JOIN verification_applications a ON a.id=v.current_application_id
      """;
  private static final RowMapper<Qualification> QUALIFICATION = (r,n) -> new Qualification(
      r.getLong("user_id"),r.getString("campus_code"),r.getString("stored_status"),
      nullableLong(r,"current_application_id"),r.getLong("last_application_version"),
      instant(r,"expires_at"),r.getString("status_reason"),r.getLong("row_version"),
      r.getBoolean("is_test"),r.getString("username"),r.getString("role"),r.getString("real_name"));
  private static final RowMapper<ApplicationRow> APPLICATION = (r,n) -> new ApplicationRow(
      r.getLong("id"),r.getLong("user_id"),r.getLong("application_version"),r.getString("status"),
      r.getString("real_name"),r.getString("student_number"),r.getString("statement"),
      instant(r,"submitted_at"),instant(r,"reviewed_at"),r.getObject("valid_through",LocalDate.class),
      r.getString("user_reason"),r.getString("method"),r.getString("evidence_summary"),
      r.getString("internal_note"),nullableLong(r,"reviewed_by"),r.getBoolean("is_test"));

  public void initialize(Long userId,String campusId,Instant now) {
    jdbc.update("""
        INSERT INTO campus_verifications(user_id,campus_code,stored_status,created_at,updated_at)
        VALUES (?,?,'UNVERIFIED',?,?)
        """,userId,campusId,utc(now),utc(now));
  }
  Optional<Qualification> find(Long userId) {
    return jdbc.query(SUMMARY_SELECT+" WHERE v.user_id=?",QUALIFICATION,userId).stream().findFirst();
  }
  Optional<Qualification> lock(Long userId) {
    // Lock only the coordinator first; joining users in FOR UPDATE would add accidental user locks.
    List<Qualification> rows=jdbc.query("""
        SELECT v.*,false AS is_test,'' AS username,'' AS role,NULL AS real_name
        FROM campus_verifications v WHERE user_id=? FOR UPDATE
        """,QUALIFICATION,userId);
    if(rows.isEmpty()) return Optional.empty();
    Qualification locked=rows.getFirst();
    // Qualification facts come from the locking read even if an outer caller uses snapshot isolation.
    return find(userId).map(metadata->new Qualification(locked.userId(),locked.campusId(),locked.status(),
        locked.currentApplicationId(),locked.applicationVersion(),locked.expiresAt(),locked.reason(),locked.version(),
        metadata.isTest(),metadata.username(),metadata.role(),metadata.realName()));
  }
  Optional<ApplicationRow> application(Long id) {
    if(id==null) return Optional.empty();
    return jdbc.query("SELECT * FROM verification_applications WHERE id=?",APPLICATION,id).stream().findFirst();
  }
  List<ApplicationRow> history(Long userId,int page,int pageSize) {
    return jdbc.query("""
        SELECT * FROM verification_applications WHERE user_id=?
        ORDER BY submitted_at DESC,id DESC LIMIT ? OFFSET ?
        """,APPLICATION,userId,pageSize,((long)page-1)*pageSize);
  }
  long historyCount(Long userId) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM verification_applications WHERE user_id=?",Long.class,userId);
  }
  List<Qualification> list(String status,String keyword,Long userId,Instant now,int page,int pageSize) {
    Filter filter=filter(status,keyword,userId,now);
    List<Object> args=new ArrayList<>(filter.args());
    args.add(pageSize); args.add(((long)page-1)*pageSize);
    return jdbc.query(SUMMARY_SELECT+filter.sql()+" ORDER BY a.submitted_at DESC,v.user_id DESC LIMIT ? OFFSET ?",
        QUALIFICATION,args.toArray());
  }
  long listCount(String status,String keyword,Long userId,Instant now) {
    Filter filter=filter(status,keyword,userId,now);
    return jdbc.queryForObject("SELECT COUNT(*) FROM campus_verifications v JOIN users u ON u.id=v.user_id "
        +"LEFT JOIN verification_applications a ON a.id=v.current_application_id"+filter.sql(),Long.class,filter.args().toArray());
  }
  private record Filter(String sql,List<Object> args) {}
  private Filter filter(String status,String keyword,Long userId,Instant now) {
    StringBuilder sql=new StringBuilder(" WHERE 1=1");
    List<Object> args=new ArrayList<>();
    if(status!=null) {
      if("EXPIRED".equals(status)) { sql.append(" AND v.stored_status='VERIFIED' AND v.expires_at<=?"); args.add(utc(now)); }
      else if("VERIFIED".equals(status)) { sql.append(" AND v.stored_status='VERIFIED' AND v.expires_at>?"); args.add(utc(now)); }
      else { sql.append(" AND v.stored_status=?"); args.add(status); }
    }
    if(keyword!=null&&!keyword.isBlank()) {
      String pattern="%"+keyword.trim().replace("!","!!").replace("%","!%").replace("_","!_")+"%";
      sql.append(" AND (u.username LIKE ? ESCAPE '!' OR a.real_name LIKE ? ESCAPE '!')");
      args.add(pattern);args.add(pattern);
    }
    if(userId!=null) { sql.append(" AND v.user_id=?"); args.add(userId); }
    return new Filter(sql.toString(),args);
  }
  Long insertApplication(Qualification q,VerificationDtos.SubmitRequest request,Instant now) {
    GeneratedKeyHolder key=new GeneratedKeyHolder();
    jdbc.update(connection -> {
      var statement=connection.prepareStatement("""
          INSERT INTO verification_applications(user_id,application_version,campus_code,real_name,
          student_number,statement,status,submitted_at,is_test) VALUES (?,?,?,?,?,?,'PENDING',?,?)
          """,Statement.RETURN_GENERATED_KEYS);
      statement.setLong(1,q.userId()); statement.setLong(2,q.applicationVersion()+1);
      statement.setString(3,q.campusId());statement.setString(4,request.realName().trim());
      statement.setString(5,clean(request.studentNumber()));statement.setString(6,clean(request.statement()));
      statement.setObject(7,utc(now));statement.setBoolean(8,q.isTest());return statement;
    },key);
    return key.getKey().longValue();
  }
  void submit(Qualification q,Long applicationId,Instant now) {
    jdbc.update("""
        UPDATE campus_verifications SET stored_status='PENDING',current_application_id=?,
        last_application_version=last_application_version+1,expires_at=NULL,status_reason=NULL,
        row_version=row_version+1,updated_at=? WHERE user_id=? AND row_version=?
        """,applicationId,utc(now),q.userId(),q.version());
  }
  void reviewApplication(Long applicationId,Long actorId,VerificationDtos.ReviewRequest request,Instant now) {
    boolean approved=request.decision()==VerificationDtos.Decision.APPROVED;
    jdbc.update("""
        UPDATE verification_applications SET status=?,method=?,evidence_summary=?,valid_through=?,
        user_reason=?,internal_note=?,reviewed_by=?,reviewed_at=? WHERE id=? AND status='PENDING'
        """,approved?"VERIFIED":"REJECTED",approved?request.method().name():null,
        approved?clean(request.evidenceSummary()):null,approved?request.validThrough():null,
        approved?null:clean(request.reason()),clean(request.internalNote()),actorId,utc(now),applicationId);
  }
  void transition(Qualification q,String status,Instant expiresAt,String reason,Instant now) {
    jdbc.update("""
        UPDATE campus_verifications SET stored_status=?,expires_at=?,status_reason=?,
        row_version=row_version+1,updated_at=? WHERE user_id=? AND row_version=?
        """,status,utc(expiresAt),clean(reason),utc(now),q.userId(),q.version());
  }
  static LocalDateTime utc(Instant time) { return time==null?null:LocalDateTime.ofInstant(time,ZoneOffset.UTC); }
  static String clean(String value) { return value==null||value.isBlank()?null:value.trim(); }
  private static Instant instant(ResultSet r,String field) throws SQLException {
    LocalDateTime value=r.getObject(field,LocalDateTime.class);return value==null?null:value.toInstant(ZoneOffset.UTC);
  }
  private static Long nullableLong(ResultSet r,String field) throws SQLException {
    long value=r.getLong(field);return r.wasNull()?null:value;
  }
}
