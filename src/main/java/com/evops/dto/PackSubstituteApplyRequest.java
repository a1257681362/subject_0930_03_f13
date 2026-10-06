package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * 临时替代包装申请：记录替代参数与同标准包装的差异，
 * 必须审批通过后才允许装箱。
 */
@Data
public class PackSubstituteApplyRequest {

    @NotBlank(message = "替代单号不能为空")
    private String substituteNo;

    @NotNull(message = "文物不能为空")
    private Long relicId;

    /** TEMPERATE / TROPICAL / ARID / COLD */
    @NotBlank(message = "气候区不能为空")
    private String climateZone;

    /** 被替代的标准规格条目（无覆盖条目时留空） */
    private Long specItemId;

    @NotBlank(message = "替代包装材质不能为空")
    private String altPackMaterial;

    /** L1 / L2 / L3 */
    @NotBlank(message = "替代缓冲等级不能为空")
    private String altCushionLevel;

    @NotNull(message = "替代长度上限不能为空")
    @Positive(message = "替代长度上限必须为正")
    private BigDecimal altMaxLengthMm;

    @NotNull(message = "替代宽度上限不能为空")
    @Positive(message = "替代宽度上限必须为正")
    private BigDecimal altMaxWidthMm;

    @NotNull(message = "替代高度上限不能为空")
    @Positive(message = "替代高度上限必须为正")
    private BigDecimal altMaxHeightMm;

    @NotNull(message = "替代承重上限不能为空")
    @Positive(message = "替代承重上限必须为正")
    private BigDecimal altMaxWeightG;

    /** 替代包装是否具备防震能力 */
    @NotNull(message = "替代包装防震能力不能为空")
    private Boolean altShockproof;

    @NotBlank(message = "与标准包装的差异说明不能为空")
    private String diffNote;

    @NotBlank(message = "申请原因不能为空")
    private String reason;

    @NotBlank(message = "申请人不能为空")
    private String requestedBy;
}
