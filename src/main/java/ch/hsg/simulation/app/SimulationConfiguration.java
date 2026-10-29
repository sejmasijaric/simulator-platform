package ch.hsg.simulation.app;

import ch.hsg.simulation.api.SimulationDefinition;
import ch.hsg.simulation.runtime.SimulationManager;
import ch.hsg.simulation.systems.demo.DemoLayoutDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the core. Layouts are registered by declaring their {@link SimulationDefinition} as a bean. */
@Configuration
public class SimulationConfiguration {

    @Bean(destroyMethod = "close")
    public SimulationManager simulationManager(ObjectProvider<SimulationDefinition> definitions) {
        return new SimulationManager(definitions.orderedStream().toList());
    }

    @Bean
    @ConditionalOnProperty(name = "simulation.layouts.demo.enabled", havingValue = "true", matchIfMissing = true)
    public SimulationDefinition demoLayout() {
        return new DemoLayoutDefinition();
    }
}
