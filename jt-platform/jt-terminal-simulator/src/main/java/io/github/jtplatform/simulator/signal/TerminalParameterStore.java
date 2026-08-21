package io.github.jtplatform.simulator.signal;

import io.github.jtplatform.simulator.config.TerminalManagementConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 终端参数的线程安全快照；下行命令线程与周期任务共享同一份状态。 */
public final class TerminalParameterStore {
    private final Object lock = new Object();
    private final Map<Integer, Object> parameters;

    public TerminalParameterStore(TerminalManagementConfig config) {
        Objects.requireNonNull(config, "config");
        this.parameters = new LinkedHashMap<>(config.parameters());
    }

    public Map<Integer, Object> all() {
        synchronized (lock) {
            return Map.copyOf(parameters);
        }
    }

    public Map<Integer, Object> select(int[] ids) {
        synchronized (lock) {
            if (ids == null || ids.length == 0) {
                return Map.copyOf(parameters);
            }
            Map<Integer, Object> selected = new LinkedHashMap<>();
            for (int id : ids) {
                if (parameters.containsKey(id)) {
                    selected.put(id, parameters.get(id));
                }
            }
            return Map.copyOf(selected);
        }
    }

    public void update(Map<Integer, Object> values) {
        if (values == null) {
            return;
        }
        synchronized (lock) {
            values.forEach((key, value) -> {
                if (key != null && value != null) {
                    parameters.put(key, value);
                }
            });
        }
    }

    public long heartbeatSeconds() {
        synchronized (lock) {
            Object value = parameters.get(TerminalManagementConfig.HEARTBEAT_INTERVAL_PARAMETER);
            if (value instanceof Number number) {
                return Math.max(1L, number.longValue());
            }
            return 30L;
        }
    }
}
