package com.evops.service;

import com.evops.config.MuseumPackProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 修复系统通知补送定时扫描：
 *
 * <p>周期扫描 PENDING 通知并补送。凭 {@code t_pack_notify.uk_pack_notify_key}
 * 唯一约束与置 SENT 前置状态校验实现<b>重启幂等</b>：外部依赖恢复后同一事件
 * 只送达一次，绝不重复通知；外部依赖仍无响应时保持 PENDING 如实待送。</p>
 *
 * <p>应用启动就绪后先补扫一次（重启场景），之后按固定间隔扫描；
 * 单次扫描串行加锁，启动扫描与周期扫描不会并发重入。</p>
 */
@Component
public class PackNotifyScheduler {

    private static final Logger log = LoggerFactory.getLogger(PackNotifyScheduler.class);

    private final PackNotifyService notifyService;
    private final MuseumPackProperties properties;
    private final ReentrantLock scanLock = new ReentrantLock();
    private final AtomicBoolean startedUp = new AtomicBoolean(false);

    public PackNotifyScheduler(PackNotifyService notifyService, MuseumPackProperties properties) {
        this.notifyService = notifyService;
        this.properties = properties;
    }

    /** 启动就绪补扫：重启后立即补送历史待送达通知 */
    @EventListener(ApplicationReadyEvent.class)
    public void scanOnStartup() {
        if (properties.isNotifyScanOnStartup() && startedUp.compareAndSet(false, true)) {
            log.info("包装规格通知：启动补扫待送达通知");
            scanOnce();
        }
    }

    /** 周期扫描（fixedDelay：上一轮结束后再计时，任务堆积不会重叠） */
    @Scheduled(fixedDelayString = "${museum.pack.notify-scan-interval-ms:60000}")
    public void scanOnSchedule() {
        scanOnce();
    }

    /**
     * 执行一轮补扫，返回本轮送达条数。
     * 公开供接口/测试手动触发；加锁保证与定时轮次不重入。
     */
    public int scanOnce() {
        if (!scanLock.tryLock()) {
            log.debug("包装规格通知补扫上一轮尚未结束，本轮跳过");
            return 0;
        }
        try {
            int sent = notifyService.retryPending();
            if (sent > 0) {
                log.info("包装规格通知补扫：本轮送达 {} 条", sent);
            }
            return sent;
        } finally {
            scanLock.unlock();
        }
    }
}
