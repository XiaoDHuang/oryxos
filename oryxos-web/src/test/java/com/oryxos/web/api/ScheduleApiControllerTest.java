package com.oryxos.web.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.oryxos.core.schedule.AgentScheduler;
import com.oryxos.core.schedule.ScheduledTaskStore;
import com.oryxos.core.schedule.ScheduledTaskView;
import com.oryxos.core.schedule.TaskExecutionView;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 定时任务管理端点契约:四操作的信封/分页/严格 PUT/404/400/504/500 语义,以及 executionId 字符串输出与非调度模式行为.
 *
 * @author OryxOS Contributors
 */
class ScheduleApiControllerTest {

  private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

  private final ScheduledTaskStore store = mock(ScheduledTaskStore.class);

  private final AgentScheduler scheduler = mock(AgentScheduler.class);

  @SuppressWarnings("unchecked")
  private final ObjectProvider<AgentScheduler> schedulerProvider = mock(ObjectProvider.class);

  private MockMvc mockMvc;

  private static final ScheduledTaskView TASK =
      new ScheduledTaskView(
          "morning-report",
          "ops",
          "0 0 9 * * *",
          "Asia/Shanghai",
          "生成日报",
          true,
          Instant.parse("2026-09-08T01:00:00Z"),
          null,
          null,
          3);

  @BeforeEach
  void setUp() {
    when(schedulerProvider.getIfAvailable()).thenReturn(scheduler);
    // standalone MockMvc 默认把 Instant 写成 epoch 数字;生产由 application.yaml 关闭,这里显式对齐
    com.fasterxml.jackson.databind.ObjectMapper mapper =
        org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(
                com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    mockMvc =
        MockMvcBuilders.standaloneSetup(new ScheduleApiController(store, schedulerProvider))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(
                new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
                    mapper))
            .build();
  }

  @Test
  @DisplayName("任务列表_全字段加available派生")
  void list_tasksWithAvailableFlag() throws Exception {
    when(store.listTasks()).thenReturn(List.of(TASK));
    when(scheduler.isRegistered("morning-report")).thenReturn(true);

    mockMvc
        .perform(get("/api/v1/schedules"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("SUCCESS"))
        .andExpect(jsonPath("$.data[0].taskId").value("morning-report"))
        .andExpect(jsonPath("$.data[0].profileName").value("ops"))
        .andExpect(jsonPath("$.data[0].cron").value("0 0 9 * * *"))
        .andExpect(jsonPath("$.data[0].zone").value("Asia/Shanghai"))
        .andExpect(jsonPath("$.data[0].message").value("生成日报"))
        .andExpect(jsonPath("$.data[0].enabled").value(true))
        .andExpect(jsonPath("$.data[0].nextRunAt").value("2026-09-08T01:00:00Z"))
        .andExpect(jsonPath("$.data[0].runCount").value(3))
        .andExpect(jsonPath("$.data[0].available").value(true));
  }

  @Test
  @DisplayName("非调度模式_列表与历史可读且available恒false")
  void nonSchedulerMode_readsAvailableFalse() throws Exception {
    when(schedulerProvider.getIfAvailable()).thenReturn(null);
    when(store.listTasks()).thenReturn(List.of(TASK));
    when(store.listExecutions("morning-report", 0, 20)).thenReturn(List.of());
    when(store.countExecutions("morning-report")).thenReturn(0L);

    mockMvc
        .perform(get("/api/v1/schedules"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].available").value(false));
    mockMvc.perform(get("/api/v1/schedules/morning-report/executions")).andExpect(status().isOk());
    // 非调度模式下的操作一律 400
    mockMvc
        .perform(post("/api/v1/schedules/morning-report/run"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    mockMvc
        .perform(
            put("/api/v1/schedules/morning-report")
                .contentType(JSON)
                .content("{\"enabled\":false}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("历史分页_信封齐全且executionId按字符串输出")
  void executions_pagedEnvelopeWithStringIds() throws Exception {
    TaskExecutionView view =
        new TaskExecutionView(
            42L,
            "morning-report",
            "scheduler:scheduler:ops",
            Instant.parse("2026-09-07T01:00:00Z"),
            true,
            null,
            1200L);
    when(store.listExecutions("morning-report", 0, 20)).thenReturn(List.of(view));
    when(store.countExecutions("morning-report")).thenReturn(1L);

    mockMvc
        .perform(get("/api/v1/schedules/morning-report/executions?page=0&size=20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.page").value(0))
        .andExpect(jsonPath("$.data.size").value(20))
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.content[0].executionId").isString())
        .andExpect(jsonPath("$.data.content[0].executionId").value("42"))
        .andExpect(jsonPath("$.data.content[0].success").value(true));
  }

  @Test
  @DisplayName("历史查询未知任务404_非法页参400")
  void executions_unknownTask404_badPage400() throws Exception {
    when(store.listExecutions(any(), anyInt(), anyInt()))
        .thenThrow(new NoSuchElementException("任务不存在: ghost"));
    mockMvc
        .perform(get("/api/v1/schedules/ghost/executions"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));

    org.mockito.Mockito.doThrow(new IllegalArgumentException("页码不能为负数: -1"))
        .when(store)
        .listExecutions(any(), anyInt(), anyInt());
    mockMvc
        .perform(get("/api/v1/schedules/morning-report/executions?page=-1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
  }

  @Test
  @DisplayName("立即执行_成功与业务失败都200,按success区分")
  void run_successAndBusinessFailureBoth200() throws Exception {
    TaskExecutionView ok =
        new TaskExecutionView(7L, "morning-report", "sid", Instant.now(), true, null, 900L);
    when(scheduler.runNow("morning-report")).thenReturn(ok);
    mockMvc
        .perform(asyncDispatch(startAsync()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.success").value(true))
        .andExpect(jsonPath("$.data.executionId").value("7"));

    TaskExecutionView failed =
        new TaskExecutionView(8L, "morning-report", "sid", Instant.now(), false, "工具执行失败", 1300L);
    when(scheduler.runNow("morning-report")).thenReturn(failed);
    mockMvc
        .perform(asyncDispatch(startAsync()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.success").value(false))
        .andExpect(jsonPath("$.data.errorMessage").value("工具执行失败"));
  }

  @Test
  @DisplayName("立即执行_超时504未知404失效400忙碌400基础设施500")
  void run_errorMatrix() throws Exception {
    when(scheduler.runNow("morning-report"))
        .thenReturn(
            new TaskExecutionView(
                9L, "morning-report", "sid", Instant.now(), false, "执行超时", 60000L));
    mockMvc
        .perform(asyncDispatch(startAsync()))
        .andExpect(status().isGatewayTimeout())
        .andExpect(jsonPath("$.errorCode").value("AGENT_TIMEOUT"));

    when(scheduler.runNow(any())).thenThrow(new NoSuchElementException("定时任务不存在: ghost"));
    mockMvc.perform(asyncDispatch(startAsync("ghost"))).andExpect(status().isNotFound());

    org.mockito.Mockito.doThrow(new IllegalArgumentException("定时任务当前规则失效,不可执行: x"))
        .when(scheduler)
        .runNow(any());
    mockMvc
        .perform(asyncDispatch(startAsync("stale")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));

    org.mockito.Mockito.doThrow(new RejectedExecutionException("定时任务正在执行中: morning-report"))
        .when(scheduler)
        .runNow(any());
    mockMvc
        .perform(asyncDispatch(startAsync()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));

    org.mockito.Mockito.doThrow(new IllegalStateException("定时任务终态记账失败: morning-report"))
        .when(scheduler)
        .runNow(any());
    mockMvc
        .perform(asyncDispatch(startAsync()))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
        .andExpect(jsonPath("$.message").value("服务器内部错误"));
  }

  @Test
  @DisplayName("启停_精确字段集校验_更新后视图返回")
  void toggle_strictFieldSet() throws Exception {
    ScheduledTaskView disabled =
        new ScheduledTaskView(
            "morning-report",
            "ops",
            "0 0 9 * * *",
            "Asia/Shanghai",
            "生成日报",
            false,
            null,
            null,
            null,
            3);
    when(scheduler.setEnabled("morning-report", false)).thenReturn(disabled);
    when(scheduler.isRegistered("morning-report")).thenReturn(true);

    mockMvc
        .perform(
            put("/api/v1/schedules/morning-report")
                .contentType(JSON)
                .content("{\"enabled\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.enabled").value(false))
        .andExpect(jsonPath("$.data.available").value(true));

    // 缺字段 / 类型错 / 额外字段 / cron 改写 一律 400
    for (String body :
        new String[] {
          "{}",
          "{\"enabled\":\"false\"}",
          "{\"enabled\":false,\"extra\":1}",
          "{\"enabled\":false,\"cron\":\"0 0 8 * * *\"}"
        }) {
      mockMvc
          .perform(put("/api/v1/schedules/morning-report").contentType(JSON).content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }
    verify(scheduler, org.mockito.Mockito.times(1)).setEnabled(any(), anyBoolean());
  }

  @Test
  @DisplayName("启停未知任务404_非调度模式400")
  void toggle_unknown404_noScheduler400() throws Exception {
    when(scheduler.setEnabled(any(), anyBoolean()))
        .thenThrow(new NoSuchElementException("定时任务不存在: ghost"));
    mockMvc
        .perform(put("/api/v1/schedules/ghost").contentType(JSON).content("{\"enabled\":false}"))
        .andExpect(status().isNotFound());

    // 非调度模式:不触碰调度器
    when(schedulerProvider.getIfAvailable()).thenReturn(null);
    org.mockito.Mockito.clearInvocations(scheduler);
    mockMvc
        .perform(
            put("/api/v1/schedules/morning-report").contentType(JSON).content("{\"enabled\":true}"))
        .andExpect(status().isBadRequest());
    verify(scheduler, never()).setEnabled(any(), anyBoolean());
  }

  @Test
  @DisplayName("含斜杠的id_按原样查找并报错,不会操作到错误任务")
  void slashInId_rejectedAs404() throws Exception {
    // 切片中 %2F 不解码、按单段 id 原样进 Store;真实容器对编码斜杠的拦截属人工验收项
    when(store.listExecutions(any(), anyInt(), anyInt()))
        .thenThrow(new NoSuchElementException("任务不存在: task%2Fevil"));
    mockMvc
        .perform(get("/api/v1/schedules/task%2Fevil/executions"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
  }

  private org.springframework.test.web.servlet.MvcResult startAsync() throws Exception {
    return startAsync("morning-report");
  }

  private org.springframework.test.web.servlet.MvcResult startAsync(String id) throws Exception {
    return mockMvc
        .perform(post("/api/v1/schedules/" + id + "/run"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.request()
                .asyncStarted())
        .andReturn();
  }
}
