package cn.edu.lostfound.service;

import cn.edu.lostfound.dto.ItemDtos;
import cn.edu.lostfound.entity.Item;
import cn.edu.lostfound.entity.User;
import cn.edu.lostfound.repository.ItemRepository;
import cn.edu.lostfound.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ItemServiceTest {
    private final ItemRepository items = mock(ItemRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ItemService service = new ItemService(items, users);
    private final ItemDtos.Save edit = new ItemDtos.Save(
            "找到校园卡", "图书馆捡到校园卡", "FOUND", "证件", "图书馆", LocalDate.of(2026, 9, 15));
    private Item item;

    @BeforeEach
    void setUp() {
        User publisher = new User("publisher", "unused-test-password", "发布者", "USER");
        ReflectionTestUtils.setField(publisher, "id", 1L);
        item = new Item(publisher, "丢失校园卡", "丢失一张校园卡", "LOST", "证件", "食堂", null);
        when(items.findById(10L)).thenReturn(Optional.of(item));
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "REJECTED", "CLOSED", "PENDING"})
    void publisherEditsRequireReviewAndPersistChangedType(String status) {
        item.setStatus(status);
        when(items.save(any(Item.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Item updated = service.update(10L, 1L, false, edit);

        assertThat(updated.getStatus()).isEqualTo("PENDING");
        assertThat(updated.getType()).isEqualTo("FOUND");
        assertThat(updated.getTitle()).isEqualTo(edit.title());
        assertThat(updated.getLocation()).isEqualTo(edit.location());
        verify(items).save(item);
    }

    @Test
    void otherUsersCannotEditOrChangeReviewStatus() {
        item.setStatus("APPROVED");

        assertThatThrownBy(() -> service.update(10L, 2L, false, edit))
                .isInstanceOf(SecurityException.class);

        assertThat(item.getTitle()).isEqualTo("丢失校园卡");
        assertThat(item.getType()).isEqualTo("LOST");
        assertThat(item.getStatus()).isEqualTo("APPROVED");
        verify(items, never()).save(any());
    }

    @Test
    void administratorCanEditOtherUsersItemsWithoutLosingApproval() {
        item.setStatus("APPROVED");
        when(items.save(any(Item.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Item updated = service.update(10L, 2L, true, edit);

        assertThat(updated.getStatus()).isEqualTo("APPROVED");
        assertThat(updated.getType()).isEqualTo("FOUND");
        verify(items).save(item);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "REJECTED", "CLOSED"})
    void otherUsersCannotUsePrivateItemsForMatching(String status) {
        item.setStatus(status);

        assertThatThrownBy(() -> service.matches(10L, 2L, false))
                .isInstanceOf(SecurityException.class);

        verify(items, never()).search(any(), any());
    }

    @Test
    void publisherCanMatchAnItemBeforeApproval() {
        when(items.search("", "FOUND")).thenReturn(List.of());

        assertThat(service.matches(10L, 1L, false)).isEmpty();

        verify(items).search("", "FOUND");
    }

    @Test
    void administratorCanMatchOtherUsersPrivateItems() {
        when(items.search("", "FOUND")).thenReturn(List.of());

        assertThat(service.matches(10L, 2L, true)).isEmpty();

        verify(items).search("", "FOUND");
    }

    @Test
    void otherUsersCanMatchApprovedItems() {
        item.setStatus("APPROVED");
        when(items.search("", "FOUND")).thenReturn(List.of());

        assertThat(service.matches(10L, 2L, false)).isEmpty();

        verify(items).search("", "FOUND");
    }
}
