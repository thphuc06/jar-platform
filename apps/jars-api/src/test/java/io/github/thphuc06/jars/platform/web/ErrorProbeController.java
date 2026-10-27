package io.github.thphuc06.jars.platform.web;

import io.github.thphuc06.jars.shared.BusinessRuleViolationException;
import io.github.thphuc06.jars.shared.ConflictException;
import io.github.thphuc06.jars.shared.NotFoundException;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoints that raise each kind of error, so the error contract can be asserted in one place. */
@Hidden // test fixture, not part of the API: keep it out of the OpenAPI contract
@RestController
@RequestMapping("/api/v1/_probe")
class ErrorProbeController {

    record CreateThing(@NotBlank String name, @Positive BigDecimal amount) {}

    @PostMapping("/things")
    CreateThing create(@Valid @RequestBody CreateThing body) {
        return body;
    }

    @GetMapping("/things/{id}")
    String byId(@PathVariable UUID id) {
        throw new NotFoundException("thing", id);
    }

    @GetMapping("/page")
    String page(@RequestParam @Max(100) int limit) {
        return "ok";
    }

    @GetMapping("/rule")
    String rule() {
        throw new BusinessRuleViolationException("insufficient-jar-balance", "Jar PLAY has 120000.00 VND left");
    }

    @GetMapping("/conflict")
    String conflict() {
        throw new ConflictException("email-already-registered", "This e-mail is already linked to another account");
    }

    @GetMapping("/db")
    String db() {
        throw new DataIntegrityViolationException("duplicate key value violates unique constraint \"secret_index\"");
    }

    @GetMapping("/stale")
    String stale() {
        throw new ObjectOptimisticLockingFailureException("io.github.thphuc06.jars.ledger.internal.Jar", "jar-id");
    }

    @GetMapping("/boom")
    String boom() {
        throw new IllegalStateException("password=hunter2 leaked in an exception message");
    }
}
