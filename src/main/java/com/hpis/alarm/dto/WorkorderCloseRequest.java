package com.hpis.alarm.dto;

import lombok.Data;

/** 工单异常关闭请求。 */
@Data
public class WorkorderCloseRequest {
    private Long workorderId;
    private String handleResult;
    private String handlePicture;
}
