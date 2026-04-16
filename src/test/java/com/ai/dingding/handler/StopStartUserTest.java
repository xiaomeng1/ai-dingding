package com.ai.dingding.handler;

import com.ai.dingding.license.LicenseChecker;
import com.ai.dingding.service.DingTalkMessageService;
import com.ai.dingding.service.SystemApiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import shade.com.alibaba.fastjson2.JSONObject;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 停用/启用用户功能单测，覆盖：
 *  - 单个/批量停用成功
 *  - 单个/批量启用成功
 *  - 用户不存在
 *  - 同名多用户
 *  - parseBatchNames 解析
 *  - parseBatchCreateArgs 智能地区合并
 */
class StopStartUserTest {

    @Mock SystemApiService systemApiService;
    @Mock DingTalkMessageService dingTalkMessageService;

    UserCommandHandler handler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // 无 NLU 模式，直接测 handler 方法
        handler = new UserCommandHandler(systemApiService, dingTalkMessageService);
    }

    // -------- parseBatchNames --------

    @Test
    void parseBatchNames_singleName() {
        List<String> names = handler.parseBatchNames("停用用户 张三", "停用用户");
        assertThat(names).containsExactly("张三");
    }

    @Test
    void parseBatchNames_multipleNames_commaSeparated() {
        List<String> names = handler.parseBatchNames("停用用户 张三,李四,王五", "停用用户");
        assertThat(names).containsExactly("张三", "李四", "王五");
    }

    @Test
    void parseBatchNames_chineseComma() {
        List<String> names = handler.parseBatchNames("启用用户 张三，李四", "启用用户");
        assertThat(names).containsExactly("张三", "李四");
    }

    @Test
    void parseBatchNames_emptyArg_returnsEmpty() {
        List<String> names = handler.parseBatchNames("停用用户 ", "停用用户");
        assertThat(names).isEmpty();
    }

    // -------- parseBatchCreateArgs（地区合并由 NLU 完成，此处只验证两段解析） --------

    @Test
    void parseBatchCreateArgs_normalParsing() {
        // NLU 已合并地区，传入的是"张三（五家渠） 13800138000"
        List<String[]> users = handler.parseBatchCreateArgs("创建用户 张三（五家渠） 13800138000");
        assertThat(users).hasSize(1);
        assertThat(users.get(0)[0]).isEqualTo("张三（五家渠）");
        assertThat(users.get(0)[1]).isEqualTo("13800138000");
    }

    @Test
    void parseBatchCreateArgs_withoutRegion_normalParsing() {
        List<String[]> users = handler.parseBatchCreateArgs("创建用户 张三 13800138000");
        assertThat(users).hasSize(1);
        assertThat(users.get(0)[0]).isEqualTo("张三");
        assertThat(users.get(0)[1]).isEqualTo("13800138000");
    }

    @Test
    void parseBatchCreateArgs_batch() {
        // NLU 已合并地区，批量格式
        List<String[]> users = handler.parseBatchCreateArgs(
                "创建用户 张三（五家渠） 13800138000,李四 13900139000");
        assertThat(users).hasSize(2);
        assertThat(users.get(0)[0]).isEqualTo("张三（五家渠）");
        assertThat(users.get(0)[1]).isEqualTo("13800138000");
        assertThat(users.get(1)[0]).isEqualTo("李四");
        assertThat(users.get(1)[1]).isEqualTo("13900139000");
    }

    // -------- handleStopUsers --------

    @Test
    void handleStopUsers_success_singleUser() {
        JSONObject user = buildUser(100L, "张三");
        when(systemApiService.searchUser("张三")).thenReturn(List.of(user));
        when(systemApiService.stopUser(100L)).thenReturn("{\"code\":200,\"msg\":\"操作成功\"}");

        handler.handleStopUsers(List.of("张三"), "2", "conv1", "sender1");

        verify(systemApiService).stopUser(100L);
        verify(dingTalkMessageService).sendTextMessage(eq("conv1"), contains("[成功]"));
        verify(dingTalkMessageService).sendTextMessage(eq("conv1"), contains("张三"));
    }

    @Test
    void handleStopUsers_success_multipleUsers() {
        JSONObject u1 = buildUser(101L, "张三");
        JSONObject u2 = buildUser(102L, "李四");
        when(systemApiService.searchUser("张三")).thenReturn(List.of(u1));
        when(systemApiService.searchUser("李四")).thenReturn(List.of(u2));
        when(systemApiService.stopUser(101L)).thenReturn("{\"code\":200,\"msg\":\"操作成功\"}");
        when(systemApiService.stopUser(102L)).thenReturn("{\"code\":200,\"msg\":\"操作成功\"}");

        handler.handleStopUsers(List.of("张三", "李四"), "2", "conv1", "sender1");

        verify(systemApiService).stopUser(101L);
        verify(systemApiService).stopUser(102L);
        verify(dingTalkMessageService).sendTextMessage(eq("conv1"), contains("共成功 2 / 2"));
    }

    @Test
    void handleStopUsers_userNotFound_reportsFailure() {
        when(systemApiService.searchUser("不存在")).thenReturn(List.of());

        handler.handleStopUsers(List.of("不存在"), "2", "conv1", "sender1");

        verify(systemApiService, never()).stopUser(anyLong());
        verify(dingTalkMessageService).sendTextMessage(eq("conv1"), contains("未找到该用户"));
    }

    @Test
    void handleStopUsers_duplicateUsers_reportsAmbiguous() {
        JSONObject u1 = buildUser(101L, "张三");
        JSONObject u2 = buildUser(102L, "张三2");
        when(systemApiService.searchUser("张三")).thenReturn(List.of(u1, u2));

        handler.handleStopUsers(List.of("张三"), "2", "conv1", "sender1");

        verify(systemApiService, never()).stopUser(anyLong());
        verify(dingTalkMessageService).sendTextMessage(eq("conv1"), contains("同名用户"));
    }

    @Test
    void handleStopUsers_apiFailure_reportsError() {
        JSONObject user = buildUser(100L, "张三");
        when(systemApiService.searchUser("张三")).thenReturn(List.of(user));
        when(systemApiService.stopUser(100L)).thenReturn("{\"code\":500,\"msg\":\"系统错误\"}");

        handler.handleStopUsers(List.of("张三"), "2", "conv1", "sender1");

        verify(dingTalkMessageService).sendTextMessage(eq("conv1"), contains("[失败]"));
        verify(dingTalkMessageService).sendTextMessage(eq("conv1"), contains("系统错误"));
    }

    // -------- handleStartUsers --------

    @Test
    void handleStartUsers_success() {
        JSONObject user = buildUser(200L, "王五");
        when(systemApiService.searchUser("王五")).thenReturn(List.of(user));
        when(systemApiService.startUser(200L)).thenReturn("{\"code\":200,\"msg\":\"操作成功\"}");

        handler.handleStartUsers(List.of("王五"), "2", "conv1", "sender1");

        verify(systemApiService).startUser(200L);
        verify(dingTalkMessageService).sendTextMessage(eq("conv1"), contains("[成功]"));
    }

    @Test
    void handleStartUsers_emptyNames_repliesError() {
        handler.handleStartUsers(List.of(), "2", "conv1", "sender1");

        verify(systemApiService, never()).startUser(anyLong());
        verify(dingTalkMessageService).sendTextMessage(eq("conv1"), contains("用户名不能为空"));
    }

    // -------- private chat (conversationType=1) --------

    @Test
    void handleStopUsers_privateChat_sendsPrivateMessage() {
        JSONObject user = buildUser(300L, "赵六");
        when(systemApiService.searchUser("赵六")).thenReturn(List.of(user));
        when(systemApiService.stopUser(300L)).thenReturn("{\"code\":200,\"msg\":\"操作成功\"}");

        handler.handleStopUsers(List.of("赵六"), "1", "conv1", "sender1");

        verify(dingTalkMessageService).sendPrivateTextMessage(eq("sender1"), contains("[成功]"));
        verify(dingTalkMessageService, never()).sendTextMessage(any(), any());
    }

    // -------- helpers --------

    private JSONObject buildUser(long userId, String nickName) {
        JSONObject u = new JSONObject();
        u.put("userId", userId);
        u.put("nickName", nickName);
        return u;
    }
}
