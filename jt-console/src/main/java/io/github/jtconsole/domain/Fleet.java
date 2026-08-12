package io.github.jtconsole.domain;

/** 单层车队档案。 */
public record Fleet(
        long id,
        String code,
        String name,
        String manager,
        String contactPhone,
        String remark,
        String createdAt,
        String updatedAt) {

    public Summary summary() {
        return new Summary(id, code, name);
    }

    public record Summary(long id, String code, String name) {}
}
