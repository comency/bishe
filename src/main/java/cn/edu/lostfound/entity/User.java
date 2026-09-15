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
  @Column(nullable=false) private String role = "USER";
  private LocalDateTime createdAt = LocalDateTime.now();
  protected User() {}
  public User(String username, String password, String nickname, String role) { this.username=username; this.password=password; this.nickname=nickname; this.role=role; }
  public Long getId(){return id;} public String getUsername(){return username;} @JsonIgnore public String getPassword(){return password;} public String getNickname(){return nickname;} public String getPhone(){return phone;} public String getRole(){return role;}
  public void setPhone(String phone){this.phone=phone;}
}
