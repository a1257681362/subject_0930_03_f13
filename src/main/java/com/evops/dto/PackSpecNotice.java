package com.evops.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 包装规格发布通知载荷：送往文物修复系统（外部依赖）。
 * notifyKey 为幂等键，外部系统据此去重，恢复后补送不会重复生效。
 */
@Data
public class PackSpecNotice {

    private String notifyKey;

    private String versionNo;

    private String title;

    private Integer itemCount;

    private String publishedBy;

    private LocalDateTime publishedAt;
}
