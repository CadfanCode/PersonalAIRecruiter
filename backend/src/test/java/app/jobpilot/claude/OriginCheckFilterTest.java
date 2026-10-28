package app.jobpilot.claude;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import jakarta.servlet.ServletException;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import app.jobpilot.common.OriginCheckFilter;
import app.jobpilot.settings.JobPilotProperties;

class OriginCheckFilterTest {

    OriginCheckFilter filter = new OriginCheckFilter(new JobPilotProperties(Path.of("/tmp/x"),
            List.of("http://localhost:5173", "http://127.0.0.1:5173"),
            new JobPilotProperties.Claude("claude", "", 1, 1, 1)), 18080);

    MockHttpServletResponse call(String path, String origin) throws ServletException, java.io.IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void theAppsOwnAddressIsAlwaysAllowed() throws Exception {
        assertThat(call("/api/runs", "http://127.0.0.1:18080").getStatus()).isEqualTo(200);
        assertThat(call("/api/runs", "http://localhost:18080").getStatus()).isEqualTo(200);
        assertThat(call("/api/runs", "http://127.0.0.1:18081").getStatus()).isEqualTo(403);
    }

    @Test
    void allowsConfiguredOrigins() throws Exception {
        assertThat(call("/api/runs", "http://localhost:5173").getStatus()).isEqualTo(200);
        assertThat(call("/api/runs", "http://127.0.0.1:5173").getStatus()).isEqualTo(200);
    }

    @Test
    void allowsRequestsWithoutOrigin() throws Exception {
        assertThat(call("/api/claude/health", null).getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsForeignOrigins() throws Exception {
        assertThat(call("/api/runs", "https://evil.example").getStatus()).isEqualTo(403);
        assertThat(call("/api/runs", "http://localhost:5174").getStatus()).isEqualTo(403);
        assertThat(call("/api/runs", "null").getStatus()).isEqualTo(403);
    }
}
