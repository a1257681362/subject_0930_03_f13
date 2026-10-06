package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 发运登记：发运前必须已有与箱号绑定的装箱照片。
 */
@Data
public class PackShipRequest {

    @NotBlank(message = "发运人不能为空")
    private String shippedBy;
}
