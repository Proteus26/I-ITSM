package com.itsm.config

import com.itsm.auth.AuthProperties
import com.nimbusds.jose.jwk.source.ImmutableSecret
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import tools.jackson.databind.json.JsonMapper
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Stateless JWT resource-server security: public auth/health/docs endpoints,
 * admin-only /api/admin, authentication required for everything else.
 */
@Configuration
@EnableMethodSecurity
class SecurityConfig(
    @Value("\${app.cors.allowed-origins}") private val allowedOrigins: List<String>,
) {

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        entryPoint: AuthenticationEntryPoint,
        deniedHandler: AccessDeniedHandler,
    ): SecurityFilterChain {
        http
            // CSRF protects cookie-based sessions; we use stateless bearer tokens, so it is not needed.
            .csrf { it.disable() }
            .cors { }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers(
                    "/api/auth/login", "/api/auth/register", "/api/ping",
                    "/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error",
                ).permitAll()
                it.requestMatchers("/api/admin/**").hasRole("ADMIN")
                it.anyRequest().authenticated()
            }
            .oauth2ResourceServer { rs ->
                rs.jwt { it.jwtAuthenticationConverter(::convert) }
                rs.authenticationEntryPoint(entryPoint)
                rs.accessDeniedHandler(deniedHandler)
            }
            .exceptionHandling {
                it.authenticationEntryPoint(entryPoint)
                it.accessDeniedHandler(deniedHandler)
            }
        return http.build()
    }

    /** Maps the single role claim to one ROLE_ authority; sub becomes the principal name. */
    private fun convert(jwt: Jwt): AbstractAuthenticationToken {
        val authorities = jwt.getClaimAsString("role")?.let { listOf(SimpleGrantedAuthority("ROLE_$it")) } ?: emptyList()
        return JwtAuthenticationToken(jwt, authorities, jwt.subject)
    }

    @Bean
    fun jwtSecretKey(properties: AuthProperties): SecretKey {
        val bytes = properties.jwtSecret.toByteArray(Charsets.UTF_8)
        check(bytes.size >= 32) {
            "JWT secret is missing or too short: set the JWT_SECRET environment variable " +
                "(app.auth.jwt-secret) to a random string of at least 32 bytes, e.g. `export JWT_SECRET=\$(openssl rand -base64 48)`."
        }
        return SecretKeySpec(bytes, "HmacSHA256")
    }

    @Bean
    fun jwtEncoder(key: SecretKey): JwtEncoder = NimbusJwtEncoder(ImmutableSecret(key))

    @Bean
    fun jwtDecoder(key: SecretKey, properties: AuthProperties): JwtDecoder =
        NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build().apply {
            setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer))
        }

    @Bean
    fun authenticationEntryPoint(mapper: JsonMapper): AuthenticationEntryPoint =
        AuthenticationEntryPoint { request, response, _ ->
            response.setHeader("WWW-Authenticate", "Bearer")
            writeProblem(mapper, request, response, HttpStatus.UNAUTHORIZED, "Unauthorized", "Authentication required")
        }

    @Bean
    fun accessDeniedHandler(mapper: JsonMapper): AccessDeniedHandler =
        AccessDeniedHandler { request, response, _ ->
            writeProblem(mapper, request, response, HttpStatus.FORBIDDEN, "Forbidden", "Access denied")
        }

    private fun writeProblem(
        mapper: JsonMapper,
        request: HttpServletRequest,
        response: HttpServletResponse,
        status: HttpStatus,
        title: String,
        detail: String,
    ) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = "UTF-8"
        val body = linkedMapOf<String, Any>(
            "type" to "about:blank",
            "title" to title,
            "status" to status.value(),
            "detail" to detail,
            "instance" to request.requestURI,
        )
        response.writer.write(mapper.writeValueAsString(body))
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val config = CorsConfiguration().apply {
            allowedOrigins = this@SecurityConfig.allowedOrigins
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("*")
            allowCredentials = true
        }
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/**", config) }
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()
}
