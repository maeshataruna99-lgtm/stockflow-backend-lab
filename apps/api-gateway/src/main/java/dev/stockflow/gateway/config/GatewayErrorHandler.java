package dev.stockflow.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Turns any exception raised in the gateway into the problem body. The real cause goes to the log, not the client. */
@Component
@Order(-2) // ahead of Spring Boot's DefaultErrorWebExceptionHandler (-1), which exposes its own JSON
public class GatewayErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    private final ProblemWriter problems;

    public GatewayErrorHandler(ProblemWriter problems) {
        this.problems = problems;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(error);
        }
        HttpStatusCode status = error instanceof ResponseStatusException rse
                ? rse.getStatusCode() : HttpStatus.INTERNAL_SERVER_ERROR;
        if (status.is5xxServerError()) {
            log.error("Gateway error {} for {} {}", status.value(), exchange.getRequest().getMethod(),
                    exchange.getRequest().getURI().getPath(), error);
        } else {
            log.debug("Gateway error {} for {}", status.value(), exchange.getRequest().getURI().getPath(), error);
        }
        return problems.write(exchange, status, ProblemWriter.detailOf(status), null);
    }
}
