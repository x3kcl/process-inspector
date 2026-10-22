-- #490: oauth2 client-credentials and custom-header registry auth.
-- Secret columns store env-var NAMES only.
ALTER TABLE engine_registry DROP CONSTRAINT engine_registry_auth_type_check;
ALTER TABLE engine_registry
    ADD CONSTRAINT engine_registry_auth_type_check
        CHECK (auth_type IN ('basic', 'bearer', 'none', 'oauth2-client-credentials', 'custom-header'));

ALTER TABLE engine_registry ADD COLUMN oauth_token_url text;
ALTER TABLE engine_registry ADD COLUMN oauth_client_id text;
ALTER TABLE engine_registry ADD COLUMN oauth_client_secret_ref text;
ALTER TABLE engine_registry ADD COLUMN oauth_scope text;
ALTER TABLE engine_registry ADD COLUMN oauth_audience text;
ALTER TABLE engine_registry ADD COLUMN auth_header_name text;
ALTER TABLE engine_registry ADD COLUMN auth_header_secret_ref text;
