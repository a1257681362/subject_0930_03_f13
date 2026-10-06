package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * 装箱登记：按生效规格（或已批替代单）校验文物重量、外形尺寸、
 * 缓冲等级与防震要求；校验依据与实测值固化在箱行形成发运前证据。
 */
@Data
public class PackBoxCreateRequest {

    @NotBlank(message = "箱号不能为空")
    private String boxNo;

    @NotNull(message = "文物不能为空")
    private Long relicId;

    /** 关联借展协议（已签押运单），可空 */
    private Long agreementId;

    /** TEMPERATE / TROPICAL / ARID / COLD */
    @NotBlank(message = "气候区不能为空")
    private String climateZone;

    @NotNull(message = "文物实测重量不能为空")
    @Positive(message = "文物实测重量必须为正")
    private BigDecimal relicWeightG;

    @NotNull(message = "文物实测长度不能为空")
    @Positive(message = "文物实测长度必须为正")
    private BigDecimal relicLengthMm;

    @NotNull(message = "文物实测宽度不能为空")
    @Positive(message = "文物实测宽度必须为正")
    private BigDecimal relicWidthMm;

    @NotNull(message = "文物实测高度不能为空")
    @Positive(message = "文物实测高度必须为正")
    private BigDecimal relicHeightMm;

    @NotBlank(message = "实际包装材质不能为空")
    private String usedPackMaterial;

    /** L1 / L2 / L3 */
    @NotBlank(message = "实际缓冲等级不能为空")
    private String usedCushionLevel;

    /** 是否已落实防震措施 */
    @NotNull(message = "防震落实情况不能为空")
    private Boolean shockproofApplied;

    /** 临时替代包装单（标准装箱留空） */
    private Long substituteId;

    @NotBlank(message = "装箱人不能为空")
    private String packedBy;
}
