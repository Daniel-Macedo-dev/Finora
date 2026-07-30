package com.finora.api.offlinesync;

import com.finora.api.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Durable proof that one offline mutation already produced its side effect.
 *
 * <p>A receipt is written in the <em>same database transaction</em> as the
 * domain mutation it describes. That single fact is what makes an ambiguous
 * network failure safe: when the client loses the HTTP response and replays the
 * mutation, the server finds the receipt and returns the original result
 * instead of writing again. A successful domain write without its receipt, or a
 * receipt without its write, is never a valid state.
 *
 * <p>Receipts are recorded only for mutations that actually changed something.
 * Conflicts and rejections leave no receipt: nothing happened, so replaying
 * them simply recomputes the same outcome — and, if the blocking condition has
 * since cleared, is allowed to succeed.
 *
 * <p>Receipts are immutable once written. A repeated key with a different
 * request fingerprint is an {@link ConflictType#IDEMPOTENCY_KEY_REUSED}
 * conflict, never an overwrite.
 */
@Entity
@Table(name = "offline_mutation_receipts")
public class MutationReceipt extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    /** Client-generated idempotency key; unique per owner, never globally. */
    @Column(name = "client_mutation_id", nullable = false, updatable = false)
    private UUID clientMutationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, updatable = false, length = 30)
    private SyncResourceType resourceType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private SyncOperation operation;

    /** Lowercase hex SHA-256 over the canonical request; see RequestFingerprint. */
    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Column(name = "client_resource_id", updatable = false)
    private UUID clientResourceId;

    @Column(name = "resource_id", updatable = false)
    private Long resourceId;

    @Column(name = "resource_version", updatable = false)
    private Long resourceVersion;

    @Column(name = "result_code", nullable = false, updatable = false, length = 40)
    private String resultCode;

    /** The exact JSON body returned the first time, replayed verbatim. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_payload", nullable = false, updatable = false)
    private String responsePayload;

    protected MutationReceipt() {
    }

    public MutationReceipt(Long userId, UUID clientMutationId, SyncResourceType resourceType,
                           SyncOperation operation, String requestHash, String resultCode,
                           String responsePayload) {
        this.userId = userId;
        this.clientMutationId = clientMutationId;
        this.resourceType = resourceType;
        this.operation = operation;
        this.requestHash = requestHash;
        this.resultCode = resultCode;
        this.responsePayload = responsePayload;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public UUID getClientMutationId() {
        return clientMutationId;
    }

    public SyncResourceType getResourceType() {
        return resourceType;
    }

    public SyncOperation getOperation() {
        return operation;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public UUID getClientResourceId() {
        return clientResourceId;
    }

    public void setClientResourceId(UUID clientResourceId) {
        this.clientResourceId = clientResourceId;
    }

    public Long getResourceId() {
        return resourceId;
    }

    public void setResourceId(Long resourceId) {
        this.resourceId = resourceId;
    }

    public Long getResourceVersion() {
        return resourceVersion;
    }

    public void setResourceVersion(Long resourceVersion) {
        this.resourceVersion = resourceVersion;
    }

    public String getResultCode() {
        return resultCode;
    }

    public String getResponsePayload() {
        return responsePayload;
    }
}
