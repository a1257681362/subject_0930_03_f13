package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.dto.PackSpecNotice;
import com.evops.entity.PackNotify;
import com.evops.entity.PackSpecItem;
import com.evops.entity.PackSpecVersion;
import com.evops.mapper.PackNotifyMapper;
import com.evops.mapper.PackSpecItemMapper;
import com.evops.mapper.PackSpecVersionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

import static com.evops.constant.MuseumConstants.*;

/**
 * 修复系统通知（事务性发件箱）：
 *
 * <p>包装规格发布同事务落 PENDING 行并立即尝试送达；外部依赖无响应时
 * 保持 PENDING 并如实记录错误——<b>绝不假装成功</b>。恢复后由
 * {@link PackNotifyScheduler} 补扫重试；{@code notify_key} 唯一约束 +
 * 置 SENT 时前置状态校验保证同一事件<b>只送达一次</b>，恢复后绝不重复通知。</p>
 */
@Service
public class PackNotifyService {

    private static final Logger log = LoggerFactory.getLogger(PackNotifyService.class);

    private final PackNotifyMapper notifyMapper;
    private final PackSpecVersionMapper versionMapper;
    private final PackSpecItemMapper itemMapper;
    private final RestorationPackGateway gateway;
    private final PackLedgerService ledgerService;

    public PackNotifyService(PackNotifyMapper notifyMapper,
                             PackSpecVersionMapper versionMapper,
                             PackSpecItemMapper itemMapper,
                             RestorationPackGateway gateway,
                             PackLedgerService ledgerService) {
        this.notifyMapper = notifyMapper;
        this.versionMapper = versionMapper;
        this.itemMapper = itemMapper;
        this.gateway = gateway;
        this.ledgerService = ledgerService;
    }

    /**
     * 规格发布同事务落待通知行并立即尝试送达。
     * 返回最新通知行（PENDING 或 SENT），调用方据此如实暴露送达状态。
     */
    public PackNotify createPendingAndAttempt(PackSpecVersion version, int itemCount, LocalDateTime now) {
        PackNotify notify = new PackNotify();
        notify.setNotifyKey(NOTIFY_EVENT_SPEC_PUBLISH + ":" + version.getId());
        notify.setEventType(NOTIFY_EVENT_SPEC_PUBLISH);
        notify.setSpecVersionId(version.getId());
        notify.setSpecVersionNo(version.getVersionNo());
        notify.setPayload("包装规格版本发布：" + version.getVersionNo() + "《"
                + version.getTitle() + "》，条目 " + itemCount + " 条，发布人：" + version.getPublishedBy());
        notify.setStatus(NOTIFY_PENDING);
        notify.setAttemptCount(0);
        notifyMapper.insert(notify);

        attempt(notify.getId());
        return notifyMapper.selectById(notify.getId());
    }

    /**
     * 尝试送达一条通知。已送达的直接跳过（不重复通知）；
     * 失败如实累加尝试次数与错误，保持 PENDING。
     *
     * @return true 本条送达成功
     */
    public boolean attempt(Long notifyId) {
        PackNotify notify = notifyMapper.selectById(notifyId);
        if (notify == null || NOTIFY_SENT.equals(notify.getStatus())) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        try {
            gateway.notifySpecPublished(toNotice(notify));
        } catch (Exception e) {
            // 外部依赖无响应：如实记录，保持 PENDING，绝不标记成功
            log.warn("包装规格通知未送达（保持待送达）: notifyKey={}, 原因={}",
                    notify.getNotifyKey(), e.getMessage());
            PackNotify failed = new PackNotify();
            failed.setId(notify.getId());
            failed.setAttemptCount(notify.getAttemptCount() + 1);
            failed.setLastError(truncate(e.getMessage()));
            failed.setFirstAttemptAt(notify.getFirstAttemptAt() == null ? now : notify.getFirstAttemptAt());
            failed.setLastAttemptAt(now);
            notifyMapper.updateById(failed);
            return false;
        }
        // 送达成功：仅当仍是 PENDING 才置 SENT——并发/重启竞态下不重复写台账
        int rows = notifyMapper.update(null, new LambdaUpdateWrapper<PackNotify>()
                .eq(PackNotify::getId, notify.getId())
                .eq(PackNotify::getStatus, NOTIFY_PENDING)
                .set(PackNotify::getStatus, NOTIFY_SENT)
                .set(PackNotify::getSentAt, now)
                .set(PackNotify::getAttemptCount, notify.getAttemptCount() + 1)
                .set(PackNotify::getFirstAttemptAt,
                        notify.getFirstAttemptAt() == null ? now : notify.getFirstAttemptAt())
                .set(PackNotify::getLastAttemptAt, now)
                .set(PackNotify::getLastError, null));
        if (rows > 0) {
            ledgerService.append(null, notify.getSpecVersionId(), null,
                    ACTION_PACK_NOTIFY_SENT, notify.getSpecVersionNo(),
                    "包装规格 " + notify.getSpecVersionNo()
                            + " 发布通知已送达文物修复系统（notifyKey=" + notify.getNotifyKey() + "）", now);
            return true;
        }
        return false;
    }

    /** 补扫全部待送达通知（定时任务/手动触发），返回本轮送达条数 */
    public int retryPending() {
        List<PackNotify> pending = notifyMapper.selectList(new LambdaQueryWrapper<PackNotify>()
                .eq(PackNotify::getStatus, NOTIFY_PENDING)
                .orderByAsc(PackNotify::getId));
        int sent = 0;
        for (PackNotify notify : pending) {
            if (attempt(notify.getId())) {
                sent++;
            }
        }
        return sent;
    }

    public PackNotify findByVersionId(Long specVersionId) {
        return notifyMapper.selectOne(new LambdaQueryWrapper<PackNotify>()
                .eq(PackNotify::getSpecVersionId, specVersionId)
                .orderByDesc(PackNotify::getId)
                .last("LIMIT 1"));
    }

    /** 由版本行与条目数组装通知载荷：补送时与首次送达内容口径一致 */
    private PackSpecNotice toNotice(PackNotify notify) {
        PackSpecVersion version = versionMapper.selectById(notify.getSpecVersionId());
        PackSpecNotice notice = new PackSpecNotice();
        notice.setNotifyKey(notify.getNotifyKey());
        notice.setVersionNo(notify.getSpecVersionNo());
        if (version != null) {
            notice.setTitle(version.getTitle());
            notice.setPublishedBy(version.getPublishedBy());
            notice.setPublishedAt(version.getPublishedAt());
        }
        Long itemCount = itemMapper.selectCount(new LambdaQueryWrapper<PackSpecItem>()
                .eq(PackSpecItem::getSpecVersionId, notify.getSpecVersionId()));
        notice.setItemCount(itemCount == null ? 0 : itemCount.intValue());
        return notice;
    }

    private String truncate(String message) {
        if (message == null) {
            return "未知错误";
        }
        return message.length() <= 480 ? message : message.substring(0, 480);
    }
}
