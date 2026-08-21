package io.github.jtconsole.operations;

import io.github.jtconsole.domain.UpgradePackage;
import io.github.jtconsole.repository.UpgradePackageRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UpgradeService {

    private final UpgradePackageRepository packages;
    private final UpgradePackageStore store;

    public UpgradeService(UpgradePackageRepository packages, UpgradePackageStore store) {
        this.packages = packages;
        this.store = store;
    }

    public List<UpgradePackage> list() {
        return packages.findAll();
    }

    public Optional<UpgradePackage> find(long id) {
        return packages.findById(id);
    }

    @Transactional
    public UpgradePackage upload(String name, String version, String makerId,
                                 String originalFileName, byte[] raw) {
        String trimmedName = requireText(name, "升级包名称不能为空");
        String trimmedVersion = requireText(version, "版本号不能为空");
        String trimmedMaker = requireText(makerId, "制造商 ID 不能为空");
        String fileName = originalFileName == null || originalFileName.isBlank()
                ? trimmedName : originalFileName.trim();

        UpgradePackageStore.Stored stored = store.save(raw);
        long id = packages.insert(new UpgradePackage(null, trimmedName, trimmedVersion, trimmedMaker,
                fileName, stored.storedName(), raw.length, stored.sha256(), null, null));
        return packages.findById(id).orElseThrow();
    }

    @Transactional
    public boolean delete(long id) {
        Optional<UpgradePackage> existing = packages.findById(id);
        if (existing.isEmpty()) return false;
        store.delete(existing.get().filePath());
        return packages.delete(id) == 1;
    }

    /** 取包体用于 8108 下发。包不存在或文件缺失时抛出。 */
    public byte[] loadBytes(long id) {
        UpgradePackage existing = packages.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("升级包不存在"));
        return store.read(existing.filePath());
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        String trimmed = value.trim();
        if (trimmed.length() > 100) {
            throw new IllegalArgumentException(message + "（过长）");
        }
        return trimmed;
    }
}
