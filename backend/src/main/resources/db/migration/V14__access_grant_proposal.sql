-- v2 IdP-Security S4 (IDP-SECURITY.md §6/§9, R-SAFE-14): the four-eyes proposal store. A mapping
-- write that WIDENS the effective grant set — a self-widen, any ≥OPERATOR grant with a wildcard
-- engine/tenant, any fleet-grant create, or any fleet-grant removal — is not applied directly: it
-- becomes a PENDING proposal that a SECOND, independent ACCESS_ADMIN (∉ the affected group, ≠ the
-- proposer) must approve before it applies. Single-actor writes (ladder narrow/remove) never touch
-- this table. The change spec is a JSON payload so one table covers ladder + fleet adds/removes.
CREATE TABLE access_grant_proposal (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    proposer     text NOT NULL,
    group_name   text NOT NULL,          -- the affected group (∉ test is computed against this)
    change_kind  text NOT NULL CHECK (change_kind IN
                    ('LADDER_ADD', 'LADDER_REMOVE', 'FLEET_ADD', 'FLEET_REMOVE')),
    change_json  text NOT NULL,          -- the serialized GrantChange (role/engine/tenant or fleet kind)
    summary      text NOT NULL,          -- human-legible one-liner for the R-SAFE-08 inbox
    reason       text NOT NULL,
    status       text NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED')),
    created_at   timestamptz NOT NULL,
    expires_at   timestamptz NOT NULL,
    approver     text,
    decided_at   timestamptz
);

CREATE INDEX idx_access_grant_proposal_status ON access_grant_proposal (status);
