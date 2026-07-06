package io.inspector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ProcessInspectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProcessInspectorApplication.class, args);
    }
}
