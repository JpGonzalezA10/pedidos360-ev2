package com.api.rabbitadmin.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/*
 * Autenticacion simple por API key para llamadas internas desde API Gateway.
 * Si no hay clave configurada, el filtro queda desactivado para facilitar dev local.
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final String apiKey;
    private final String headerName;

    public ApiKeyAuthFilter(
            @Value("${rabbit-admin.security.api-key:}") String apiKey,
            @Value("${rabbit-admin.security.header-name:X-API-Key}") String headerName) {
        this.apiKey = apiKey;
        this.headerName = headerName;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return apiKey == null
                || apiKey.isBlank()
                || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String providedApiKey = request.getHeader(headerName);
        if (!matchesConfiguredApiKey(providedApiKey)) {
            writeUnauthorizedResponse(request, response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean matchesConfiguredApiKey(String providedApiKey) {
        if (providedApiKey == null) {
            return false;
        }
        return MessageDigest.isEqual(
                apiKey.getBytes(StandardCharsets.UTF_8),
                providedApiKey.getBytes(StandardCharsets.UTF_8));
    }

    private void writeUnauthorizedResponse(
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("""
                {"timestamp":"%s","status":401,"error":"Unauthorized","message":"API key invalida o ausente","path":"%s","details":{}}
                """.formatted(OffsetDateTime.now(), request.getRequestURI()));
    }
}
