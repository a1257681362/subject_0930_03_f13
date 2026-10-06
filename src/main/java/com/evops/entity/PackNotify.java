package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 修复系统通知（事务性发件箱）：规格发布同事务落 PENDING 行。
 * 外部依赖无响应时保持 PENDING 并如实记录错误，绝不标记已送达；
 * notify_key 唯一，恢复后补送只送达一次，绝不重复通知。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pack_notify")
public class PackNotify extends BaseEntity {

    /** 幂等键：事件类型 + 业务主键（如 PACK_SPEC_PUBLISH:版本id） */
    private String notifyKey;

    private String eventType;

    private Long specVersionId;

    private String specVersionNo;

    private String payload;

    /** PENDING 待送达 / SENT 已送达 */
    private String status;

    private Integer attemptCount;

    private String lastError;

    private LocalDateTime firstAttemptAt;

    private LocalDateTime lastAttemptAt;

    private LocalDateTime sentAt;
}
