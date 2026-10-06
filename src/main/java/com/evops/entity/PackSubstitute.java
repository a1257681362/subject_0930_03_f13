package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 临时替代包装审批：规格覆盖不到或现场无法满足标准包装时，
 * 申请替代参数并记录与标准的差异；审批通过后才允许装箱。
 * status: PENDING 待审批 / APPROVED 已批准 / REJECTED 已驳回
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pack_substitute")
public class PackSubstitute extends BaseEntity {

    private String substituteNo;

    private Long relicId;

    private String climateZone;

    /** 被替代的标准规格条目（无覆盖条目时为 NULL） */
    private Long specItemId;

    private String altPackMaterial;

    private String altCushionLevel;

    private BigDecimal altMaxLengthMm;

    private BigDecimal altMaxWidthMm;

    private BigDecimal altMaxHeightMm;

    private BigDecimal altMaxWeightG;

    /** 替代包装是否具备防震能力：0 否 / 1 是 */
    private Integer altShockproof;

    /** 与标准包装的差异说明（必填，差异永久留痕） */
    private String diffNote;

    /** 申请原因 */
    private String reason;

    private String requestedBy;

    /** PENDING / APPROVED / REJECTED */
    private String status;

    private String approvedBy;

    private LocalDateTime approvedAt;

    private String decisionNote;
}
