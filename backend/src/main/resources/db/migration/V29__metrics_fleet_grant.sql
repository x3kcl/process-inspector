-- #772: METRICS joins the orthogonal fleet grants (the scraper's grant for /actuator/prometheus and
-- /actuator/info). Widens the V13 CHECK on group_fleet_grant.grant_kind; no row changes.
ALTER TABLE group_fleet_grant DROP CONSTRAINT group_fleet_grant_grant_kind_check;
ALTER TABLE group_fleet_grant ADD CONSTRAINT group_fleet_grant_grant_kind_check
    CHECK (grant_kind IN ('REGISTRY_ADMIN', 'ACCESS_ADMIN', 'METRICS'));
