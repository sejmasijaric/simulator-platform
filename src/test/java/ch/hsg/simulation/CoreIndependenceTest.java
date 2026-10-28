package ch.hsg.simulation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR 0001: core packages use no framework and no adapter. */
class CoreIndependenceTest {

    private static final List<String> CORE = List.of("api", "model", "execution", "event", "runtime");

    @Test
    void corePackagesDoNotImportSpringOrAdapters() throws IOException {
        Path root = Path.of("src/main/java/ch/hsg/simulation");
        for (String pkg : CORE) {
            try (Stream<Path> files = Files.walk(root.resolve(pkg))) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(file);
                    assertThat(source).as(file.toString())
                            .doesNotContain("import org.springframework")
                            .doesNotContain("import ch.hsg.simulation.adapters")
                            .doesNotContain("import ch.hsg.simulation.app");
                }
            }
        }
    }
}
