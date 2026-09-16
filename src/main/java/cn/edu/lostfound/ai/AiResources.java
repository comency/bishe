package cn.edu.lostfound.ai;

import java.lang.management.ManagementFactory;
import org.springframework.stereotype.Component;

@Component
public class AiResources {
  public boolean available(long minimumFreeBytes){
    var bean=ManagementFactory.getOperatingSystemMXBean();
    return bean instanceof com.sun.management.OperatingSystemMXBean os&&os.getFreeMemorySize()>=minimumFreeBytes;
  }
}
