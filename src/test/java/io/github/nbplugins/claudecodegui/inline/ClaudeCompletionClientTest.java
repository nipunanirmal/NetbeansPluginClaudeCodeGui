package io.github.nbplugins.claudecodegui.inline;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ClaudeCompletionClient}: prompt building, NDJSON parsing,
 * and response cleaning — all without spawning any process.
 */
class ClaudeCompletionClientTest {

    // -------------------------------------------------------------------------
    // buildPrompt
    // -------------------------------------------------------------------------

    @Test
    void buildPromptIncludesTextBeforeCaret() {
        String doc = "public class Foo {\n    void bar() {\n        int x = 1;\n    }\n}";
        int caret = doc.indexOf("int x = 1;");
        String prompt = ClaudeCompletionClient.buildPrompt(doc, caret);
        assertTrue(prompt.contains("void bar()"), "Prefix should include code before caret");
    }

    @Test
    void buildPromptIncludesTextAfterCaret() {
        String doc = "public class Foo {\n    void bar() {\n        int x = 1;\n    }\n}";
        int caret = doc.indexOf("int x = 1;");
        String prompt = ClaudeCompletionClient.buildPrompt(doc, caret);
        assertTrue(prompt.contains("}"), "Suffix should include closing brace after caret");
    }

    @Test
    void buildPromptAtStartOfDocument() {
        String doc = "class A {}";
        String prompt = ClaudeCompletionClient.buildPrompt(doc, 0);
        assertNotNull(prompt);
        assertFalse(prompt.isBlank());
    }

    @Test
    void buildPromptAtEndOfDocument() {
        String doc = "class A {}";
        String prompt = ClaudeCompletionClient.buildPrompt(doc, doc.length());
        assertTrue(prompt.contains("class A {}"), "All text is prefix when caret is at end");
    }

    @Test
    void buildPromptLimitsPrefix() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) sb.append("line ").append(i).append('\n');
        String doc = sb.toString();
        String prompt = ClaudeCompletionClient.buildPrompt(doc, doc.length());
        long lineCount = prompt.lines()
                .filter(l -> l.startsWith("line "))
                .count();
        assertTrue(lineCount <= 40, "Prompt should include at most 40 prefix lines, got: " + lineCount);
    }

    // -------------------------------------------------------------------------
    // extractFromNdjsonLine
    // -------------------------------------------------------------------------

    @Test
    void extractFromNdjsonLineReturnsResultText() {
        String line = "{\"type\":\"result\",\"subtype\":\"success\",\"result\":\"    return x + 1;\"}";
        String text = ClaudeCompletionClient.extractFromNdjsonLine(line);
        assertEquals("    return x + 1;", text);
    }

    @Test
    void extractFromNdjsonLineIgnoresNonResultEvents() {
        String line = "{\"type\":\"assistant\",\"message\":{\"content\":\"thinking\"}}";
        assertNull(ClaudeCompletionClient.extractFromNdjsonLine(line),
                "Non-result events should return null");
    }

    @Test
    void extractFromNdjsonLineHandlesMalformedJson() {
        assertNull(ClaudeCompletionClient.extractFromNdjsonLine("not json at all"));
    }

    @Test
    void extractFromNdjsonLineHandlesNull() {
        assertNull(ClaudeCompletionClient.extractFromNdjsonLine(null));
    }

    @Test
    void extractFromNdjsonLineHandlesBlank() {
        assertNull(ClaudeCompletionClient.extractFromNdjsonLine("   "));
    }

    // -------------------------------------------------------------------------
    // cleanResponse
    // -------------------------------------------------------------------------

    @Test
    void cleanResponseStripsMarkdownFences() {
        String raw = "```java\nreturn x + 1;\n```";
        assertEquals("return x + 1;", ClaudeCompletionClient.cleanResponse(raw));
    }

    @Test
    void cleanResponseStripsPlainFences() {
        String raw = "```\nreturn x;\n```";
        assertEquals("return x;", ClaudeCompletionClient.cleanResponse(raw));
    }

    @Test
    void cleanResponseLeavesCleanTextUnchanged() {
        String raw = "    return x + 1;";
        assertEquals("return x + 1;", ClaudeCompletionClient.cleanResponse(raw));
    }

    @Test
    void cleanResponseHandlesNull() {
        assertEquals("", ClaudeCompletionClient.cleanResponse(null));
    }

    @Test
    void cleanResponseHandlesEmpty() {
        assertEquals("", ClaudeCompletionClient.cleanResponse(""));
    }

    // -------------------------------------------------------------------------
    // buildCommand
    // -------------------------------------------------------------------------

    @Test
    void buildCommandForClaudeIncludesStreamJsonFlag() {
        List<String> cmd = ClaudeCompletionClient.buildCommand("claude", "test prompt", true);
        assertTrue(cmd.contains("--output-format"), "Claude command must include --output-format");
        assertTrue(cmd.contains("stream-json"),     "Claude command must include stream-json");
        assertTrue(cmd.contains("--print"),         "Command must include --print");
        assertTrue(cmd.contains("-p"),              "Command must include -p");
        assertTrue(cmd.contains("test prompt"),     "Command must include prompt text");
    }

    @Test
    void buildCommandForDevinOmitsStreamJsonFlag() {
        List<String> cmd = ClaudeCompletionClient.buildCommand("devin", "test prompt", false);
        assertFalse(cmd.contains("--output-format"), "Devin command must NOT include --output-format");
        assertTrue(cmd.contains("--print"),          "Command must include --print");
        assertTrue(cmd.contains("-p"),               "Command must include -p");
    }
}
