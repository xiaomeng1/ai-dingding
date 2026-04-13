package com.ai.dingding.nlu;

import java.util.Map;

/**
 * NLU 解析结果，封装意图、参数、是否识别失败及提示信息。
 */
public class ParseResult {

    private String intent;
    private Map<String, Object> params;
    private boolean unrecognized;
    private String hint;

    public ParseResult() {
    }

    /**
     * 创建一个"无法识别"的 ParseResult，携带提示信息。
     */
    public static ParseResult ofUnrecognized(String hint) {
        ParseResult r = new ParseResult();
        r.unrecognized = true;
        r.hint = hint;
        return r;
    }

    public String getIntent() {
        return intent;
    }

    public void setIntent(String intent) {
        this.intent = intent;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params;
    }

    public boolean isUnrecognized() {
        return unrecognized;
    }

    public void setUnrecognized(boolean unrecognized) {
        this.unrecognized = unrecognized;
    }

    public String getHint() {
        return hint;
    }

    public void setHint(String hint) {
        this.hint = hint;
    }
}
