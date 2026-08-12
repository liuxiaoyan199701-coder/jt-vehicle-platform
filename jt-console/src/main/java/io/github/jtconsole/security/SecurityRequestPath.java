package io.github.jtconsole.security;

import jakarta.servlet.http.HttpServletRequest;

final class SecurityRequestPath {

    private SecurityRequestPath() {
    }

    static String of(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        if (servletPath != null && !servletPath.isEmpty()) {
            return servletPath;
        }
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null
                && !contextPath.isEmpty()
                && requestUri.startsWith(contextPath)) {
            return requestUri.substring(contextPath.length());
        }
        return requestUri;
    }
}
