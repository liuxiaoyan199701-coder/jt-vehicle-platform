package io.github.jtplatform.simulator.ui;

import java.net.URL;
import java.util.Objects;
import java.util.function.Consumer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Window;
import javafx.util.Duration;

/** WebView 地图选点薄壳；地图页面失败时只关闭对话框，不影响手输表单。 */
public final class MapPickerDialog {
    private final Dialog<MapSelection> dialog = new Dialog<>();
    private final WebView webView = new WebView();
    private final Label coordinates = new Label("请先点击地图选择起点和终点");
    private final MapSelectionBridge bridge;
    private final Consumer<Throwable> unavailable;
    private Timeline eventPoller;
    private boolean pageFailed;
    private boolean unavailableReported;

    private MapPickerDialog(Window owner, MapPoint origin, MapPoint destination,
            Consumer<MapSelection> confirmed, Consumer<Throwable> unavailable) {
        bridge = new MapSelectionBridge(origin, destination, confirmed);
        this.unavailable = Objects.requireNonNull(unavailable, "unavailable");
        dialog.setTitle("地图选点（GCJ-02）");
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setResizable(true);
        DialogPane pane = dialog.getDialogPane();
        pane.setPrefSize(920, 680);
        pane.getButtonTypes().add(ButtonType.CANCEL);

        Button confirm = new Button("确认回填");
        confirm.setDefaultButton(true);
        confirm.setOnAction(event -> {
            if (!bridge.ready()) {
                coordinates.setText("请先选择起点和终点");
                return;
            }
            bridge.confirm();
            dialog.setResult(bridge.selection());
            dialog.close();
        });
        Button reset = new Button("清空标记");
        reset.setOnAction(event -> webView.getEngine().executeScript("window.clearMarkers && window.clearMarkers()"));
        HBox footer = new HBox(10, coordinates, reset, confirm);
        HBox.setHgrow(coordinates, Priority.ALWAYS);
        footer.setPadding(new Insets(8));

        BorderPane content = new BorderPane(webView);
        content.setBottom(footer);
        pane.setContent(content);
        loadPage();
    }

    public static void show(Window owner, MapPoint origin, MapPoint destination,
            Consumer<MapSelection> confirmed, Consumer<Throwable> unavailable) {
        Objects.requireNonNull(confirmed, "confirmed");
        Objects.requireNonNull(unavailable, "unavailable");
        MapPickerDialog picker = new MapPickerDialog(owner, origin, destination, confirmed, unavailable);
        if (picker.pageFailed) {
            unavailable.accept(new IllegalStateException("地图不可用，请手输"));
            return;
        }
        // 对话框显示完成后布局尺寸才最终确定，此时必须让地图重算容器尺寸，
        // 否则缩放锚点按旧尺寸算，一放大就偏到别处。
        picker.dialog.setOnShown(event -> picker.syncMapSize());
        picker.dialog.setOnHidden(event -> {
            if (picker.eventPoller != null) {
                picker.eventPoller.stop();
            }
        });
        picker.dialog.showAndWait();
    }

    private void syncMapSize() {
        try {
            webView.getEngine().executeScript("window.syncMapSize && window.syncMapSize()");
        } catch (RuntimeException ignored) {
            // 页面尚未就绪时同步失败无妨：页面自身还有定时与 ResizeObserver 兜底
        }
    }

    private void loadPage() {
        try {
            URL page = MapPickerDialog.class.getResource("/io/github/jtplatform/simulator/ui/map-picker.html");
            if (page == null) {
                pageFailed = true;
                return;
            }
            WebEngine engine = webView.getEngine();
            engine.setOnError(event -> pageFailed = true);
            engine.getLoadWorker().exceptionProperty().addListener((observable, oldValue, failure) -> {
                if (failure != null) {
                    pageFailed = true;
                }
            });
            engine.getLoadWorker().stateProperty().addListener((observable, oldState, state) -> {
                if (state == Worker.State.SUCCEEDED) {
                    engine.executeScript("window.initMap(" + bridge.initialStateJson() + ")");
                    startEventPolling(engine);
                }
            });
            engine.load(page.toExternalForm());
        } catch (RuntimeException failure) {
            pageFailed = true;
        }
    }

    private void startEventPolling(WebEngine engine) {
        eventPoller = new Timeline(new KeyFrame(Duration.millis(100), event -> {
            Object unavailable = engine.executeScript("window.__mapUnavailable === true");
            if (Boolean.TRUE.equals(unavailable) && !unavailableReported) {
                unavailableReported = true;
                this.unavailable.accept(new IllegalStateException("地图不可用，请手输"));
            }
            Object raw = engine.executeScript("window.__mapEvent || null");
            if (raw == null) {
                return;
            }
            engine.executeScript("window.__mapEvent = null");
            String[] fields = raw.toString().split(",", -1);
            if (fields.length != 3) {
                return;
            }
            try {
                double lat = Double.parseDouble(fields[1]);
                double lng = Double.parseDouble(fields[2]);
                if ("origin".equals(fields[0])) {
                    bridge.setOrigin(lat, lng);
                } else if ("destination".equals(fields[0])) {
                    bridge.setDestination(lat, lng);
                }
                coordinates.setText("起点/终点：" + (bridge.ready() ? "已选 · " : "待选 · ")
                        + "%.6f, %.6f".formatted(lat, lng));
            } catch (RuntimeException ignored) {
                coordinates.setText("地图坐标无效，请重新点选");
            }
        }));
        eventPoller.setCycleCount(Timeline.INDEFINITE);
        eventPoller.play();
    }

    MapSelectionBridge bridge() {
        return bridge;
    }
}
