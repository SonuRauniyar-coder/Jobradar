package com.jobradar.auth;

import com.jobradar.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}
    public record LoginResponse(String token, String email) {}

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwtService;
    private final LoginThrottle throttle;
    /** Checked when the email is unknown, so a wrong email takes as long as a wrong password. */
    private final String dummyHash;

    public AuthController(UserRepository users, PasswordEncoder encoder, JwtService jwtService, LoginThrottle throttle) {
        this.users = users;
        this.encoder = encoder;
        this.jwtService = jwtService;
        this.throttle = throttle;
        this.dummyHash = encoder.encode("not-a-real-password");
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        String address = http.getRemoteAddr();
        long wait = throttle.retryAfterSeconds(request.email(), address);
        if (wait > 0) {
            long minutes = Math.max(1, (wait + 59) / 60);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many failed sign-in attempts. Try again in " + minutes + (minutes == 1 ? " minute." : " minutes."));
        }
        Optional<User> user = users.findByEmailIgnoreCase(request.email());
        boolean matches = encoder.matches(request.password(), user.map(User::getPasswordHash).orElse(dummyHash));
        if (user.isEmpty() || !matches) {
            throttle.recordFailure(request.email(), address);
            throw new BadCredentialsException("bad credentials");
        }
        throttle.recordSuccess(request.email());
        return new LoginResponse(jwtService.issue(user.get().getId()), user.get().getEmail());
    }
}
