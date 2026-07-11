package io.inspector.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The acknowledge overlay on one Stage 0 error group (R-BAU-01), joined from the BFF's
 * own store at RENDER time — never part of the cached engine aggregation. Display fields
 * come from the newest slice row; the baselines drive the auto-resurface predicate
 * ({@code io.inspector.triage.ErrorGroupAckPolicy}).
 *
 * <p>{@code resurfaced=false} → the UI collapses the group into the labeled
 * "Acknowledged (N)" section (never hidden). {@code resurfaced=true} → the group renders
 * among the active cards again, badged by {@code resurfaceReason}:
 * {@code grew} ("GREW SINCE ACK: +n") · {@code new-version} · {@code expired}.
 *
 * <p>{@code NON_NULL}: absent optionals are omitted on the wire, matching the generated
 * contract's {@code field?:} optionality (external review, W3-2).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorGroupAcknowledgement(
        String acknowledgedBy,
        String acknowledgedAt, // ISO-8601 UTC
        String reason,
        String ticketId, // nullable
        String expiresAt, // nullable ISO-8601 UTC
        long acknowledgedTotal, // summed member-count baseline across the ack's slices
        boolean resurfaced,
        String resurfaceReason, // grew | new-version | expired — null while collapsed
        long grownBy) {} // max(0, current total − acknowledged total), always reported
