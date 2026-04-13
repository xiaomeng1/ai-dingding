package com.ai.dingding.handler;

import com.ai.dingding.license.LicenseChecker;
import com.ai.dingding.nlu.CommandRouter;
import com.ai.dingding.nlu.NluService;
import com.ai.dingding.nlu.ParseResult;
import com.ai.dingding.service.DingTalkMessageService;
import com.ai.dingding.service.SystemApiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserCommandHandler#handle(String)} NLU integration paths.
 *
 * Validates: Requirements 5.1, 5.3, 1.5
 */
class UserCommandHandlerTest {

    @Mock NluService nluService;
    @Mock CommandRouter commandRouter;
    @Mock SystemApiService systemApiService;
    @Mock DingTalkMessageService dingTalkMessageService;

    UserCommandHandler handler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        handler = new UserCommandHandler(systemApiService, dingTalkMessageService, nluService, commandRouter);
    }

    // -------- helpers --------

    private String buildMsg(String text) {
        return "{\"conversationId\":\"conv1\",\"conversationType\":\"2\",\"senderId\":\"sender1\","
                + "\"text\":{\"content\":\"" + text + "\"}}";
    }

    // -------- tests --------

    /**
     * Validates: Requirements 5.1
     * When NluService.parse() throws a RuntimeException, handle() should reply with
     * a degradation message containing "自然语言解析服务暂时不可用".
     */
    @Test
    void handle_nluException_repliesDegradationMessage() {
        when(nluService.parse(any())).thenThrow(new RuntimeException("NLU timeout"));

        try (MockedStatic<LicenseChecker> licenseCheckerMock = Mockito.mockStatic(LicenseChecker.class)) {
            licenseCheckerMock.when(LicenseChecker::isActive).thenReturn(true);

            handler.handle(buildMsg("任意消息"));
        }

        verify(dingTalkMessageService).sendTextMessage(any(), contains("自然语言解析服务暂时不可用"));
    }

    /**
     * Validates: Requirements 1.5
     * When NluService.parse() returns an unrecognized result with a hint,
     * handle() should reply with exactly that hint text.
     */
    @Test
    void handle_unrecognized_repliesHint() {
        when(nluService.parse(any())).thenReturn(ParseResult.ofUnrecognized("请提供更多信息"));

        try (MockedStatic<LicenseChecker> licenseCheckerMock = Mockito.mockStatic(LicenseChecker.class)) {
            licenseCheckerMock.when(LicenseChecker::isActive).thenReturn(true);

            handler.handle(buildMsg("模糊消息"));
        }

        verify(dingTalkMessageService).sendTextMessage(any(), contains("请提供更多信息"));
    }

    /**
     * Validates: Requirements 5.3
     * When the handler is created without an NluService (2-arg constructor),
     * handle() should reply with a message containing "NLU 功能未启用".
     */
    @Test
    void handle_nluServiceNull_repliesNluNotEnabled() {
        UserCommandHandler handlerNoNlu = new UserCommandHandler(systemApiService, dingTalkMessageService);

        try (MockedStatic<LicenseChecker> licenseCheckerMock = Mockito.mockStatic(LicenseChecker.class)) {
            licenseCheckerMock.when(LicenseChecker::isActive).thenReturn(true);

            handlerNoNlu.handle(buildMsg("任意消息"));
        }

        verify(dingTalkMessageService).sendTextMessage(any(), contains("NLU 功能未启用"));
    }
}
