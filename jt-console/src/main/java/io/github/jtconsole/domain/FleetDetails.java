package io.github.jtconsole.domain;

import java.util.List;

/** 车队档案、聚合统计和当前成员的一体化详情。 */
public record FleetDetails(Fleet fleet, FleetSummary summary, List<FleetMember> members) {
    public FleetDetails {
        members = List.copyOf(members);
    }
}
