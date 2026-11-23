package ch.hsg.simulation.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"ch.hsg.simulation.app", "ch.hsg.simulation.adapter"})
public class SimulationApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimulationApplication.class, args);
    }
}
