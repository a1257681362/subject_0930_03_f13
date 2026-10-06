package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * 包装规格条目：按 文物材质 + 气候区 配置尺寸/承重上限、缓冲等级、
 * 包装材质与防震要求。
 */
@Data
public class PackSpecItemRequest {

    @NotBlank(message = "适用文物材质不能为空")
    private String material;

    /** TEMPERATE 温和 / TROPICAL 湿热 / ARID 干旱 / COLD 寒冷 */
    @NotBlank(message = "气候区不能为空")
    private String climateZone;

    @NotNull(message = "长度上限不能为空")
    @Positive(message = "长度上限必须为正")
    private BigDecimal maxLengthMm;

    @NotNull(message = "宽度上限不能为空")
    @Positive(message = "宽度上限必须为正")
    private BigDecimal maxWidthMm;

    @NotNull(message = "高度上限不能为空")
    @Positive(message = "高度上限必须为正")
    private BigDecimal maxHeightMm;

    @NotNull(message = "承重上限不能为空")
    @Positive(message = "承重上限必须为正")
    private BigDecimal maxWeightG;

    /** L1 / L2 / L3 */
    @NotBlank(message = "缓冲等级不能为空")
    private String cushionLevel;

    @NotBlank(message = "包装材质不能为空")
    private String packMaterial;

    /** 是否必须落实防震措施 */
    @NotNull(message = "防震要求不能为空")
    private Boolean shockproofRequired;
}
