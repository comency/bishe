package cn.edu.lostfound.repository;
import cn.edu.lostfound.entity.Item;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;
public interface ItemRepository extends JpaRepository<Item,Long>, JpaSpecificationExecutor<Item> {
  @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select i from Item i where i.id=:id")
  Optional<Item> lockById(@Param("id") Long id);
  @Query("select i from Item i where i.status='APPROVED' and (:keyword='' or lower(i.title) like lower(concat('%',:keyword,'%')) or lower(i.description) like lower(concat('%',:keyword,'%'))) and (:type='' or i.type=:type) order by i.createdAt desc,i.id desc")
  List<Item> search(@Param("keyword") String keyword,@Param("type") String type);
  @Query("select i from Item i where i.publisher.id=:userId order by i.createdAt desc,i.id desc")
  List<Item> findByPublisherIdOrderByCreatedAtDesc(@Param("userId") Long userId);
  @Query("select i from Item i where i.status=:status order by i.createdAt desc,i.id desc")
  List<Item> findByStatusOrderByCreatedAtDesc(@Param("status") String status);
}
