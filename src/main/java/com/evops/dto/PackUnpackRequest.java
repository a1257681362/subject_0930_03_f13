package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 拆箱回写：只回写包装状态与拆箱信息，
 * 发运前证据（规格快照/实测值/装箱照片）永不改写。
 */
@Data
public class PackUnpackRequest {

    /** INTACT 完好 / DAMAGED 受损 */
    @NotBlank(message = "拆箱包装状态不能为空")
    private String condition;

    @NotBlank(message = "拆箱人不能为空")
    private String unpackedBy;

    /** 拆箱说明（包装受损时必填） */
    private String unpackNote;
}
