package cn.edu.lostfound.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity @Table(name="users")
public class User {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
  @Column(unique=true, nullable=false, length=40) private String username;
  @Column(nullable=false) private String password;
  private String nickname; private String phone;
  @Column(length=100) private String contact;
  @Version @Column(name="row_version", nullable=false) private long version;
  @Column(name="is_test", nullable=false) private boolean test;
  @Column(nullable=false) private LocalDateTime updatedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
  @Column(nullable=false) private String role = "USER";
  private LocalDateTime createdAt = LocalDateTime.now();
  protected User() {}
  public User(String username, String password, String nickname, String role) { this.username=username; this.password=password; this.nickname=nickname; this.role=role; }
  public Long getId(){return id;} public String getUsername(){return username;} @JsonIgnore public String getPassword(){return password;} public String getNickname(){return nickname;} public String getPhone(){return phone;} public String getRole(){return role;}
  public void setPhone(String phone){this.phone=phone;}
  public String getContact(){return contact;}
  public long getVersion(){return version;}
  public boolean isTest(){return test;}
  public void initializeEnvironment(boolean test, java.time.Instant now){
    this.test=test;
    this.createdAt=LocalDateTime.ofInstant(now, java.time.ZoneOffset.UTC);
    this.updatedAt=this.createdAt;
  }
  public void updateProfile(String nickname, String contact, java.time.Instant now){
    this.nickname=nickname;
    this.contact=contact;
    this.updatedAt=LocalDateTime.ofInstant(now, java.time.ZoneOffset.UTC);
  }
}
