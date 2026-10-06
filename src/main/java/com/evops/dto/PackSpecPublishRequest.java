package com.evops.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 发布包装规格新版本：发布后旧版本置 SUPERSEDED 只读，永不改写；
 * 已装箱箱行与已签押运单固化的快照不受新版本影响。
 */
@Data
public class PackSpecPublishRequest {

    @NotBlank(message = "规格版本号不能为空")
    private String versionNo;

    @NotBlank(message = "规格标题不能为空")
    private String title;

    private String remark;

    @NotBlank(message = "发布人不能为空")
    private String publishedBy;

    @NotEmpty(message = "规格条目不能为空")
    @Valid
    private List<PackSpecItemRequest> items;
}
