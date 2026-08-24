package io.github.nbplugins.claudecodegui.inline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nbplugins.claudecodegui.process.ClaudeProcess;
import io.github.nbplugins.claudecodegui.settings.ClaudeCodePreferences;
import io.github.nbplugins.claudecodegui.settings.ClaudeProfile;
import io.github.nbplugins.claudecodegui.settings.ClaudeProfileStore;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Calls the configured AI CLI ({@code claude}, {@code devin}, {@code agy}, or
 * {@code cursor-agent}) in headless/print mode to obtain a code-completion suggestion.
 *
 * <p>The CLI is spawned as a plain {@link ProcessBuilder} process (not PTY).
 * Auth and proxy config are inherited from the resolved {@link ClaudeProfile}
 * via environment variables, exactly as {@link ClaudeProcess#buildEnv} does for
 * full sessions.
 *
 * <p>For the Claude CLI the output is NDJSON ({@code --output-format stream-json});
 * the final {@code result} event's {@code result} field is returned.
 * For other CLIs, stdout is read as plain text.
 */
public final class ClaudeCompletionClient {

    private static final Logger LOG = Logger.getLogger(ClaudeCompletionClient.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Lines of prefix context to include before the caret. */
    private static final int PREFIX_LINES = 40;
    /** Lines of suffix context to include after the caret. */
    private static final int SUFFIX_LINES = 10;

    private ClaudeCompletionClient() {}

    /**
     * Builds a completion prompt from the given document text and caret offset,
     * calls the CLI asynchronously, and returns the suggestion text.
     *
     * @param documentText full text of the document being edited
     * @param caretOffset  0-based caret position within {@code documentText}
     * @return future resolving to the completion text, or empty string on failure/cancel
     */
    public static CompletableFuture<String> requestCompletion(String documentText, int caretOffset) {
        return requestCompletion(documentText, caretOffset, false);
    }

    /**
     * Builds a completion prompt and calls the CLI asynchronously.
     *
     * @param documentText  full text of the document being edited
     * @param caretOffset   0-based caret position within {@code documentText}
     * @param commentTrigger {@code true} when the current line is a {@code //} comment;
     *                       generates an "implement this" style prompt instead of FIM
     * @return future resolving to the completion text, or empty string on failure/cancel
     */
    public static CompletableFuture<String> requestCompletion(String documentText, int caretOffset,
                                                               boolean commentTrigger) {
        String prompt = commentTrigger
                ? buildCommentPrompt(documentText, caretOffset)
                : buildPrompt(documentText, caretOffset);
        ClaudeProfile profile = resolveProfile();
        String executable     = ClaudeCodePreferences.resolveClaudeExecutable();
        boolean isClaude      = !ClaudeCodePreferences.isDevinCli()
                && !ClaudeCodePreferences.isAntigravityCli()
                && !ClaudeCodePreferences.isCursorCli()
                && !ClaudeCodePreferences.isCodexCli();

        // Use an atomic reference so the process can be force-killed when the
        // future is cancelled (prevents parallel CLI processes piling up).
        java.util.concurrent.atomic.AtomicReference<Process> procRef =
                new java.util.concurrent.atomic.AtomicReference<>();

        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            List<String> cmd = buildCommand(executable, prompt, isClaude);
            Map<String, String> extraEnv = ClaudeProcess.buildEnv(profile, ClaudeCodePreferences.getProfilesDir());
            extraEnv.remove("CLAUDECODE");
            extraEnv.remove("CLAUDE_CODE_ENTRYPOINT");

            LOG.info("Inline completion: spawning " + String.join(" ", cmd.subList(0, Math.min(3, cmd.size()))));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().putAll(extraEnv);
            pb.redirectErrorStream(false);

            try {
                Process proc = pb.start();
                procRef.set(proc);
                proc.getOutputStream().close();
                StringBuilder stderrBuf = new StringBuilder();
                Thread stderrThread = new Thread(() -> {
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                        String l;
                        while ((l = r.readLine()) != null) stderrBuf.append(l).append('\n');
                    } catch (Exception ignored) {}
                });
                stderrThread.setDaemon(true);
                stderrThread.start();

                String raw = readStdout(proc, isClaude);
                proc.waitFor();
                stderrThread.join(2000);

                if (stderrBuf.length() > 0) {
                    LOG.warning("Inline completion CLI stderr: " + stderrBuf.toString().trim());
                }
                String result = cleanResponse(raw);
                LOG.info("Inline completion raw=" + (raw.length() > 200 ? raw.substring(0, 200) : raw)
                        + " cleaned=" + (result.length() > 100 ? result.substring(0, 100) : result));
                return result;
            } catch (Exception ex) {
                LOG.log(Level.WARNING, "Inline completion process error", ex);
                return "";
            }
        });

        // When the future is cancelled, also kill the OS process immediately.
        future.whenComplete((r, t) -> {
            if (future.isCancelled()) {
                Process p = procRef.get();
                if (p != null && p.isAlive()) {
                    p.destroyForcibly();
                    LOG.info("Inline completion: killed stale CLI process");
                }
            }
        });

        return future;
    }

    /**
     * Sends a pre-built prompt to the CLI, optionally using a named profile override.
     * Used by {@link InlineInstructionDialog} for Ctrl+I instruction requests.
     *
     * @param prompt      the full prompt to send
     * @param profileName named profile override (null or blank = use default resolution)
     * @return future resolving to the cleaned response text
     */
    public static CompletableFuture<String> requestWithPrompt(String prompt, String profileName) {
        ClaudeProfile profile;
        if (profileName != null && !profileName.isBlank()) {
            profile = ClaudeProfileStore.findByName(profileName);
        } else {
            profile = resolveProfile();
        }
        String executable = ClaudeCodePreferences.resolveClaudeExecutable();
        boolean isClaude  = !ClaudeCodePreferences.isDevinCli()
                && !ClaudeCodePreferences.isAntigravityCli()
                && !ClaudeCodePreferences.isCursorCli()
                && !ClaudeCodePreferences.isCodexCli();

        return CompletableFuture.supplyAsync(() -> {
            List<String> cmd = buildCommand(executable, prompt, isClaude);
            Map<String, String> extraEnv = ClaudeProcess.buildEnv(profile, ClaudeCodePreferences.getProfilesDir());
            extraEnv.remove("CLAUDECODE");
            extraEnv.remove("CLAUDE_CODE_ENTRYPOINT");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().putAll(extraEnv);
            pb.redirectErrorStream(false);

            try {
                Process proc = pb.start();
                proc.getOutputStream().close();
                StringBuilder stderrBuf = new StringBuilder();
                Thread stderrThread = new Thread(() -> {
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                        String l;
                        while ((l = r.readLine()) != null) stderrBuf.append(l).append('\n');
                    } catch (Exception ignored) {}
                });
                stderrThread.setDaemon(true);
                stderrThread.start();
                String raw = readStdout(proc, isClaude);
                proc.waitFor();
                stderrThread.join(2000);
                if (stderrBuf.length() > 0) {
                    LOG.warning("InlineInstruction CLI stderr: " + stderrBuf.toString().trim());
                }
                return cleanResponse(raw);
            } catch (Exception ex) {
                LOG.log(Level.WARNING, "InlineInstruction process error", ex);
                return "";
            }
        });
    }

    /**
     * Builds the CLI command list.
     *
     * @param executable CLI binary path/name
     * @param prompt     the completion prompt
     * @param isClaude   {@code true} to add {@code --output-format stream-json}
     * @return command list for {@link ProcessBuilder}
     */
    static List<String> buildCommand(String executable, String prompt, boolean isClaude) {
        List<String> cmd = new ArrayList<>();
        cmd.add(executable);
        if (isClaude) {
            // Claude CLI: --print --output-format stream-json -p "prompt"
            cmd.add("--print");
            cmd.add("--output-format");
            cmd.add("stream-json");
            cmd.add("-p");
            cmd.add(prompt);
        } else if (ClaudeCodePreferences.isDevinCli()) {
            // Devin CLI: -p "prompt" --permission-mode auto
            // Devin's -p flag IS the print/non-interactive flag and accepts inline prompt.
            cmd.add("-p");
            cmd.add(prompt);
            cmd.add("--permission-mode");
            cmd.add("auto");
        } else if (ClaudeCodePreferences.isCodexCli()) {
            // Codex CLI: exec "prompt" runs a non-interactive completion.
            cmd.add("exec");
            cmd.add("--skip-git-repo-check");
            cmd.add(prompt);
        } else {
            // Antigravity / Cursor / other: assume --print -p "prompt" like Claude
            cmd.add("--print");
            cmd.add("-p");
            cmd.add(prompt);
        }
        return cmd;
    }

    /**
     * Builds the FIM-style prompt from the document and caret offset.
     *
     * @param documentText full document text
     * @param caretOffset  0-based caret position
     * @return prompt string
     */
    static String buildPrompt(String documentText, int caretOffset) {
        int safeOffset = Math.max(0, Math.min(caretOffset, documentText.length()));
        String before = documentText.substring(0, safeOffset);
        String after  = documentText.substring(safeOffset);

        String[] prefixLines = before.split("\n", -1);
        String[] suffixLines = after.split("\n", -1);

        int prefixStart = Math.max(0, prefixLines.length - PREFIX_LINES);
        StringBuilder sb = new StringBuilder();
        sb.append("Complete the following code. Output ONLY the completion text, ");
        sb.append("no explanations, no markdown fences:\n");
        for (int i = prefixStart; i < prefixLines.length; i++) {
            sb.append(prefixLines[i]).append('\n');
        }
        int suffixEnd = Math.min(suffixLines.length, SUFFIX_LINES);
        for (int i = 0; i < suffixEnd; i++) {
            sb.append(suffixLines[i]);
            if (i < suffixEnd - 1) sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Reads and parses stdout from the CLI process.
     * For Claude (NDJSON), finds the {@code result} event and returns its text.
     * For other CLIs, returns all stdout as plain text.
     *
     * @param proc     the running process
     * @param isClaude {@code true} to parse NDJSON
     * @return extracted completion text
     */
    private static String readStdout(Process proc, boolean isClaude) throws Exception {
        StringBuilder plainOut = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (isClaude) {
                    String text = extractFromNdjsonLine(line);
                    if (text != null) return text;
                } else {
                    if (plainOut.length() > 0) plainOut.append('\n');
                    plainOut.append(line);
                }
            }
        }
        return plainOut.toString();
    }

    /**
     * Builds a prompt that treats the {@code //} comment on the current line as an
     * instruction, asking the AI to generate the code that should follow it.
     *
     * <p>Example: if the comment is {@code // add null check here}, the AI receives the
     * surrounding code context plus a request to implement that instruction.
     *
     * @param documentText full document text
     * @param caretOffset  0-based caret position (end of the comment line)
     * @return instruction-style prompt
     */
    static String buildCommentPrompt(String documentText, int caretOffset) {
        int safeOffset = Math.max(0, Math.min(caretOffset, documentText.length()));
        String before = documentText.substring(0, safeOffset);
        String after  = documentText.substring(safeOffset);

        String[] prefixLines = before.split("\n", -1);
        int prefixStart = Math.max(0, prefixLines.length - PREFIX_LINES);

        // Extract the comment line (last line of prefix)
        String commentLine = prefixLines[prefixLines.length - 1].strip();

        StringBuilder sb = new StringBuilder();
        sb.append("You are a code assistant. Given the following code context and a developer ");
        sb.append("comment instruction, generate ONLY the code that should replace/follow the ");
        sb.append("comment. Output ONLY the code, no explanations, no markdown fences.\n\n");
        sb.append("Developer instruction (from comment): ").append(commentLine).append("\n\n");
        sb.append("Code context before the comment:\n");
        for (int i = prefixStart; i < prefixLines.length - 1; i++) {
            sb.append(prefixLines[i]).append('\n');
        }
        String[] suffixLines = after.split("\n", -1);
        int suffixEnd = Math.min(suffixLines.length, SUFFIX_LINES);
        if (suffixEnd > 0) {
            sb.append("\nCode context after the comment:\n");
            for (int i = 0; i < suffixEnd; i++) {
                sb.append(suffixLines[i]);
                if (i < suffixEnd - 1) sb.append('\n');
            }
        }
        sb.append("\n\nGenerate the code to implement: ").append(commentLine);
        return sb.toString();
    }

    /**
     * Tries to parse one NDJSON line and return the result text if it is the
     * final {@code result} event.
     *
     * @param line one NDJSON line
     * @return result text, or {@code null} if this is not the result event
     */
    static String extractFromNdjsonLine(String line) {
        if (line == null || line.isBlank()) return null;
        try {
            JsonNode node = MAPPER.readTree(line);
            // Log every NDJSON event type for diagnosis
            JsonNode type = node.get("type");
            String typeStr = type != null ? type.asText() : "(no type)";
            LOG.fine("NDJSON event: " + typeStr);

            if (type != null && "result".equals(typeStr)) {
                // Claude CLI final result event — text is in "result" field
                JsonNode result = node.get("result");
                if (result != null && !result.isNull()) {
                    String text = result.asText("");
                    LOG.info("NDJSON result event text length=" + text.length());
                    return text;
                }
            }
            // Some Claude CLI versions put content in "content" on assistant events
            if (type != null && "assistant".equals(typeStr)) {
                JsonNode message = node.get("message");
                if (message != null) {
                    JsonNode content = message.get("content");
                    if (content != null && content.isArray()) {
                        StringBuilder sb = new StringBuilder();
                        for (JsonNode block : content) {
                            JsonNode blockType = block.get("type");
                            if (blockType != null && "text".equals(blockType.asText())) {
                                JsonNode text = block.get("text");
                                if (text != null) sb.append(text.asText(""));
                            }
                        }
                        if (sb.length() > 0) {
                            LOG.info("NDJSON assistant event text length=" + sb.length());
                            return sb.toString();
                        }
                    }
                }
            }
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Could not parse NDJSON line: " + line, ex);
        }
        return null;
    }

    /**
     * Strips Markdown code fences ({@code ```...```}) from the response text,
     * and trims leading/trailing blank lines.
     *
     * @param raw raw CLI output
     * @return cleaned completion text
     */
    static String cleanResponse(String raw) {
        if (raw == null) return "";
        String s = raw.strip();
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            if (firstNl >= 0) s = s.substring(firstNl + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
            s = s.strip();
        }
        return s;
    }

    /**
     * Resolves the {@link ClaudeProfile} to use for this completion request.
     * Uses the override profile if set, otherwise falls back to the last-started
     * session profile, or {@code null} for the Default profile.
     *
     * @return resolved profile (may be {@code null} for Default)
     */
    private static ClaudeProfile resolveProfile() {
        String override = ClaudeCodePreferences.getInlineProfileOverride();
        if (override != null && !override.isBlank()) {
            ClaudeProfile found = ClaudeProfileStore.findByName(override);
            if (found != null && !found.isDefault()) return found;
        }
        return ClaudeProcess.getLastStartedProfile();
    }
}
