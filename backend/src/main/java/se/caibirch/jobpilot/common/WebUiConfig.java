package se.caibirch.jobpilot.common;

import java.io.IOException;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the built React app so one process answers both the UI and /api: from {@code jobpilot.web-dir} when set, else
 * from {@code web/} inside the jar when the installer bundled it. In development neither exists and Vite serves the UI.
 */
@Configuration
public class WebUiConfig implements WebMvcConfigurer {

    private static final String BUNDLED = "classpath:/web/";

    private final String webDir;

    public WebUiConfig(@Value("${jobpilot.web-dir:}") String webDir) {
        this.webDir = webDir;
    }

    /** The folder holding index.html, or null when there is no built UI to serve. */
    private String location() {
        if (!webDir.isBlank()) {
            return Path.of(webDir).toAbsolutePath().normalize().toUri().toString();
        }
        return new ClassPathResource("web/index.html").exists() ? BUNDLED : null;
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        if (location() != null) {
            // Spring does not route the bare "/" to the resource handler.
            registry.addViewController("/").setViewName("forward:/index.html");
        }
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = location();
        if (location == null) {
            return;
        }
        registry.addResourceHandler("/**").addResourceLocations(location).resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource root) throws IOException {
                        if (resourcePath.startsWith("api/")) {
                            return null; // an unknown API path is a 404, never the UI
                        }
                        Resource file = super.getResource(resourcePath, root);
                        if (file != null && file.isReadable()) {
                            return file;
                        }
                        // Client-side routes (/runs/3, /documents) load the single-page app.
                        Resource index = root.createRelative("index.html");
                        return index.isReadable() ? index : null;
                    }
                });
    }
}
