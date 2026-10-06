package com.evops.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 装箱照片：与箱号绑定、只追加。
 * phase: PACK 装箱（发运前证据，拆箱后封存）/ UNPACK 拆箱。
 */
@Data
@TableName("t_pack_photo")
public class PackPhoto {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long boxId;

    /** 冗余箱号：照片与箱号绑定，按箱号直查 */
    private String boxNo;

    /** PACK / UNPACK */
    private String phase;

    private String photoUrl;

    private String photoName;

    private String contentHash;

    private String uploadedBy;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
