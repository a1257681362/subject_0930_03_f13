package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.dto.PackSubstituteApplyRequest;
import com.evops.dto.PackSubstituteDecisionRequest;
import com.evops.entity.PackSubstitute;
import com.evops.entity.Relic;
import com.evops.mapper.PackSubstituteMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static com.evops.constant.MuseumConstants.*;

/**
 * 临时替代包装审批：
 * 规格覆盖不到或现场无法满足标准包装时，申请替代参数并记录与标准的差异；
 * 必须审批通过后才允许装箱，审批人不得与申请人相同（不能自审自批）；
 * 差异说明随申请固化，审批只改状态，差异事实永不改写。
 */
@Service
public class PackSubstituteService {

    private final PackSubstituteMapper substituteMapper;
    private final RelicService relicService;
    private final PackSpecService specService;
    private final PackLedgerService ledgerService;

    public PackSubstituteService(PackSubstituteMapper substituteMapper,
                                 RelicService relicService,
                                 PackSpecService specService,
                                 PackLedgerService ledgerService) {
        this.substituteMapper = substituteMapper;
        this.relicService = relicService;
        this.specService = specService;
        this.ledgerService = ledgerService;
    }

    /** 申请临时替代包装：登记替代参数与差异说明，状态 PENDING */
    @Transactional
    public PackSubstitute apply(PackSubstituteApplyRequest req) {
        Relic relic = relicService.requireActiveRelic(req.getRelicId());
        PackSpecService.requireClimateZone(req.getClimateZone());
        PackSpecService.requireCushionLevel(req.getAltCushionLevel());
        if (substituteMapper.selectCount(new LambdaQueryWrapper<PackSubstitute>()
                .eq(PackSubstitute::getSubstituteNo, req.getSubstituteNo())) > 0) {
            throw new IllegalArgumentException("替代单号已存在: " + req.getSubstituteNo());
        }
        if (req.getSpecItemId() != null) {
            // 被替代的标准条目必须存在，差异说明才有对照基准
            specService.requireItem(req.getSpecItemId());
        }

        LocalDateTime now = LocalDateTime.now();
        PackSubstitute sub = new PackSubstitute();
        sub.setSubstituteNo(req.getSubstituteNo());
        sub.setRelicId(req.getRelicId());
        sub.setClimateZone(req.getClimateZone());
        sub.setSpecItemId(req.getSpecItemId());
        sub.setAltPackMaterial(req.getAltPackMaterial().trim());
        sub.setAltCushionLevel(req.getAltCushionLevel());
        sub.setAltMaxLengthMm(req.getAltMaxLengthMm());
        sub.setAltMaxWidthMm(req.getAltMaxWidthMm());
        sub.setAltMaxHeightMm(req.getAltMaxHeightMm());
        sub.setAltMaxWeightG(req.getAltMaxWeightG());
        sub.setAltShockproof(Boolean.TRUE.equals(req.getAltShockproof()) ? 1 : 0);
        sub.setDiffNote(req.getDiffNote());
        sub.setReason(req.getReason());
        sub.setRequestedBy(req.getRequestedBy());
        sub.setStatus(SUB_PENDING);
        substituteMapper.insert(sub);

        ledgerService.append(null, null, sub.getId(),
                ACTION_PACK_SUB_APPLY, sub.getSubstituteNo(),
                "临时替代包装申请：文物 " + relic.getRelicNo() + "，气候区 " + sub.getClimateZone()
                        + "，替代材质 " + sub.getAltPackMaterial() + "，差异：" + sub.getDiffNote()
                        + "，原因：" + sub.getReason() + "，申请人：" + sub.getRequestedBy(), now);
        return sub;
    }

    /** 审批通过：仅 PENDING 可批；审批人不得与申请人相同 */
    @Transactional
    public PackSubstitute approve(Long substituteId, PackSubstituteDecisionRequest req) {
        PackSubstitute sub = requirePending(substituteId);
        if (sub.getRequestedBy().equals(req.getDecidedBy())) {
            throw new IllegalArgumentException("审批人不能与申请人相同，不能自审自批: " + req.getDecidedBy());
        }
        LocalDateTime now = LocalDateTime.now();
        sub.setStatus(SUB_APPROVED);
        sub.setApprovedBy(req.getDecidedBy());
        sub.setApprovedAt(now);
        sub.setDecisionNote(req.getDecisionNote());
        substituteMapper.updateById(sub);

        ledgerService.append(null, null, sub.getId(),
                ACTION_PACK_SUB_APPROVE, sub.getSubstituteNo(),
                "临时替代包装审批通过，审批人：" + req.getDecidedBy()
                        + (req.getDecisionNote() == null ? "" : "，审批意见：" + req.getDecisionNote()), now);
        return sub;
    }

    /** 审批驳回：仅 PENDING 可驳，驳回意见必填 */
    @Transactional
    public PackSubstitute reject(Long substituteId, PackSubstituteDecisionRequest req) {
        PackSubstitute sub = requirePending(substituteId);
        if (sub.getRequestedBy().equals(req.getDecidedBy())) {
            throw new IllegalArgumentException("审批人不能与申请人相同，不能自审自批: " + req.getDecidedBy());
        }
        if (req.getDecisionNote() == null || req.getDecisionNote().trim().isEmpty()) {
            throw new IllegalArgumentException("驳回必须填写审批意见");
        }
        LocalDateTime now = LocalDateTime.now();
        sub.setStatus(SUB_REJECTED);
        sub.setApprovedBy(req.getDecidedBy());
        sub.setApprovedAt(now);
        sub.setDecisionNote(req.getDecisionNote());
        substituteMapper.updateById(sub);

        ledgerService.append(null, null, sub.getId(),
                ACTION_PACK_SUB_REJECT, sub.getSubstituteNo(),
                "临时替代包装审批驳回，审批人：" + req.getDecidedBy()
                        + "，驳回意见：" + req.getDecisionNote(), now);
        return sub;
    }

    private PackSubstitute requirePending(Long substituteId) {
        PackSubstitute sub = requireSubstitute(substituteId);
        if (!SUB_PENDING.equals(sub.getStatus())) {
            throw new IllegalArgumentException("替代单已审批（" + sub.getStatus() + "），不能重复审批: "
                    + sub.getSubstituteNo());
        }
        return sub;
    }

    public PackSubstitute requireSubstitute(Long substituteId) {
        PackSubstitute sub = substituteMapper.selectById(substituteId);
        if (sub == null) {
            throw new IllegalArgumentException("临时替代包装单不存在: id=" + substituteId);
        }
        return sub;
    }

    public List<PackSubstitute> list(Long relicId, String status) {
        return substituteMapper.selectList(new LambdaQueryWrapper<PackSubstitute>()
                .eq(relicId != null, PackSubstitute::getRelicId, relicId)
                .eq(status != null && !status.isEmpty(), PackSubstitute::getStatus, status)
                .orderByDesc(PackSubstitute::getId));
    }
}
