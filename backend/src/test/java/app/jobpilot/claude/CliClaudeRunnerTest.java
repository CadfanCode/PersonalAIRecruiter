package app.jobpilot.claude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import app.jobpilot.settings.JobPilotProperties;

/**
 * Drives the real ProcessBuilder path against stub scripts that print the recorded output of a
 * real {@code claude} 2.1.310 call. The real CLI is never started.
 */
class CliClaudeRunnerTest {

    record Status(String status) {
    }

    @TempDir
    Path dir;

    ClaudeCallLog callLog = mock(ClaudeCallLog.class);
    String schema = ClaudeResources.schema("health");
    String recorded;

    @BeforeEach
    void loadFixture() throws IOException {
        recorded = Files.readString(Path.of("src/test/resources/claude/envelope-success.json"));
    }

    CliClaudeRunner runnerFor(String scriptBody, int timeoutSeconds) throws IOException {
        Path script = dir.resolve("claude-stub.sh");
        Files.writeString(script, "#!/bin/bash\n" + scriptBody + "\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        JobPilotProperties props = new JobPilotProperties(dir.resolve("data"), List.of(),
                new JobPilotProperties.Claude(script.toString(), "", timeoutSeconds, timeoutSeconds, 2));
        return new CliClaudeRunner(props, new ObjectMapper(), callLog);
    }

    String printsFile(String name, String content) throws IOException {
        Path f = dir.resolve(name);
        Files.writeString(f, content);
        return "cat '" + f + "'";
    }

    ClaudeTask<Status> task(String prompt) {
        return ClaudeTask.of("health", prompt, schema, Status.class);
    }

    @Test
    void theModelAliasIsReadOnEveryCallSoAChangeInSettingsNeedsNoRestart() {
        JobPilotProperties props = new JobPilotProperties(dir.resolve("data"), List.of(), new JobPilotProperties.Claude("claude", "", 5, 5, 2));
        java.util.concurrent.atomic.AtomicReference<String> model = new java.util.concurrent.atomic.AtomicReference<>("");
        CliClaudeRunner runner = new CliClaudeRunner(props, new ObjectMapper(), callLog, ClaudeConcurrency.fixed(1), model::get);

        assertThat(runner.buildCommand(task("x"), false)).doesNotContain("--model");
        model.set("opus");
        List<String> command = runner.buildCommand(task("x"), false);
        assertThat(command).containsSubsequence("--model", "opus");
        model.set("sonnet");
        assertThat(runner.buildCommand(task("x"), false)).containsSubsequence("--model", "sonnet");
    }

    @Test
    void parsesStructuredOutputFromRecordedEnvelope() throws IOException {
        CliClaudeRunner runner = runnerFor(printsFile("out.json", recorded), 10);

        Status status = runner.run(task("Return status ok"));

        assertThat(status.status()).isEqualTo("ok");
        ArgumentCaptor<ClaudeCallLog.Entry> entry = ArgumentCaptor.forClass(ClaudeCallLog.Entry.class);
        verify(callLog).record(entry.capture());
        assertThat(entry.getValue().success()).isTrue();
        assertThat(entry.getValue().task()).isEqualTo("health");
        assertThat(entry.getValue().costUsd()).isPositive();
        assertThat(entry.getValue().outputTokens()).isEqualTo(52);
        assertThat(entry.getValue().model()).isEqualTo("claude-opus-5-5");
    }

    @Test
    void passesPromptAsArgumentWithNoToolsAndStdinFromDevNull() throws IOException {
        Path argsFile = dir.resolve("args.txt");
        Path stdinFile = dir.resolve("stdin.txt");
        CliClaudeRunner runner = runnerFor("printf '%s\\n' \"$@\" > '" + argsFile + "'\ncat > '" + stdinFile + "'\n"
                + printsFile("out.json", recorded), 10);

        runner.run(task("Return status ok"));

        List<String> args = Files.readAllLines(argsFile);
        assertThat(args).containsSubsequence("-p", "Return status ok");
        assertThat(args).containsSubsequence("--output-format", "json");
        assertThat(args).contains("--json-schema", "--no-session-persistence");
        int tools = args.indexOf("--tools");
        assertThat(args.get(tools + 1)).isEmpty();
        assertThat(args).doesNotContain("--allowedTools");
        assertThat(args).contains("--disable-slash-commands", "--strict-mcp-config");
        assertThat(args).containsSubsequence("--setting-sources", "project");
        assertThat(Files.readString(stdinFile)).isEmpty();
    }

    @Test
    void sendsLargePromptThroughStdin() throws IOException {
        Path argsFile = dir.resolve("args.txt");
        Path stdinFile = dir.resolve("stdin.txt");
        CliClaudeRunner runner = runnerFor("printf '%s\\n' \"$@\" > '" + argsFile + "'\ncat > '" + stdinFile + "'\n"
                + printsFile("out.json", recorded), 10);
        String big = "ä".repeat(CliClaudeRunner.MAX_ARGUMENT_PROMPT_BYTES);

        runner.run(task(big));

        assertThat(Files.readAllLines(argsFile)).contains("--input-format", "text").doesNotContain(big);
        assertThat(Files.readString(stdinFile, StandardCharsets.UTF_8)).isEqualTo(big);
    }

    @Test
    void webTasksAllowWebToolsOnly() throws IOException {
        Path argsFile = dir.resolve("args.txt");
        CliClaudeRunner runner = runnerFor("printf '%s\\n' \"$@\" > '" + argsFile + "'\n"
                + printsFile("out.json", recorded), 10);

        runner.run(new ClaudeTask<>("discoverJobsWeb", "find jobs", schema, Status.class, true));

        List<String> args = Files.readAllLines(argsFile);
        assertThat(args).containsSubsequence("--tools", "WebSearch,WebFetch", "--allowedTools", "WebSearch", "WebFetch");
    }

    @Test
    void isErrorIsReported() throws IOException {
        String body = recorded.replace("\"is_error\":false", "\"is_error\":true");
        CliClaudeRunner runner = runnerFor(printsFile("out.json", body), 10);

        assertThatThrownBy(() -> runner.run(task("x"))).isInstanceOf(ClaudeCallException.class)
                .hasMessageContaining("reported an error");
        ArgumentCaptor<ClaudeCallLog.Entry> entry = ArgumentCaptor.forClass(ClaudeCallLog.Entry.class);
        verify(callLog).record(entry.capture());
        assertThat(entry.getValue().success()).isFalse();
    }

    @Test
    void nonSuccessSubtypeIsReported() throws IOException {
        String body = recorded.replace("\"subtype\":\"success\"", "\"subtype\":\"error_max_turns\"");
        CliClaudeRunner runner = runnerFor(printsFile("out.json", body), 10);

        assertThatThrownBy(() -> runner.run(task("x"))).isInstanceOf(ClaudeCallException.class)
                .hasMessageContaining("error_max_turns");
    }

    @Test
    void missingStructuredOutputIsReported() throws IOException {
        String body = recorded.replace("\"structured_output\":{\"status\":\"ok\"},", "");
        assertThat(body).doesNotContain("structured_output");
        CliClaudeRunner runner = runnerFor(printsFile("out.json", body), 10);

        assertThatThrownBy(() -> runner.run(task("x"))).isInstanceOf(ClaudeCallException.class)
                .hasMessageContaining("no structured_output");
    }

    @Test
    void outputThatViolatesTheSchemaIsRejected() throws IOException {
        String body = recorded.replace("\"structured_output\":{\"status\":\"ok\"}", "\"structured_output\":{\"status\":\"nope\"}");
        CliClaudeRunner runner = runnerFor(printsFile("out.json", body), 10);

        assertThatThrownBy(() -> runner.run(task("x"))).isInstanceOf(ClaudeCallException.class)
                .hasMessageContaining("does not match its schema");
    }

    @Test
    void nonZeroExitCarriesStderr() throws IOException {
        CliClaudeRunner runner = runnerFor("echo 'Not logged in' >&2\nexit 1", 10);

        assertThatThrownBy(() -> runner.run(task("x"))).isInstanceOfSatisfying(ClaudeCallException.class,
                e -> {
                    assertThat(e.getMessage()).contains("exited with code 1");
                    assertThat(e.stderr()).contains("Not logged in");
                });
    }

    @Test
    void garbageOnStdoutIsReported() throws IOException {
        CliClaudeRunner runner = runnerFor("echo 'not json'", 10);

        assertThatThrownBy(() -> runner.run(task("x"))).isInstanceOf(ClaudeCallException.class)
                .hasMessageContaining("no JSON");
    }

    @Test
    void timeoutKillsTheProcess() throws IOException {
        CliClaudeRunner runner = runnerFor("sleep 30", 1);

        long start = System.nanoTime();
        assertThatThrownBy(() -> runner.run(task("x"))).isInstanceOf(ClaudeCallException.class)
                .hasMessageContaining("timed out");
        assertThat((System.nanoTime() - start) / 1_000_000_000).isLessThan(10);
    }

    @Test
    void longRunningTasksGetTheLongTimeout() throws IOException {
        Path script = dir.resolve("claude-stub.sh");
        Files.writeString(script, "#!/bin/bash\nsleep 2\n" + printsFile("out.json", recorded) + "\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        JobPilotProperties props = new JobPilotProperties(dir.resolve("data"), List.of(),
                new JobPilotProperties.Claude(script.toString(), "", 1, 10, 2));
        CliClaudeRunner runner = new CliClaudeRunner(props, new ObjectMapper(), callLog);

        assertThatThrownBy(() -> runner.run(task("x"))).hasMessageContaining("timed out");
        assertThat(runner.run(ClaudeTask.longRunning("importCv", "x", schema, Status.class)).status()).isEqualTo("ok");
    }

    @Test
    void missingBinaryGivesActionableMessage() {
        JobPilotProperties props = new JobPilotProperties(dir.resolve("data"), List.of(),
                new JobPilotProperties.Claude(dir.resolve("nope").toString(), "", 5, 5, 1));
        CliClaudeRunner runner = new CliClaudeRunner(props, new ObjectMapper(), callLog);

        assertThatThrownBy(() -> runner.run(task("x"))).isInstanceOf(ClaudeCallException.class)
                .hasMessageContaining("Cannot start");
        verify(callLog).record(any());
    }

    @Test
    void modelSettingIsPassedThrough() throws IOException {
        Path argsFile = dir.resolve("args.txt");
        Path script = dir.resolve("claude-stub.sh");
        Files.writeString(script, "#!/bin/bash\nprintf '%s\\n' \"$@\" > '" + argsFile + "'\n"
                + printsFile("out.json", recorded) + "\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        JobPilotProperties props = new JobPilotProperties(dir.resolve("data"), List.of(),
                new JobPilotProperties.Claude(script.toString(), "sonnet", 10, 10, 1));

        new CliClaudeRunner(props, new ObjectMapper(), callLog).run(task("x"));

        assertThat(Files.readAllLines(argsFile)).containsSubsequence("--model", "sonnet");
    }
}
