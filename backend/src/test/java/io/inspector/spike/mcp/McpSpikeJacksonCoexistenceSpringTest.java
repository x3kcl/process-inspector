package io.inspector.spike.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.inspector.support.NoDbTestSupport;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

/**
 * SPIKE #492 S0 U1 (NOT FOR MERGE) — with the Spring AI MCP starter on the classpath: which JSON mapper
 * serves the existing controllers, which one the MCP transport uses, and is there a bean conflict.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
class McpSpikeJacksonCoexistenceSpringTest {

    @Autowired
    ApplicationContext ctx;

    @Autowired
    RequestMappingHandlerAdapter mvc;

    @Test
    void controllersStayOnJackson2AndMcpUsesItsOwnJackson3Mapper() {
        List<String> converters = mvc.getMessageConverters().stream()
                .map(HttpMessageConverter::getClass)
                .map(Class::getName)
                .toList();
        System.out.println("SPIKE-CONVERTERS " + converters);
        // Same list as origin/main except the YAML converter: main has MappingJackson2YamlHttpMessageConverter,
        // the starter's transitive jackson-dataformat-yaml 3.x swaps in JacksonYamlHttpMessageConverter.
        // ProjectingJacksonHttpMessageConverter (Spring Data) is first on main too.
        assertThat(converters).contains("org.springframework.http.converter.json.MappingJackson2HttpMessageConverter");
        assertThat(converters)
                .doesNotContain("org.springframework.http.converter.json.JacksonJsonHttpMessageConverter");
        assertThat(converters).contains("org.springframework.http.converter.yaml.JacksonYamlHttpMessageConverter");

        String[] j2 = ctx.getBeanNamesForType(com.fasterxml.jackson.databind.ObjectMapper.class);
        String[] j3 = ctx.getBeanNamesForType(tools.jackson.databind.json.JsonMapper.class);
        System.out.println("SPIKE-MAPPER-BEANS jackson2 ObjectMapper=" + Arrays.toString(j2) + " jackson3 JsonMapper="
                + Arrays.toString(j3));
        assertThat(j3).contains("mcpServerJsonMapper");
        // defaultCandidate=false: an unqualified JsonMapper injection never picks the MCP mapper.
        assertThat(((org.springframework.context.ConfigurableApplicationContext) ctx)
                                .getBeanFactory()
                                .getBeanDefinition("mcpServerJsonMapper")
                                .isAutowireCandidate()
                        && ((org.springframework.beans.factory.support.AbstractBeanDefinition)
                                        ((org.springframework.context.ConfigurableApplicationContext) ctx)
                                                .getBeanFactory()
                                                .getBeanDefinition("mcpServerJsonMapper"))
                                .isDefaultCandidate())
                .isFalse();
        var transport =
                ctx.getBean(org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport.class);
        System.out.println("SPIKE-TRANSPORT " + transport.getClass().getName());
    }
}
