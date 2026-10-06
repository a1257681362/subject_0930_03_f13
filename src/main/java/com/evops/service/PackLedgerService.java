package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.entity.PackLedger;
import com.evops.mapper.PackLedgerMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 包装台账：只追加、不修改、不删除。
 * 规格发布/装箱/替代申请审批/照片绑定/发运/拆箱/通知送达全部留痕。
 */
@Service
public class PackLedgerService {

    private final PackLedgerMapper ledgerMapper;

    public PackLedgerService(PackLedgerMapper ledgerMapper) {
        this.ledgerMapper = ledgerMapper;
    }

    public void append(Long boxId, Long specVersionId, Long substituteId,
                       String action, String bizNo, String summary, LocalDateTime occurredAt) {
        PackLedger entry = new PackLedger();
        entry.setBoxId(boxId);
        entry.setSpecVersionId(specVersionId);
        entry.setSubstituteId(substituteId);
        entry.setAction(action);
        entry.setBizNo(bizNo);
        entry.setSummary(summary);
        entry.setOccurredAt(occurredAt == null ? LocalDateTime.now() : occurredAt);
        ledgerMapper.insert(entry);
    }

    /** 按箱时间序列查询（装箱/照片/发运/拆箱追溯口径） */
    public List<PackLedger> listByBox(Long boxId) {
        return ledgerMapper.selectList(new LambdaQueryWrapper<PackLedger>()
                .eq(PackLedger::getBoxId, boxId)
                .orderByAsc(PackLedger::getOccurredAt)
                .orderByAsc(PackLedger::getId));
    }

    public List<PackLedger> listBySpecVersion(Long specVersionId) {
        return ledgerMapper.selectList(new LambdaQueryWrapper<PackLedger>()
                .eq(PackLedger::getSpecVersionId, specVersionId)
                .orderByAsc(PackLedger::getOccurredAt)
                .orderByAsc(PackLedger::getId));
    }
}
