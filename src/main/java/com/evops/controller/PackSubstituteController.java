package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.PackSubstituteApplyRequest;
import com.evops.dto.PackSubstituteDecisionRequest;
import com.evops.entity.PackSubstitute;
import com.evops.service.PackSubstituteService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * 临时替代包装：申请（记录替代参数与差异）-> 审批（批准/驳回）-> 装箱引用。
 * 未审批通过不能装箱；审批人不得与申请人相同。
 */
@RestController
public class PackSubstituteController {

    private final PackSubstituteService substituteService;

    public PackSubstituteController(PackSubstituteService substituteService) {
        this.substituteService = substituteService;
    }

    /** 申请临时替代包装 */
    @PostMapping("/api/pack-substitutes")
    public ApiResponse<PackSubstitute> apply(@Valid @RequestBody PackSubstituteApplyRequest request) {
        return ApiResponse.ok(substituteService.apply(request));
    }

    /** 审批通过 */
    @PostMapping("/api/pack-substitutes/{id}/approve")
    public ApiResponse<PackSubstitute> approve(@PathVariable Long id,
                                               @Valid @RequestBody PackSubstituteDecisionRequest request) {
        return ApiResponse.ok(substituteService.approve(id, request));
    }

    /** 审批驳回 */
    @PostMapping("/api/pack-substitutes/{id}/reject")
    public ApiResponse<PackSubstitute> reject(@PathVariable Long id,
                                              @Valid @RequestBody PackSubstituteDecisionRequest request) {
        return ApiResponse.ok(substituteService.reject(id, request));
    }

    /** 替代单列表（可按文物/状态过滤） */
    @GetMapping("/api/pack-substitutes")
    public ApiResponse<List<PackSubstitute>> list(@RequestParam(required = false) Long relicId,
                                                  @RequestParam(required = false) String status) {
        return ApiResponse.ok(substituteService.list(relicId, status));
    }

    /** 替代单详情 */
    @GetMapping("/api/pack-substitutes/{id}")
    public ApiResponse<PackSubstitute> detail(@PathVariable Long id) {
        return ApiResponse.ok(substituteService.requireSubstitute(id));
    }
}
