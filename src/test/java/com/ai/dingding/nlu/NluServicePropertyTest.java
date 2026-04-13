package com.ai.dingding.nlu;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import shade.com.alibaba.fastjson2.JSON;
import shade.com.alibaba.fastjson2.JSONObject;

import java.util.Map;
import java.util.Set;

/**
 * Property-based tests for NluService.
 */
class NluServicePropertyTest {

    private static final Set<String> VALID_INTENTS =
            Set.of("CREATE_USER", "SEARCH_USER", "EXPORT_EXAM", "STUDENT_STATS", "HELP");

    // -----------------------------------------------------------------------
    // Helper: replicates NluService's JSON parsing logic without BaiLianClient
    // -----------------------------------------------------------------------
    private ParseResult parseJson(String input) {
        try {
            String cleaned = NluService.stripMarkdownCodeBlock(input);
            JSONObject obj = JSON.parseObject(cleaned);
            if (obj == null) return ParseResult.ofUnrecognized(input);
            if (obj.getBooleanValue("unrecognized")) {
                return ParseResult.ofUnrecognized(obj.getString("hint"));
            }
            String intent = obj.getString("intent");
            ParseResult r = new ParseResult();
            r.setIntent(intent);
            r.setParams(obj.getObject("params", java.util.Map.class));
            return r;
        } catch (Exception e) {
            return ParseResult.ofUnrecognized(input);
        }
    }

    // -----------------------------------------------------------------------
    // Helper: check whether a string is valid JSON
    // -----------------------------------------------------------------------
    private boolean isValidJson(String s) {
        if (s == null || s.isBlank()) return false;
        try {
            JSONObject obj = JSON.parseObject(s);
            return obj != null;
        } catch (Exception e) {
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Property 2: Markdown 标记清洗等价性
    // Validates: Requirements 3.6, 6.3
    // -----------------------------------------------------------------------
    // Feature: natural-language-command, Property 2: Markdown 标记清洗等价性
    @Property(tries = 100)
    void markdownStrippingIdempotent(
            @ForAll @StringLength(min = 1, max = 20) String key,
            @ForAll @StringLength(min = 0, max = 20) String value) {

        // Build a simple JSON object string
        String original = "{\"" + escapeJson(key) + "\": \"" + escapeJson(value) + "\"}";

        String wrapped = "```json\n" + original + "\n```";

        String strippedOriginal = NluService.stripMarkdownCodeBlock(original);
        String strippedWrapped  = NluService.stripMarkdownCodeBlock(wrapped);

        assert strippedOriginal.equals(strippedWrapped)
                : "Expected stripped original [" + strippedOriginal + "] to equal stripped wrapped [" + strippedWrapped + "]";
    }

    // -----------------------------------------------------------------------
    // Property 3: 解析结果意图合法性
    // Validates: Requirements 1.4, 6.1
    // -----------------------------------------------------------------------
    // Feature: natural-language-command, Property 3: 解析结果意图合法性
    @Property(tries = 100)
    void parsedIntentIsAlwaysValid(@ForAll String input) {
        ParseResult result = parseJson(input);

        boolean intentValid = result.isUnrecognized()
                || VALID_INTENTS.contains(result.getIntent());

        assert intentValid
                : "Intent [" + result.getIntent() + "] is not in valid set and result is not unrecognized";
    }

    // -----------------------------------------------------------------------
    // Property 4: 非法 JSON 输入视为 unrecognized
    // Validates: Requirements 5.2
    // -----------------------------------------------------------------------
    // Feature: natural-language-command, Property 4: 非法 JSON 输入视为 unrecognized
    @Property(tries = 100)
    void invalidJsonIsUnrecognized(@ForAll @StringLength(min = 1) String input) {
        Assume.that(!isValidJson(input));

        ParseResult result = parseJson(input);

        assert result.isUnrecognized()
                : "Expected unrecognized=true for non-JSON input [" + input + "]";
        assert result.getHint() != null
                : "Expected non-null hint for non-JSON input [" + input + "]";
    }

    // -----------------------------------------------------------------------
    // Utility: minimal JSON string escaping for key/value generation
    // -----------------------------------------------------------------------
    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
