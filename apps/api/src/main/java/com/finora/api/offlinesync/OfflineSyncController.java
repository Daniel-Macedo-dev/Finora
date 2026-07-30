package com.finora.api.offlinesync;

import com.finora.api.offlinesync.OfflineSyncDtos.MutationBatchRequest;
import com.finora.api.offlinesync.OfflineSyncDtos.MutationBatchResponse;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The single entry point for replaying offline mutations.
 *
 * <p>Authentication and CSRF apply exactly as they do to every other unsafe
 * request — the queue changes when a mutation runs, not whether it is allowed
 * to. The owner is taken from the session; the request body has no place to
 * name a user.
 *
 * <p>A 200 means the batch was processed, not that every mutation succeeded:
 * each one carries its own status. Only a malformed or oversized batch fails
 * the request as a whole.
 */
@RestController
@RequestMapping("/api/offline-sync")
public class OfflineSyncController {

    private final OfflineSyncService service;

    public OfflineSyncController(OfflineSyncService service) {
        this.service = service;
    }

    @PostMapping("/mutations")
    public MutationBatchResponse replay(@Valid @RequestBody MutationBatchRequest request) {
        return service.replay(request);
    }

    @ExceptionHandler(SyncBatchRejectedException.class)
    ProblemDetail handleBatchRejected(SyncBatchRejectedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setTitle("Lote de sincronização inválido");
        problem.setType(URI.create("https://finora.app/errors/offline-sync-batch"));
        problem.setProperty("code", ex.getCode());
        return problem;
    }
}
