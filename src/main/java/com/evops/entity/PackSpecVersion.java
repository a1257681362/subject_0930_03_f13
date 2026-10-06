package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 包装规格版本：只追加。
 * status: ACTIVE 当前生效 / SUPERSEDED 已被新版本取代。
 * 发布新版本时旧 ACTIVE 置 SUPERSEDED，旧版本内容与引用它的箱行永不改写。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_pack_spec_version")
public class PackSpecVersion extends BaseEntity {

    private String versionNo;

    private String title;

    private String remark;

    /** ACTIVE / SUPERSEDED */
    private String status;

    private String publishedBy;

    private LocalDateTime publishedAt;
}
