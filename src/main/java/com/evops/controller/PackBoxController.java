package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.PackBoxCreateRequest;
import com.evops.dto.PackPhotoBindRequest;
import com.evops.dto.PackShipRequest;
import com.evops.dto.PackUnpackRequest;
import com.evops.entity.PackBox;
import com.evops.entity.PackLedger;
import com.evops.entity.PackPhoto;
import com.evops.service.PackBoxService;
import com.evops.service.PackLedgerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文物装箱 / 照片绑定 / 发运 / 拆箱：
 * 装箱校验重量、外形、缓冲与防震并固化发运前证据；照片与箱号绑定只追加；
 * 拆箱回写包装状态但不覆盖发运前证据。
 */
@RestController
public class PackBoxController {

    private final PackBoxService boxService;
    private final PackLedgerService ledgerService;

    public PackBoxController(PackBoxService boxService, PackLedgerService ledgerService) {
        this.boxService = boxService;
        this.ledgerService = ledgerService;
    }

    /** 装箱登记（校验重量/外形/缓冲/防震，固化规格快照） */
    @PostMapping("/api/pack-boxes")
    public ApiResponse<PackBox> pack(@Valid @RequestBody PackBoxCreateRequest request) {
        return ApiResponse.ok(boxService.pack(request));
    }

    /** 箱列表（可按文物/协议过滤） */
    @GetMapping("/api/pack-boxes")
    public ApiResponse<List<PackBox>> list(@RequestParam(required = false) Long relicId,
                                           @RequestParam(required = false) Long agreementId) {
        return ApiResponse.ok(boxService.listBoxes(relicId, agreementId));
    }

    /** 箱详情：箱行（含发运前证据快照）+ 已绑定照片 */
    @GetMapping("/api/pack-boxes/{boxNo}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String boxNo) {
        PackBox box = boxService.requireByBoxNo(boxNo);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("box", box);
        detail.put("photos", boxService.listPhotos(box.getId()));
        return ApiResponse.ok(detail);
    }

    /** 绑定装箱/拆箱照片（与箱号绑定、只追加） */
    @PostMapping("/api/pack-boxes/{boxNo}/photos")
    public ApiResponse<PackPhoto> bindPhoto(@PathVariable String boxNo,
                                            @Valid @RequestBody PackPhotoBindRequest request) {
        return ApiResponse.ok(boxService.bindPhoto(boxNo, request));
    }

    /** 发运（发运前必须已有装箱照片） */
    @PostMapping("/api/pack-boxes/{boxNo}/ship")
    public ApiResponse<PackBox> ship(@PathVariable String boxNo,
                                     @Valid @RequestBody PackShipRequest request) {
        return ApiResponse.ok(boxService.ship(boxNo, request));
    }

    /** 拆箱回写包装状态（不覆盖发运前证据） */
    @PostMapping("/api/pack-boxes/{boxNo}/unpack")
    public ApiResponse<PackBox> unpack(@PathVariable String boxNo,
                                       @Valid @RequestBody PackUnpackRequest request) {
        return ApiResponse.ok(boxService.unpack(boxNo, request));
    }

    /** 箱台账：装箱/照片/发运/拆箱全程留痕 */
    @GetMapping("/api/pack-boxes/{boxNo}/ledger")
    public ApiResponse<List<PackLedger>> ledger(@PathVariable String boxNo) {
        PackBox box = boxService.requireByBoxNo(boxNo);
        return ApiResponse.ok(ledgerService.listByBox(box.getId()));
    }
}
