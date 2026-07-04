package com.panghu.food.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.panghu.food.dto.DishDetailResponse;
import com.panghu.food.dto.DishSummaryResponse;
import com.panghu.food.entity.BuddyCircleMember;
import com.panghu.food.entity.DishVisibilityCircle;
import com.panghu.food.entity.FriendRelation;
import com.panghu.food.entity.UserDefaultVisibilityCircle;
import com.panghu.food.entity.UserProfileSettings;
import com.panghu.food.mapper.BuddyCircleMemberMapper;
import com.panghu.food.mapper.DishVisibilityCircleMapper;
import com.panghu.food.mapper.FriendRelationMapper;
import com.panghu.food.mapper.UserDefaultVisibilityCircleMapper;
import com.panghu.food.mapper.UserProfileSettingsMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class MenuVisibilitySupport {
    private final UserProfileSettingsMapper userProfileSettingsMapper;
    private final UserDefaultVisibilityCircleMapper userDefaultVisibilityCircleMapper;
    private final DishVisibilityCircleMapper dishVisibilityCircleMapper;
    private final BuddyCircleMemberMapper buddyCircleMemberMapper;
    private final FriendRelationMapper friendRelationMapper;

    public MenuVisibilitySupport(UserProfileSettingsMapper userProfileSettingsMapper,
                                 UserDefaultVisibilityCircleMapper userDefaultVisibilityCircleMapper,
                                 DishVisibilityCircleMapper dishVisibilityCircleMapper,
                                 BuddyCircleMemberMapper buddyCircleMemberMapper,
                                 FriendRelationMapper friendRelationMapper) {
        this.userProfileSettingsMapper = userProfileSettingsMapper;
        this.userDefaultVisibilityCircleMapper = userDefaultVisibilityCircleMapper;
        this.dishVisibilityCircleMapper = dishVisibilityCircleMapper;
        this.buddyCircleMemberMapper = buddyCircleMemberMapper;
        this.friendRelationMapper = friendRelationMapper;
    }

    public ResolvedDishVisibility resolveDish(String ownerUserId, String dishVisibility, String dishId) {
        String normalizedDishVisibility = VisibilityUtils.normalizeDishVisibility(dishVisibility);
        List<String> visibilityCircleIds = dishId == null
                ? Collections.emptyList()
                : getDishVisibilityCircleIds(dishId);
        String defaultVisibility = resolveDefaultVisibility(ownerUserId);
        List<String> defaultCircleIds = getDefaultMenuCircleIds(ownerUserId);
        String effectiveVisibility = VisibilityUtils.effectiveVisibility(normalizedDishVisibility, defaultVisibility);
        List<String> effectiveCircleIds = VisibilityUtils.DISH_VISIBILITY_INHERIT.equals(normalizedDishVisibility)
                ? defaultCircleIds
                : VisibilityUtils.VISIBILITY_CIRCLE.equals(normalizedDishVisibility) ? visibilityCircleIds : Collections.emptyList();
        return new ResolvedDishVisibility(
                normalizedDishVisibility,
                visibilityCircleIds,
                effectiveVisibility,
                effectiveCircleIds);
    }

    public void hydrateSummaries(List<DishSummaryResponse> dishes) {
        if (dishes == null || dishes.isEmpty()) {
            return;
        }
        Map<String, List<String>> dishCircleIdsByDishId = getDishVisibilityCircleIdsByDishIds(dishes.stream()
                .map(DishSummaryResponse::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList()));
        Map<String, String> defaultVisibilityByUserId = getDefaultVisibilityByUserIds(dishes.stream()
                .map(DishSummaryResponse::getOwnerUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList()));
        Map<String, List<String>> defaultCircleIdsByUserId = getDefaultCircleIdsByUserIds(dishes.stream()
                .map(DishSummaryResponse::getOwnerUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList()));
        for (DishSummaryResponse dish : dishes) {
            applySummaryVisibility(dish, dishCircleIdsByDishId, defaultVisibilityByUserId, defaultCircleIdsByUserId);
        }
    }

    public void hydrateDetail(DishDetailResponse dish) {
        if (dish == null) {
            return;
        }
        ResolvedDishVisibility resolved = resolveDish(dish.getOwnerUserId(), dish.getVisibility(), dish.getId());
        dish.setVisibility(resolved.visibility());
        dish.setVisibilityCircleIds(new ArrayList<>(resolved.visibilityCircleIds()));
        dish.setEffectiveVisibility(resolved.effectiveVisibility());
        dish.setEffectiveCircleIds(new ArrayList<>(resolved.effectiveCircleIds()));
    }

    public boolean canViewDish(DishSummaryResponse dish, String viewerUserId, boolean legacyCircleMode) {
        if (dish == null) {
            return false;
        }
        return canViewDish(
                dish.getOwnerUserId(),
                dish.getEffectiveVisibility(),
                dish.getEffectiveCircleIds(),
                viewerUserId,
                legacyCircleMode);
    }

    public boolean canViewDish(DishDetailResponse dish, String viewerUserId, boolean legacyCircleMode) {
        if (dish == null) {
            return false;
        }
        return canViewDish(
                dish.getOwnerUserId(),
                dish.getEffectiveVisibility(),
                dish.getEffectiveCircleIds(),
                viewerUserId,
                legacyCircleMode);
    }

    public boolean canViewDish(String ownerUserId,
                               String effectiveVisibility,
                               List<String> effectiveCircleIds,
                               String viewerUserId,
                               boolean legacyCircleMode) {
        // 菜谱作者本人始终可见，避免“仅自己可见”在创建后回读详情时被误拦截。
        if (viewerUserId != null && Objects.equals(viewerUserId, ownerUserId) && !legacyCircleMode) {
            return true;
        }
        if (VisibilityUtils.VISIBILITY_PUBLIC.equals(effectiveVisibility)) {
            return viewerUserId != null && isUserInAnyCircle(viewerUserId);
        }
        if (VisibilityUtils.VISIBILITY_PRIVATE.equals(effectiveVisibility)) {
            return false;
        }
        if (VisibilityUtils.VISIBILITY_CIRCLE.equals(effectiveVisibility)) {
            return viewerUserId != null && hasAnyCircleMembership(viewerUserId, effectiveCircleIds);
        }
        // if (VisibilityUtils.VISIBILITY_FRIENDS.equals(effectiveVisibility)) {
        //     return viewerUserId != null
        //             && (isFriend(viewerUserId, ownerUserId)
        //             || (legacyCircleMode && hasSharedCircle(viewerUserId, ownerUserId)));
        // }
        return false;
    }

    public String resolveDefaultVisibility(String userId) {
        if (userId == null) {
            return VisibilityUtils.DEFAULT_PROFILE_VISIBILITY;
        }
        UserProfileSettings settings = userProfileSettingsMapper.selectById(userId);
        return settings == null
                ? VisibilityUtils.DEFAULT_PROFILE_VISIBILITY
                : VisibilityUtils.normalizeProfileVisibility(settings.getDefaultMenuVisibility());
    }

    public List<String> getDefaultMenuCircleIds(String userId) {
        if (userId == null) {
            return Collections.emptyList();
        }
        return dedupe(userDefaultVisibilityCircleMapper.selectList(new QueryWrapper<UserDefaultVisibilityCircle>()
                        .eq("user_id", userId)
                        .orderByAsc("created_at")
                        .orderByAsc("id"))
                .stream()
                .map(UserDefaultVisibilityCircle::getCircleId)
                .collect(Collectors.toList()));
    }

    public List<String> getDishVisibilityCircleIds(String dishId) {
        if (dishId == null) {
            return Collections.emptyList();
        }
        return dedupe(dishVisibilityCircleMapper.selectList(new QueryWrapper<DishVisibilityCircle>()
                        .eq("dish_id", dishId)
                        .orderByAsc("created_at")
                        .orderByAsc("id"))
                .stream()
                .map(DishVisibilityCircle::getCircleId)
                .collect(Collectors.toList()));
    }

    public boolean isFriend(String userId, String otherUserId) {
        if (userId == null || otherUserId == null) {
            return false;
        }
        return friendRelationMapper.selectCount(new QueryWrapper<FriendRelation>()
                .eq("user_id", userId)
                .eq("friend_user_id", otherUserId)) > 0;
    }

    public boolean hasSharedCircle(String userId, String otherUserId) {
        if (userId == null || otherUserId == null) {
            return false;
        }
        List<String> myCircles = getUserCircleIds(userId);
        if (myCircles.isEmpty()) {
            return false;
        }
        return buddyCircleMemberMapper.selectCount(new QueryWrapper<BuddyCircleMember>()
                .eq("user_id", otherUserId)
                .in("circle_id", myCircles)) > 0;
    }

    public boolean isUserInAnyCircle(String userId) {
        if (userId == null) {
            return false;
        }
        return buddyCircleMemberMapper.selectCount(new QueryWrapper<BuddyCircleMember>()
                .eq("user_id", userId)) > 0;
    }

    public boolean hasAnyCircleMembership(String userId, Collection<String> circleIds) {
        if (userId == null || circleIds == null || circleIds.isEmpty()) {
            return false;
        }
        return buddyCircleMemberMapper.selectCount(new QueryWrapper<BuddyCircleMember>()
                .eq("user_id", userId)
                .in("circle_id", dedupe(circleIds))) > 0;
    }

    public List<String> getUserCircleIds(String userId) {
        if (userId == null) {
            return Collections.emptyList();
        }
        return dedupe(buddyCircleMemberMapper.selectList(new QueryWrapper<BuddyCircleMember>()
                        .eq("user_id", userId))
                .stream()
                .map(BuddyCircleMember::getCircleId)
                .collect(Collectors.toList()));
    }

    private void applySummaryVisibility(DishSummaryResponse dish,
                                        Map<String, List<String>> dishCircleIdsByDishId,
                                        Map<String, String> defaultVisibilityByUserId,
                                        Map<String, List<String>> defaultCircleIdsByUserId) {
        String dishVisibility = VisibilityUtils.normalizeDishVisibility(dish.getVisibility());
        String ownerDefaultVisibility = defaultVisibilityByUserId.getOrDefault(
                dish.getOwnerUserId(),
                VisibilityUtils.DEFAULT_PROFILE_VISIBILITY);
        List<String> visibilityCircleIds = dishCircleIdsByDishId.getOrDefault(dish.getId(), Collections.emptyList());
        String effectiveVisibility = VisibilityUtils.effectiveVisibility(dishVisibility, ownerDefaultVisibility);
        List<String> effectiveCircleIds = VisibilityUtils.DISH_VISIBILITY_INHERIT.equals(dishVisibility)
                ? defaultCircleIdsByUserId.getOrDefault(dish.getOwnerUserId(), Collections.emptyList())
                : VisibilityUtils.VISIBILITY_CIRCLE.equals(dishVisibility) ? visibilityCircleIds : Collections.emptyList();
        dish.setVisibility(dishVisibility);
        dish.setVisibilityCircleIds(new ArrayList<>(visibilityCircleIds));
        dish.setEffectiveVisibility(effectiveVisibility);
        dish.setEffectiveCircleIds(new ArrayList<>(effectiveCircleIds));
    }

    private Map<String, List<String>> getDishVisibilityCircleIdsByDishIds(List<String> dishIds) {
        return groupCircleIds(dishIds, DishVisibilityCircle.class, DishVisibilityCircle::getDishId, DishVisibilityCircle::getCircleId);
    }

    private Map<String, List<String>> getDefaultCircleIdsByUserIds(List<String> userIds) {
        return groupCircleIds(userIds, UserDefaultVisibilityCircle.class, UserDefaultVisibilityCircle::getUserId, UserDefaultVisibilityCircle::getCircleId);
    }

    private Map<String, String> getDefaultVisibilityByUserIds(List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return userProfileSettingsMapper.selectList(new QueryWrapper<UserProfileSettings>()
                        .in("user_id", dedupe(userIds)))
                .stream()
                .collect(Collectors.toMap(
                        UserProfileSettings::getUserId,
                        item -> VisibilityUtils.normalizeProfileVisibility(item.getDefaultMenuVisibility()),
                        (left, right) -> right));
    }

    private <T> Map<String, List<String>> groupCircleIds(List<String> ids,
                                                         Class<T> type,
                                                         Function<T, String> keyExtractor,
                                                         Function<T, String> valueExtractor) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        List<String> uniqueIds = dedupe(ids);
        if (DishVisibilityCircle.class.equals(type)) {
            return dishVisibilityCircleMapper.selectList(new QueryWrapper<DishVisibilityCircle>()
                            .in("dish_id", uniqueIds)
                            .orderByAsc("created_at")
                            .orderByAsc("id"))
                    .stream()
                    .collect(Collectors.groupingBy(
                            item -> keyExtractor.apply(type.cast(item)),
                            Collectors.collectingAndThen(
                                    Collectors.mapping(item -> valueExtractor.apply(type.cast(item)), Collectors.toList()),
                                    MenuVisibilitySupport::dedupe)));
        }
        return userDefaultVisibilityCircleMapper.selectList(new QueryWrapper<UserDefaultVisibilityCircle>()
                        .in("user_id", uniqueIds)
                        .orderByAsc("created_at")
                        .orderByAsc("id"))
                .stream()
                .collect(Collectors.groupingBy(
                        item -> keyExtractor.apply(type.cast(item)),
                        Collectors.collectingAndThen(
                                Collectors.mapping(item -> valueExtractor.apply(type.cast(item)), Collectors.toList()),
                                MenuVisibilitySupport::dedupe)));
    }

    private static List<String> dedupe(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                result.add(value.trim());
            }
        }
        return new ArrayList<>(result);
    }

    public record ResolvedDishVisibility(
            String visibility,
            List<String> visibilityCircleIds,
            String effectiveVisibility,
            List<String> effectiveCircleIds
    ) {
    }
}
