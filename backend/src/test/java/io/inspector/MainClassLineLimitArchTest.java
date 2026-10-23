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
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Size guard for issue #475: a top-level class under {@code backend/src/main} whose source is over
 * 800 lines ({@code wc -l}, newline count) fails the unit suite. The dated allowlist is only for
 * classes this change does not split; each entry names why and points at #475. The four targets
 * (BulkJobService, InstanceDetailService, SearchService, ProcessApiClient) must not be listed.
 */
@AnalyzeClasses(packages = "io.inspector", importOptions = ImportOption.DoNotIncludeTests.class)
class MainClassLineLimitArchTest {

    static final int MAX_LINES = 800;

    /**
     * Dated exceptions for classes this change does not split. Each reason names why and points at
     * #475. The four targets (BulkJobService, InstanceDetailService, SearchService, ProcessApiClient)
     * are not listed.
     */
    private static final List<Allowance> ALLOWLIST = List.of(
            new Allowance(
                    "io.inspector.action.CorrectiveActionService",
                    "2026-10-23: 1565-line corrective-action verb rail, outside the four #475 seams;"
                            + " splitting it is a separate change (issue #475)"),
            new Allowance(
                    "io.inspector.config.InspectorProperties",
                    "2026-10-23: 1122-line configuration binding after the #476 constraints,"
                            + " not a service seam; splitting the property tree risks binding behavior"
                            + " (issue #475)"));

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
                            Allowance allowed = ALLOWLIST.stream()
                                    .filter(entry -> entry.className().equals(name))
                                    .findFirst()
                                    .orElse(null);
                            if (allowed != null) {
                                String reason = allowed.datedReason();
                                if (!reason.contains("#475") && !reason.contains("#474")) {
                                    events.add(SimpleConditionEvent.violated(
                                            javaClass,
                                            name + " is allowlisted without an issue pointer (#475 or #474): "
                                                    + reason));
                                }
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

    private static final Pattern TOP_LEVEL_TYPE =
            Pattern.compile("^(?:(?:public|protected|private|abstract|final|sealed|non-sealed|static)\\s+)*"
                    + "(?:class|interface|enum|record)\\s+(\\w+)\\b");

    private static Path mainSource(JavaClass javaClass) {
        Path pkg = mainSourceRoot().resolve(javaClass.getPackageName().replace('.', '/'));
        Path direct = pkg.resolve(javaClass.getSimpleName() + ".java");
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        if (!Files.isDirectory(pkg)) {
            return null;
        }
        try (Stream<Path> files = Files.list(pkg)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .toList()) {
                for (String line : Files.readAllLines(file)) {
                    var matcher = TOP_LEVEL_TYPE.matcher(line);
                    if (matcher.find() && javaClass.getSimpleName().equals(matcher.group(1))) {
                        return file;
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return null;
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
