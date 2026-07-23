package com.hpis.alarm.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hpis.alarm.domain.AlarmWorkorder;
import com.hpis.alarm.service.IAlarmWorkorderService;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

public class AlarmWorkorderControllerTest {

    private IAlarmWorkorderService service;
    private MockMvc mockMvc;

    @Before
    public void setUp() {
        service = mock(IAlarmWorkorderService.class, invocation -> {
            if (Page.class.isAssignableFrom(invocation.getMethod().getReturnType())) {
                return new Page<AlarmWorkorder>(1, 20);
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        AlarmWorkorderController controller = new AlarmWorkorderController();
        ReflectionTestUtils.setField(controller, "alarmWorkorderService", service);
        mockMvc = standaloneSetup(controller).build();
    }

    @Test
    public void exposesTenantAllAndMyWorkorderQueries() throws Exception {
        mockMvc.perform(get("/workorder/list").param("pageNum", "1").param("pageSize", "20"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/workorder/my").param("pageNum", "1").param("pageSize", "20"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/workorder/my/300"))
                .andExpect(status().isOk());
    }

    @Test
    public void completeBoundaryIgnoresClientOwnedAlarmStateAndAssigneeFields() throws Exception {
        mockMvc.perform(put("/workorder/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workorderId\":300,\"handleResult\":\"现场已处理\",\"handlePicture\":\"/upload/a.jpg\","
                                + "\"alarmId\":999,\"status\":\"3\",\"assigneeId\":888}"))
                .andExpect(status().isOk());

        ArgumentCaptor<AlarmWorkorder> captor = ArgumentCaptor.forClass(AlarmWorkorder.class);
        verify(service).completeWorkorder(captor.capture());
        assertNull(captor.getValue().getAlarmId());
        assertNull(captor.getValue().getStatus());
        assertNull(captor.getValue().getAssigneeId());
    }

    @Test
    public void exposesAbnormalCloseEndpoint() throws Exception {
        mockMvc.perform(put("/workorder/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workorderId\":300,\"handleResult\":\"重复报警，异常关闭\"}"))
                .andExpect(status().isOk());
    }
}
