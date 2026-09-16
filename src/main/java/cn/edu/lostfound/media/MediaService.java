package cn.edu.lostfound.media;

import cn.edu.lostfound.common.BusinessException;
import cn.edu.lostfound.identity.AccountApi;
import cn.edu.lostfound.verification.VerificationApi;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/** Files never live in a web-served directory. Bytes are staged before acquiring business locks. */
@Service
public class MediaService {
  public static final int MAX_BYTES=5*1024*1024;
  private final JdbcTemplate jdbc;
  private final VerificationApi verification;
  private final AccountApi accounts;
  private final Clock clock;
  private final Path root;
  private final long maxPixels,ttlHours;
  private final int maxEdge;
  private final TransactionTemplate tx;
  public MediaService(JdbcTemplate jdbc,VerificationApi verification,AccountApi accounts,Clock clock,
      PlatformTransactionManager manager,@Value("${app.media.root:.local/media}") String root,
      @Value("${app.media.max-pixels:12000000}") long maxPixels,@Value("${app.media.max-edge:8192}") int maxEdge,
      @Value("${app.media.temporary-hours:24}") long ttlHours) {
    this.jdbc=jdbc;this.verification=verification;this.accounts=accounts;this.clock=clock;
    this.root=Path.of(root).toAbsolutePath().normalize();this.maxPixels=maxPixels;this.maxEdge=maxEdge;this.ttlHours=ttlHours;
    if(maxPixels<1||maxEdge<1||ttlHours<1)throw new IllegalArgumentException("Invalid media limits");
    tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }
  public record Meta(long id,String mediaType,long sizeBytes,int width,int height,String readPath,
      String state,Instant createdAt,Instant expiresAt) {}
  public record Content(byte[] bytes,String mime) {}
  private record Row(long id,long owner,String key,String mime,long size,int width,int height,String state,
      Instant created,Instant expires) {
    Meta meta(){return new Meta(id,mime,size,width,height,"/api/uploads/images/"+id,state,created,expires);}
  }
  private Row row(ResultSet r,int n)throws SQLException {
    LocalDateTime expiry=r.getObject("expires_at",LocalDateTime.class);
    return new Row(r.getLong("id"),r.getLong("uploader_id"),r.getString("storage_key"),r.getString("mime_type"),
      r.getLong("size_bytes"),r.getInt("width"),r.getInt("height"),r.getString("lifecycle"),
      r.getObject("created_at",LocalDateTime.class).toInstant(ZoneOffset.UTC),expiry==null?null:expiry.toInstant(ZoneOffset.UTC));
  }
  private Row get(long id,boolean lock){return jdbc.query("SELECT * FROM media_files WHERE id=?"+(lock?" FOR UPDATE":""),this::row,id).stream().findFirst().orElseThrow(MediaService::hidden);}
  private static BusinessException hidden(){return new BusinessException(404,"NOT_ACCESSIBLE","图片不存在或不可访问");}
  private static LocalDateTime utc(Instant time){return LocalDateTime.ofInstant(time,ZoneOffset.UTC);}
  private Path path(String key){
    if(!key.matches("[a-f0-9]{32}\\.bin"))throw new IllegalStateException("Invalid stored media key");
    Path result=root.resolve(key).normalize();
    if(!result.getParent().equals(root)||Files.isSymbolicLink(root)||Files.isSymbolicLink(result))throw new IllegalStateException("Unsafe media path");
    return result;
  }
  public Meta upload(long actor,MultipartFile file) {
    verification.requireEligible(actor);
    if(file.isEmpty())throw new IllegalArgumentException("图片不能为空");
    if(file.getSize()>MAX_BYTES)throw new BusinessException(413,"PAYLOAD_TOO_LARGE","图片不能超过5MiB");
    Path staged=null;boolean attemptedMetadata=false;
    try {
      byte[] bytes;
      try(InputStream stream=file.getInputStream()){bytes=stream.readNBytes(MAX_BYTES+1);}
      if(bytes.length>MAX_BYTES)throw new BusinessException(413,"PAYLOAD_TOO_LARGE","图片不能超过5MiB");
      String mime;int width,height;
      try(var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))){
        var readers=ImageIO.getImageReaders(input);
        if(!readers.hasNext())throw new IllegalArgumentException("仅支持可解码的JPEG或PNG图片");
        var reader=readers.next();
        try {
          String format=reader.getFormatName().toLowerCase(Locale.ROOT);
          if(!Set.of("jpeg","jpg","png").contains(format))throw new IllegalArgumentException("仅支持JPEG或PNG图片");
          reader.setInput(input,true,true);width=reader.getWidth(0);height=reader.getHeight(0);
          if(width<1||height<1||width>maxEdge||height>maxEdge||(long)width*height>maxPixels)throw new IllegalArgumentException("图片尺寸超过限制");
          if(reader.read(0)==null)throw new IllegalArgumentException("图片无法解码");
          mime=format.equals("png")?"image/png":"image/jpeg";
        }finally{reader.dispose();}
      }
      String key=UUID.randomUUID().toString().replace("-","")+".bin";
      Files.createDirectories(root);staged=path(key);Files.write(staged,bytes,StandardOpenOption.CREATE_NEW);
      String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      Path completedFile=staged;attemptedMetadata=true;
      return tx.execute(status->{
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
          @Override public void afterCompletion(int completion){if(completion==STATUS_ROLLED_BACK)discard(completedFile);}
        });
        verification.lockEligible(actor);
        Instant now=clock.instant();var keys=new GeneratedKeyHolder();
        jdbc.update(connection->{
          var statement=connection.prepareStatement("INSERT INTO media_files(uploader_id,storage_key,mime_type,size_bytes,width,height,sha256,lifecycle,expires_at,created_at,updated_at) VALUES (?,?,?,?,?,?,?,'TEMPORARY',?,?,?)",Statement.RETURN_GENERATED_KEYS);
          statement.setLong(1,actor);statement.setString(2,key);statement.setString(3,mime);statement.setLong(4,bytes.length);
          statement.setInt(5,width);statement.setInt(6,height);statement.setString(7,hash);
          statement.setObject(8,utc(now.plus(Duration.ofHours(ttlHours))));statement.setObject(9,utc(now));statement.setObject(10,utc(now));return statement;
        },keys);
        return get(Objects.requireNonNull(keys.getKey()).longValue(),false).meta();
      });
    }catch(BusinessException|IllegalArgumentException e){if(!attemptedMetadata)discard(staged);throw e;}
    catch(javax.imageio.IIOException e){if(!attemptedMetadata)discard(staged);throw new IllegalArgumentException("图片内容损坏");}
    // A lost commit acknowledgement is not proof of rollback. Preserve that file
    // until reconciliation; never delete bytes that may have committed metadata.
    catch(Exception e){if(!attemptedMetadata)discard(staged);throw new BusinessException(503,"SERVICE_UNAVAILABLE","图片存储暂时不可用");}
  }
  private void discard(Path file){if(file!=null)try{Files.deleteIfExists(file);}catch(IOException ignored){/* orphan retained for operational reconciliation */}}
  public List<Meta> images(long item){return jdbc.query("SELECT m.* FROM media_files m JOIN item_images i ON i.media_id=m.id WHERE i.item_id=? AND m.lifecycle='BOUND' ORDER BY i.display_order",this::row,item).stream().map(Row::meta).toList();}

  @Transactional(propagation=Propagation.MANDATORY)
  public void bind(long item,long owner,List<Long> requested) {
    if(requested==null)return;
    if(requested.size()>3||new HashSet<>(requested).size()!=requested.size()||requested.stream().anyMatch(id->id==null||id<=0))throw new IllegalArgumentException("最多三张图片，且ID不能重复");
    var old=jdbc.queryForList("SELECT media_id FROM item_images WHERE item_id=?",Long.class,item);
    var ids=new TreeSet<>(old);ids.addAll(requested);
    var locked=new HashMap<Long,Row>();for(long id:ids)locked.put(id,get(id,true));
    Instant now=clock.instant();
    for(long id:requested){
      Row r=locked.get(id);
      if(r.owner!=owner)throw hidden();
      if(old.contains(id)&&r.state.equals("BOUND"))continue;
      if(!r.state.equals("TEMPORARY")||r.expires==null||!now.isBefore(r.expires))throw new BusinessException(409,"STATE_CONFLICT","图片已绑定或过期，请重新上传");
    }
    jdbc.update("DELETE FROM item_images WHERE item_id=?",item);
    for(long id:old)if(!requested.contains(id))jdbc.update("UPDATE media_files SET lifecycle='REMOVED',updated_at=?,row_version=row_version+1 WHERE id=?",utc(now),id);
    for(int n=0;n<requested.size();n++){
      long id=requested.get(n);
      jdbc.update("UPDATE media_files SET lifecycle='BOUND',expires_at=NULL,updated_at=?,row_version=row_version+1 WHERE id=?",utc(now),id);
      jdbc.update("INSERT INTO item_images(item_id,media_id,display_order,bound_at) VALUES (?,?,?,?)",item,id,n+1,utc(now));
    }
  }
  private Row readable(long id,long actor,boolean admin){
    if(admin)accounts.requireAdministrator(actor);else verification.requireEligible(actor);
    Row r=get(id,false);
    if(r.state.equals("TEMPORARY")){
      if(r.owner!=actor||r.expires==null||!clock.instant().isBefore(r.expires))throw hidden();
      verification.requireEligible(actor);return r;
    }
    if(!r.state.equals("BOUND"))throw hidden();
    Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM item_images x JOIN items i ON i.id=x.item_id WHERE x.media_id=? AND (? OR i.publisher_id=? OR i.status='APPROVED')",Integer.class,id,admin,actor);
    if(count==null||count!=1)throw hidden();return r;
  }
  public Content read(long id,long actor,boolean admin){
    Row r=readable(id,actor,admin);
    try{
      byte[] bytes;try(var input=Files.newInputStream(path(r.key))){bytes=input.readNBytes(MAX_BYTES+1);}
      if(bytes.length!=r.size)throw new IOException("Size mismatch");
      readable(id,actor,admin); // Recheck after disk I/O, without holding a DB lock across it.
      return new Content(bytes,r.mime);
    }catch(IOException e){throw new BusinessException(503,"SERVICE_UNAVAILABLE","图片暂时不可读取");}
  }

  /** Bounded, retryable cleanup. No HTTP endpoint; called only by the opt-in local scheduler. */
  public int cleanup(){
    var candidates=jdbc.queryForList("SELECT id FROM media_files WHERE lifecycle IN ('REMOVED','PURGING') OR (lifecycle='TEMPORARY' AND expires_at<=?) ORDER BY id LIMIT 100",Long.class,utc(clock.instant()));
    int deleted=0;
    for(long id:candidates){
      String key=tx.execute(status->{
        Row r=get(id,true);
        if(!(Set.of("REMOVED","PURGING").contains(r.state)||(r.state.equals("TEMPORARY")&&!clock.instant().isBefore(r.expires))))return null;
        if(jdbc.queryForObject("SELECT COUNT(*) FROM item_images WHERE media_id=?",Integer.class,id)!=0)return null;
        jdbc.update("UPDATE media_files SET lifecycle='PURGING',updated_at=?,row_version=row_version+1 WHERE id=?",utc(clock.instant()),id);return r.key;
      });
      if(key==null)continue;
      try{Files.deleteIfExists(path(key));jdbc.update("UPDATE media_files SET lifecycle='DELETED',deleted_at=?,updated_at=?,row_version=row_version+1 WHERE id=? AND lifecycle='PURGING'",utc(clock.instant()),utc(clock.instant()),id);deleted++;}
      catch(IOException ignored){/* PURGING is invisible and retryable next run. */}
    }
    return deleted;
  }

  /** Read-only orphan inventory. Unknown commit outcomes require operator reconciliation;
   * they are deliberately not treated as ordinary expired metadata. */
  public List<String> orphanInventory() {
    if(!Files.isDirectory(root)||Files.isSymbolicLink(root))return List.of();
    try(var paths=Files.list(root)){
      return paths.filter(p->p.getFileName().toString().matches("[a-f0-9]{32}\\.bin"))
        .filter(p->!Files.isSymbolicLink(p)&&Files.isRegularFile(p))
        .filter(p->{try{return Files.getLastModifiedTime(p).toInstant().isBefore(clock.instant().minus(Duration.ofDays(7)));}catch(IOException e){return false;}})
        .limit(100).map(p->p.getFileName().toString())
        .filter(key->jdbc.queryForObject("SELECT COUNT(*) FROM media_files WHERE storage_key=?",Integer.class,key)==0).toList();
    }catch(IOException e){throw new BusinessException(503,"SERVICE_UNAVAILABLE","图片目录核对暂时不可用");}
  }
}
