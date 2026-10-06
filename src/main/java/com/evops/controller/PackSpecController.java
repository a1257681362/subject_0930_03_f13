package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.PackSpecPublishRequest;
import com.evops.entity.PackNotify;
import com.evops.entity.PackSpecVersion;
import com.evops.service.PackSpecService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.evops.constant.MuseumConstants.NOTIFY_SENT;

/**
 * 包装规格版本：发布新版本（按 材质+气候区 配置尺寸/承重/缓冲/防震），
 * 旧版本自动置 SUPERSEDED 只读；已装箱箱行固化快照，规格变更不影响已签押运单。
 * 每次发布向文物修复系统发通知，响应如实暴露送达状态（PENDING/SENT），
 * 外部依赖无响应时绝不假装成功。
 */
@RestController
public class PackSpecController {

    private final PackSpecService specService;

    public PackSpecController(PackSpecService specService) {
        this.specService = specService;
    }

    /** 发布规格新版本（含修复系统通知送达状态） */
    @PostMapping("/api/pack-specs/publish")
    public ApiResponse<Map<String, Object>> publish(@Valid @RequestBody PackSpecPublishRequest request) {
        PackSpecVersion version = specService.publish(request);
        return ApiResponse.ok(detail(version));
    }

    /** 规格版本列表（只追加，含 SUPERSEDED 历史版本） */
    @GetMapping("/api/pack-specs")
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(specService.listVersions().stream()
                .map(this::detail).collect(Collectors.toList()));
    }

    /** 当前生效规格版本（未发布为 null） */
    @GetMapping("/api/pack-specs/active")
    public ApiResponse<Map<String, Object>> active() {
        PackSpecVersion version = specService.activeVersionEntity();
        return ApiResponse.ok(version == null ? null : detail(version));
    }

    /** 规格版本详情：版本头 + 条目 + 修复系统通知状态 */
    @GetMapping("/api/pack-specs/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(detail(specService.requireVersion(id)));
    }

    private Map<String, Object> detail(PackSpecVersion version) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("version", version);
        detail.put("items", specService.listItems(version.getId()));
        PackNotify notify = specService.notifyOf(version.getId());
        detail.put("notify", notify);
        // 送达状态未达 SENT 时如实给出提示，绝不假装成功
        if (notify != null && !NOTIFY_SENT.equals(notify.getStatus())) {
            detail.put("notifyWarning", "文物修复系统通知未送达（" + notify.getStatus()
                    + "），待恢复后自动补送"
                    + (notify.getLastError() == null ? "" : "；最近错误：" + notify.getLastError()));
        }
        return detail;
    }
}
