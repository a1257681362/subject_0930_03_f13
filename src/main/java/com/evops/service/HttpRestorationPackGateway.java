package com.evops.service;

import com.evops.config.MuseumPackProperties;
import com.evops.dto.PackSpecNotice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 文物修复系统（外部依赖）HTTP 通知网关。
 * 端点未配置、连接超时、读取超时或非 2xx 响应一律抛
 * {@link RestorationNotifyException}——绝不把未送达伪装成成功。
 */
@Component
public class HttpRestorationPackGateway implements RestorationPackGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpRestorationPackGateway.class);

    private final MuseumPackProperties properties;

    public HttpRestorationPackGateway(MuseumPackProperties properties) {
        this.properties = properties;
    }

    @Override
    public void notifySpecPublished(PackSpecNotice notice) throws RestorationNotifyException {
        MuseumPackProperties.Restoration cfg = properties.getRestoration();
        String endpoint = cfg.getEndpoint();
        if (endpoint == null || endpoint.trim().isEmpty()) {
            throw new RestorationNotifyException(
                    "文物修复系统端点未配置（museum.pack.restoration.endpoint），通知无法送达");
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(cfg.getConnectTimeoutMs());
            conn.setReadTimeout(cfg.getReadTimeoutMs());
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("X-Notify-Key", notice.getNotifyKey());
            byte[] body = toJson(notice).getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new RestorationNotifyException("文物修复系统返回非成功状态: HTTP " + code);
            }
            log.info("包装规格 {} 已送达文物修复系统", notice.getVersionNo());
        } catch (IOException e) {
            throw new RestorationNotifyException("文物修复系统无响应: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String toJson(PackSpecNotice n) {
        return "{"
                + "\"notifyKey\":\"" + escape(n.getNotifyKey()) + "\","
                + "\"versionNo\":\"" + escape(n.getVersionNo()) + "\","
                + "\"title\":\"" + escape(n.getTitle()) + "\","
                + "\"itemCount\":" + (n.getItemCount() == null ? 0 : n.getItemCount()) + ","
                + "\"publishedBy\":\"" + escape(n.getPublishedBy()) + "\","
                + "\"publishedAt\":\"" + (n.getPublishedAt() == null ? "" : n.getPublishedAt().toString()) + "\""
                + "}";
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
