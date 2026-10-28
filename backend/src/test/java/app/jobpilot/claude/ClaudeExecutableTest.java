package app.jobpilot.claude;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import app.jobpilot.settings.JobPilotProperties;

class ClaudeExecutableTest {

    private static JobPilotProperties.Claude claude(String path) {
        return new JobPilotProperties.Claude(path, "", 180, 600, 2);
    }

    @Test
    void anExplicitPathIsUsedAsGiven() {
        assertThat(claude("/opt/tools/claude").executable()).isEqualTo("/opt/tools/claude");
    }

    @Test
    void aNameThatIsNowhereIsLeftAloneSoTheErrorNamesIt() {
        assertThat(claude("no-such-claude-binary").executable()).isEqualTo("no-such-claude-binary");
    }

    @Test
    void aBareNameIsFoundInAnUsualInstallDirectoryEvenWhenPathLacksIt() throws Exception {
        java.nio.file.Path home = java.nio.file.Files.createTempDirectory("jp-home");
        java.nio.file.Path bin = java.nio.file.Files.createDirectories(home.resolve(".local/bin"));
        java.nio.file.Path exe = java.nio.file.Files.createFile(bin.resolve("fake-claude-for-test"));
        exe.toFile().setExecutable(true);
        String old = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            assertThat(claude("fake-claude-for-test").executable()).isEqualTo(exe.toString());
        } finally {
            System.setProperty("user.home", old);
        }
    }
}
