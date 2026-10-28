package app.jobpilot.claude;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class ModelStageTest {

    /** A new prompt without a stage would silently run on the default model, so every prompt must be placed (or named here). */
    @Test
    void everyPromptTemplateBelongsToAStageExceptTheConnectionCheck() throws Exception {
        Path dir = Path.of("src/main/resources/claude/prompts");
        List<String> names;
        try (Stream<Path> files = Files.list(dir)) {
            names = files.map(p -> p.getFileName().toString().replaceFirst("\\.md$", "")).sorted().toList();
        }

        assertThat(names).isNotEmpty();
        for (String name : names) {
            if (!name.equals("health")) {
                assertThat(ModelStage.ofTask(name)).as("stage of task " + name).isPresent();
            }
        }
        assertThat(ModelStage.ofTask("health")).isEmpty();
        assertThat(ModelStage.ofTask(null)).isEmpty();
    }

    @Test
    void theHighVolumeAndTheWritingTasksAreSeparateStages() {
        assertThat(ModelStage.ofTask("scoreJob")).contains(ModelStage.RANKING);
        assertThat(ModelStage.ofTask("writeCv")).contains(ModelStage.DOCUMENTS);
        assertThat(ModelStage.ofTask("writeLetter")).contains(ModelStage.DOCUMENTS);
        assertThat(ModelStage.ofTask("mapFormFields")).contains(ModelStage.AUTOFILL);
        assertThat(ModelStage.ofTask("buildSearchProfile")).contains(ModelStage.SEARCH);
    }
}
