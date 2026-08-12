package io.github.jtplatform.simulator.ui;

import io.github.jtplatform.simulator.config.SimulatorConfig;
import java.util.Objects;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

public final class SimulatorApplication extends Application {
    public static final String SMOKE_TEST_ARGUMENT = "--smoke-test";
    public static final String STYLESHEET = "/io/github/jtplatform/simulator/ui/simulator.css";

    private SimulatorView view;

    @Override
    public void start(Stage stage) {
        SimulatorRuntime runtime = null;
        try {
            runtime = new SimulatorRuntime();
            SimulatorConfig config;
            Throwable loadFailure = null;
            try {
                config = runtime.loadConfig();
            } catch (Exception failure) {
                config = SimulatorConfig.defaults();
                loadFailure = failure;
            }

            view = new SimulatorView(runtime, config);
            Scene scene = new Scene(view, 1_280, 800);
            scene.getStylesheets().add(stylesheetUrl());
            stage.setTitle("JT Platform 终端模拟器");
            stage.setMinWidth(960);
            stage.setMinHeight(640);
            stage.setScene(scene);
            stage.setOnCloseRequest(event -> view.close());
            stage.show();

            boolean smokeTest = smokeTestRequested();
            if (loadFailure != null && !smokeTest) {
                view.reportStartupError(loadFailure);
            }
            if (smokeTest) {
                Platform.runLater(() -> {
                    stage.close();
                    Platform.exit();
                });
            } else {
                view.activate();
            }
        } catch (Throwable failure) {
            if (view != null) {
                view.close();
                view = null;
            } else if (runtime != null) {
                runtime.close();
            }
            showFatalStartupError(stage, failure);
        }
    }

    private boolean smokeTestRequested() {
        return getParameters().getRaw().contains(SMOKE_TEST_ARGUMENT);
    }

    public static String stylesheetUrl() {
        return Objects.requireNonNull(
                SimulatorApplication.class.getResource(STYLESHEET),
                "Missing JavaFX stylesheet: " + STYLESHEET).toExternalForm();
    }

    private static void showFatalStartupError(Stage stage, Throwable failure) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(stage);
        alert.setTitle("终端模拟器");
        alert.setHeaderText("应用启动失败");
        String message = failure.getMessage();
        alert.setContentText(message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message);
        alert.showAndWait();
        Platform.exit();
    }

    @Override
    public void stop() {
        if (view != null) {
            view.close();
            view = null;
        }
    }
}
