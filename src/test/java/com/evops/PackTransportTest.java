package com.evops;

import com.evops.service.PackLedgerService;
import com.evops.service.PackNotifyScheduler;
import com.evops.service.RestorationNotifyException;
import com.evops.service.RestorationPackGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文物运输包装规格（museum-13）验收：
 * 1) 规格按尺寸/材质/缓冲等级/气候区版本化配置，发布新版本旧版本置 SUPERSEDED；
 * 2) 装箱逐箱校验文物重量、外形尺寸、缓冲等级与防震要求，包装材质必须一致；
 * 3) 规格变更不影响已装箱行与已签押运单（箱行固化规格版本与阈值快照）；
 * 4) 临时替代包装必须审批（不能自审自批）并记录差异，一单最多一箱；
 * 5) 装箱照片与箱号绑定、只追加，发运前必须有装箱照片；
 * 6) 拆箱回写包装状态但不覆盖发运前证据（快照/实测/装箱照片封存）；
 * 7) 修复系统外部依赖无响应时通知保持 PENDING 绝不假装成功，恢复后补送且不重复通知。
 * 使用独立内存库，不调用起步接口。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:museum-pack;DB_CLOSE_DELAY=-1",
        "museum.pack.notify-scan-interval-ms=3600000",
        "museum.pack.notify-scan-on-startup=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PackTransportTest {

    @MockBean
    private RestorationPackGateway gateway;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PackNotifyScheduler notifyScheduler;
    @Autowired
    private PackLedgerService packLedgerService;

    // ------------------------------------------------------------------
    // 一、规格版本化配置 + 装箱校验 + 照片绑定 + 拆箱回写 + 规格变更隔离
    // ------------------------------------------------------------------

    @Test
    @Order(1)
    void spec_versioning_and_boxing_rules_hold() throws Exception {
        long bronzeId = createRelic("PK-R-1", "运输测试青铜鼎", "青铜", "ONE");
        long paperId = createRelic("PK-R-2", "运输测试纸本画", "纸", "TWO");

        // ========== 发布规格 PV-1：按 材质+气候区 配置尺寸/承重/缓冲/防震 ==========
        publishSpec("PV-1", 20000, "L2", 5000, "L1");
        JsonNode active = dataOf(getJson("/api/pack-specs/active"));
        assertEquals("PV-1", active.get("version").get("versionNo").asText());
        assertEquals("ACTIVE", active.get("version").get("status").asText());
        assertEquals(2, active.get("items").size());
        assertEquals("SENT", active.get("notify").get("status").asText(),
                "外部依赖正常时发布通知应送达");
        assertTrue(active.get("notify").get("notifyKey").asText().startsWith("PACK_SPEC_PUBLISH:"));
        // 同版本号重复发布拒绝
        expectFail(postJson("/api/pack-specs/publish", specBody("PV-1", 20000, "L2", 5000, "L1")),
                "已存在");

        // ========== 装箱校验：重量 / 外形 / 缓冲 / 防震 / 包装材质 / 无覆盖条目 ==========
        // 超重：青铜 25000g > 上限 20000g
        expectFail(postJson("/api/pack-boxes",
                boxBody("BX-901", bronzeId, "TEMPERATE", 25000, 800, 400, 400,
                        "实木箱", "L2", true, null)), "超过包装承重上限");
        // 外形超限：长 1200mm > 上限 1000mm
        expectFail(postJson("/api/pack-boxes",
                boxBody("BX-901", bronzeId, "TEMPERATE", 15000, 1200, 400, 400,
                        "实木箱", "L2", true, null)), "超过包装规格上限");
        // 缓冲不足：要求 L2，实际 L1
        expectFail(postJson("/api/pack-boxes",
                boxBody("BX-901", bronzeId, "TEMPERATE", 15000, 800, 400, 400,
                        "实木箱", "L1", true, null)), "低于规格要求");
        // 防震未落实：青铜条目要求防震
        expectFail(postJson("/api/pack-boxes",
                boxBody("BX-901", bronzeId, "TEMPERATE", 15000, 800, 400, 400,
                        "实木箱", "L2", false, null)), "防震");
        // 包装材质不符：要求实木箱
        expectFail(postJson("/api/pack-boxes",
                boxBody("BX-901", bronzeId, "TEMPERATE", 15000, 800, 400, 400,
                        "纸箱", "L2", true, null)), "包装材质");
        // 无覆盖条目：青铜+COLD 无规格条目，须走临时替代
        expectFail(postJson("/api/pack-boxes",
                boxBody("BX-901", bronzeId, "COLD", 15000, 800, 400, 400,
                        "实木箱", "L2", true, null)), "临时替代包装");
        // 未知气候区 / 未知缓冲等级
        expectFail(postJson("/api/pack-boxes",
                boxBody("BX-901", bronzeId, "HOT", 15000, 800, 400, 400,
                        "实木箱", "L2", true, null)), "气候区");
        expectFail(postJson("/api/pack-boxes",
                boxBody("BX-901", bronzeId, "TEMPERATE", 15000, 800, 400, 400,
                        "实木箱", "L9", true, null)), "缓冲等级");

        // ========== 合规装箱 BX-001：缓冲 L3 高于要求 L2 允许 ==========
        expectOk(postJson("/api/pack-boxes",
                boxBody("BX-001", bronzeId, "TEMPERATE", 15000, 800, 400, 400,
                        "实木箱", "L3", true, null)));
        JsonNode bx1 = dataOf(getJson("/api/pack-boxes/BX-001")).get("box");
        assertEquals("PACKED", bx1.get("status").asText());
        assertEquals("PV-1", bx1.get("specVersionNo").asText());
        assertEquals(20000, bx1.get("snapMaxWeightG").asInt(), "校验依据快照随箱固化");
        assertEquals("L2", bx1.get("snapCushionLevel").asText());
        assertEquals("L3", bx1.get("usedCushionLevel").asText());
        assertEquals(1, bx1.get("snapShockproofRequired").asInt());
        assertEquals(1, bx1.get("shockproofApplied").asInt());
        assertEquals(15000, bx1.get("relicWeightG").asInt());
        // 箱号唯一
        expectFail(postJson("/api/pack-boxes",
                boxBody("BX-001", bronzeId, "TEMPERATE", 15000, 800, 400, 400,
                        "实木箱", "L3", true, null)), "箱号已存在");

        // ========== 装箱照片与箱号绑定、只追加 ==========
        expectOk(postJson("/api/pack-boxes/BX-001/photos",
                photoBody("PACK", "hash-bx1-a", "装箱人甲")));
        // 同箱同阶段同内容照片不重复绑定
        expectFail(postJson("/api/pack-boxes/BX-001/photos",
                photoBody("PACK", "hash-bx1-a", "装箱人甲")), "重复绑定");
        // 未拆箱不能绑拆箱照片
        expectFail(postJson("/api/pack-boxes/BX-001/photos",
                photoBody("UNPACK", "hash-bx1-u", "拆箱人乙")), "尚未拆箱");
        // 未知箱号
        expectFail(postJson("/api/pack-boxes/BX-404/photos",
                photoBody("PACK", "hash-x", "装箱人甲")), "不存在");

        // ========== 发运前必须有装箱照片 ==========
        expectOk(postJson("/api/pack-boxes",
                boxBody("BX-002", paperId, "TEMPERATE", 4000, 700, 500, 200,
                        "纸箱", "L1", false, null)));
        expectFail(postJson("/api/pack-boxes/BX-002/ship", "{\"shippedBy\":\"押运人丙\"}"),
                "装箱照片");
        expectOk(postJson("/api/pack-boxes/BX-002/photos",
                photoBody("PACK", "hash-bx2-a", "装箱人甲")));
        expectOk(postJson("/api/pack-boxes/BX-002/ship", "{\"shippedBy\":\"押运人丙\"}"));
        assertEquals("SHIPPED", dataOf(getJson("/api/pack-boxes/BX-002")).get("box")
                .get("status").asText());

        // ========== 拆箱回写：只写包装状态，不覆盖发运前证据 ==========
        // 未发运不能拆箱
        expectFail(postJson("/api/pack-boxes/BX-001/unpack",
                "{\"condition\":\"INTACT\",\"unpackedBy\":\"拆箱人乙\"}"), "不能拆箱");
        expectOk(postJson("/api/pack-boxes/BX-001/ship", "{\"shippedBy\":\"押运人丙\"}"));
        // 受损必须填说明
        expectFail(postJson("/api/pack-boxes/BX-001/unpack",
                "{\"condition\":\"DAMAGED\",\"unpackedBy\":\"拆箱人乙\"}"), "受损必须填写");
        expectOk(postJson("/api/pack-boxes/BX-001/unpack",
                "{\"condition\":\"INTACT\",\"unpackedBy\":\"拆箱人乙\",\"unpackNote\":\"外包装完好\"}"));
        JsonNode bx1After = dataOf(getJson("/api/pack-boxes/BX-001")).get("box");
        assertEquals("UNPACKED", bx1After.get("status").asText());
        assertEquals("INTACT", bx1After.get("unpackCondition").asText());
        assertEquals("拆箱人乙", bx1After.get("unpackedBy").asText());
        // 发运前证据未被覆盖：快照/实测/装箱人原样保留
        assertEquals(20000, bx1After.get("snapMaxWeightG").asInt());
        assertEquals(15000, bx1After.get("relicWeightG").asInt());
        assertEquals("L3", bx1After.get("usedCushionLevel").asText());
        assertEquals("装箱人甲", bx1After.get("packedBy").asText());
        assertNotNull(bx1After.get("packedAt").asText());
        // 拆箱后装箱证据封存：不能再补装箱照片，可补拆箱照片
        expectFail(postJson("/api/pack-boxes/BX-001/photos",
                photoBody("PACK", "hash-bx1-late", "装箱人甲")), "封存");
        expectOk(postJson("/api/pack-boxes/BX-001/photos",
                photoBody("UNPACK", "hash-bx1-u", "拆箱人乙")));
        JsonNode bx1Photos = dataOf(getJson("/api/pack-boxes/BX-001")).get("photos");
        assertEquals(2, bx1Photos.size(), "装箱+拆箱照片均与箱号绑定可查");
        for (JsonNode p : bx1Photos) {
            assertEquals("BX-001", p.get("boxNo").asText(), "照片与箱号绑定");
        }

        // ========== 已签押运单装箱 + 规格变更不影响已签押运单 ==========
        long agreementId = dataOf(postJson("/api/loan-agreements", "{"
                + "\"agreementNo\":\"LA-PK-1\",\"relicId\":" + bronzeId + ","
                + "\"borrower\":\"省博物馆\",\"insuranceAmount\":500000,"
                + "\"transportRoute\":\"本馆-省博\",\"returnDate\":\"2026-12-31\","
                + "\"items\":[{\"itemNo\":\"I-1\",\"itemName\":\"青铜鼎\",\"qty\":1}]}")).get("id").asLong();
        expectOk(postJson("/api/loan-agreements/" + agreementId + "/sign",
                "{\"signedBy\":\"馆长\"}"));
        // 未签押运单不能装箱：另登记一份未签协议验证拦截
        long paperAgreementId = dataOf(postJson("/api/loan-agreements", "{"
                + "\"agreementNo\":\"LA-PK-2\",\"relicId\":" + paperId + ","
                + "\"borrower\":\"市博物馆\",\"insuranceAmount\":80000,"
                + "\"transportRoute\":\"本馆-市博\",\"returnDate\":\"2026-11-30\","
                + "\"items\":[{\"itemNo\":\"I-1\",\"itemName\":\"纸本画\",\"qty\":1}]}")).get("id").asLong();
        expectFail(postJson("/api/pack-boxes",
                boxBodyWithAgreement("BX-903", paperId, paperAgreementId, "TEMPERATE",
                        4000, 700, 500, 200, "纸箱", "L1", false, null)), "尚未签署生效");
        // 已签押运单装箱 BX-003（PV-1 口径）
        expectOk(postJson("/api/pack-boxes",
                boxBodyWithAgreement("BX-003", bronzeId, agreementId, "TEMPERATE",
                        18000, 900, 450, 450, "实木箱", "L2", true, null)));
        JsonNode bx3 = dataOf(getJson("/api/pack-boxes/BX-003")).get("box");
        assertEquals("PV-1", bx3.get("specVersionNo").asText());
        assertEquals(agreementId, bx3.get("agreementId").asLong());

        // 发布 PV-2：青铜承重降到 12000、缓冲升到 L3；纸不变
        publishSpec("PV-2", 12000, "L3", 5000, "L1");
        JsonNode versions = dataOf(getJson("/api/pack-specs"));
        assertEquals("PV-2", versions.get(0).get("version").get("versionNo").asText());
        assertEquals("SUPERSEDED", versions.get(1).get("version").get("status").asText(),
                "旧版本置 SUPERSEDED 只读");

        // 已签押运单的箱行仍是 PV-1 快照，不被新版本改写
        JsonNode bx3After = dataOf(getJson("/api/pack-boxes/BX-003")).get("box");
        assertEquals("PV-1", bx3After.get("specVersionNo").asText());
        assertEquals(20000, bx3After.get("snapMaxWeightG").asInt(),
                "规格变更不改写已签押运单箱行的校验快照");
        assertEquals("L2", bx3After.get("snapCushionLevel").asText());
        // 已装箱的 BX-001 同样保持 PV-1 快照
        JsonNode bx1Final = dataOf(getJson("/api/pack-boxes/BX-001")).get("box");
        assertEquals("PV-1", bx1Final.get("specVersionNo").asText());
        assertEquals(20000, bx1Final.get("snapMaxWeightG").asInt());
        // 押运流程不受规格变更影响：封箱节点照常登记
        expectOk(postJson("/api/loan-agreements/" + agreementId + "/escort-nodes",
                "{\"nodeType\":\"SEAL\",\"handler\":\"押运人丙\"}"));

        // 新装箱按 PV-2 口径校验：青铜 15000g 超过新上限 12000g
        expectFail(postJson("/api/pack-boxes",
                boxBody("BX-904", bronzeId, "TEMPERATE", 15000, 800, 400, 400,
                        "实木箱", "L3", true, null)), "超过包装承重上限");

        // ========== 台账全程留痕 ==========
        JsonNode bx1Ledger = dataOf(getJson("/api/pack-boxes/BX-001/ledger"));
        assertTrue(hasAction(bx1Ledger, "PACK_BOX"), "装箱留痕");
        assertTrue(hasAction(bx1Ledger, "PACK_PHOTO_BIND"), "照片绑定留痕");
        assertTrue(hasAction(bx1Ledger, "PACK_BOX_SHIP"), "发运留痕");
        assertTrue(hasAction(bx1Ledger, "PACK_BOX_UNPACK"), "拆箱回写留痕");
    }

    // ------------------------------------------------------------------
    // 二、临时替代包装：必须审批并记录差异
    // ------------------------------------------------------------------

    @Test
    @Order(2)
    void substitute_requires_approval_and_records_diff() throws Exception {
        long jadeId = createRelic("PK-S-1", "运输测试玉璧", "玉", "ONE");

        // 生效规格无 玉+TEMPERATE 条目：直接装箱拒绝，须先申请替代
        expectFail(postJson("/api/pack-boxes",
                boxBody("BX-S01", jadeId, "TEMPERATE", 25000, 1100, 500, 500,
                        "复合减震箱", "L3", true, null)), "临时替代包装");

        // ========== 申请替代：记录替代参数与差异说明 ==========
        long subId = dataOf(postJson("/api/pack-substitutes", "{"
                + "\"substituteNo\":\"SUB-1\",\"relicId\":" + jadeId + ","
                + "\"climateZone\":\"TEMPERATE\","
                + "\"altPackMaterial\":\"复合减震箱\",\"altCushionLevel\":\"L3\","
                + "\"altMaxLengthMm\":1200,\"altMaxWidthMm\":600,\"altMaxHeightMm\":600,"
                + "\"altMaxWeightG\":30000,\"altShockproof\":true,"
                + "\"diffNote\":\"标准实木箱无大尺寸现货，以复合减震箱替代，缓冲由L2升至L3并加防震棉\","
                + "\"reason\":\"玉璧超出标准箱尺寸档，发运档期紧张\","
                + "\"requestedBy\":\"张三\"}")).get("id").asLong();
        assertEquals("PENDING", dataOf(getJson("/api/pack-substitutes/" + subId))
                .get("status").asText());
        // 替代单号唯一
        expectFail(postJson("/api/pack-substitutes", "{"
                + "\"substituteNo\":\"SUB-1\",\"relicId\":" + jadeId + ","
                + "\"climateZone\":\"TEMPERATE\","
                + "\"altPackMaterial\":\"复合减震箱\",\"altCushionLevel\":\"L3\","
                + "\"altMaxLengthMm\":1200,\"altMaxWidthMm\":600,\"altMaxHeightMm\":600,"
                + "\"altMaxWeightG\":30000,\"altShockproof\":true,"
                + "\"diffNote\":\"重复单号\",\"reason\":\"重复\",\"requestedBy\":\"张三\"}"), "已存在");

        // ========== 未审批不能装箱；不能自审自批 ==========
        expectFail(postJson("/api/pack-boxes",
                boxBody("BX-S01", jadeId, "TEMPERATE", 25000, 1100, 500, 500,
                        "复合减震箱", "L3", true, subId)), "未审批通过");
        expectFail(postJson("/api/pack-substitutes/" + subId + "/approve",
                "{\"decidedBy\":\"张三\"}"), "自审自批");
        expectOk(postJson("/api/pack-substitutes/" + subId + "/approve",
                "{\"decidedBy\":\"李四\",\"decisionNote\":\"同意替代，注意防震棉厚度\"}"));
        expectFail(postJson("/api/pack-substitutes/" + subId + "/approve",
                "{\"decidedBy\":\"王五\"}"), "不能重复审批");

        // ========== 装箱引用已批替代单：实际参数必须与批准参数一致 ==========
        // 材质与批准的替代材质不符
        expectFail(postJson("/api/pack-boxes",
                boxBody("BX-S01", jadeId, "TEMPERATE", 25000, 1100, 500, 500,
                        "实木箱", "L3", true, subId)), "包装材质");
        // 超过替代单承重上限
        expectFail(postJson("/api/pack-boxes",
                boxBody("BX-S01", jadeId, "TEMPERATE", 31000, 1100, 500, 500,
                        "复合减震箱", "L3", true, subId)), "超过包装承重上限");
        // 合规装箱：差异说明随箱固化
        expectOk(postJson("/api/pack-boxes",
                boxBody("BX-S01", jadeId, "TEMPERATE", 25000, 1100, 500, 500,
                        "复合减震箱", "L3", true, subId)));
        JsonNode subBox = dataOf(getJson("/api/pack-boxes/BX-S01")).get("box");
        assertEquals(subId, subBox.get("substituteId").asLong());
        assertEquals("复合减震箱", subBox.get("snapPackMaterial").asText());
        assertEquals("L3", subBox.get("snapCushionLevel").asText());
        assertEquals(30000, subBox.get("snapMaxWeightG").asInt());
        assertTrue(subBox.get("packDiffNote").asText().contains("复合减震箱替代"),
                "替代差异说明随箱行固化留痕");
        assertTrue(subBox.get("specItemId").isNull(), "无标准条目时 specItemId 为空");

        // 一张替代单最多被一只箱引用
        expectFail(postJson("/api/pack-boxes",
                boxBody("BX-S02", jadeId, "TEMPERATE", 25000, 1100, 500, 500,
                        "复合减震箱", "L3", true, subId)), "已被其他箱引用");

        // ========== 驳回流程：驳回意见必填，已驳回不能装箱 ==========
        long sub2Id = dataOf(postJson("/api/pack-substitutes", "{"
                + "\"substituteNo\":\"SUB-2\",\"relicId\":" + jadeId + ","
                + "\"climateZone\":\"TEMPERATE\","
                + "\"altPackMaterial\":\"普通木箱\",\"altCushionLevel\":\"L1\","
                + "\"altMaxLengthMm\":1200,\"altMaxWidthMm\":600,\"altMaxHeightMm\":600,"
                + "\"altMaxWeightG\":30000,\"altShockproof\":false,"
                + "\"diffNote\":\"以普通木箱替代且不带防震\",\"reason\":\"节约成本\","
                + "\"requestedBy\":\"张三\"}")).get("id").asLong();
        expectFail(postJson("/api/pack-substitutes/" + sub2Id + "/reject",
                "{\"decidedBy\":\"李四\"}"), "驳回必须填写审批意见");
        expectOk(postJson("/api/pack-substitutes/" + sub2Id + "/reject",
                "{\"decidedBy\":\"李四\",\"decisionNote\":\"一级文物不得降低缓冲与防震要求\"}"));
        assertEquals("REJECTED", dataOf(getJson("/api/pack-substitutes/" + sub2Id))
                .get("status").asText());
        expectFail(postJson("/api/pack-boxes",
                boxBody("BX-S03", jadeId, "TEMPERATE", 25000, 1100, 500, 500,
                        "普通木箱", "L1", false, sub2Id)), "未审批通过");

        // 替代装箱台账应标明替代单与差异
        boolean substituteLedgerFound = false;
        for (com.evops.entity.PackLedger e : packLedgerService.listByBox(
                dataOf(getJson("/api/pack-boxes/BX-S01")).get("box").get("id").asLong())) {
            if ("PACK_BOX".equals(e.getAction()) && e.getSummary().contains("临时替代单")) {
                substituteLedgerFound = true;
            }
        }
        assertTrue(substituteLedgerFound, "替代装箱台账应标明替代单与差异");
    }

    // ------------------------------------------------------------------
    // 三、修复系统外部依赖：无响应不假装成功，恢复后不重复通知
    // ------------------------------------------------------------------

    @Test
    @Order(3)
    void restoration_notify_failure_and_recovery() throws Exception {
        // ========== 外部依赖无响应：规格发布不假装成功 ==========
        doThrow(new RestorationNotifyException("连接超时，文物修复系统无响应"))
                .when(gateway).notifySpecPublished(any());

        JsonNode published = dataOf(postJson("/api/pack-specs/publish",
                specBody("PV-FAIL-1", 20000, "L2", 5000, "L1")));
        assertEquals("PV-FAIL-1", published.get("version").get("versionNo").asText());
        assertEquals("PENDING", published.get("notify").get("status").asText(),
                "外部依赖无响应时通知必须保持待送达，绝不标记成功");
        assertTrue(published.get("notify").get("lastError").asText().contains("无响应"));
        assertTrue(published.get("notifyWarning").asText().contains("未送达"),
                "响应必须如实提示通知未送达");
        assertEquals(1, published.get("notify").get("attemptCount").asInt());

        // 生效规格可查，但送达状态诚实暴露为 PENDING
        JsonNode active = dataOf(getJson("/api/pack-specs/active"));
        assertEquals("PV-FAIL-1", active.get("version").get("versionNo").asText());
        assertEquals("PENDING", active.get("notify").get("status").asText());
        assertTrue(active.get("notify").get("sentAt").isNull(), "未送达时送达时间必须为空");

        // 依赖仍无响应：补扫不送达、不假装成功，尝试次数如实累加
        assertEquals(0, notifyScheduler.scanOnce());
        JsonNode stillPending = dataOf(getJson("/api/pack-specs/active"));
        assertEquals("PENDING", stillPending.get("notify").get("status").asText());
        assertEquals(2, stillPending.get("notify").get("attemptCount").asInt());

        // ========== 依赖恢复：补送送达，只送达一次 ==========
        Mockito.reset(gateway); // 解除故障：默认空实现即送达成功
        assertEquals(1, notifyScheduler.scanOnce(), "恢复后补扫应送达待通知");
        JsonNode sent = dataOf(getJson("/api/pack-specs/active"));
        assertEquals("SENT", sent.get("notify").get("status").asText());
        assertNotNull(sent.get("notify").get("sentAt"));
        assertEquals(3, sent.get("notify").get("attemptCount").asInt());

        // 恢复后不重复通知：再次补扫零送达、零调用
        Mockito.reset(gateway);
        assertEquals(0, notifyScheduler.scanOnce(), "已送达通知不得重复补送");
        verify(gateway, never()).notifySpecPublished(any());

        // 送达事实写台账（仅一条 PACK_NOTIFY_SENT）
        long sentCount = packLedgerService.listBySpecVersion(
                sent.get("version").get("id").asLong()).stream()
                .filter(e -> "PACK_NOTIFY_SENT".equals(e.getAction()))
                .count();
        assertEquals(1, sentCount, "通知送达台账只留一条，不重复");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 发布规格版本：青铜+纸 各一条 TEMPERATE 条目 */
    private void publishSpec(String versionNo, int bronzeMaxWeight, String bronzeCushion,
                             int paperMaxWeight, String paperCushion) throws Exception {
        expectOk(postJson("/api/pack-specs/publish",
                specBody(versionNo, bronzeMaxWeight, bronzeCushion, paperMaxWeight, paperCushion)));
    }

    private String specBody(String versionNo, int bronzeMaxWeight, String bronzeCushion,
                            int paperMaxWeight, String paperCushion) {
        return "{"
                + "\"versionNo\":\"" + versionNo + "\",\"title\":\"运输包装规格" + versionNo + "\","
                + "\"publishedBy\":\"包装管理员\",\"items\":["
                + "{\"material\":\"青铜\",\"climateZone\":\"TEMPERATE\","
                + "\"maxLengthMm\":1000,\"maxWidthMm\":500,\"maxHeightMm\":500,"
                + "\"maxWeightG\":" + bronzeMaxWeight + ",\"cushionLevel\":\"" + bronzeCushion + "\","
                + "\"packMaterial\":\"实木箱\",\"shockproofRequired\":true},"
                + "{\"material\":\"纸\",\"climateZone\":\"TEMPERATE\","
                + "\"maxLengthMm\":800,\"maxWidthMm\":600,\"maxHeightMm\":300,"
                + "\"maxWeightG\":" + paperMaxWeight + ",\"cushionLevel\":\"" + paperCushion + "\","
                + "\"packMaterial\":\"纸箱\",\"shockproofRequired\":false}"
                + "]}";
    }

    private String boxBody(String boxNo, long relicId, String climateZone,
                           int weightG, int len, int wid, int hei,
                           String packMaterial, String cushion, boolean shockproof,
                           Long substituteId) {
        return boxBodyWithAgreement(boxNo, relicId, null, climateZone,
                weightG, len, wid, hei, packMaterial, cushion, shockproof, substituteId);
    }

    private String boxBodyWithAgreement(String boxNo, long relicId, Long agreementId,
                                        String climateZone, int weightG, int len, int wid, int hei,
                                        String packMaterial, String cushion, boolean shockproof,
                                        Long substituteId) {
        return "{"
                + "\"boxNo\":\"" + boxNo + "\",\"relicId\":" + relicId
                + (agreementId == null ? "" : ",\"agreementId\":" + agreementId)
                + ",\"climateZone\":\"" + climateZone + "\","
                + "\"relicWeightG\":" + weightG + ",\"relicLengthMm\":" + len
                + ",\"relicWidthMm\":" + wid + ",\"relicHeightMm\":" + hei + ","
                + "\"usedPackMaterial\":\"" + packMaterial + "\","
                + "\"usedCushionLevel\":\"" + cushion + "\","
                + "\"shockproofApplied\":" + shockproof
                + (substituteId == null ? "" : ",\"substituteId\":" + substituteId)
                + ",\"packedBy\":\"装箱人甲\"}";
    }

    private String photoBody(String phase, String contentHash, String uploadedBy) {
        return "{\"phase\":\"" + phase + "\",\"photoUrl\":\"pack/" + contentHash + ".jpg\","
                + "\"photoName\":\"" + contentHash + ".jpg\",\"contentHash\":\"" + contentHash + "\","
                + "\"uploadedBy\":\"" + uploadedBy + "\"}";
    }

    private boolean hasAction(JsonNode ledger, String action) {
        for (JsonNode e : ledger) {
            if (action.equals(e.get("action").asText())) {
                return true;
            }
        }
        return false;
    }

    private long createRelic(String no, String name, String material, String grade) throws Exception {
        return dataOf(postJson("/api/relics", "{"
                + "\"relicNo\":\"" + no + "\",\"name\":\"" + name + "\",\"category\":\"杂项\","
                + "\"material\":\"" + material + "\",\"era\":\"宋代\",\"source\":\"考古移交\","
                + "\"preservationGrade\":\"" + grade + "\"}")).get("id").asLong();
    }

    private MvcResult postJson(String uri, String body) throws Exception {
        return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult getJson(String uri) throws Exception {
        return mockMvc.perform(get(uri)).andExpect(status().isOk()).andReturn();
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("data");
    }

    private void expectOk(MvcResult result) throws Exception {
        JsonNode root = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertTrue(root.get("success").asBoolean(),
                "期望成功，实际: " + root.get("message").asText());
    }

    private void expectFail(MvcResult result, String messageContains) throws Exception {
        JsonNode root = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertFalse(root.get("success").asBoolean());
        assertTrue(root.get("message").asText().contains(messageContains),
                "错误信息应包含「" + messageContains + "」，实际: " + root.get("message").asText());
    }
}
