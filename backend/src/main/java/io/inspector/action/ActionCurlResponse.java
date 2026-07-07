package io.inspector.action;

/**
 * The server-computed copy-as-cURL for a proposed action (v1.x #6). Returned by
 * {@code POST …/actions/{verb}/curl}, which runs the SAME RBAC door as execute but touches
 * neither the engine nor the audit log — it only renders the command the modal is about to
 * dispatch. Rendered verbatim by the UI (never recomputed client-side).
 */
public record ActionCurlResponse(String curl) {}
