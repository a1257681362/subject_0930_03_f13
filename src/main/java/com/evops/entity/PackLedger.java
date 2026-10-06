package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 包装台账：只追加、不修改、不删除。
 * 规格发布/装箱/替代申请审批/照片绑定/发运/拆箱/通知送达全部留痕。
 */
@Data
@TableName("t_pack_ledger")
public class PackLedger {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long boxId;

    private Long specVersionId;

    private Long substituteId;

    private String action;

    private String bizNo;

    private String summary;

    private LocalDateTime occurredAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
