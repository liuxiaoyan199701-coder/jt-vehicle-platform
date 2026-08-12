package io.github.jtconsole.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

final class IngestKeyAuthenticationFilter extends OncePerRequestFilter {

    static final String INGEST_KEY_HEADER = "X-JT-Ingest-Key";

    private final IngestKeyProvider keys;
    private final JsonSecurityResponseWriter responses;

    IngestKeyAuthenticationFilter(
            IngestKeyProvider keys,
            JsonSecurityResponseWriter responses) {
        this.keys = keys;
        this.responses = responses;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod())
                || !"/ingest/jt-events".equals(SecurityRequestPath.of(request));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!keys.matches(request.getHeader(INGEST_KEY_HEADER))) {
            responses.unauthorized(response);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
