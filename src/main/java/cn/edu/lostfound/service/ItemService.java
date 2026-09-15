package cn.edu.lostfound.service;

import cn.edu.lostfound.dto.ItemDtos;
import cn.edu.lostfound.entity.*;
import cn.edu.lostfound.repository.*;
import org.springframework.cache.annotation.*;
import org.springframework.stereotype.Service;
import java.util.*;

@Service public class ItemService {
  private final ItemRepository items; private final UserRepository users;
  public ItemService(ItemRepository items,UserRepository users){this.items=items;this.users=users;}
  public Item create(Long userId,ItemDtos.Save r){User u=users.findById(userId).orElseThrow();return items.save(new Item(u,r.title(),r.description(),r.type(),r.category(),r.location(),r.occurredAt()));}
  @Cacheable(value="itemSearch",key="#keyword + ':' + #type") public List<Item> search(String keyword,String type){return items.search(keyword==null?"":keyword,type==null?"":type);}
  public List<Item> mine(Long id){return items.findByPublisherIdOrderByCreatedAtDesc(id);}
  public List<Item> pending(){return items.findByStatusOrderByCreatedAtDesc("PENDING");}
  @CacheEvict(value="itemSearch",allEntries=true) public Item review(Long id,String status){Item i=items.findById(id).orElseThrow(()->new IllegalArgumentException("信息不存在"));i.setStatus(status);return items.save(i);}
  @CacheEvict(value="itemSearch",allEntries=true)
  public Item update(Long id,Long userId,boolean admin,ItemDtos.Save r){
    Item i=items.findById(id).orElseThrow(()->new IllegalArgumentException("信息不存在"));
    if(!admin&&!i.getPublisher().getId().equals(userId))throw new SecurityException("无权修改");
    i.update(r.title(),r.description(),r.type(),r.category(),r.location(),r.occurredAt());
    if(!admin)i.setStatus("PENDING");
    return items.save(i);
  }
  public List<Map<String,Object>> matches(Long itemId,Long userId,boolean admin){
    Item base=items.findById(itemId).orElseThrow(()->new IllegalArgumentException("信息不存在"));
    if(!"APPROVED".equals(base.getStatus())&&!admin&&!base.getPublisher().getId().equals(userId)){
      throw new SecurityException("无权查看未公开信息");
    }
    return items.search("",base.getType().equals("LOST")?"FOUND":"LOST").stream().map(i->Map.<String,Object>of("id",i.getId(),"title",i.getTitle(),"type",i.getType(),"location",String.valueOf(i.getLocation()),"score",similarity(base,i))).sorted((a,b)->Double.compare((double)b.get("score"),(double)a.get("score"))).limit(5).toList();
  }
  private double similarity(Item a,Item b){Set<String> x=tokens(a.getTitle()+" "+a.getDescription()+" "+a.getCategory()+" "+a.getLocation()),y=tokens(b.getTitle()+" "+b.getDescription()+" "+b.getCategory()+" "+b.getLocation());Set<String> all=new HashSet<>(x);all.addAll(y);if(all.isEmpty())return 0;Set<String> common=new HashSet<>(x);common.retainAll(y);return Math.round(common.size()*10000.0/all.size())/100.0;}
  private Set<String> tokens(String text){Set<String>s=new HashSet<>();String normalized=text.toLowerCase().replaceAll("[^\\p{IsHan}a-z0-9]","");for(int i=0;i<normalized.length();i++)s.add(normalized.substring(i,i+1));return s;}
}
