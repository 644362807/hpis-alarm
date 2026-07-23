package com.hpis.alarm.dto;

import lombok.Data;

/** 工单正常完成请求，只接收由客户端填写的处理结果。 */
@Data
public class WorkorderCompleteRequest {
    private Long workorderId;
    private String handleResult;
    private String handlePicture;
}
