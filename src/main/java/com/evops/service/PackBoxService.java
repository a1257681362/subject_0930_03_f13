package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.dto.PackBoxCreateRequest;
import com.evops.dto.PackPhotoBindRequest;
import com.evops.dto.PackShipRequest;
import com.evops.dto.PackUnpackRequest;
import com.evops.entity.LoanAgreement;
import com.evops.entity.PackBox;
import com.evops.entity.PackPhoto;
import com.evops.entity.PackSpecItem;
import com.evops.entity.PackSpecVersion;
import com.evops.entity.PackSubstitute;
import com.evops.entity.Relic;
import com.evops.mapper.LoanAgreementMapper;
import com.evops.mapper.PackBoxMapper;
import com.evops.mapper.PackPhotoMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static com.evops.constant.MuseumConstants.*;

/**
 * 文物装箱 / 发运 / 拆箱：
 *
 * <p>装箱按生效规格（或已批临时替代单）逐箱校验文物重量、外形尺寸、缓冲等级
 * 与防震要求，包装材质必须与校验依据一致；校验依据快照与实测值固化在箱行，
 * 形成发运前证据——规格版本变更不影响已装箱行与已签押运单。</p>
 *
 * <p>装箱照片与箱号绑定、只追加；发运前必须已有装箱照片。拆箱只回写
 * unpack_* 列与状态，发运前证据永不改写。</p>
 */
@Service
public class PackBoxService {

    private final PackBoxMapper boxMapper;
    private final PackPhotoMapper photoMapper;
    private final LoanAgreementMapper agreementMapper;
    private final RelicService relicService;
    private final PackSpecService specService;
    private final PackSubstituteService substituteService;
    private final PackLedgerService ledgerService;

    public PackBoxService(PackBoxMapper boxMapper,
                          PackPhotoMapper photoMapper,
                          LoanAgreementMapper agreementMapper,
                          RelicService relicService,
                          PackSpecService specService,
                          PackSubstituteService substituteService,
                          PackLedgerService ledgerService) {
        this.boxMapper = boxMapper;
        this.photoMapper = photoMapper;
        this.agreementMapper = agreementMapper;
        this.relicService = relicService;
        this.specService = specService;
        this.substituteService = substituteService;
        this.ledgerService = ledgerService;
    }

    // ------------------------------------------------------------------
    // 装箱
    // ------------------------------------------------------------------

    /**
     * 装箱登记：校验重量/外形/缓冲/防震/包装材质，固化校验依据快照与实测值。
     * 使用临时替代包装时，替代单必须已审批通过且未被其他箱引用。
     */
    @Transactional
    public PackBox pack(PackBoxCreateRequest req) {
        Relic relic = relicService.requireActiveRelic(req.getRelicId());
        PackSpecService.requireClimateZone(req.getClimateZone());
        PackSpecService.requireCushionLevel(req.getUsedCushionLevel());
        if (boxMapper.selectCount(new LambdaQueryWrapper<PackBox>()
                .eq(PackBox::getBoxNo, req.getBoxNo())) > 0) {
            throw new IllegalArgumentException("箱号已存在: " + req.getBoxNo());
        }
        if (req.getAgreementId() != null) {
            LoanAgreement agreement = agreementMapper.selectById(req.getAgreementId());
            if (agreement == null) {
                throw new IllegalArgumentException("借展协议不存在: id=" + req.getAgreementId());
            }
            if (!AGR_SIGNED.equals(agreement.getStatus()) && !AGR_IN_TRANSIT.equals(agreement.getStatus())) {
                throw new IllegalArgumentException("押运单尚未签署生效，不能装箱: "
                        + agreement.getAgreementNo() + "（" + agreement.getStatus() + "）");
            }
            if (!agreement.getRelicId().equals(req.getRelicId())) {
                throw new IllegalArgumentException("箱中文物与押运单文物不符: " + agreement.getAgreementNo());
            }
        }

        PackSpecVersion spec = specService.requireActiveVersion();
        PackSubstitute sub = null;
        PackSpecItem item = null;
        String requirePackMaterial;
        String requireCushion;
        BigDecimal maxLength, maxWidth, maxHeight, maxWeight;
        int shockproofRequired;
        String diffNote = null;

        if (req.getSubstituteId() != null) {
            // 临时替代包装：必须已审批通过，且一单最多被一只箱引用
            sub = substituteService.requireSubstitute(req.getSubstituteId());
            if (!SUB_APPROVED.equals(sub.getStatus())) {
                throw new IllegalArgumentException("临时替代包装未审批通过（" + sub.getStatus()
                        + "），不能装箱: " + sub.getSubstituteNo());
            }
            if (!sub.getRelicId().equals(req.getRelicId())) {
                throw new IllegalArgumentException("替代单不属于该文物: " + sub.getSubstituteNo());
            }
            if (!sub.getClimateZone().equals(req.getClimateZone())) {
                throw new IllegalArgumentException("替代单气候区（" + sub.getClimateZone()
                        + "）与装箱气候区不符: " + sub.getSubstituteNo());
            }
            if (boxMapper.selectCount(new LambdaQueryWrapper<PackBox>()
                    .eq(PackBox::getSubstituteId, sub.getId())) > 0) {
                throw new IllegalArgumentException("替代单已被其他箱引用，一单最多一箱: "
                        + sub.getSubstituteNo());
            }
            requirePackMaterial = sub.getAltPackMaterial();
            requireCushion = sub.getAltCushionLevel();
            maxLength = sub.getAltMaxLengthMm();
            maxWidth = sub.getAltMaxWidthMm();
            maxHeight = sub.getAltMaxHeightMm();
            maxWeight = sub.getAltMaxWeightG();
            shockproofRequired = sub.getAltShockproof();
            diffNote = sub.getDiffNote();
        } else {
            item = specService.findItem(spec.getId(), relic.getMaterial(), req.getClimateZone());
            if (item == null) {
                throw new IllegalArgumentException("生效规格 " + spec.getVersionNo()
                        + " 无材质【" + relic.getMaterial() + "】气候区【" + req.getClimateZone()
                        + "】的包装条目，须先申请临时替代包装并审批通过");
            }
            requirePackMaterial = item.getPackMaterial();
            requireCushion = item.getCushionLevel();
            maxLength = item.getMaxLengthMm();
            maxWidth = item.getMaxWidthMm();
            maxHeight = item.getMaxHeightMm();
            maxWeight = item.getMaxWeightG();
            shockproofRequired = item.getShockproofRequired();
        }

        // ---- 装箱校验：重量、外形、缓冲等级、防震要求、包装材质 ----
        if (req.getRelicWeightG().compareTo(maxWeight) > 0) {
            throw new IllegalArgumentException("文物重量 " + req.getRelicWeightG()
                    + "g 超过包装承重上限 " + maxWeight + "g，不能装箱");
        }
        if (req.getRelicLengthMm().compareTo(maxLength) > 0
                || req.getRelicWidthMm().compareTo(maxWidth) > 0
                || req.getRelicHeightMm().compareTo(maxHeight) > 0) {
            throw new IllegalArgumentException("文物外形尺寸 " + req.getRelicLengthMm() + "x"
                    + req.getRelicWidthMm() + "x" + req.getRelicHeightMm()
                    + "mm 超过包装规格上限 " + maxLength + "x" + maxWidth + "x" + maxHeight
                    + "mm，不能装箱");
        }
        if (PackSpecService.cushionRank(req.getUsedCushionLevel())
                < PackSpecService.cushionRank(requireCushion)) {
            throw new IllegalArgumentException("缓冲等级 " + req.getUsedCushionLevel()
                    + " 低于规格要求 " + requireCushion + "，不能装箱");
        }
        if (shockproofRequired == 1 && !Boolean.TRUE.equals(req.getShockproofApplied())) {
            throw new IllegalArgumentException("规格要求落实防震措施，装箱未落实防震，不能装箱");
        }
        if (!requirePackMaterial.equals(req.getUsedPackMaterial().trim())) {
            throw new IllegalArgumentException("包装材质【" + req.getUsedPackMaterial()
                    + "】与要求【" + requirePackMaterial + "】不符，须改用标准包装或走临时替代审批");
        }

        // ---- 固化箱行：校验依据快照 + 实测值 + 使用参数（发运前证据） ----
        LocalDateTime now = LocalDateTime.now();
        PackBox box = new PackBox();
        box.setBoxNo(req.getBoxNo());
        box.setRelicId(req.getRelicId());
        box.setAgreementId(req.getAgreementId());
        box.setClimateZone(req.getClimateZone());
        box.setSpecVersionId(spec.getId());
        box.setSpecVersionNo(spec.getVersionNo());
        box.setSpecItemId(item == null ? null : item.getId());
        box.setSubstituteId(sub == null ? null : sub.getId());
        box.setSnapPackMaterial(requirePackMaterial);
        box.setSnapCushionLevel(requireCushion);
        box.setSnapMaxLengthMm(maxLength);
        box.setSnapMaxWidthMm(maxWidth);
        box.setSnapMaxHeightMm(maxHeight);
        box.setSnapMaxWeightG(maxWeight);
        box.setSnapShockproofRequired(shockproofRequired);
        box.setRelicWeightG(req.getRelicWeightG());
        box.setRelicLengthMm(req.getRelicLengthMm());
        box.setRelicWidthMm(req.getRelicWidthMm());
        box.setRelicHeightMm(req.getRelicHeightMm());
        box.setUsedPackMaterial(req.getUsedPackMaterial().trim());
        box.setUsedCushionLevel(req.getUsedCushionLevel());
        box.setShockproofApplied(Boolean.TRUE.equals(req.getShockproofApplied()) ? 1 : 0);
        box.setPackDiffNote(diffNote);
        box.setStatus(BOX_PACKED);
        box.setPackedBy(req.getPackedBy());
        box.setPackedAt(now);
        boxMapper.insert(box);

        ledgerService.append(box.getId(), spec.getId(), sub == null ? null : sub.getId(),
                ACTION_PACK_BOX_PACK, box.getBoxNo(),
                "装箱完成：文物 " + relic.getRelicNo() + "，箱号 " + box.getBoxNo()
                        + "，规格 " + spec.getVersionNo()
                        + (sub != null ? "（临时替代单 " + sub.getSubstituteNo() + "，差异：" + diffNote + "）" : "")
                        + "，实测 " + req.getRelicWeightG() + "g / "
                        + req.getRelicLengthMm() + "x" + req.getRelicWidthMm() + "x"
                        + req.getRelicHeightMm() + "mm，缓冲 " + req.getUsedCushionLevel()
                        + "，装箱人：" + req.getPackedBy(), now);
        return box;
    }

    // ------------------------------------------------------------------
    // 装箱照片（与箱号绑定、只追加）
    // ------------------------------------------------------------------

    /**
     * 绑定装箱/拆箱照片：照片行冗余箱号，随箱可查。
     * 装箱照片在拆箱后封存（发运前证据不可再补）；拆箱照片仅拆箱后可绑。
     */
    @Transactional
    public PackPhoto bindPhoto(String boxNo, PackPhotoBindRequest req) {
        PackBox box = requireByBoxNo(boxNo);
        String phase = req.getPhase();
        if (PHOTO_PHASE_PACK.equals(phase)) {
            if (BOX_UNPACKED.equals(box.getStatus())) {
                throw new IllegalArgumentException("箱 " + boxNo
                        + " 已拆箱，装箱证据封存，不能再补装箱照片");
            }
        } else if (PHOTO_PHASE_UNPACK.equals(phase)) {
            if (!BOX_UNPACKED.equals(box.getStatus())) {
                throw new IllegalArgumentException("箱 " + boxNo + " 尚未拆箱，不能绑定拆箱照片");
            }
        } else {
            throw new IllegalArgumentException("未知照片阶段: " + phase + "，只能为 PACK/UNPACK");
        }

        PackPhoto photo = new PackPhoto();
        photo.setBoxId(box.getId());
        photo.setBoxNo(box.getBoxNo());
        photo.setPhase(phase);
        photo.setPhotoUrl(req.getPhotoUrl());
        photo.setPhotoName(req.getPhotoName());
        photo.setContentHash(req.getContentHash());
        photo.setUploadedBy(req.getUploadedBy());
        photo.setRemark(req.getRemark());
        try {
            photoMapper.insert(photo);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("同一箱同一阶段相同内容的照片已绑定，不能重复绑定");
        }

        ledgerService.append(box.getId(), box.getSpecVersionId(), box.getSubstituteId(),
                ACTION_PACK_PHOTO_BIND, box.getBoxNo(),
                ("PACK".equals(phase) ? "装箱" : "拆箱") + "照片绑定箱号 " + box.getBoxNo()
                        + "，上传人：" + req.getUploadedBy()
                        + (req.getRemark() == null ? "" : "，备注：" + req.getRemark()),
                LocalDateTime.now());
        return photo;
    }

    // ------------------------------------------------------------------
    // 发运 / 拆箱
    // ------------------------------------------------------------------

    /** 发运：仅已装箱可发运；发运前必须已有与箱号绑定的装箱照片 */
    @Transactional
    public PackBox ship(String boxNo, PackShipRequest req) {
        PackBox box = requireByBoxNo(boxNo);
        if (!BOX_PACKED.equals(box.getStatus())) {
            throw new IllegalArgumentException("箱 " + boxNo + " 当前状态（" + box.getStatus()
                    + "）不能发运，只有已装箱未发运的箱才能发运");
        }
        Long packPhotos = photoMapper.selectCount(new LambdaQueryWrapper<PackPhoto>()
                .eq(PackPhoto::getBoxId, box.getId())
                .eq(PackPhoto::getPhase, PHOTO_PHASE_PACK));
        if (packPhotos == null || packPhotos == 0) {
            throw new IllegalArgumentException("箱 " + boxNo + " 尚无装箱照片，发运前必须至少绑定一张装箱照片");
        }

        LocalDateTime now = LocalDateTime.now();
        box.setStatus(BOX_SHIPPED);
        box.setShippedBy(req.getShippedBy());
        box.setShippedAt(now);
        boxMapper.updateById(box);

        ledgerService.append(box.getId(), box.getSpecVersionId(), box.getSubstituteId(),
                ACTION_PACK_BOX_SHIP, box.getBoxNo(),
                "箱 " + boxNo + " 发运，发运人：" + req.getShippedBy()
                        + "，装箱照片 " + packPhotos + " 张已绑定", now);
        return box;
    }

    /**
     * 拆箱回写：只写 unpack_* 列与状态——包装状态（完好/受损）回写到箱行，
     * 发运前证据（规格快照/实测值/装箱照片）永不改写。
     */
    @Transactional
    public PackBox unpack(String boxNo, PackUnpackRequest req) {
        PackBox box = requireByBoxNo(boxNo);
        if (!BOX_SHIPPED.equals(box.getStatus())) {
            throw new IllegalArgumentException("箱 " + boxNo + " 当前状态（" + box.getStatus()
                    + "）不能拆箱回写，只有已发运的箱才能拆箱");
        }
        if (!UNPACK_INTACT.equals(req.getCondition()) && !UNPACK_DAMAGED.equals(req.getCondition())) {
            throw new IllegalArgumentException("拆箱包装状态只能为 INTACT（完好）或 DAMAGED（受损）");
        }
        if (UNPACK_DAMAGED.equals(req.getCondition())
                && (req.getUnpackNote() == null || req.getUnpackNote().trim().isEmpty())) {
            throw new IllegalArgumentException("包装受损必须填写拆箱说明");
        }

        LocalDateTime now = LocalDateTime.now();
        box.setStatus(BOX_UNPACKED);
        box.setUnpackCondition(req.getCondition());
        box.setUnpackedBy(req.getUnpackedBy());
        box.setUnpackedAt(now);
        box.setUnpackNote(req.getUnpackNote());
        boxMapper.updateById(box);

        ledgerService.append(box.getId(), box.getSpecVersionId(), box.getSubstituteId(),
                ACTION_PACK_BOX_UNPACK, box.getBoxNo(),
                "箱 " + boxNo + " 拆箱，包装状态："
                        + (UNPACK_INTACT.equals(req.getCondition()) ? "完好" : "受损")
                        + "，拆箱人：" + req.getUnpackedBy()
                        + (req.getUnpackNote() == null ? "" : "，说明：" + req.getUnpackNote()), now);
        return box;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public PackBox requireByBoxNo(String boxNo) {
        PackBox box = boxMapper.selectOne(new LambdaQueryWrapper<PackBox>()
                .eq(PackBox::getBoxNo, boxNo)
                .last("LIMIT 1"));
        if (box == null) {
            throw new IllegalArgumentException("包装箱不存在: " + boxNo);
        }
        return box;
    }

    public List<PackBox> listBoxes(Long relicId, Long agreementId) {
        return boxMapper.selectList(new LambdaQueryWrapper<PackBox>()
                .eq(relicId != null, PackBox::getRelicId, relicId)
                .eq(agreementId != null, PackBox::getAgreementId, agreementId)
                .orderByDesc(PackBox::getId));
    }

    public List<PackPhoto> listPhotos(Long boxId) {
        return photoMapper.selectList(new LambdaQueryWrapper<PackPhoto>()
                .eq(PackPhoto::getBoxId, boxId)
                .orderByAsc(PackPhoto::getId));
    }
}
