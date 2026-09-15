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
  protected Item() {}
  public Item(User publisher,String title,String description,String type,String category,String location,LocalDate occurredAt){this.publisher=publisher;this.title=title;this.description=description;this.type=type;this.category=category;this.location=location;this.occurredAt=occurredAt;}
  @PreUpdate void updateTime(){updatedAt=LocalDateTime.now();}
  public Long getId(){return id;} @JsonIgnore public User getPublisher(){return publisher;} public Long getPublisherId(){return publisher.getId();} public String getTitle(){return title;} public String getDescription(){return description;} public String getType(){return type;} public String getCategory(){return category;} public String getLocation(){return location;} public LocalDate getOccurredAt(){return occurredAt;} public String getStatus(){return status;} public LocalDateTime getCreatedAt(){return createdAt;}
  public void setStatus(String status){this.status=status;} public void update(String title,String description,String type,String category,String location,LocalDate occurredAt){this.title=title;this.description=description;this.type=type;this.category=category;this.location=location;this.occurredAt=occurredAt;}
}
