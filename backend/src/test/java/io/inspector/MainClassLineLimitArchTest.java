package io.inspector;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Size guard for issue #475: a top-level class under {@code backend/src/main} whose source is over
 * 800 lines ({@code wc -l}, newline count) fails the unit suite. The dated allowlist is only for
 * classes this change does not split; each entry names why and points at #475. The four targets
 * (BulkJobService, InstanceDetailService, SearchService, ProcessApiClient) must not be listed.
 */
@AnalyzeClasses(packages = "io.inspector", importOptions = ImportOption.DoNotIncludeTests.class)
class MainClassLineLimitArchTest {

    static final int MAX_LINES = 800;

    /** Empty on the red commit. Split commit adds dated reasons for classes left over 800 lines. */
    private static final List<Allowance> ALLOWLIST = List.of();

    @ArchTest
    static final ArchRule MAIN_CLASSES_STAY_AT_OR_UNDER_800_LINES = classes()
            .that()
            .areTopLevelClasses()
            .should(
                    new ArchCondition<JavaClass>(
                            "stay at or under 800 source lines (wc -l), or be on the dated #475 allowlist") {
                        @Override
                        public void check(JavaClass javaClass, ConditionEvents events) {
                            String name = javaClass.getName();
                            if (ALLOWLIST.stream()
                                    .anyMatch(entry -> entry.className().equals(name))) {
                                return;
                            }
                            Path source = mainSource(javaClass);
                            if (source == null) {
                                events.add(SimpleConditionEvent.violated(
                                        javaClass, name + " has no backend/src/main source file to count"));
                                return;
                            }
                            int lines = lineCount(source);
                            if (lines > MAX_LINES) {
                                events.add(SimpleConditionEvent.violated(
                                        javaClass,
                                        name + " has " + lines + " lines in " + source + " (limit " + MAX_LINES + ")"));
                            }
                        }
                    })
            .because("issue #475: classes under backend/src/main must stay at or under 800 lines;"
                    + " a dated allowlist entry must name why and point at #475");

    private static Path mainSource(JavaClass javaClass) {
        String relative = javaClass.getPackageName().replace('.', '/') + "/" + javaClass.getSimpleName() + ".java";
        Path root = mainSourceRoot();
        Path source = root.resolve(relative);
        return Files.isRegularFile(source) ? source : null;
    }

    private static Path mainSourceRoot() {
        try {
            Path classes = Path.of(MainClassLineLimitArchTest.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            Path backend = classes.getParent().getParent();
            Path root = backend.resolve("src/main/java");
            if (Files.isDirectory(root)) {
                return root;
            }
        } catch (URISyntaxException e) {
            throw new IllegalStateException("cannot resolve test-classes location", e);
        }
        Path cwd = Path.of("src/main/java");
        if (Files.isDirectory(cwd)) {
            return cwd;
        }
        throw new IllegalStateException("cannot locate backend/src/main/java");
    }

    /** {@code wc -l}: count newline bytes, not logical lines. */
    private static int lineCount(Path source) {
        try {
            byte[] bytes = Files.readAllBytes(source);
            int newlines = 0;
            for (byte b : bytes) {
                if (b == '\n') {
                    newlines++;
                }
            }
            return newlines;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record Allowance(String className, String datedReason) {}
}
