package cn.edu.lostfound.service;

import cn.edu.lostfound.dto.AuthDtos;
import cn.edu.lostfound.entity.User;
import cn.edu.lostfound.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Service public class AuthService {
  private final UserRepository users; private final BCryptPasswordEncoder encoder; private final StringRedisTemplate redis; private final Duration ttl;
  public AuthService(UserRepository users,BCryptPasswordEncoder encoder,StringRedisTemplate redis,@Value("${app.session-ttl-hours:24}") long hours){this.users=users;this.encoder=encoder;this.redis=redis;this.ttl=Duration.ofHours(hours);}
  public void register(AuthDtos.Register req){if("admin".equalsIgnoreCase(req.username().strip()))throw new IllegalArgumentException("该用户名不可注册");if(exceedsBcryptLimit(req.password()))throw new IllegalArgumentException("密码不能超过 72 个 UTF-8 字节");if(users.existsByUsername(req.username())) throw new IllegalArgumentException("用户名已存在"); users.save(new User(req.username(),encoder.encode(req.password()),req.nickname(),"USER"));}
  public Map<String,Object> login(AuthDtos.Login req){if(exceedsBcryptLimit(req.password()))throw new IllegalArgumentException("用户名或密码错误");User u=users.findByUsername(req.username()).orElseThrow(()->new IllegalArgumentException("用户名或密码错误"));if(!encoder.matches(req.password(),u.getPassword()))throw new IllegalArgumentException("用户名或密码错误");String token=UUID.randomUUID().toString().replace("-","");redis.opsForValue().set("session:"+token,u.getId()+":"+u.getRole(),ttl);return Map.of("token",token,"userId",u.getId(),"username",u.getUsername(),"nickname",u.getNickname(),"role",u.getRole());}
  private boolean exceedsBcryptLimit(String password){return password.getBytes(StandardCharsets.UTF_8).length>72;}
  public void logout(String token){if(token!=null)redis.delete("session:"+token);}
}
