package com.itsm.auth

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** Binds the app.auth section of application.yml. */
@ConfigurationProperties(prefix = "app.auth")
class AuthProperties {
    var jwtSecret: String = ""
    var tokenTtl: Duration = Duration.ofHours(8)
    var issuer: String = "itsm-backend"
    var bootstrapAdmin: BootstrapAdmin = BootstrapAdmin()
    var loginThrottle: LoginThrottle = LoginThrottle()

    /**
     * Every login attempt for one email counts (failed ones, and ones for disabled accounts). When [maxAttempts] is
     * reached the email is locked for [window], measured from that moment; further logins get 429 until then.
     */
    class LoginThrottle {
        var maxAttempts: Int = 5
        var window: Duration = Duration.ofMinutes(15)
    }

    class BootstrapAdmin {
        var email: String = ""
        var password: String = ""
        var fullName: String = "System Administrator"
    }
}
