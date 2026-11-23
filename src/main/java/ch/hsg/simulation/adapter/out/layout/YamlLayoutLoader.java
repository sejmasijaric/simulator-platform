package ch.hsg.simulation.adapter.out.layout;

import ch.hsg.simulation.core.net.NetDefinition;
import ch.hsg.simulation.core.port.LayoutSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Driven adapter for the layout port: reads every {@code *.yaml} layout bundled under
 * {@code src/main/resources/layouts/} and, if {@code simulation.layouts.dir} is set, every file
 * in that folder too, so new layouts can be added without rebuilding.
 */
@Component
public class YamlLayoutLoader implements LayoutSource {

    private static final Logger LOG = LoggerFactory.getLogger(YamlLayoutLoader.class);

    private final LayoutYamlParser parser = new LayoutYamlParser();
    private final String directory;

    public YamlLayoutLoader(@Value("${simulation.layouts.dir:}") String directory) {
        this.directory = directory;
    }

    @Override
    public List<NetDefinition> load() {
        List<NetDefinition> layouts = new ArrayList<>();
        try {
            Resource[] bundled = new PathMatchingResourcePatternResolver().getResources("classpath*:layouts/*.y*ml");
            for (Resource resource : bundled) {
                String yaml = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                layouts.add(parser.parse(yaml, "layouts/" + resource.getFilename()));
            }
            if (!directory.isBlank()) {
                try (Stream<Path> files = Files.list(Path.of(directory))) {
                    for (Path file : files.filter(YamlLayoutLoader::isYaml).sorted(Comparator.naturalOrder()).toList()) {
                        layouts.add(parser.parse(Files.readString(file), file.toString()));
                    }
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Reading layouts failed", exception);
        }
        layouts.forEach(layout -> LOG.info("Layout {} ({}): {} places, {} transitions, {} routes", layout.id(),
                layout.name(), layout.places().size(), layout.transitions().size(), layout.routes().size()));
        return layouts;
    }

    private static boolean isYaml(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }
}
