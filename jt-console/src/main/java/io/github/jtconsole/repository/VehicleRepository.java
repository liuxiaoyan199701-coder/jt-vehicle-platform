package io.github.jtconsole.repository;

import io.github.jtconsole.config.Timestamps;
import io.github.jtconsole.domain.Vehicle;
import io.github.jtconsole.security.DataScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 车辆档案。{@code vehicle.tenant_id} 是「设备 → 租户」的唯一权威映射：
 * 轨迹、状态、告警、日统计、多媒体都不带租户列，一律经这张表过滤。
 *
 * <p>{@link DataScope} 是查询方法的必选参数而不是可选默认值——可选就意味着某天会有人漏传，
 * 而漏传的后果是静默越权。
 */
@Repository
public class VehicleRepository {

    private static final String COLUMNS = """
            device_id, plate_no, plate_color, brand, channel_count, remark,
            tenant_id, department_id, created_at, updated_at
            """;

    private final JdbcClient jdbc;

    public VehicleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Vehicle> findAll(DataScope scope) {
        String sql = "SELECT " + COLUMNS + " FROM vehicle WHERE 1 = 1"
                + scope.vehicleCondition("") + " ORDER BY plate_no";
        return jdbc.sql(sql).params(scope.parameters())
                .query(VehicleRepository::map).list();
    }

    public Optional<Vehicle> findById(String deviceId, DataScope scope) {
        String sql = "SELECT " + COLUMNS + " FROM vehicle WHERE device_id = ?"
                + scope.vehicleCondition("");
        List<Object> params = new ArrayList<>();
        params.add(deviceId);
        params.addAll(scope.parameters());
        return jdbc.sql(sql).params(params).query(VehicleRepository::map).optional();
    }

    /** 不带范围过滤的查找。仅供归属判定与调拨等必须先看到全局事实的路径使用。 */
    public Optional<Vehicle> findByIdUnscoped(String deviceId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM vehicle WHERE device_id = ?")
                .param(deviceId).query(VehicleRepository::map).optional();
    }

    /** 设备所属租户。网关档案接口与广播过滤缓存都靠它建立设备到租户的映射。 */
    public Optional<Long> findTenantId(String deviceId) {
        return jdbc.sql("SELECT tenant_id FROM vehicle WHERE device_id = ?")
                .param(deviceId).query(Long.class).optional();
    }

    /** 全量的「设备 → 租户/部门」映射，供广播过滤在启动时装载内存缓存。 */
    public List<DeviceOwnership> findAllOwnerships() {
        return jdbc.sql("SELECT device_id, tenant_id, department_id FROM vehicle")
                .query((rs, rowNum) -> new DeviceOwnership(
                        rs.getString("device_id"),
                        RowValues.nullableLong(rs, "tenant_id"),
                        RowValues.nullableLong(rs, "department_id")))
                .list();
    }

    public void insert(Vehicle vehicle) {
        String now = Timestamps.now();
        jdbc.sql("""
                        INSERT INTO vehicle (device_id, plate_no, plate_color, brand, channel_count,
                                             remark, tenant_id, department_id, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .param(vehicle.deviceId())
                .param(vehicle.plateNo())
                .param(vehicle.plateColor())
                .param(vehicle.brand())
                .param(vehicle.channelCount())
                .param(vehicle.remark())
                .param(vehicle.tenantId())
                .param(vehicle.departmentId())
                .param(now)
                .param(now)
                .update();
    }

    public int update(Vehicle vehicle) {
        return jdbc.sql("""
                        UPDATE vehicle
                        SET plate_no = ?, plate_color = ?, brand = ?, channel_count = ?,
                            remark = ?, department_id = ?, updated_at = ?
                        WHERE device_id = ?
                        """)
                .param(vehicle.plateNo())
                .param(vehicle.plateColor())
                .param(vehicle.brand())
                .param(vehicle.channelCount())
                .param(vehicle.remark())
                .param(vehicle.departmentId())
                .param(Timestamps.now())
                .param(vehicle.deviceId())
                .update();
    }

    /**
     * 跨租户调拨。同事务内清空部门归属、车队归属与围栏绑定：
     * 这些都是原租户的组织内实体，跟着车走会把两个租户的组织结构缝在一起。
     */
    @Transactional
    public int reassignTenant(String deviceId, long targetTenantId) {
        jdbc.sql("DELETE FROM fleet_vehicle WHERE device_id = ?").param(deviceId).update();
        jdbc.sql("DELETE FROM geofence_vehicle WHERE device_id = ?").param(deviceId).update();
        jdbc.sql("DELETE FROM geofence_presence WHERE device_id = ?").param(deviceId).update();
        jdbc.sql("DELETE FROM alarm_condition_state WHERE device_id = ? AND source = 'GEOFENCE'")
                .param(deviceId).update();
        return jdbc.sql("""
                        UPDATE vehicle SET tenant_id = ?, department_id = NULL, updated_at = ?
                        WHERE device_id = ?
                        """)
                .param(targetTenantId).param(Timestamps.now()).param(deviceId)
                .update();
    }

    @Transactional
    public int delete(String deviceId) {
        jdbc.sql("DELETE FROM fleet_vehicle WHERE device_id = ?").param(deviceId).update();
        jdbc.sql("DELETE FROM geofence_presence WHERE device_id = ?").param(deviceId).update();
        jdbc.sql("DELETE FROM geofence_vehicle WHERE device_id = ?").param(deviceId).update();
        jdbc.sql("DELETE FROM alarm_condition_state WHERE device_id = ? AND source = 'GEOFENCE'")
                .param(deviceId).update();
        return jdbc.sql("DELETE FROM vehicle WHERE device_id = ?").param(deviceId).update();
    }

    /** 全局存在性判定。deviceId 是全局主键，跨租户重复建档必须被挡住。 */
    public boolean exists(String deviceId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM vehicle WHERE device_id = ?")
                .param(deviceId)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    /** 该设备是否落在给定范围内。指令、开流、多媒体等入口据此拒绝越权目标。 */
    public boolean visible(String deviceId, DataScope scope) {
        String sql = "SELECT COUNT(*) FROM vehicle WHERE device_id = ?"
                + scope.vehicleCondition("");
        List<Object> params = new ArrayList<>();
        params.add(deviceId);
        params.addAll(scope.parameters());
        Integer count = jdbc.sql(sql).params(params).query(Integer.class).single();
        return count != null && count > 0;
    }

    public int countByTenant(long tenantId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM vehicle WHERE tenant_id = ?")
                .param(tenantId).query(Integer.class).single();
        return count == null ? 0 : count;
    }

    public int clearDepartment(long departmentId) {
        return jdbc.sql("""
                        UPDATE vehicle SET department_id = NULL, updated_at = ?
                        WHERE department_id = ?
                        """)
                .param(Timestamps.now()).param(departmentId)
                .update();
    }

    private static Vehicle map(ResultSet rs, int rowNum) throws SQLException {
        return new Vehicle(
                rs.getString("device_id"),
                rs.getString("plate_no"),
                rs.getString("plate_color"),
                rs.getString("brand"),
                rs.getInt("channel_count"),
                rs.getString("remark"),
                RowValues.nullableLong(rs, "tenant_id"),
                RowValues.nullableLong(rs, "department_id"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }

    /** 设备归属的最小投影，供广播过滤缓存使用。 */
    public record DeviceOwnership(String deviceId, Long tenantId, Long departmentId) {}
}
