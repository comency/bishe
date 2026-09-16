package cn.edu.lostfound.config;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class PackagedBenchmarkTargetTest {
  @TempDir Path directory;
  @Test void childEnvironmentDoesNotInheritDatabaseOrJvmOverrides(){
    var environment=new HashMap<>(Map.of("SystemRoot","C:/Windows","PATH","tools","SPRING_DATASOURCE_URL","synthetic",
        "JAVA_TOOL_OPTIONS","synthetic","JDK_JAVA_OPTIONS","synthetic","_JAVA_OPTIONS","synthetic",
        "TEST_DB_PASSWORD","synthetic","AI_ENABLED","true","SPRING_APPLICATION_JSON","synthetic"));
    PackagedBenchmarkTarget.retainSafeEnvironment(environment);
    assertThat(environment).containsExactlyInAnyOrderEntriesOf(Map.of("SystemRoot","C:/Windows","PATH","tools"));
  }
  @Test void portMustComeFromStartedHttpServerNotInitializationOrOtherLogs(){
    assertThat(PackagedBenchmarkTarget.listeningPort("Tomcat initialized with port 0 (http)")).isZero();
    assertThat(PackagedBenchmarkTarget.listeningPort("Tomcat started on port 53001 (http) with context path '/'")).isEqualTo(53001);
    assertThat(PackagedBenchmarkTarget.listeningPort("Tomcat started on port 53001 (https)")).isZero();
    assertThatThrownBy(()->PackagedBenchmarkTarget.listeningPort("Tomcat started on port 80 (http)")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->PackagedBenchmarkTarget.listeningPort("Tomcat started on port 99999 (http)")).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void digestUsesActualBytesAndChangesOnMutation() throws Exception {
    Path file=directory.resolve("synthetic.jar");Files.writeString(file,"abc");
    assertThat(PackagedBenchmarkTarget.digest(file)).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    Files.writeString(file,"changed");assertThat(PackagedBenchmarkTarget.digest(file)).isNotEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }
}
