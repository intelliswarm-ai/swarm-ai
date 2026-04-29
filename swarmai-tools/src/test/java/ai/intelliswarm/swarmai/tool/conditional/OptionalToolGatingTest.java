package ai.intelliswarm.swarmai.tool.conditional;

import ai.intelliswarm.swarmai.tool.common.EmailTool;
import ai.intelliswarm.swarmai.tool.integrations.OpenApiToolkit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for @ConditionalOnClass guards on optional-dep tools.
 *
 * Several tools depend on libraries that are marked &lt;optional&gt;true&lt;/optional&gt;
 * in swarmai-tools/pom.xml so downstream consumers aren't forced to pull
 * every transitive (mail, swagger-parser, htmlunit, pdfbox, csv, ...).
 *
 * Without @ConditionalOnClass on the @Component, Spring would still try to
 * load the bean class during component scan and crash with NoClassDefFoundError
 * the moment a method signature references the missing optional class.
 *
 * These tests pin the gating contract: when the optional class is hidden,
 * context must load cleanly and the tool bean must NOT be present.
 */
class OptionalToolGatingTest {

    @Configuration
    @ComponentScan(basePackages = {
            "ai.intelliswarm.swarmai.tool.common",
            "ai.intelliswarm.swarmai.tool.integrations"
    })
    static class ScanConfig { }

    // ------------------------------------------------------------------ EmailTool

    @Test
    void emailTool_isAbsent_whenJavaMailSenderNotOnClasspath() {
        new ApplicationContextRunner()
                .withUserConfiguration(ScanConfig.class)
                .withClassLoader(new FilteredClassLoader(
                        "org.springframework.mail.javamail"))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(EmailTool.class);
                });
    }

    // ------------------------------------------------------------------ OpenApiToolkit

    @Test
    void openApiToolkit_isAbsent_whenSwaggerParserNotOnClasspath() {
        new ApplicationContextRunner()
                .withUserConfiguration(ScanConfig.class)
                .withClassLoader(new FilteredClassLoader(
                        "io.swagger.v3.parser"))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(OpenApiToolkit.class);
                });
    }
}
