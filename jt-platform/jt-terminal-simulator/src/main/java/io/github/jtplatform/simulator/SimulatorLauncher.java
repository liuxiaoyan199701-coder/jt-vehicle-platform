package io.github.jtplatform.simulator;

import io.github.jtplatform.simulator.ui.SimulatorApplication;
import javafx.application.Application;

public final class SimulatorLauncher {
    private SimulatorLauncher() {
    }

    public static void main(String[] args) {
        Application.launch(SimulatorApplication.class, args);
    }
}
