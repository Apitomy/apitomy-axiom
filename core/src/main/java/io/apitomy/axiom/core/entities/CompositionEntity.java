package io.apitomy.axiom.core.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A workbench composition: one workflow plus the action types and tools it uses, edited as drafts by the user
 * and the AI until published.
 */
@Entity
@Table(name = "composition")
public class CompositionEntity extends PanacheEntity {

    /** No AI turn is running. */
    public static final String TURN_IDLE = "idle";
    /** An AI turn is running. */
    public static final String TURN_AI_WORKING = "ai-working";
    /** AI workflow change sets are applied immediately. */
    public static final String APPLY_LIVE = "live";
    /** AI workflow change sets are staged for review. */
    public static final String APPLY_REVIEW = "review";

    @Column(nullable = false)
    public String name;

    @Column(columnDefinition = "TEXT")
    public String goal;

    public String engine;

    public String model;

    @Column(name = "assistant_session_id")
    public String assistantSessionId;

    @Column(name = "turn_state", nullable = false)
    public String turnState = TURN_IDLE;

    @Column(name = "current_turn_id")
    public String currentTurnId;

    @Column(name = "apply_mode", nullable = false)
    public String applyMode = APPLY_LIVE;

    @Column(name = "pending_change_set", columnDefinition = "TEXT")
    public String pendingChangeSet;

    @Column(name = "pending_turn_id")
    public String pendingTurnId;

    /** When the most recent AI turn started; user edits after it are summarized for the next turn. */
    @Column(name = "last_turn_started_on")
    public Instant lastTurnStartedOn;

    @Column(name = "created_on", nullable = false)
    public Instant createdOn;

    @Column(name = "updated_on", nullable = false)
    public Instant updatedOn;
}
