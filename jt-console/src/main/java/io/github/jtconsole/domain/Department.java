package io.github.jtconsole.domain;

import java.util.List;

/** 租户内的部门。{@code parentId} 为空即根部门。 */
public record Department(
        long id,
        long tenantId,
        Long parentId,
        String name,
        int sortOrder,
        boolean enabled,
        String createdAt,
        String updatedAt) {

    /** 部门树节点，附带直接挂载的账号数与车辆数供界面提示非空删除。 */
    public record Node(
            long id,
            Long parentId,
            String name,
            int sortOrder,
            boolean enabled,
            int accountCount,
            int vehicleCount,
            List<Node> children) {}
}
