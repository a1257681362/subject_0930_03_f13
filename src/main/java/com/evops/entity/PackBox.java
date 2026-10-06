package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 包装箱：一箱一行。
 * snap_* 为装箱时校验依据快照（规格条目或已批替代单参数），与实测值、
 * 实际使用参数共同构成发运前证据，永不改写；规格版本变更不影响本行。
 * 拆箱回写只写 unpack_* 列与 status，不覆盖发运前证据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pack_box")
public class PackBox extends BaseEntity {

    private String boxNo;

    private Long relicId;

    /** 关联借展协议（已签押运单），可空 */
    private Long agreementId;

    private String climateZone;

    private Long specVersionId;

    private String specVersionNo;

    private Long specItemId;

    /** 使用的临时替代包装单（标准装箱为 NULL；一单最多一箱） */
    private Long substituteId;

    // ---------------- 校验依据快照（发运前证据，不改写） ----------------

    private String snapPackMaterial;

    private String snapCushionLevel;

    private BigDecimal snapMaxLengthMm;

    private BigDecimal snapMaxWidthMm;

    private BigDecimal snapMaxHeightMm;

    private BigDecimal snapMaxWeightG;

    private Integer snapShockproofRequired;

    // ---------------- 装箱实测与使用参数（发运前证据，不改写） ----------------

    private BigDecimal relicWeightG;

    private BigDecimal relicLengthMm;

    private BigDecimal relicWidthMm;

    private BigDecimal relicHeightMm;

    private String usedPackMaterial;

    private String usedCushionLevel;

    private Integer shockproofApplied;

    /** 替代装箱时固化替代单差异说明 */
    private String packDiffNote;

    // ---------------- 生命周期 ----------------

    /** PACKED 已装箱 / SHIPPED 已发运 / UNPACKED 已拆箱 */
    private String status;

    private String packedBy;

    private LocalDateTime packedAt;

    private String shippedBy;

    private LocalDateTime shippedAt;

    // ---------------- 拆箱回写（只写本组列，不覆盖发运前证据） ----------------

    /** INTACT 完好 / DAMAGED 受损 */
    private String unpackCondition;

    private String unpackedBy;

    private LocalDateTime unpackedAt;

    private String unpackNote;
}
