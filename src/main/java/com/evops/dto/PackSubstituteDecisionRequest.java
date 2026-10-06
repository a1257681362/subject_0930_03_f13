package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 临时替代包装审批决定：批准或驳回。
 * 审批人不得与申请人相同（不能自审自批）。
 */
@Data
public class PackSubstituteDecisionRequest {

    @NotBlank(message = "审批人不能为空")
    private String decidedBy;

    /** 审批意见（驳回时必填） */
    private String decisionNote;
}
