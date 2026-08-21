package io.github.jtconsole.web;

import io.github.jtconsole.api.ApiResponse;
import io.github.jtconsole.audit.Audited;
import io.github.jtconsole.domain.UpgradePackage;
import io.github.jtconsole.operations.UpgradeService;
import io.github.jtconsole.security.Permissions;
import io.github.jtconsole.security.RequirePermission;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/upgrade-packages")
public class UpgradeController {

    private final UpgradeService upgrades;

    public UpgradeController(UpgradeService upgrades) {
        this.upgrades = upgrades;
    }

    @GetMapping
    @RequirePermission(Permissions.COMMAND_SEND)
    public ApiResponse<List<UpgradePackage>> list() {
        return ApiResponse.ok(upgrades.list());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequirePermission(Permissions.COMMAND_SEND)
    @Audited(value = "上传升级包", resourceType = "upgrade-package")
    public ApiResponse<UpgradePackage> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("name") String name,
            @RequestParam("version") String version,
            @RequestParam("makerId") String makerId) {
        byte[] raw;
        try {
            raw = file.getBytes();
        } catch (IOException failure) {
            throw new IllegalArgumentException("升级包读取失败", failure);
        }
        return ApiResponse.ok(upgrades.upload(name, version, makerId,
                file.getOriginalFilename(), raw));
    }

    @DeleteMapping("/{id}")
    @RequirePermission(Permissions.COMMAND_SEND)
    @Audited(value = "删除升级包", resourceType = "upgrade-package")
    public ApiResponse<Void> delete(@PathVariable long id) {
        return upgrades.delete(id)
                ? ApiResponse.ok(null) : ApiResponse.error("4004", "升级包不存在");
    }
}
