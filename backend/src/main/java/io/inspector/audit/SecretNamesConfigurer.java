package io.inspector.audit;

import io.inspector.config.InspectorProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/** Installs the validated {@code inspector.secret-names.additional} into {@link SecretNames} at boot. */
@Component
class SecretNamesConfigurer implements InitializingBean {
    private final InspectorProperties properties;

    SecretNamesConfigurer(InspectorProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        SecretNames.configure(properties.secretNamesOrDefault().additional());
    }
}
