package io.github.jtconsole.security;

import io.github.jtconsole.security.SessionTokenService.AuthenticatedSession;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

final class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private final SessionTokenService tokens;
    private final JsonSecurityResponseWriter responses;

    BearerTokenAuthenticationFilter(
            SessionTokenService tokens,
            JsonSecurityResponseWriter responses) {
        this.tokens = tokens;
        this.responses = responses;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = SecurityRequestPath.of(request);
        String method = request.getMethod();
        return (HttpMethod.POST.matches(method)
                        && ("/api/auth/login".equals(path)
                                || "/api/auth/refreshToken".equals(path)
                                || "/ingest/jt-events".equals(path)))
                || path.equals("/actuator/health")
                || path.startsWith("/actuator/health/")
                || path.equals("/ws")
                || path.startsWith("/ws/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null) {
            filterChain.doFilter(request, response);
            return;
        }

        Optional<String> accessToken = BearerTokenSupport.parse(authorization);
        if (accessToken.isEmpty()) {
            responses.unauthorized(response);
            return;
        }
        Optional<AuthenticatedSession> authenticatedSession =
                tokens.validateAccessSession(accessToken.get());
        if (authenticatedSession.isEmpty()) {
            responses.unauthorized(response);
            return;
        }

        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        authenticatedSession.get(),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        authentication.setDetails(
                new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        filterChain.doFilter(request, response);
    }
}
