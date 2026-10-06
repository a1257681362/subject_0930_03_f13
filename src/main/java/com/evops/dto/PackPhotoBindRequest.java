package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 装箱照片绑定：照片与箱号绑定、只追加。
 * phase: PACK 装箱（发运前证据，拆箱后封存）/ UNPACK 拆箱。
 */
@Data
public class PackPhotoBindRequest {

    /** PACK / UNPACK */
    @NotBlank(message = "照片阶段不能为空")
    private String phase;

    @NotBlank(message = "照片地址不能为空")
    private String photoUrl;

    private String photoName;

    @NotBlank(message = "照片内容摘要不能为空")
    private String contentHash;

    @NotBlank(message = "上传人不能为空")
    private String uploadedBy;

    private String remark;
}
