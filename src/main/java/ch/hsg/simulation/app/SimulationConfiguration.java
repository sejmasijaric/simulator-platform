package ch.hsg.simulation.app;

import ch.hsg.simulation.adapter.in.web.LayoutHttpApi;
import ch.hsg.simulation.core.port.LayoutSource;
import ch.hsg.simulation.core.port.SimulationControl;
import ch.hsg.simulation.core.runtime.SimulationManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Composition root: the core knows no Spring, so its one entry point, the
 * {@link SimulationManager} behind the control port, is created here from whichever
 * {@link LayoutSource} adapter is on the classpath.
 */
@Configuration
public class SimulationConfiguration {

    @Bean(destroyMethod = "close")
    public SimulationManager simulationManager(LayoutSource layouts) {
        return new SimulationManager(layouts);
    }

    @Bean
    public LayoutHttpApi layoutHttpApi(SimulationControl control) {
        return new LayoutHttpApi(control, Clock.systemDefaultZone());
    }
}
