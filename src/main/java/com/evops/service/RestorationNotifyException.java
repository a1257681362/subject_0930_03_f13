package com.evops.service;

/**
 * 文物修复系统（外部依赖）通知失败：无响应、超时或非成功状态。
 * 抛出该异常时调用方必须如实记录并保持待送达，绝不假装成功。
 */
public class RestorationNotifyException extends RuntimeException {

    public RestorationNotifyException(String message) {
        super(message);
    }

    public RestorationNotifyException(String message, Throwable cause) {
        super(message, cause);
    }
}
