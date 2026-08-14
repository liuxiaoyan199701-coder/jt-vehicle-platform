package io.github.jtconsole.security;

import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.gateway.DeviceRegistryKeyFilter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService disabledDefaultUserDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("默认用户名密码认证已禁用");
        };
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(ConsoleProperties properties) {
        List<String> origins = properties.getSecurity().getAllowedOrigins().stream()
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
        if (properties.getSecurity().isDeploymentMode()
                && (origins.isEmpty() || origins.contains("*"))) {
            throw new IllegalStateException("部署模式必须配置非通配的 Web Origin 白名单");
        }

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(new ArrayList<>(origins));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                HttpHeaders.AUTHORIZATION,
                HttpHeaders.CONTENT_TYPE,
                IngestKeyAuthenticationFilter.INGEST_KEY_HEADER));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SessionTokenService tokens,
            IngestKeyProvider ingestKeys,
            JsonSecurityResponseWriter responses,
            CorsConfigurationSource corsConfigurationSource,
            ConsoleProperties properties) throws Exception {
        BearerTokenAuthenticationFilter bearerFilter =
                new BearerTokenAuthenticationFilter(tokens, responses);
        IngestKeyAuthenticationFilter ingestFilter =
                new IngestKeyAuthenticationFilter(ingestKeys, responses);
        DeviceRegistryKeyFilter registryFilter =
                new DeviceRegistryKeyFilter(properties, responses);

        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .securityContext(context -> context.requireExplicitSave(false))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, failure) ->
                                responses.unauthorized(response))
                        .accessDeniedHandler((request, response, failure) ->
                                responses.forbidden(response)))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/login", "/api/auth/refreshToken")
                        .permitAll()
                        // 自助注册与验证码是公网入口，由图形验证码、来源限流与人工审批
                        // 三重约束，并可由配置整体关闭。
                        .requestMatchers(HttpMethod.GET, "/api/public/registration/captcha")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/public/registration")
                        .permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/ws/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/ingest/jt-events")
                        .permitAll()
                        // 网关设备档案接口由独立共享密钥过滤器把关，不走用户会话。
                        .requestMatchers("/gateway/device-registry")
                        .permitAll()
                        .requestMatchers("/api/**")
                        .authenticated()
                        .anyRequest()
                        .denyAll())
                .addFilterBefore(registryFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(ingestFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(bearerFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
