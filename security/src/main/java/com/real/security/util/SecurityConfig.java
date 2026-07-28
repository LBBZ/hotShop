package com.real.security.util;

import com.real.security.api.ProblemAccessDeniedHandler;
import com.real.security.api.ProblemAuthenticationEntryPoint;
import com.real.security.config.SecurityProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    private final JwtFilter jwtFilter;
    private final ProblemAuthenticationEntryPoint authenticationEntryPoint;
    private final ProblemAccessDeniedHandler accessDeniedHandler;
    public SecurityConfig(
            JwtFilter jwtFilter,
            ProblemAuthenticationEntryPoint authenticationEntryPoint,
            ProblemAccessDeniedHandler accessDeniedHandler
    ) {
        this.jwtFilter = jwtFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // Bearer endpoints are stateless. Cookie refresh/logout endpoints apply an explicit,
                // domain-specific double-submit check in their controllers.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-resources/**",
                                "/webjars/**",
                                "/static/**",
                                "/favicon.ico").permitAll()
                        .requestMatchers(
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/logout",
                                "/api/v1/auth/refresh",
                                "/admin/api/v1/auth/login",
                                "/admin/api/v1/auth/logout",
                                "/admin/api/v1/auth/refresh",
                                "/agent/api/v1/auth/token-exchange",
                                "/api/v1/products/**"
                        ).permitAll()
                        .requestMatchers("/api/v1/**").hasAuthority("ROLE_USER")
                        .requestMatchers("/admin/api/v1/**").hasAuthority("ROLE_ADMIN")
                        .requestMatchers("/agent/api/v1/**").hasAuthority("AGENT_DELEGATION")
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
