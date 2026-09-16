package cn.edu.lostfound.claim;

import cn.edu.lostfound.entity.Item;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** JDBC shares the JPA transaction/data source; rows are never cached across locking reads. */
@Repository
public class ClaimStore {
  private final JdbcTemplate jdbc;
  public ClaimStore(JdbcTemplate jdbc) {this.jdbc=jdbc;}
  public record Row(long id,long itemId,long publisherId,long applicantId,String title,long contentVersion,
      String evidence,String applicantContact,String publisherContact,String status,long version,
      Instant createdAt,Instant acceptedAt,Instant handedOverAt,Instant receivedAt,Instant completedAt,String endReason) {
    boolean participant(long actor){return actor==publisherId||actor==applicantId;}
    boolean singleConfirmed(){return (handedOverAt!=null) != (receivedAt!=null);}
    boolean anyConfirmed(){return handedOverAt!=null||receivedAt!=null;}
  }
  private static Instant instant(ResultSet rs,String col)throws SQLException {
    var v=rs.getObject(col,LocalDateTime.class);return v==null?null:v.toInstant(ZoneOffset.UTC);
  }
  static LocalDateTime utc(Instant value){return LocalDateTime.ofInstant(value,ZoneOffset.UTC);}
  static final RowMapper<Row> MAPPER=(r,n)->new Row(r.getLong("id"),r.getLong("item_id"),r.getLong("publisher_id"),r.getLong("applicant_id"),
      r.getString("item_title_snapshot"),r.getLong("item_content_version_snapshot"),r.getString("evidence"),r.getString("applicant_contact_snapshot"),
      r.getString("publisher_contact_snapshot"),r.getString("status"),r.getLong("row_version"),instant(r,"created_at"),instant(r,"accepted_at"),
      instant(r,"handed_over_at"),instant(r,"received_at"),instant(r,"completed_at"),r.getString("end_reason"));
  Optional<Row> find(long id){return jdbc.query("SELECT * FROM claims WHERE id=?",MAPPER,id).stream().findFirst();}
  Row lock(long id){return jdbc.query("SELECT * FROM claims WHERE id=? FOR UPDATE",MAPPER,id).stream().findFirst().orElseThrow(ClaimService::hidden);}
  List<Row> lockItem(long item){
    // Caller owns the item lock, so no claim can be inserted or changed while IDs are collected.
    return jdbc.queryForList("SELECT id FROM claims WHERE item_id=? ORDER BY id",Long.class,item).stream().map(this::lock).toList();
  }
  long create(Item item,long applicant,ClaimDtos.Create r,Instant now){
    var key=new GeneratedKeyHolder();
    jdbc.update(c->{var s=c.prepareStatement("""
      INSERT INTO claims(item_id,publisher_id,applicant_id,item_title_snapshot,item_type_snapshot,item_content_version_snapshot,
      evidence,applicant_contact_snapshot,status,created_at,updated_at) VALUES (?,?,?,?,'FOUND',?,?,?,'APPLIED',?,?)
      """,Statement.RETURN_GENERATED_KEYS);
      s.setLong(1,item.getId());s.setLong(2,item.getPublisherId());s.setLong(3,applicant);s.setString(4,item.getTitle());
      s.setLong(5,item.getContentVersion());s.setString(6,r.identification().trim());s.setString(7,r.contact().trim());
      s.setObject(8,utc(now));s.setObject(9,utc(now));return s;},key);
    return Objects.requireNonNull(key.getKey()).longValue();
  }
  void accept(Row r,String contact,Instant now){jdbc.update("UPDATE claims SET status='ACCEPTED',publisher_contact_snapshot=?,accepted_at=?,updated_at=?,row_version=row_version+1 WHERE id=?",contact.trim(),utc(now),utc(now),r.id());}
  void end(Row r,String status,String code,String reason,boolean exceptional,Instant now){
    jdbc.update("UPDATE claims SET status=?,end_reason_code=?,end_reason=?,exception_terminated=?,ended_at=?,updated_at=?,row_version=row_version+1 WHERE id=?",status,code,reason.trim(),exceptional,utc(now),utc(now),r.id());
  }
  void confirm(Row r,boolean handover,boolean complete,Instant now){
    String column=handover?"handed_over_at":"received_at";
    jdbc.update("UPDATE claims SET "+column+"=?,updated_at=?,row_version=row_version+1"+
        (complete?",status='COMPLETED',completed_at=?,ended_at=?":"")+" WHERE id=?",
        complete?new Object[]{utc(now),utc(now),utc(now),utc(now),r.id()}:new Object[]{utc(now),utc(now),r.id()});
  }
  void touch(Row r,Instant now){jdbc.update("UPDATE claims SET row_version=row_version+1,updated_at=? WHERE id=?",utc(now),r.id());}
}
