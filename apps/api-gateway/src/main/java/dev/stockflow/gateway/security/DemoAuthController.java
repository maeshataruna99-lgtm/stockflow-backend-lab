package dev.stockflow.gateway.security;

import dev.stockflow.gateway.security.dto.LoginRequest;
import dev.stockflow.gateway.security.dto.TokenResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v1/auth")
@Profile("!prod")
public class DemoAuthController {

    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final PasswordEncoder passwordEncoder;

    private final String rootUsername;
    private final String rootHashedPassword;
    private final String demoUserHashedPassword;

    public DemoAuthController(
            JwtService jwtService,
            JwtProperties jwtProperties,
            PasswordEncoder passwordEncoder,
            @Value("${ROOT_USERNAME:root}") String rootUsername,
            @Value("${ROOT_PASSWORD:root_demo_password_123}") String rootPassword,
            @Value("${DEMO_USER_PASSWORD:user_demo_password_123}") String demoUserPassword
    ) {
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.passwordEncoder = passwordEncoder;
        this.rootUsername = rootUsername;
        this.rootHashedPassword = passwordEncoder.encode(rootPassword);
        this.demoUserHashedPassword = passwordEncoder.encode(demoUserPassword);
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        if (request == null || request.username() == null || request.password() == null) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_REQUEST, "Username and password must not be empty"
            );
            problem.setType(URI.create("urn:stockflow:problem:bad-request"));
            problem.setTitle("Bad Request");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
        }

        // 1. Root / Admin Account
        if (rootUsername.equalsIgnoreCase(request.username()) &&
                passwordEncoder.matches(request.password(), rootHashedPassword)) {
            String token = jwtService.generateToken(rootUsername, List.of("ROLE_ADMIN", "ROLE_USER"));
            return ResponseEntity.ok(new TokenResponse(token, "Bearer", jwtProperties.getExpirationSeconds()));
        }

        // 2. Demo User Account
        if ("demo-user".equalsIgnoreCase(request.username()) &&
                passwordEncoder.matches(request.password(), demoUserHashedPassword)) {
            String token = jwtService.generateToken("demo-user", List.of("ROLE_USER"));
            return ResponseEntity.ok(new TokenResponse(token, "Bearer", jwtProperties.getExpirationSeconds()));
        }

        // Authentication failed
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "Invalid username or password"
        );
        problem.setType(URI.create("urn:stockflow:problem:unauthorized"));
        problem.setTitle("Unauthorized");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }
}
