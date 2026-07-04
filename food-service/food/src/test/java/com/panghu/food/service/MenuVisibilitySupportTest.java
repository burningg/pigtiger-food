package com.panghu.food.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.panghu.food.entity.BuddyCircleMember;
import com.panghu.food.entity.FriendRelation;
import com.panghu.food.entity.UserDefaultVisibilityCircle;
import com.panghu.food.entity.UserProfileSettings;
import com.panghu.food.mapper.BuddyCircleMemberMapper;
import com.panghu.food.mapper.DishVisibilityCircleMapper;
import com.panghu.food.mapper.FriendRelationMapper;
import com.panghu.food.mapper.UserDefaultVisibilityCircleMapper;
import com.panghu.food.mapper.UserProfileSettingsMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MenuVisibilitySupportTest {
    private final UserProfileSettingsMapper userProfileSettingsMapper = mock(UserProfileSettingsMapper.class);
    private final UserDefaultVisibilityCircleMapper userDefaultVisibilityCircleMapper = mock(UserDefaultVisibilityCircleMapper.class);
    private final DishVisibilityCircleMapper dishVisibilityCircleMapper = mock(DishVisibilityCircleMapper.class);
    private final BuddyCircleMemberMapper buddyCircleMemberMapper = mock(BuddyCircleMemberMapper.class);
    private final FriendRelationMapper friendRelationMapper = mock(FriendRelationMapper.class);

    private final MenuVisibilitySupport support = new MenuVisibilitySupport(
            userProfileSettingsMapper,
            userDefaultVisibilityCircleMapper,
            dishVisibilityCircleMapper,
            buddyCircleMemberMapper,
            friendRelationMapper);

    @Test
    void resolveDishInheritUsesDefaultCircleIds() {
        UserProfileSettings settings = new UserProfileSettings();
        settings.setUserId("owner");
        settings.setDefaultMenuVisibility("circle");
        when(userProfileSettingsMapper.selectById("owner")).thenReturn(settings);
        when(userDefaultVisibilityCircleMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
                defaultCircle("owner", "circle-1"),
                defaultCircle("owner", "circle-2")));

        MenuVisibilitySupport.ResolvedDishVisibility resolved = support.resolveDish("owner", "inherit", "dish-1");

        assertThat(resolved.effectiveVisibility()).isEqualTo("circle");
        assertThat(resolved.effectiveCircleIds()).containsExactly("circle-1", "circle-2");
    }

    @Test
    void canViewPublicDishOnlyWhenViewerHasJoinedAnyCircle() {
        when(buddyCircleMemberMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L, 0L);

        boolean joinedViewerCanSee = support.canViewDish("owner", "public", List.of(), "viewer-a", false);
        boolean outsiderCannotSee = support.canViewDish("owner", "public", List.of(), "viewer-b", false);

        assertThat(joinedViewerCanSee).isTrue();
        assertThat(outsiderCannotSee).isFalse();
    }

    @Test
    void canViewCircleDishWhenViewerBelongsToAnyEffectiveCircle() {
        when(buddyCircleMemberMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);

        boolean visible = support.canViewDish("owner", "circle", List.of("circle-1", "circle-2"), "viewer", false);

        assertThat(visible).isTrue();
    }

    @Test
    void ownerCanAlwaysViewOwnPrivateDish() {
        boolean visible = support.canViewDish("owner", "private", List.of(), "owner", false);

        assertThat(visible).isTrue();
    }



    private UserDefaultVisibilityCircle defaultCircle(String userId, String circleId) {
        UserDefaultVisibilityCircle relation = new UserDefaultVisibilityCircle();
        relation.setUserId(userId);
        relation.setCircleId(circleId);
        return relation;
    }
}
