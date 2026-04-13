package com.ai.dingding.nlu;

import com.ai.dingding.handler.UserCommandHandler;
import com.ai.dingding.service.DingTalkMessageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for CommandRouter routing behavior.
 * Validates: Requirements 4.1-4.6
 */
@ExtendWith(MockitoExtension.class)
class CommandRouterTest {

    @Mock
    UserCommandHandler handler;

    @Mock
    DingTalkMessageService messageService;

    @InjectMocks
    CommandRouter commandRouter;

    // -------- helpers --------

    private ParseResult buildResult(String intent, Map<String, Object> params) {
        ParseResult result = new ParseResult();
        result.setIntent(intent);
        result.setParams(params);
        return result;
    }

    // -------- tests --------

    /**
     * Validates: Requirements 4.1
     * When intent is CREATE_USER, parseBatchCreateArgs and handleBatchCreate should be called.
     */
    @Test
    void route_createUser_callsHandleBatchCreate() {
        Map<String, Object> params = new HashMap<>();
        params.put("users", "张三 13800138000");
        ParseResult result = buildResult("CREATE_USER", params);

        List<String[]> userList = new java.util.ArrayList<>();
        userList.add(new String[]{"张三", "13800138000"});
        when(handler.parseBatchCreateArgs(any())).thenReturn(userList);

        commandRouter.route(result, "2", "conv123", "sender456");

        verify(handler).parseBatchCreateArgs(any());
        verify(handler).handleBatchCreate(any(), any(), any(), any());
    }

    /**
     * Validates: Requirements 4.2
     * When intent is SEARCH_USER, handleSearch should be called with the nickName param.
     */
    @Test
    void route_searchUser_callsHandleSearch() {
        Map<String, Object> params = new HashMap<>();
        params.put("nickName", "张三");
        ParseResult result = buildResult("SEARCH_USER", params);

        commandRouter.route(result, "2", "conv123", "sender456");

        verify(handler).handleSearch(eq("张三"), any(), any(), any());
    }

    /**
     * Validates: Requirements 4.3
     * When intent is EXPORT_EXAM with timeRange, handleExportExam should be called with that range.
     */
    @Test
    void route_exportExam_callsHandleExportExam() {
        Map<String, Object> params = new HashMap<>();
        params.put("timeRange", "近一周");
        ParseResult result = buildResult("EXPORT_EXAM", params);

        commandRouter.route(result, "2", "conv123", "sender456");

        verify(handler).handleExportExam(eq("近一周"), any(), any(), any());
    }

    /**
     * Validates: Requirements 4.4
     * When intent is STUDENT_STATS, handleStudentStats should be called with the timeRange param.
     */
    @Test
    void route_studentStats_callsHandleStudentStats() {
        Map<String, Object> params = new HashMap<>();
        params.put("timeRange", "近一个月");
        ParseResult result = buildResult("STUDENT_STATS", params);

        commandRouter.route(result, "2", "conv123", "sender456");

        verify(handler).handleStudentStats(eq("近一个月"), any(), any(), any());
    }

    /**
     * Validates: Requirements 4.5
     * When intent is HELP, buildHelpText and reply should be called.
     */
    @Test
    void route_help_repliesHelpText() {
        ParseResult result = buildResult("HELP", null);

        when(handler.buildHelpText()).thenReturn("help text");

        commandRouter.route(result, "2", "conv123", "sender456");

        verify(handler).buildHelpText();
        verify(handler).reply(any(), any(), any(), eq("help text"));
    }

    /**
     * Validates: Requirements 4.6
     * When intent is unknown, buildHelpText and reply should be called.
     */
    @Test
    void route_unknownIntent_repliesHelpText() {
        ParseResult result = buildResult("UNKNOWN_INTENT", null);

        when(handler.buildHelpText()).thenReturn("help text");

        commandRouter.route(result, "2", "conv123", "sender456");

        verify(handler).buildHelpText();
        verify(handler).reply(any(), any(), any(), eq("help text"));
    }
}
