package com.finora.api.offlinesync;

import com.finora.api.offlinesync.OfflineSyncDtos.MutationBatchRequest;
import com.finora.api.offlinesync.OfflineSyncDtos.MutationBatchResponse;
import jakarta.servlet.http.HttpServletRequest;
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

    /**
     * Declared size ceiling, refused before the body is read.
     *
     * <p>The per-operation and per-batch limits live in the service and are
     * enforced on the parsed request — by which point the body has already been
     * buffered. A caller announcing a gigabyte would be believed for a gigabyte
     * before being refused. A legitimate batch is at most 25 operations of
     * 64 KiB plus envelope overhead, so anything beyond this is not a large
     * batch; it is an attempt to make the server hold memory.
     */
    static final long MAX_DECLARED_BODY_BYTES = 2L * 1024 * 1024;

    private final OfflineSyncService service;

    public OfflineSyncController(OfflineSyncService service) {
        this.service = service;
    }

    /**
     * Replays one batch of queued mutations for the authenticated owner.
     *
     * <p>Each mutation is answered individually and in submission order, so a
     * 200 says the batch was processed — not that it succeeded. A caller must
     * read every {@code status} to know what happened.
     *
     * @param http    inspected only for the declared body size, refused before
     *                the body is read
     * @param request at most 25 mutations, 64 KiB each and 512 KiB in total
     * @return one result per input mutation, carrying the assigned server id
     *         and version on success, the server's snapshot on a conflict, or a
     *         typed error when a domain rule refused it
     * @throws SyncBatchRejectedException when the batch as a whole is
     *         unacceptable — too many operations, or a payload beyond the
     *         limits — answered as 422 with a {@code code}
     */
    @PostMapping("/mutations")
    public MutationBatchResponse replay(HttpServletRequest http,
                                        @Valid @RequestBody MutationBatchRequest request) {
        if (http.getContentLengthLong() > MAX_DECLARED_BODY_BYTES) {
            throw new SyncBatchRejectedException("SYNC_BATCH_PAYLOAD_TOO_LARGE",
                    "O lote de operações excede o tamanho máximo permitido.");
        }
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
