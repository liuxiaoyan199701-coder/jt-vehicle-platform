package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.domain.Vehicle;
import io.github.jtconsole.repository.VehicleRepository;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {

    private final VehicleRepository vehicles;

    public VehicleController(VehicleRepository vehicles) {
        this.vehicles = vehicles;
    }

    @GetMapping
    public ApiResponse<List<Vehicle>> list() {
        return ApiResponse.ok(vehicles.findAll());
    }

    @GetMapping("/{deviceId}")
    public ApiResponse<Vehicle> get(@PathVariable String deviceId) {
        String canonicalId = canonicalDeviceId(deviceId);
        return vehicles.findById(canonicalId)
                .map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.error("4004", "车辆不存在：" + canonicalId));
    }

    @PostMapping
    public ApiResponse<Vehicle> create(@RequestBody Vehicle body) {
        Vehicle vehicle = withCanonicalDeviceId(body);
        validate(vehicle);
        if (vehicles.exists(vehicle.deviceId())) {
            return ApiResponse.error("4009", "终端号已存在：" + vehicle.deviceId());
        }
        vehicles.insert(vehicle);
        return ApiResponse.ok(vehicles.findById(vehicle.deviceId()).orElse(vehicle));
    }

    @PutMapping("/{deviceId}")
    public ApiResponse<Vehicle> update(@PathVariable String deviceId, @RequestBody Vehicle body) {
        Vehicle vehicle = new Vehicle(canonicalDeviceId(deviceId), body.plateNo(), body.plateColor(),
                body.brand(), body.channelCount() <= 0 ? 1 : body.channelCount(), body.remark(), null, null);
        validate(vehicle);
        if (vehicles.update(vehicle) == 0) {
            return ApiResponse.error("4004", "车辆不存在：" + deviceId);
        }
        return ApiResponse.ok(vehicles.findById(vehicle.deviceId()).orElse(vehicle));
    }

    @DeleteMapping("/{deviceId}")
    public ApiResponse<Void> delete(@PathVariable String deviceId) {
        vehicles.delete(canonicalDeviceId(deviceId));
        return ApiResponse.ok(null);
    }

    private static void validate(Vehicle vehicle) {
        if (vehicle.deviceId() == null || vehicle.deviceId().isBlank()) {
            throw new IllegalArgumentException("终端号不能为空");
        }
        if (vehicle.plateNo() == null || vehicle.plateNo().isBlank()) {
            throw new IllegalArgumentException("车牌号不能为空");
        }
    }

    private static Vehicle withCanonicalDeviceId(Vehicle vehicle) {
        return new Vehicle(canonicalDeviceId(vehicle.deviceId()), vehicle.plateNo(),
                vehicle.plateColor(), vehicle.brand(),
                vehicle.channelCount() <= 0 ? 1 : vehicle.channelCount(),
                vehicle.remark(), null, null);
    }

    private static String canonicalDeviceId(String deviceId) {
        if (deviceId == null) {
            throw new IllegalArgumentException("终端号不能为空");
        }
        String trimmed = deviceId.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("终端号不能为空");
        }
        return trimmed;
    }
}
