package cn.edu.lostfound.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/** Opt-in, real MySQL on an independently initialized process; never uses application credentials. */
@EnabledIfEnvironmentVariable(named="RUN_MIGRATION_REHEARSAL",matches="true")
class MigrationRehearsalTest {
  private static final List<String> TABLES=List.of("users","items","campus_verifications",
      "verification_applications","business_logs","media_files","item_images","claims","flyway_schema_history");
  private String prefix,password;
  private Path directory;

  @BeforeEach void requireOwnedRehearsal() throws Exception {
    prefix=System.getenv("REHEARSAL_SCHEMA_PREFIX"); password=System.getenv("REHEARSAL_DB_PASSWORD");
    assertThat(prefix).matches("rehearsal_[0-9]{14}_[a-f0-9]{8}_");
    assertThat(System.getenv("REHEARSAL_DB_USERNAME")).isEqualTo("rehearsal_runner");
    assertThat(password).matches("[a-f0-9]{48}");
    directory=Path.of(System.getenv("REHEARSAL_DIRECTORY")).toRealPath();
    assertThat(directory.startsWith(Path.of(".local/database-rehearsal").toRealPath())).isTrue();
    var instance=jdbc("empty").queryForMap("SELECT @@port p,@@server_uuid u,@@datadir d");
    assertThat(((Number)instance.get("p")).intValue()).isEqualTo(13307);
    assertThat(instance.get("u")).isEqualTo(System.getenv("REHEARSAL_SERVER_UUID"));
    assertThat(Path.of(instance.get("d").toString()).toRealPath()).isEqualTo(directory.resolve("data").toRealPath());
    assertThatThrownBy(()->jdbc("empty").queryForObject("SELECT COUNT(*) FROM lost_found.users",Long.class))
        .as("runner must not have an existing application schema").isInstanceOf(org.springframework.dao.DataAccessException.class);
  }
  private String schema(String suffix) {
    if(!Set.of("empty","legacy","closed","checksum","unmanaged","source","restored").contains(suffix))
      throw new IllegalArgumentException("Unknown rehearsal schema");
    return prefix+suffix;
  }
  private String url(String suffix) {
    return "jdbc:mysql://127.0.0.1:13307/"+schema(suffix)+"?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&connectTimeout=3000&socketTimeout=10000";
  }
  private JdbcTemplate jdbc(String suffix) {return new JdbcTemplate(new DriverManagerDataSource(url(suffix),"rehearsal_runner",password));}
  private Flyway flyway(String suffix,String target,String location) {
    return Flyway.configure().dataSource(url(suffix),"rehearsal_runner",password).locations(location)
        .target(target).cleanDisabled(true).baselineOnMigrate(false).load();
  }
  private Flyway flyway(String suffix,String target) {return flyway(suffix,target,"classpath:db/migration");}

  @Test void emptySchemaMigratesToV4AndRepeatedStartupDoesNotChangeHistory() {
    var migration=flyway("empty","latest");
    assertThat(migration.migrate().migrationsExecuted).isEqualTo(4);
    assertThat(jdbc("empty").queryForList("SHOW TABLES",String.class)).containsExactlyInAnyOrderElementsOf(TABLES);
    var before=jdbc("empty").queryForList("SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank");
    migration.validate(); assertThat(migration.migrate().migrationsExecuted).isZero();
    assertThat(jdbc("empty").queryForList("SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank")).isEqualTo(before);
    assertThat(migration.getConfiguration().isCleanDisabled()).isTrue();
    assertThat(migration.getConfiguration().isBaselineOnMigrate()).isFalse();
  }

  @Test void legacyV1DataRetainsFactsAndNeverReceivesAutomaticQualification() {
    flyway("legacy","1").migrate(); var db=jdbc("legacy");
    String longPhone="9".repeat(150),description="合成旧物品".repeat(400);
    db.update("INSERT INTO users(id,username,password,nickname,phone,role,created_at) VALUES(1,'legacy_synthetic','not-a-real-hash',?,?, 'USER',NULL)","旧昵称".repeat(60),longPhone);
    db.update("INSERT INTO items(id,publisher_id,title,description,type,status,created_at,updated_at) VALUES(1,1,'Synthetic legacy',?,'LOST','APPROVED','2026-01-02 03:04:05.123456',NULL)",description);
    assertThat(flyway("legacy","latest").migrate().migrationsExecuted).isEqualTo(3);
    var user=db.queryForMap("SELECT phone,contact,is_test,created_at,nickname FROM users WHERE id=1");
    assertThat(user.get("phone")).isEqualTo(longPhone);assertThat(user.get("contact")).isNull();
    assertThat(user.get("is_test").toString()).isIn("true","1");assertThat(user.get("created_at")).isNull();
    assertThat(user.get("nickname")).isEqualTo("旧昵称".repeat(60));
    var item=db.queryForMap("SELECT description,CAST(created_at AS CHAR) created_at,updated_at,created_at_utc,updated_at_utc,close_reason FROM items WHERE id=1");
    assertThat(item.get("description")).isEqualTo(description);
    assertThat(item.get("created_at")).isEqualTo("2026-01-02 03:04:05.123456");
    for(String key:List.of("updated_at","created_at_utc","updated_at_utc","close_reason"))assertThat(item.get(key)).isNull();
    assertThat(db.queryForMap("SELECT campus_code,stored_status,current_application_id,expires_at FROM campus_verifications WHERE user_id=1"))
        .containsEntry("campus_code","TEST_CAMPUS").containsEntry("stored_status","UNVERIFIED")
        .containsEntry("current_application_id",null).containsEntry("expires_at",null);
  }

  @Test void unexplainedLegacyClosedItemStopsUpgradeWithoutInventingAReason() {
    flyway("closed","1").migrate();var db=jdbc("closed");
    db.update("INSERT INTO users(id,username,password,role) VALUES(1,'closed_synthetic','not-a-real-hash','USER')");
    db.update("INSERT INTO items(id,publisher_id,title,description,type,status) VALUES(1,1,'Synthetic old closed','Unknown closure history','FOUND','CLOSED')");
    assertThatThrownBy(()->flyway("closed","latest").migrate()).isInstanceOf(FlywayException.class);
    assertThat(db.queryForObject("SELECT status FROM items WHERE id=1",String.class)).isEqualTo("CLOSED");
    assertThat(db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='4' AND success=1",Long.class)).isZero();
    // MySQL DDL is not one multi-migration transaction. V2 remains; no repair/clean is attempted.
    assertThat(db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='2' AND success=1",Long.class)).isEqualTo(1L);
  }

  @Test void editedAppliedMigrationIsDetectedBeforeFurtherUpgrade() throws Exception {
    flyway("checksum","1").migrate();
    Path changed=Files.createDirectory(directory.resolve("changed-migration-fixture"));
    try(var migrations=Files.list(Path.of("src/main/resources/db/migration"))){
      for(Path migration:migrations.filter(p->p.getFileName().toString().endsWith(".sql")).toList())
        Files.copy(migration,changed.resolve(migration.getFileName()));
    }
    Files.writeString(changed.resolve("V1__prototype_baseline.sql"),"\nSELECT 1;\n",StandardOpenOption.APPEND);
    var altered=flyway("checksum","latest","filesystem:"+changed);
    assertThatThrownBy(altered::validate).isInstanceOf(FlywayException.class).hasMessageContaining("checksum");
    assertThatThrownBy(altered::migrate).isInstanceOf(FlywayException.class);
    assertThat(jdbc("checksum").queryForObject("SELECT COUNT(*) FROM flyway_schema_history",Long.class)).isEqualTo(1L);
  }

  @Test void unmanagedNonemptySchemaIsRejectedWithoutAutomaticBaseline() {
    var db=jdbc("unmanaged");db.execute("CREATE TABLE existing_synthetic_record (id BIGINT PRIMARY KEY)");
    db.update("INSERT INTO existing_synthetic_record VALUES (1)");
    assertThatThrownBy(()->flyway("unmanaged","latest").migrate()).isInstanceOf(FlywayException.class);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM existing_synthetic_record",Long.class)).isEqualTo(1L);
    assertThat(db.queryForList("SHOW TABLES",String.class)).containsExactly("existing_synthetic_record");
  }

  @Test void databaseAndPrivateImageBackupRestoreIntoNewEmptySchema() throws Exception {
    long start=System.nanoTime();flyway("source","latest").migrate();var source=jdbc("source");
    Path media=Files.createDirectory(directory.resolve("source-media")), backupMedia=Files.createDirectory(directory.resolve("backup-media")), restoredMedia=Files.createDirectory(directory.resolve("restored-media"));
    Path picture=media.resolve("synthetic.png");
    assertThat(ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",picture.toFile())).isTrue();
    String imageDigest=digest(Files.readAllBytes(picture));
    seedRepresentativeData(source,Files.size(picture),imageDigest);
    Map<String,String> before=snapshot(source), definitions=definitions(source);
    Path dump=directory.resolve("synthetic-backup.sql");
    runClient("mysqldump.exe",List.of("--single-transaction","--no-tablespaces","--skip-lock-tables","--set-gtid-purged=OFF","--skip-add-drop-table","--skip-add-locks","--hex-blob","--result-file="+dump,schema("source")),null);
    check(Files.size(dump)>0,"SQL backup is nonempty");
    Files.copy(picture,backupMedia.resolve(picture.getFileName()));
    assertThat(jdbc("restored").queryForList("SHOW TABLES",String.class)).isEmpty();
    runClient("mysql.exe",List.of("--database="+schema("restored")),dump);
    Files.copy(backupMedia.resolve(picture.getFileName()),restoredMedia.resolve(picture.getFileName()));
    var restored=jdbc("restored");assertThat(snapshot(restored)).isEqualTo(before);
    assertThat(definitions(restored)).as("restored columns, indexes, foreign keys, checks and table options").isEqualTo(definitions);
    var restoredMigration=flyway("restored","latest");restoredMigration.validate();assertThat(restoredMigration.migrate().migrationsExecuted).isZero();
    assertThat(digest(Files.readAllBytes(restoredMedia.resolve("synthetic.png")))).isEqualTo(imageDigest);
    assertThat(ImageIO.read(restoredMedia.resolve("synthetic.png").toFile()).getWidth()).isEqualTo(2);
    assertThat(restored.queryForObject("SELECT COUNT(*) FROM item_images i JOIN media_files m ON m.id=i.media_id JOIN items t ON t.id=i.item_id WHERE m.sha256=? AND m.storage_key='synthetic.png'",Long.class,imageDigest)).isEqualTo(1L);
    assertThatThrownBy(()->restored.update("UPDATE claims SET received_at=UTC_TIMESTAMP(6),handed_over_at=UTC_TIMESTAMP(6) WHERE id=1"))
        .as("restored CHECK constraints remain enforced").isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThat(snapshot(source)).isEqualTo(before);
    var evidence=new LinkedHashMap<String,Object>();evidence.put("serverPort",13307);evidence.put("schemaPrefix",prefix);
    evidence.put("tables",before.keySet());evidence.put("tableDataDigests",before);evidence.put("tableDefinitionDigests",definitions);
    evidence.put("sqlSha256",digest(Files.readAllBytes(dump)));
    evidence.put("imageSha256",imageDigest);evidence.put("imageMetadataMatches",true);evidence.put("sourceUnchanged",true);
    evidence.put("elapsedMs",(System.nanoTime()-start)/1_000_000);evidence.put("limitation","Synthetic rehearsal only; not production restore, point-in-time recovery, or application smoke test");
    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(directory.resolve("restore-evidence.json").toFile(),evidence);
  }
  private void seedRepresentativeData(JdbcTemplate db,long bytes,String hash) {
    for(int id=1;id<=3;id++)db.update("INSERT INTO users(id,username,password,role,is_test,updated_at) VALUES(?,?,'synthetic-not-a-usable-password',?,TRUE,UTC_TIMESTAMP(6))",id,"restore_synthetic_"+id,id==3?"ADMIN":"USER");
    for(int id=1;id<=3;id++)db.update("INSERT INTO campus_verifications(user_id,campus_code,stored_status,created_at,updated_at) VALUES(?,'TEST_CAMPUS','UNVERIFIED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",id);
    for(int id=1;id<=2;id++){
      db.update("INSERT INTO verification_applications(id,user_id,application_version,campus_code,real_name,status,method,evidence_summary,valid_through,reviewed_by,reviewed_at,submitted_at,is_test) VALUES(?,?,1,'TEST_CAMPUS','合成恢复用户','VERIFIED','IN_PERSON','Synthetic only','2026-12-31',3,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),TRUE)",id,id);
      db.update("UPDATE campus_verifications SET stored_status='VERIFIED',current_application_id=?,last_application_version=1,expires_at='2027-01-01' WHERE user_id=?",id,id);
    }
    db.update("INSERT INTO items(id,publisher_id,title,description,type,status,created_at_utc,updated_at_utc) VALUES(1,1,'合成恢复水杯','Synthetic recovery only','FOUND','APPROVED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
    db.update("INSERT INTO media_files(id,uploader_id,storage_key,mime_type,size_bytes,width,height,sha256,lifecycle,created_at,updated_at) VALUES(1,1,'synthetic.png','image/png',?,2,2,?,'BOUND',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",bytes,hash);
    db.update("INSERT INTO item_images(item_id,media_id,display_order,bound_at) VALUES(1,1,1,UTC_TIMESTAMP(6))");
    db.update("INSERT INTO claims(id,item_id,publisher_id,applicant_id,item_title_snapshot,item_type_snapshot,item_content_version_snapshot,evidence,applicant_contact_snapshot,publisher_contact_snapshot,status,accepted_at,created_at,updated_at) VALUES(1,1,1,2,'合成恢复水杯','FOUND',1,'SYNTHETIC_PRIVATE_EVIDENCE','SYNTHETIC_CONTACT_A','SYNTHETIC_CONTACT_B','ACCEPTED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
    db.update("INSERT INTO business_logs(event_type,actor_id,actor_kind,subject_user_id,item_id,claim_id,trace_id,occurred_at) VALUES('CLAIM_ACCEPTED',1,'USER',2,1,1,'synthetic-restore-trace',UTC_TIMESTAMP(6))");
  }
  private Map<String,String> snapshot(JdbcTemplate db) throws Exception {
    Map<String,String> result=new TreeMap<>();
    for(String table:TABLES){
      var rows=db.query("SELECT * FROM "+table,(rs,n)->{
        List<String> cells=new ArrayList<>();for(int i=1;i<=rs.getMetaData().getColumnCount();i++)cells.add(rs.getString(i));return cells;
      });
      var encoded=new ArrayList<String>();for(var row:rows)encoded.add(new ObjectMapper().writeValueAsString(row));Collections.sort(encoded);
      result.put(table,digest(String.join("\n",encoded).getBytes(StandardCharsets.UTF_8)));
    }
    return result;
  }
  private Map<String,String> definitions(JdbcTemplate db) throws Exception {
    Map<String,String> result=new TreeMap<>();
    for(String table:TABLES)result.put(table,digest(db.queryForMap("SHOW CREATE TABLE "+table).get("Create Table").toString().getBytes(StandardCharsets.UTF_8)));
    return result;
  }
  private void runClient(String executable,List<String> extra,Path input) throws Exception {
    List<String> command=new ArrayList<>(List.of("E:/MySQL/MySQL Server 8.0/bin/"+executable,"--no-defaults","--protocol=TCP","--host=127.0.0.1","--port=13307","--user=rehearsal_runner","--default-character-set=utf8mb4"));
    if(executable.equals("mysql.exe"))command.add("--connect-timeout=3");
    command.addAll(extra);
    var builder=new ProcessBuilder(command);builder.environment().put("MYSQL_PWD",password);
    builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);builder.redirectError(directory.resolve(executable+"-private.log").toFile());
    if(input!=null)builder.redirectInput(input.toFile());
    var process=builder.start();process.getOutputStream().close();
    if(!process.waitFor(30,TimeUnit.SECONDS)){process.destroyForcibly();fail("Isolated backup/restore client timed out");}
    assertThat(process.exitValue()).as("Isolated backup/restore client (details retained privately)").isZero();
  }
  private static String digest(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
  private static void check(boolean condition,String label){assertThat(condition).as(label).isTrue();}
}
