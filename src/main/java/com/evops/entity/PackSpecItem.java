package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 包装规格条目：按 文物材质 + 气候区 配置尺寸/承重上限、缓冲等级、
 * 包装材质与防震要求；同版本内 (材质, 气候区) 唯一。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pack_spec_item")
public class PackSpecItem extends BaseEntity {

    private Long specVersionId;

    /** 适用的文物材质 */
    private String material;

    /** TEMPERATE 温和 / TROPICAL 湿热 / ARID 干旱 / COLD 寒冷 */
    private String climateZone;

    private BigDecimal maxLengthMm;

    private BigDecimal maxWidthMm;

    private BigDecimal maxHeightMm;

    private BigDecimal maxWeightG;

    /** L1 / L2 / L3（L3 缓冲最高） */
    private String cushionLevel;

    /** 规格要求的包装材质（如 实木箱） */
    private String packMaterial;

    /** 0 不要求 / 1 必须落实防震措施 */
    private Integer shockproofRequired;
}
