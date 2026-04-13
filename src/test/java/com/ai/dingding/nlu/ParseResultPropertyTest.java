package com.ai.dingding.nlu;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import shade.com.alibaba.fastjson2.JSON;
import java.util.Objects;

/**
 * Property-based tests for ParseResult serialization round-trip.
 * Validates: Requirements 6.2
 */
class ParseResultPropertyTest {

    @Provide
    Arbitrary<ParseResult> validParseResults() {
        // Option 1: recognized result with intent
        String[] intents = {"CREATE_USER", "SEARCH_USER", "EXPORT_EXAM", "STUDENT_STATS", "HELP"};
        Arbitrary<ParseResult> recognized = Arbitraries.of(intents).map(intent -> {
            ParseResult r = new ParseResult();
            r.setIntent(intent);
            return r;
        });

        // Option 2: unrecognized result with hint
        Arbitrary<ParseResult> unrecognized = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(50).map(hint -> {
            return ParseResult.ofUnrecognized(hint);
        });

        return Arbitraries.oneOf(recognized, unrecognized);
    }

    // Feature: natural-language-command, Property 1: 合法 ParseResult 的序列化往返
    @Property(tries = 100)
    void parseResultRoundTrip(@ForAll("validParseResults") ParseResult original) {
        String json = JSON.toJSONString(original);
        ParseResult deserialized = JSON.parseObject(json, ParseResult.class);

        assert deserialized != null;
        assert Objects.equals(original.getIntent(), deserialized.getIntent());
        assert original.isUnrecognized() == deserialized.isUnrecognized();
        assert Objects.equals(original.getHint(), deserialized.getHint());
    }
}
