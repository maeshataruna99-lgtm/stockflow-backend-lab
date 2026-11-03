package dev.stockflow.gateway.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

/** Target of the circuit breaker fallback. It never reports success, so a failed write is never shown as done. */
@RestController
public class FallbackController {

    @RequestMapping("/fallback")
    public ResponseEntity<ProblemDetail> fallback(ServerWebExchange exchange) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, ProblemWriter.detailOf(HttpStatus.SERVICE_UNAVAILABLE));
        problem.setTitle(ProblemWriter.titleOf(HttpStatus.SERVICE_UNAVAILABLE));
        problem.setProperty("requestId", exchange.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }
}
