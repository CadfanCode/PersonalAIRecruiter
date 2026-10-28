package app.jobpilot.common;

import java.io.IOException;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import app.jobpilot.settings.JobPilotProperties;

/**
 * CSRF protection for a localhost API without authentication (SPEC 3): a browser always sends
 * {@code Origin} on cross-site requests, so any Origin outside the allow-list is refused. Requests
 * without an Origin header (curl, same-origin GET, the Vite proxy) pass.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OriginCheckFilter extends OncePerRequestFilter {

    private final Set<String> allowed;

    public OriginCheckFilter(JobPilotProperties props, @Value("${server.port:8080}") int port) {
        // The app's own address is always allowed, whichever port the installed app found free.
        Set<String> own = Set.of("http://127.0.0.1:" + port, "http://localhost:" + port);
        this.allowed = new java.util.HashSet<>(props.allowedOrigins());
        this.allowed.addAll(own);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (origin != null && !allowed.contains(origin)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Origin not allowed");
            return;
        }
        chain.doFilter(request, response);
    }
}
