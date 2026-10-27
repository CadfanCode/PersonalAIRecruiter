package se.caibirch.jobpilot.claude;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import se.caibirch.jobpilot.settings.JobPilotProperties;

/**
 * Runs the user's local {@code claude -p} headless (SPEC 5.1). Pure text tasks get no tools; the
 * prompt goes on the command line unless it is large, then on stdin.
 */
@Component
public class CliClaudeRunner implements ClaudeRunner {

    /** Above this many UTF-8 bytes the prompt is piped through stdin instead of passed as an argument. */
    static final int MAX_ARGUMENT_PROMPT_BYTES = 100_000;

    private static final Logger log = LoggerFactory.getLogger(CliClaudeRunner.class);
    private static final File DEV_NULL = new File("/dev/null");

    private final JobPilotProperties.Claude config;
    private final File workDir;
    private final ObjectMapper mapper;
    private final ClaudeCallLog callLog;
    private final Semaphore concurrency;

    public CliClaudeRunner(JobPilotProperties props, ObjectMapper mapper, ClaudeCallLog callLog) {
        this.config = props.claude();
        this.workDir = props.claudeWorkDir().toFile();
        this.mapper = mapper;
        this.callLog = callLog;
        this.concurrency = new Semaphore(Math.max(1, config.concurrency()), true);
    }

    @Override
    public <T> T run(ClaudeTask<T> task) {
        try {
            concurrency.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClaudeCallException("Interrupted while waiting for a Claude slot for " + task.name());
        }
        try {
            return runLocked(task);
        } finally {
            concurrency.release();
        }
    }

    /**
     * One tiny call in stream-json mode: the CLI emits a {@code rate_limit_event} with the 5-hour and 7-day windows
     * of the signed-in subscription. Nothing but that event is read.
     */
    @Override
    public java.util.Optional<UsageLimits> limits() {
        try {
            concurrency.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClaudeCallException("Interrupted while waiting for a Claude slot");
        }
        try {
            List<String> command = new ArrayList<>(List.of(config.executable(), "-p", "ok", "--output-format", "stream-json", "--verbose",
                    "--no-session-persistence", "--disable-slash-commands", "--strict-mcp-config", "--setting-sources", "project",
                    "--tools", ""));
            if (config.model() != null && !config.model().isBlank()) {
                command.addAll(List.of("--model", config.model()));
            }
            Files.createDirectories(workDir.toPath());
            ProcessBuilder pb = new ProcessBuilder(command).directory(workDir).redirectInput(ProcessBuilder.Redirect.from(DEV_NULL));
            Process process = pb.start();
            AtomicReference<String> stdout = new AtomicReference<>("");
            Thread outReader = Thread.ofVirtual().start(() -> stdout.set(readAll(process.getInputStream())));
            Thread errReader = Thread.ofVirtual().start(() -> readAll(process.getErrorStream()));
            if (!process.waitFor(config.timeoutSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new ClaudeCallException("Reading the usage limits timed out");
            }
            outReader.join();
            errReader.join();
            return parseLimits(stdout.get(), mapper);
        } catch (IOException e) {
            throw new ClaudeCallException("Cannot start '" + config.path() + "': " + e.getMessage(), null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClaudeCallException("Interrupted while reading the usage limits");
        } finally {
            concurrency.release();
        }
    }

    /** Finds the {@code rate_limit_event} line in stream-json output; empty when there is none. Package-private for tests. */
    static java.util.Optional<UsageLimits> parseLimits(String streamJson, ObjectMapper mapper) {
        UsageLimits found = null;
        for (String line : streamJson.split("\\R")) {
            if (!line.contains("rate_limit_event")) {
                continue;
            }
            try {
                JsonNode event = mapper.readTree(line);
                JsonNode windows = event.path("rate_limit_info").path("unifiedWindows");
                UsageLimits.Window session = window(windows.path("five_hour"));
                UsageLimits.Window week = window(windows.path("seven_day"));
                if (session != null || week != null) {
                    // Later events are newer; keep the last one that has anything.
                    found = new UsageLimits(session, week, null);
                }
            } catch (JsonProcessingException ignored) {
                // a partial line: skip it
            }
        }
        return java.util.Optional.ofNullable(found);
    }

    private static UsageLimits.Window window(JsonNode w) {
        if (!w.has("utilization")) {
            return null;
        }
        return new UsageLimits.Window(w.get("utilization").asDouble(),
                w.has("resetsAt") ? java.time.Instant.ofEpochSecond(w.get("resetsAt").asLong()) : null, w.path("status").asText(null));
    }

    private <T> T runLocked(ClaudeTask<T> task) {
        long started = System.nanoTime();
        JsonNode envelope = null;
        try {
            JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(task.schemaJson());
            envelope = execute(task);
            T result = parseStructuredOutput(task, envelope, schema);
            record(task, envelope, started, true, null);
            return result;
        } catch (ClaudeCallException e) {
            record(task, envelope, started, false, e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            record(task, envelope, started, false, e.toString());
            throw new ClaudeCallException("Claude task " + task.name() + " failed: " + e.getMessage(), null, e);
        }
    }

    private JsonNode execute(ClaudeTask<?> task) {
        boolean viaStdin = task.prompt().getBytes(StandardCharsets.UTF_8).length > MAX_ARGUMENT_PROMPT_BYTES;
        List<String> command = buildCommand(task, viaStdin);
        int timeout = task.usesLongTimeout() ? config.webTimeoutSeconds() : config.timeoutSeconds();

        ProcessBuilder pb = new ProcessBuilder(command);
        try {
            Files.createDirectories(workDir.toPath());
        } catch (IOException e) {
            throw new ClaudeCallException("Cannot create Claude working directory " + workDir, null, e);
        }
        // Not the project directory, so the CLI does not load JobPilot's own CLAUDE.md into every call.
        pb.directory(workDir);
        if (!viaStdin) {
            pb.redirectInput(ProcessBuilder.Redirect.from(DEV_NULL));
        }

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new ClaudeCallException("Cannot start '" + config.path() + "': " + e.getMessage()
                    + ". Set the Claude CLI path in settings.", null, e);
        }

        AtomicReference<String> stderr = new AtomicReference<>("");
        AtomicReference<String> stdout = new AtomicReference<>("");
        Thread outReader = Thread.ofVirtual().start(() -> stdout.set(readAll(process.getInputStream())));
        Thread errReader = Thread.ofVirtual().start(() -> stderr.set(readAll(process.getErrorStream())));
        if (viaStdin) {
            Thread.ofVirtual().start(() -> {
                try (OutputStream in = process.getOutputStream()) {
                    in.write(task.prompt().getBytes(StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // the process died early; the exit code reports it
                }
            });
        }

        try {
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new ClaudeCallException("Claude task " + task.name() + " timed out after " + timeout + " s");
            }
            outReader.join();
            errReader.join();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new ClaudeCallException("Interrupted during Claude task " + task.name());
        }

        int exit = process.exitValue();
        JsonNode envelope = parseEnvelope(stdout.get());
        if (exit != 0) {
            throw new ClaudeCallException("Claude CLI exited with code " + exit + " for task " + task.name()
                    + describe(envelope), stderr.get(), null);
        }
        if (envelope == null) {
            throw new ClaudeCallException("Claude CLI printed no JSON for task " + task.name(), stderr.get(), null);
        }
        return envelope;
    }

    List<String> buildCommand(ClaudeTask<?> task, boolean viaStdin) {
        List<String> command = new ArrayList<>();
        command.add(config.executable());
        command.add("-p");
        if (viaStdin) {
            command.add("--input-format");
            command.add("text");
        } else {
            command.add(task.prompt());
        }
        command.addAll(List.of("--output-format", "json", "--json-schema", task.schemaJson(),
                "--no-session-persistence"));
        // Skip the user's plugins, skills, MCP servers and user-level settings: they add ~50k tokens
        // (about $0.40) to every call. Project settings come from the empty work directory.
        command.addAll(List.of("--disable-slash-commands", "--strict-mcp-config", "--setting-sources", "project"));
        if (task.webTools()) {
            command.addAll(List.of("--tools", "WebSearch,WebFetch", "--allowedTools", "WebSearch", "WebFetch"));
        } else {
            command.addAll(List.of("--tools", ""));
        }
        if (config.model() != null && !config.model().isBlank()) {
            command.addAll(List.of("--model", config.model()));
        }
        return command;
    }

    private <T> T parseStructuredOutput(ClaudeTask<T> task, JsonNode envelope, JsonSchema schema) {
        if (envelope.path("is_error").asBoolean(false)) {
            throw new ClaudeCallException("Claude reported an error for task " + task.name() + describe(envelope));
        }
        String subtype = envelope.path("subtype").asText("");
        if (!"success".equals(subtype)) {
            throw new ClaudeCallException("Claude finished with subtype '" + subtype + "' for task " + task.name()
                    + describe(envelope));
        }
        JsonNode structured = envelope.get("structured_output");
        if (structured == null || structured.isNull()) {
            throw new ClaudeCallException("Claude returned no structured_output for task " + task.name()
                    + describe(envelope));
        }
        Set<ValidationMessage> problems = schema.validate(structured);
        if (!problems.isEmpty()) {
            throw new ClaudeCallException("structured_output of task " + task.name()
                    + " does not match its schema: " + problems);
        }
        try {
            return mapper.treeToValue(structured, task.resultType());
        } catch (JsonProcessingException e) {
            throw new ClaudeCallException("Cannot bind structured_output of task " + task.name() + " to "
                    + task.resultType().getSimpleName(), null, e);
        }
    }

    private JsonNode parseEnvelope(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(stdout);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String describe(JsonNode envelope) {
        if (envelope == null) {
            return "";
        }
        String result = envelope.path("result").asText("");
        return result.isBlank() ? "" : ": " + (result.length() > 300 ? result.substring(0, 300) + "..." : result);
    }

    private void record(ClaudeTask<?> task, JsonNode envelope, long startedNanos, boolean success, String error) {
        long wallMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        try {
            JsonNode usage = envelope == null ? null : envelope.path("usage");
            long duration = envelope != null && envelope.has("duration_ms") ? envelope.get("duration_ms").asLong() : wallMs;
            BigDecimal cost = envelope != null && envelope.has("total_cost_usd")
                    ? envelope.get("total_cost_usd").decimalValue() : null;
            callLog.record(new ClaudeCallLog.Entry(task.name(), modelOf(envelope), duration, cost,
                    tokens(usage, "input_tokens"), tokens(usage, "output_tokens"),
                    tokens(usage, "cache_read_input_tokens"), tokens(usage, "cache_creation_input_tokens"),
                    success, error));
        } catch (RuntimeException e) {
            // Usage accounting must never break a call.
            log.warn("Could not record claude_call for {}: {}", task.name(), e.toString());
        }
        log.info("claude task={} success={} wallMs={}", task.name(), success, wallMs);
    }

    private static String modelOf(JsonNode envelope) {
        if (envelope == null || !envelope.path("modelUsage").isObject() || envelope.get("modelUsage").isEmpty()) {
            return null;
        }
        return envelope.get("modelUsage").fieldNames().next();
    }

    private static Long tokens(JsonNode usage, String field) {
        return usage != null && usage.has(field) ? usage.get(field).asLong() : null;
    }

    private static String readAll(java.io.InputStream in) {
        try {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
