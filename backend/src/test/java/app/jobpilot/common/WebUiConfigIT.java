package app.jobpilot.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import app.jobpilot.support.PostgresTestConfig;

/** The installed desktop app serves the built UI and the API from one process. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
class WebUiConfigIT {

    static Path web;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws IOException {
        web = Files.createTempDirectory("jobpilot-web");
        Files.writeString(web.resolve("index.html"), "<title>JobPilot</title>");
        Files.createDirectories(web.resolve("assets"));
        Files.writeString(web.resolve("assets/app.js"), "console.log('app')");
        registry.add("jobpilot.web-dir", () -> web.toString());
    }

    @Autowired
    MockMvc mvc;

    @Test
    void theRootAndClientSideRoutesLoadTheApp() throws Exception {
        mvc.perform(get("/")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl("/index.html"));
        mvc.perform(get("/aktivitet")).andExpect(status().isOk()).andExpect(content().string("<title>JobPilot</title>"));
        mvc.perform(get("/runs/3")).andExpect(status().isOk()).andExpect(content().string("<title>JobPilot</title>"));
    }

    @Test
    void realFilesAreServedAsThemselves() throws Exception {
        mvc.perform(get("/assets/app.js")).andExpect(status().isOk()).andExpect(content().string("console.log('app')"));
    }

    @Test
    void anUnknownApiPathIsA404NotTheApp() throws Exception {
        mvc.perform(get("/api/nope")).andExpect(status().isNotFound());
    }

    @Test
    void theApiStillAnswers() throws Exception {
        mvc.perform(get("/api/runs")).andExpect(status().isOk());
    }
}
