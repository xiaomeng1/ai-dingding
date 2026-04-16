package com.ai.dingding.simulate;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 模拟测试用：拦截 DingTalkMessageService 发出的消息，存入队列供 SimulateController 读取。
 * 每次模拟请求前调用 {@link #begin()} 开启捕获，结束后调用 {@link #drain(int)} 取出所有回复。
 * 仅在 dev profile 下激活。
 */
@Profile("dev")
@Component
public class ReplyCapture {

    /** 是否处于捕获模式 */
    private volatile boolean capturing = false;

    private final BlockingQueue<ReplyItem> queue = new ArrayBlockingQueue<>(64);

    public void begin() {
        queue.clear();
        capturing = true;
    }

    public boolean isCapturing() {
        return capturing;
    }

    /** 记录一条文本回复 */
    public void addText(String target, String content) {
        if (capturing) {
            queue.offer(new ReplyItem("text", target, content, null));
        }
    }

    /** 记录一条文件回复 */
    public void addFile(String target, String fileName) {
        if (capturing) {
            queue.offer(new ReplyItem("file", target, null, fileName));
        }
    }

    /**
     * 等待并取出所有已捕获的回复，最多等待 {@code timeoutSeconds} 秒。
     * 策略：
     *   - 第一条消息：最多等 timeoutSeconds 秒（兼容异步任务）
     *   - 后续消息：滑动窗口 2 秒，避免每个用例都多等一个长超时
     * 调用后自动关闭捕获模式。
     */
    public List<ReplyItem> drain(int timeoutSeconds) throws InterruptedException {
        List<ReplyItem> result = new ArrayList<>();
        // 等第一条回复（最多 timeoutSeconds 秒）
        ReplyItem first = queue.poll(timeoutSeconds, TimeUnit.SECONDS);
        if (first != null) {
            result.add(first);
            // 后续消息用 2 秒短窗口收尾，避免空等一个长超时
            ReplyItem next;
            while ((next = queue.poll(5, TimeUnit.SECONDS)) != null) {
                result.add(next);
            }
        }
        capturing = false;
        return result;
    }

    public record ReplyItem(String type, String target, String text, String fileName) {}
}
