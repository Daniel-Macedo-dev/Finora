package com.finora.api.commitment;

import com.finora.api.commitment.CommitmentDtos.CommitmentRequest;
import com.finora.api.commitment.CommitmentDtos.CommitmentResponse;
import com.finora.api.commitment.CommitmentDtos.UpcomingResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/commitments")
public class CommitmentController {

    private final CommitmentService service;

    public CommitmentController(CommitmentService service) {
        this.service = service;
    }

    @GetMapping
    public List<CommitmentResponse> list() {
        return service.list();
    }

    @GetMapping("/upcoming")
    public UpcomingResponse upcoming(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(defaultValue = "2") int months) {
        return service.upcoming(from, months);
    }

    @PostMapping("/{id}/pause")
    public CommitmentResponse pause(@PathVariable Long id) {
        return service.pause(id);
    }

    @PostMapping("/{id}/resume")
    public CommitmentResponse resume(@PathVariable Long id) {
        return service.resume(id);
    }

    @PostMapping("/{id}/end")
    public CommitmentResponse end(@PathVariable Long id, @Valid @RequestBody EndRequest request) {
        return service.end(id, request.endDate());
    }

    public record EndRequest(@NotNull(message = "Informe a data de término.") LocalDate endDate) {
    }

    @GetMapping("/{id}")
    public CommitmentResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CommitmentResponse create(@Valid @RequestBody CommitmentRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public CommitmentResponse update(@PathVariable Long id, @Valid @RequestBody CommitmentRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
