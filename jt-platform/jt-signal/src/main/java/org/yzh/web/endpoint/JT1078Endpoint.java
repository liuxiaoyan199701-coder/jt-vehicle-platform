package org.yzh.web.endpoint;

import io.github.yezhihao.netmc.core.annotation.Endpoint;
import io.github.yezhihao.netmc.core.annotation.Mapping;
import io.github.yezhihao.netmc.session.Session;
import org.springframework.stereotype.Component;
import org.yzh.protocol.t1078.T1003;
import org.yzh.protocol.t1078.T1005;
import org.yzh.protocol.t1078.T1205;
import org.yzh.protocol.t1078.T1206;
import org.yzh.web.model.entity.DeviceDO;
import org.yzh.web.model.enums.SessionKey;

import static org.yzh.protocol.commons.JT1078.文件上传完成通知;
import static org.yzh.protocol.commons.JT1078.终端上传乘客流量;
import static org.yzh.protocol.commons.JT1078.终端上传音视频属性;
import static org.yzh.protocol.commons.JT1078.终端上传音视频资源列表;

@Endpoint
@Component
public class JT1078Endpoint {
    @Mapping(types = 终端上传音视频资源列表, desc = "终端上传音视频资源列表")
    public void T1205(T1205 message, Session session) {
        session.response(message);
    }

    @Mapping(types = 终端上传音视频属性, desc = "终端上传音视频属性")
    public void T1003(T1003 message, Session session) {
        DeviceDO device = session.getAttribute(SessionKey.Device);
        if (device != null) {
            device.setAudioVideoCapabilities(message);
        }
        session.response(message);
    }

    @Mapping(types = 文件上传完成通知, desc = "文件上传完成通知")
    public void T1206(T1206 message, Session session) {
        session.response(message);
    }

    @Mapping(types = 终端上传乘客流量, desc = "终端上传乘客流量")
    public void T1005(T1005 message, Session session) {
    }
}
