package org.yzh.web.endpoint;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.yezhihao.netmc.core.annotation.Endpoint;
import io.github.yezhihao.netmc.core.annotation.Mapping;
import io.github.yezhihao.netmc.session.Session;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.yzh.protocol.commons.JSATL12;
import org.yzh.protocol.jsatl12.DataPacket;
import org.yzh.protocol.jsatl12.T1210;
import org.yzh.protocol.jsatl12.T1211;
import org.yzh.protocol.jsatl12.T9212;
import org.yzh.web.service.FileService;

@Endpoint
@Component
public class JSATL12Endpoint {
    private final FileService fileService;
    private final Cache<String, Map<String, T1210.Item>> files = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(20))
            .build();

    public JSATL12Endpoint(FileService fileService) {
        this.fileService = fileService;
    }

    @Mapping(types = JSATL12.报警附件信息消息, desc = "报警附件信息消息")
    public void alarmFileInfoList(T1210 message, Session session) {
        session.register(message.getDeviceId(), message);
        List<T1210.Item> items = message.getItems();
        if (items == null || items.isEmpty()) {
            return;
        }
        Map<String, T1210.Item> fileInfos = files.get(message.getClientId(), ignored -> new HashMap<>());
        for (T1210.Item item : items) {
            fileInfos.put(item.getName().strip(), item.setParent(message));
        }
        fileService.createDir(message);
    }

    @Mapping(types = JSATL12.文件信息上传, desc = "文件信息上传")
    public void alarmFileInfo(T1211 message, Session session) {
        if (!session.isRegistered()) {
            session.register(message);
        }
    }

    @Mapping(types = JSATL12.文件数据上传, desc = "文件数据上传")
    public Object alarmFile(DataPacket packet, Session session) {
        Map<String, T1210.Item> fileInfos = files.getIfPresent(session.getClientId());
        if (fileInfos == null) {
            return null;
        }
        T1210.Item fileInfo = fileInfos.get(packet.getName().strip());
        if (fileInfo == null) {
            return null;
        }
        boolean last = Integer.toUnsignedLong(packet.getOffset()) + Integer.toUnsignedLong(packet.getLength())
                >= fileInfo.getSize();
        if (packet.getOffset() == 0 && last) {
            fileService.writeFileSingle(fileInfo.getParent(), packet);
        } else {
            fileService.writeFile(fileInfo.getParent(), packet, last);
        }
        return null;
    }

    @Mapping(types = JSATL12.文件上传完成消息, desc = "文件上传完成消息")
    public T9212 alarmFileComplete(T1211 message) {
        T9212 response = new T9212();
        response.setName(message.getName().strip());
        response.setType(message.getType());
        Map<String, T1210.Item> fileInfos = files.getIfPresent(message.getClientId());
        if (fileInfos == null) {
            response.setResult(1);
            response.setItems(new int[] {0, Math.toIntExact(Math.min(message.getSize(), Integer.MAX_VALUE))});
            return response;
        }
        T1210.Item fileInfo = fileInfos.get(response.getName());
        if (fileInfo == null) {
            response.setResult(1);
            response.setItems(new int[] {0, Math.toIntExact(Math.min(message.getSize(), Integer.MAX_VALUE))});
            return response;
        }
        int[] missing = fileService.checkFile(fileInfo.getParent(), message);
        if (missing == null) {
            fileInfos.remove(response.getName());
            if (fileInfos.isEmpty()) {
                files.invalidate(message.getClientId());
            }
            response.setResult(0);
        } else {
            response.setItems(missing);
            response.setResult(1);
        }
        return response;
    }
}
