package se.caibirch.jobpilot.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the built React app from {@code jobpilot.web-dir} when the installed desktop app sets it, so one process
 * answers both the UI and /api. In development the property is empty and Vite serves the UI instead.
 */
@Configuration
public class WebUiConfig implements WebMvcConfigurer {

    private final String webDir;

    public WebUiConfig(@Value("${jobpilot.web-dir:}") String webDir) {
        this.webDir = webDir;
    }

    @Override
    public void addViewControllers(org.springframework.web.servlet.config.annotation.ViewControllerRegistry registry) {
        if (!webDir.isBlank()) {
            // Spring does not route the bare "/" to the resource handler.
            registry.addViewController("/").setViewName("forward:/index.html");
        }
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (webDir.isBlank()) {
            return;
        }
        Path root = Path.of(webDir).toAbsolutePath().normalize();
        registry.addResourceHandler("/**").addResourceLocations(root.toUri().toString()).resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        if (resourcePath.startsWith("api/")) {
                            return null; // an unknown API path is a 404, never the UI
                        }
                        Resource file = super.getResource(resourcePath, location);
                        if (file != null && file.isReadable()) {
                            return file;
                        }
                        // Client-side routes (/runs/3, /aktivitet) load the single-page app.
                        Path index = root.resolve("index.html");
                        return Files.isReadable(index) ? new FileSystemResource(index) : null;
                    }
                });
    }
}
