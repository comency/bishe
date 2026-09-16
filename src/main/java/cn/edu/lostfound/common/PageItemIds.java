package cn.edu.lostfound.common;

import java.util.*;

/** Internal batch boundary, not an authorization check. Callers must first select visible items. */
public final class PageItemIds {
  private PageItemIds() {}
  public static List<Long> copyOf(Collection<Long> ids) {
    if(ids==null || ids.size()>50)throw new IllegalArgumentException("Expected at most 50 page item IDs");
    var copy=new ArrayList<>(ids);
    if(copy.stream().anyMatch(id->id==null || id<=0))throw new IllegalArgumentException("Invalid page item ID");
    return List.copyOf(new LinkedHashSet<>(copy));
  }
}
