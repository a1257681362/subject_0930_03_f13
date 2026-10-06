package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.dto.PackSpecItemRequest;
import com.evops.dto.PackSpecPublishRequest;
import com.evops.entity.PackNotify;
import com.evops.entity.PackSpecItem;
import com.evops.entity.PackSpecVersion;
import com.evops.mapper.PackSpecItemMapper;
import com.evops.mapper.PackSpecVersionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.evops.constant.MuseumConstants.*;

/**
 * 包装规格版本：
 * 发布新版本（按 文物材质+气候区 配置尺寸/承重上限、缓冲等级、包装材质与
 * 防震要求）后，旧 ACTIVE 版本置 SUPERSEDED，版本与条目只追加不改写；
 * 已装箱箱行固化版本与条目快照——规格变更不影响已签押运单与已装箱事实。
 *
 * 每次发布同时向文物修复系统（外部依赖）发通知：通知行同事务落库，
 * 外部依赖无响应时保持 PENDING 如实暴露，绝不假装成功。
 */
@Service
public class PackSpecService {

    /** 合法气候区 */
    public static final Set<String> CLIMATE_ZONES = new HashSet<>(Arrays.asList(
            CLIMATE_TEMPERATE, CLIMATE_TROPICAL, CLIMATE_ARID, CLIMATE_COLD));

    /** 合法缓冲等级（L1 < L2 < L3） */
    public static final Set<String> CUSHION_LEVELS = new HashSet<>(Arrays.asList(
            CUSHION_L1, CUSHION_L2, CUSHION_L3));

    private final PackSpecVersionMapper versionMapper;
    private final PackSpecItemMapper itemMapper;
    private final PackNotifyService notifyService;
    private final PackLedgerService ledgerService;

    public PackSpecService(PackSpecVersionMapper versionMapper,
                           PackSpecItemMapper itemMapper,
                           PackNotifyService notifyService,
                           PackLedgerService ledgerService) {
        this.versionMapper = versionMapper;
        this.itemMapper = itemMapper;
        this.notifyService = notifyService;
        this.ledgerService = ledgerService;
    }

    /**
     * 发布规格新版本：同事务内将旧生效版本置 SUPERSEDED、落通知发件箱。
     * 版本号全库唯一；同版本内 (材质, 气候区) 不可重复。
     */
    @Transactional
    public PackSpecVersion publish(PackSpecPublishRequest req) {
        if (versionMapper.selectCount(new LambdaQueryWrapper<PackSpecVersion>()
                .eq(PackSpecVersion::getVersionNo, req.getVersionNo())) > 0) {
            throw new IllegalArgumentException("包装规格版本号已存在: " + req.getVersionNo());
        }
        validateItems(req.getItems());

        LocalDateTime now = LocalDateTime.now();
        PackSpecVersion oldActive = activeVersionEntity();
        if (oldActive != null) {
            // 旧版本只置状态、内容永不改写；引用旧版本的箱行仍指向旧快照
            oldActive.setStatus(PACK_SPEC_SUPERSEDED);
            versionMapper.updateById(oldActive);
        }

        PackSpecVersion version = new PackSpecVersion();
        version.setVersionNo(req.getVersionNo());
        version.setTitle(req.getTitle());
        version.setRemark(req.getRemark());
        version.setStatus(PACK_SPEC_ACTIVE);
        version.setPublishedBy(req.getPublishedBy());
        version.setPublishedAt(now);
        versionMapper.insert(version);

        for (PackSpecItemRequest it : req.getItems()) {
            PackSpecItem item = new PackSpecItem();
            item.setSpecVersionId(version.getId());
            item.setMaterial(it.getMaterial().trim());
            item.setClimateZone(it.getClimateZone());
            item.setMaxLengthMm(it.getMaxLengthMm());
            item.setMaxWidthMm(it.getMaxWidthMm());
            item.setMaxHeightMm(it.getMaxHeightMm());
            item.setMaxWeightG(it.getMaxWeightG());
            item.setCushionLevel(it.getCushionLevel());
            item.setPackMaterial(it.getPackMaterial().trim());
            item.setShockproofRequired(Boolean.TRUE.equals(it.getShockproofRequired()) ? 1 : 0);
            itemMapper.insert(item);
        }

        ledgerService.append(null, version.getId(), null,
                ACTION_PACK_SPEC_PUBLISH, version.getVersionNo(),
                "包装规格【" + version.getTitle() + "】" + version.getVersionNo()
                        + " 发布生效，含 " + req.getItems().size() + " 个条目"
                        + (oldActive != null ? "；旧版本 " + oldActive.getVersionNo() + " 置 SUPERSEDED 只读" : ""),
                now);

        // 通知文物修复系统：同事务落发件箱；外部依赖无响应时保持 PENDING 如实暴露
        notifyService.createPendingAndAttempt(version, req.getItems().size(), now);
        return version;
    }

    private void validateItems(List<PackSpecItemRequest> items) {
        Set<String> keys = new HashSet<>();
        for (PackSpecItemRequest it : items) {
            requireClimateZone(it.getClimateZone());
            requireCushionLevel(it.getCushionLevel());
            String key = it.getMaterial().trim() + "|" + it.getClimateZone();
            if (!keys.add(key)) {
                throw new IllegalArgumentException("同一规格版本内 材质+气候区 不可重复: " + key);
            }
        }
    }

    public static void requireClimateZone(String climateZone) {
        if (!CLIMATE_ZONES.contains(climateZone)) {
            throw new IllegalArgumentException("未知气候区: " + climateZone
                    + "，只能为 TEMPERATE/TROPICAL/ARID/COLD");
        }
    }

    public static void requireCushionLevel(String cushionLevel) {
        if (!CUSHION_LEVELS.contains(cushionLevel)) {
            throw new IllegalArgumentException("未知缓冲等级: " + cushionLevel + "，只能为 L1/L2/L3");
        }
    }

    /** 缓冲等级排序：L1=1 < L2=2 < L3=3 */
    public static int cushionRank(String cushionLevel) {
        requireCushionLevel(cushionLevel);
        return Integer.parseInt(cushionLevel.substring(1));
    }

    public PackSpecVersion requireActiveVersion() {
        PackSpecVersion version = activeVersionEntity();
        if (version == null) {
            throw new IllegalArgumentException("尚未发布生效的包装规格");
        }
        return version;
    }

    public PackSpecVersion activeVersionEntity() {
        return versionMapper.selectOne(new LambdaQueryWrapper<PackSpecVersion>()
                .eq(PackSpecVersion::getStatus, PACK_SPEC_ACTIVE)
                .last("LIMIT 1"));
    }

    public PackSpecVersion requireVersion(Long versionId) {
        PackSpecVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new IllegalArgumentException("包装规格版本不存在: id=" + versionId);
        }
        return version;
    }

    public List<PackSpecVersion> listVersions() {
        return versionMapper.selectList(new LambdaQueryWrapper<PackSpecVersion>()
                .orderByDesc(PackSpecVersion::getId));
    }

    public List<PackSpecItem> listItems(Long specVersionId) {
        return itemMapper.selectList(new LambdaQueryWrapper<PackSpecItem>()
                .eq(PackSpecItem::getSpecVersionId, specVersionId)
                .orderByAsc(PackSpecItem::getMaterial)
                .orderByAsc(PackSpecItem::getClimateZone));
    }

    /** 按 材质+气候区 找版本内条目；无覆盖返回 null（须走临时替代包装） */
    public PackSpecItem findItem(Long specVersionId, String material, String climateZone) {
        return itemMapper.selectOne(new LambdaQueryWrapper<PackSpecItem>()
                .eq(PackSpecItem::getSpecVersionId, specVersionId)
                .eq(PackSpecItem::getMaterial, material)
                .eq(PackSpecItem::getClimateZone, climateZone)
                .last("LIMIT 1"));
    }

    public PackSpecItem requireItem(Long itemId) {
        PackSpecItem item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new IllegalArgumentException("包装规格条目不存在: id=" + itemId);
        }
        return item;
    }

    /** 版本对应的修复系统通知行（如实暴露送达状态） */
    public PackNotify notifyOf(Long specVersionId) {
        return notifyService.findByVersionId(specVersionId);
    }
}
