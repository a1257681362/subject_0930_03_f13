package com.evops.service;

import com.evops.dto.PackSpecNotice;

/**
 * 文物修复系统（外部依赖）通知网关。
 * 实现必须诚实：送达失败时抛 {@link RestorationNotifyException}，
 * 不得在无响应时返回成功。
 */
public interface RestorationPackGateway {

    /**
     * 通知修复系统包装规格版本已发布。
     *
     * @throws RestorationNotifyException 外部依赖无响应/超时/非成功状态时抛出
     */
    void notifySpecPublished(PackSpecNotice notice) throws RestorationNotifyException;
}
