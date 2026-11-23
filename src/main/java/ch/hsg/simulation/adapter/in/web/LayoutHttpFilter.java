package ch.hsg.simulation.adapter.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Driving adapter: serves the endpoints the layouts declare (e.g. {@code /ov/burn}) through
 * {@link LayoutHttpApi}. A filter rather than a controller, because the paths are only known at
 * runtime and every other request (control API, web UI files) must pass through untouched.
 */
@Component
public class LayoutHttpFilter extends OncePerRequestFilter {

    private final LayoutHttpApi api;

    public LayoutHttpFilter(LayoutHttpApi api) {
        this.api = api;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !api.handles(request.getMethod(), path(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Map<String, String> parameters = new LinkedHashMap<>();
        request.getParameterMap().forEach((name, values) -> parameters.put(name, values.length > 0 ? values[0] : ""));
        Optional<LayoutHttpApi.Response> answer = api.handle(new LayoutHttpApi.Request(
                request.getMethod(), path(request), parameters, request.getRequestURL().toString()));
        if (answer.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(answer.get().status());
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(answer.get().body());
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}
