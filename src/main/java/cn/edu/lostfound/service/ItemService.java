package cn.edu.lostfound.service;

import cn.edu.lostfound.dto.ItemDtos;
import cn.edu.lostfound.entity.*;
import cn.edu.lostfound.repository.*;
import cn.edu.lostfound.verification.VerificationApi;
import cn.edu.lostfound.security.UserContext;
import cn.edu.lostfound.identity.AccountApi;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;
import java.util.*;

@Service public class ItemService {
  private final ItemRepository items; private final UserRepository users;
  private final VerificationApi verification; private final AccountApi accounts;
  public ItemService(ItemRepository items,UserRepository users,VerificationApi verification,AccountApi accounts){this.items=items;this.users=users;this.verification=verification;this.accounts=accounts;}
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public Item create(Long userId,ItemDtos.Save r){verification.lockEligible(userId);User u=users.findById(userId).orElseThrow();return items.save(new Item(u,r.title(),r.description(),r.type(),r.category(),r.location(),r.occurredAt()));}
  public List<Item> search(String keyword,String type){return items.search(keyword==null?"":keyword,type==null?"":type);}
  public List<Item> mine(Long id){return items.findByPublisherIdOrderByCreatedAtDesc(id);}
  public List<Item> pending(){return items.findByStatusOrderByCreatedAtDesc("PENDING");}
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public Item review(Long id,String status){verification.lockForAccount(UserContext.id());accounts.requireAdministrator(UserContext.id());Item i=items.findById(id).orElseThrow(()->new IllegalArgumentException("信息不存在"));i.setStatus(status);return items.save(i);}
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public Item update(Long id,Long userId,boolean admin,ItemDtos.Save r){
    verification.lockEligible(userId);
    Item i=items.findById(id).orElseThrow(()->new IllegalArgumentException("信息不存在"));
    if(!i.getPublisher().getId().equals(userId))throw new SecurityException("无权修改");
    i.update(r.title(),r.description(),r.type(),r.category(),r.location(),r.occurredAt());
    i.setStatus("PENDING");
    return items.save(i);
  }
  public List<Map<String,Object>> matches(Long itemId,Long userId,boolean admin){
    Item base=items.findById(itemId).orElseThrow(()->new IllegalArgumentException("信息不存在"));
    if(!"APPROVED".equals(base.getStatus())&&!base.getPublisher().getId().equals(userId)){
      throw new SecurityException("无权查看未公开信息");
    }
    return items.search("",base.getType().equals("LOST")?"FOUND":"LOST").stream().map(i->Map.<String,Object>of("id",i.getId(),"title",i.getTitle(),"type",i.getType(),"location",String.valueOf(i.getLocation()),"score",similarity(base,i))).sorted((a,b)->Double.compare((double)b.get("score"),(double)a.get("score"))).limit(5).toList();
  }
  private double similarity(Item a,Item b){Set<String> x=tokens(a.getTitle()+" "+a.getDescription()+" "+a.getCategory()+" "+a.getLocation()),y=tokens(b.getTitle()+" "+b.getDescription()+" "+b.getCategory()+" "+b.getLocation());Set<String> all=new HashSet<>(x);all.addAll(y);if(all.isEmpty())return 0;Set<String> common=new HashSet<>(x);common.retainAll(y);return Math.round(common.size()*10000.0/all.size())/100.0;}
  private Set<String> tokens(String text){Set<String>s=new HashSet<>();String normalized=text.toLowerCase().replaceAll("[^\\p{IsHan}a-z0-9]","");for(int i=0;i<normalized.length();i++)s.add(normalized.substring(i,i+1));return s;}
}
