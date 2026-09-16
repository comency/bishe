package cn.edu.lostfound.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.*;

@Entity @Table(name="items")
public class Item {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
  @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="publisher_id", nullable=false) private User publisher;
  @Column(nullable=false, length=100) private String title;
  @Column(nullable=false, length=3000) private String description;
  @Column(nullable=false, length=10) private String type; // LOST or FOUND
  private String category; private String location; private LocalDate occurredAt;
  @Column(nullable=false, length=20) private String status="PENDING"; // PENDING, APPROVED, CLOSED, REJECTED
  private LocalDateTime createdAt=LocalDateTime.now();
  private LocalDateTime updatedAt=LocalDateTime.now();
  @Version @Column(name="row_version") private long version;
  private long contentVersion=1;
  private Long reviewedContentVersion;
  private Long reviewedBy;
  private Instant reviewedAt;
  @Column(length=500) private String reviewReason;
  @Column(length=32) private String closeReason;
  @Column(length=500) private String closeNote;
  private Instant closedAt;
  private Instant createdAtUtc;
  private Instant updatedAtUtc;
  protected Item() {}
  public Item(User publisher,String title,String description,String type,String category,String location,LocalDate occurredAt){this.publisher=publisher;this.title=title;this.description=description;this.type=type;this.category=category;this.location=location;this.occurredAt=occurredAt;}
  public void initializeTime(Instant now,ZoneId zone){createdAtUtc=now;updatedAtUtc=now;createdAt=LocalDateTime.ofInstant(now,zone);updatedAt=createdAt;}
  public void changed(Instant now,ZoneId zone){updatedAtUtc=now;updatedAt=LocalDateTime.ofInstant(now,zone);}
  public long getVersion(){return version;}
  public long getContentVersion(){return contentVersion;}
  public String getReviewReason(){return reviewReason;}
  public String getCloseReason(){return closeReason;}
  public Instant createdInstant(ZoneId legacyZone){return createdAtUtc==null?createdAt.atZone(legacyZone).toInstant():createdAtUtc;}
  public Instant updatedInstant(ZoneId legacyZone){return updatedAtUtc==null?updatedAt.atZone(legacyZone).toInstant():updatedAtUtc;}
  public void resubmit(){contentVersion++;status="PENDING";reviewReason=null;reviewedBy=null;reviewedAt=null;reviewedContentVersion=null;}
  public void review(String decision,Long reviewer,String reason,Instant now){status=decision;reviewedBy=reviewer;reviewReason=reason;reviewedAt=now;reviewedContentVersion=contentVersion;}
  public void close(String reason,String note,Instant now){status="CLOSED";closeReason=reason;closeNote=note;closedAt=now;}
  public Long getId(){return id;} @JsonIgnore public User getPublisher(){return publisher;} public Long getPublisherId(){return publisher.getId();} public String getTitle(){return title;} public String getDescription(){return description;} public String getType(){return type;} public String getCategory(){return category;} public String getLocation(){return location;} public LocalDate getOccurredAt(){return occurredAt;} public String getStatus(){return status;} public LocalDateTime getCreatedAt(){return createdAt;}
  public void setStatus(String status){this.status=status;} public void update(String title,String description,String type,String category,String location,LocalDate occurredAt){this.title=title;this.description=description;this.type=type;this.category=category;this.location=location;this.occurredAt=occurredAt;}
}
