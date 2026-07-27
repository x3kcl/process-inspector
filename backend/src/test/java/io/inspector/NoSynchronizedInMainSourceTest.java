package io.inspector;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Structural guard for CLAUDE.md's Java-21-VT rule (issue #306): with {@code
 * spring.threads.virtual.enabled=true}, {@code synchronized} around blocking I/O pins the
 * carrier thread — a defect class until the JDK 24 bump. Issue #309's ArchUnit spike (see
 * docs/TEST-STRATEGY.md §5a) tried to express this as a structural rule and DROPPED it:
 * {@code JavaModifier.SYNCHRONIZED} only sees {@code synchronized} METHODS, not the {@code
 * synchronized (lock) { … }} BLOCKS every real instance used, and ArchUnit's bytecode-level API
 * has no visibility into block-level monitor instructions — and back then, "blocking I/O" had no
 * closed structural definition short of a hand-maintained type list (some {@code synchronized}
 * usage might have been legitimately non-blocking).
 *
 * <p>Issue #327 (this fix) eliminated the last four sites (EngineRegistryStore,
 * AccessMappingAdminService, ScopeMappingService, BreakGlassAuditSink), all swapped to {@link
 * java.util.concurrent.locks.ReentrantLock}. That changes the expressibility calculus: the fuzzy
 * "which synchronized usage is around blocking I/O" question is moot when there is supposed to be
 * ZERO legitimate {@code synchronized} usage of ANY kind left in {@code io.inspector} main
 * sources — no hand-maintained list needed, no method-vs-block distinction needed. So this is a
 * plain source-text scan (ArchUnit genuinely cannot express "no synchronized block", per the
 * #309 spike) rather than an ArchUnit rule: every {@code .java} file under {@code
 * src/main/java/io/inspector} must contain zero occurrences of the {@code synchronized} keyword
 * outside comments. A future re-introduction (method OR block) fails this test — that is the
 * ban this issue asked for.
 */
class NoSynchronizedInMainSourceTest {

    private static final Path MAIN_SOURCE_ROOT = Path.of("src", "main", "java", "io", "inspector");

    // Strips /* block */ and // line comments (DOTALL/non-greedy for block comments) before the
    // keyword search, so this doc comment's own prose mentions of "synchronized" never self-trip.
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");
    private static final Pattern SYNCHRONIZED_KEYWORD = Pattern.compile("\\bsynchronized\\b");

    @Test
    void noSynchronizedKeywordOutsideComments() throws IOException {
        assertThat(Files.isDirectory(MAIN_SOURCE_ROOT))
                .as("expected %s to exist — run from the backend/ module directory", MAIN_SOURCE_ROOT)
                .isTrue();

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN_SOURCE_ROOT)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(path -> {
                String source;
                try {
                    source = Files.readString(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                String withoutComments = LINE_COMMENT
                        .matcher(BLOCK_COMMENT.matcher(source).replaceAll(""))
                        .replaceAll("");
                if (SYNCHRONIZED_KEYWORD.matcher(withoutComments).find()) {
                    offenders.add(MAIN_SOURCE_ROOT.relativize(path).toString());
                }
            });
        }

        assertThat(offenders)
                .as("synchronized (method or block) is banned in io.inspector main sources — use "
                        + "ReentrantLock instead (CLAUDE.md Java-21-VT rule, issue #306/#327)")
                .isEmpty();
    }
}
