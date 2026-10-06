package com.evops.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文物运输包装配置（museum.pack.*）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "museum.pack")
public class MuseumPackProperties {

    /** 待送达通知定时补送扫描间隔（毫秒）；重启后启动即补扫一次 */
    private long notifyScanIntervalMs = 60000;

    /** 应用就绪后补扫待送达通知（重启场景） */
    private boolean notifyScanOnStartup = true;

    /** 文物修复系统（外部依赖）接入配置 */
    private Restoration restoration = new Restoration();

    @Data
    public static class Restoration {
        /** 修复系统通知端点；未配置时通知如实失败并保持待送达，绝不假装成功 */
        private String endpoint = "";

        private int connectTimeoutMs = 2000;

        private int readTimeoutMs = 2000;
    }
}
