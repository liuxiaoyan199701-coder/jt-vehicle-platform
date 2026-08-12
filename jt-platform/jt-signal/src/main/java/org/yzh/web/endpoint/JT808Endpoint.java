package org.yzh.web.endpoint;

import io.github.jtplatform.signal.auth.DeviceAuthenticationDecision;
import io.github.jtplatform.signal.auth.DeviceAuthenticationService;
import io.github.jtplatform.signal.delivery.SignalMessageDispatcher;
import io.github.jtplatform.signal.session.RegistrationTokenStore;
import io.github.yezhihao.netmc.core.annotation.Async;
import io.github.yezhihao.netmc.core.annotation.AsyncBatch;
import io.github.yezhihao.netmc.core.annotation.Endpoint;
import io.github.yezhihao.netmc.core.annotation.Mapping;
import io.github.yezhihao.netmc.session.Session;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Component;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.protocol.commons.JT808;
import org.yzh.protocol.t808.T0001;
import org.yzh.protocol.t808.T0100;
import org.yzh.protocol.t808.T0102;
import org.yzh.protocol.t808.T0104;
import org.yzh.protocol.t808.T0107;
import org.yzh.protocol.t808.T0108;
import org.yzh.protocol.t808.T0200;
import org.yzh.protocol.t808.T0201_0500;
import org.yzh.protocol.t808.T0301;
import org.yzh.protocol.t808.T0302;
import org.yzh.protocol.t808.T0303;
import org.yzh.protocol.t808.T0608;
import org.yzh.protocol.t808.T0700;
import org.yzh.protocol.t808.T0702;
import org.yzh.protocol.t808.T0704;
import org.yzh.protocol.t808.T0705;
import org.yzh.protocol.t808.T0800;
import org.yzh.protocol.t808.T0801;
import org.yzh.protocol.t808.T0802;
import org.yzh.protocol.t808.T0805;
import org.yzh.protocol.t808.T0900;
import org.yzh.protocol.t808.T0901;
import org.yzh.protocol.t808.T0A00_8A00;
import org.yzh.protocol.t808.T8003;
import org.yzh.protocol.t808.T8004;
import org.yzh.protocol.t808.T8100;
import org.yzh.protocol.t808.T8800;
import org.yzh.web.model.entity.DeviceDO;
import org.yzh.web.model.enums.SessionKey;
import org.yzh.web.service.FileService;
import org.yzh.web.service.StoredMediaFile;

import static org.yzh.protocol.commons.JT808.CAN总线数据上传;
import static org.yzh.protocol.commons.JT808.事件报告;
import static org.yzh.protocol.commons.JT808.信息点播_取消;
import static org.yzh.protocol.commons.JT808.多媒体事件信息上传;
import static org.yzh.protocol.commons.JT808.多媒体数据上传;
import static org.yzh.protocol.commons.JT808.存储多媒体数据检索应答;
import static org.yzh.protocol.commons.JT808.定位数据批量上传;
import static org.yzh.protocol.commons.JT808.平台RSA公钥;
import static org.yzh.protocol.commons.JT808.提问应答;
import static org.yzh.protocol.commons.JT808.摄像头立即拍摄命令应答;
import static org.yzh.protocol.commons.JT808.数据上行透传;
import static org.yzh.protocol.commons.JT808.数据压缩上报;
import static org.yzh.protocol.commons.JT808.查询服务器时间;
import static org.yzh.protocol.commons.JT808.查询区域或线路数据应答;
import static org.yzh.protocol.commons.JT808.查询终端参数应答;
import static org.yzh.protocol.commons.JT808.查询终端属性应答;
import static org.yzh.protocol.commons.JT808.电子运单上报;
import static org.yzh.protocol.commons.JT808.终端RSA公钥;
import static org.yzh.protocol.commons.JT808.终端升级结果通知;
import static org.yzh.protocol.commons.JT808.终端心跳;
import static org.yzh.protocol.commons.JT808.终端注销;
import static org.yzh.protocol.commons.JT808.终端注册;
import static org.yzh.protocol.commons.JT808.终端补传分包请求;
import static org.yzh.protocol.commons.JT808.终端鉴权;
import static org.yzh.protocol.commons.JT808.终端通用应答;
import static org.yzh.protocol.commons.JT808.行驶记录数据上传;
import static org.yzh.protocol.commons.JT808.车辆控制应答;
import static org.yzh.protocol.commons.JT808.驾驶员身份信息采集上报;
import static org.yzh.protocol.commons.JT808.位置信息查询应答;
import static org.yzh.protocol.commons.JT808.位置信息汇报;

@Endpoint
@Component
public class JT808Endpoint {
    private final FileService fileService;
    private final RegistrationTokenStore registrationTokens;
    private final DeviceAuthenticationService deviceAuthenticationService;
    private final SignalMessageDispatcher messageDispatcher;

    public JT808Endpoint(
            FileService fileService,
            RegistrationTokenStore registrationTokens,
            DeviceAuthenticationService deviceAuthenticationService,
            SignalMessageDispatcher messageDispatcher) {
        this.fileService = fileService;
        this.registrationTokens = registrationTokens;
        this.deviceAuthenticationService = deviceAuthenticationService;
        this.messageDispatcher = messageDispatcher;
    }

    @Mapping(types = 终端通用应答, desc = "终端通用应答")
    public Object T0001(T0001 message, Session session) {
        session.response(message);
        return null;
    }

    @Mapping(types = 终端心跳, desc = "终端心跳")
    public void T0002(JTMessage message, Session session) {
    }

    @Mapping(types = 终端注销, desc = "终端注销")
    public void T0003(JTMessage message, Session session) {
        session.invalidate();
    }

    @Mapping(types = 查询服务器时间, desc = "查询服务器时间")
    public T8004 T0004(JTMessage message, Session session) {
        return new T8004().setDateTime(LocalDateTime.now(ZoneOffset.UTC));
    }

    @Mapping(types = 终端补传分包请求, desc = "终端补传分包请求")
    public void T8003(T8003 message, Session session) {
    }

    @Mapping(types = 终端注册, desc = "终端注册")
    public T8100 T0100(T0100 message, Session session) {
        DeviceDO presentedDevice = new DeviceDO()
                .setProtocolVersion(message.getProtocolVersion())
                .setMobileNo(message.getClientId())
                .setDeviceId(message.getDeviceId())
                .setPlateNo(message.getPlateNo());

        T8100 response = new T8100();
        response.setResponseSerialNo(message.getSerialNo());
        DeviceAuthenticationDecision decision = deviceAuthenticationService.authenticate(presentedDevice);
        if (!decision.allowed()) {
            session.removeAttribute(SessionKey.Device);
            response.setResultCode(T8100.NotFoundTerminal);
            return response;
        }

        DeviceDO device = decision.device();
        session.setAttribute(SessionKey.Device, device);
        response.setToken(registrationTokens.issue(device));
        response.setResultCode(T8100.Success);
        return response;
    }

    @Mapping(types = 终端鉴权, desc = "终端鉴权")
    public T0001 T0102(T0102 message, Session session) {
        DeviceDO presentedDevice = registrationTokens.resolve(message.getToken())
                .orElseGet(() -> new DeviceDO()
                        .setDeviceId(message.getClientId())
                        .setMobileNo(message.getClientId()));
        presentedDevice.setProtocolVersion(message.getProtocolVersion());
        presentedDevice.setMobileNo(message.getClientId());

        T0001 response = new T0001();
        response.setResponseSerialNo(message.getSerialNo());
        response.setResponseMessageId(message.getMessageId());
        DeviceAuthenticationDecision decision = deviceAuthenticationService.authenticate(presentedDevice);
        if (!decision.allowed()) {
            session.removeAttribute(SessionKey.Device);
            response.setResultCode(T0001.Failure);
            return response;
        }

        DeviceDO device = decision.device();
        session.setAttribute(SessionKey.Device, device);
        session.register(device.getDeviceId(), message);
        response.setResultCode(T0001.Success);
        return response;
    }

    @Mapping(types = 查询终端参数应答, desc = "查询终端参数应答")
    public void T0104(T0104 message, Session session) {
        session.response(message);
    }

    @Mapping(types = 查询终端属性应答, desc = "查询终端属性应答")
    public void T0107(T0107 message, Session session) {
        session.response(message);
    }

    @Mapping(types = 终端升级结果通知, desc = "终端升级结果通知")
    public void T0108(T0108 message, Session session) {
    }

    @AsyncBatch(poolSize = 2, maxElements = 4000, maxWait = 1000)
    @Mapping(types = 位置信息汇报, desc = "位置信息汇报")
    public void T0200(List<T0200> messages) {
    }

    @Mapping(types = 定位数据批量上传, desc = "定位数据批量上传")
    public void T0704(T0704 message) {
    }

    @Mapping(types = {位置信息查询应答, 车辆控制应答}, desc = "位置信息查询应答/车辆控制应答")
    public void T0201_0500(T0201_0500 message, Session session) {
        session.response(message);
    }

    @Mapping(types = 事件报告, desc = "事件报告")
    public void T0301(T0301 message, Session session) {
    }

    @Mapping(types = 提问应答, desc = "提问应答")
    public void T0302(T0302 message, Session session) {
    }

    @Mapping(types = 信息点播_取消, desc = "信息点播/取消")
    public void T0303(T0303 message, Session session) {
    }

    @Mapping(types = 查询区域或线路数据应答, desc = "查询区域或线路数据应答")
    public void T0608(T0608 message, Session session) {
        session.response(message);
    }

    @Mapping(types = 行驶记录数据上传, desc = "行驶记录仪数据上传")
    public void T0700(T0700 message, Session session) {
        session.response(message);
    }

    @Mapping(types = 电子运单上报, desc = "电子运单上报")
    public void T0701(JTMessage message, Session session) {
    }

    @Mapping(types = 驾驶员身份信息采集上报, desc = "驾驶员身份信息采集上报")
    public void T0702(T0702 message, Session session) {
        session.response(message);
    }

    @Mapping(types = CAN总线数据上传, desc = "CAN总线数据上传")
    public void T0705(T0705 message, Session session) {
    }

    @Mapping(types = 多媒体事件信息上传, desc = "多媒体事件信息上传")
    public void T0800(T0800 message, Session session) {
    }

    @Async
    @Mapping(types = 多媒体数据上传, desc = "多媒体数据上传")
    public JTMessage T0801(T0801 message, Session session) {
        if (message.getPacket() == null) {
            T0001 response = new T0001();
            response.copyBy(message);
            response.setMessageId(JT808.平台通用应答);
            response.setSerialNo(session.nextSerialNo());
            response.setResponseSerialNo(message.getSerialNo());
            response.setResponseMessageId(message.getMessageId());
            response.setResultCode(T0001.Success);
            return response;
        }
        StoredMediaFile stored = fileService.saveMediaFile(message).orElse(null);
        if (stored == null) {
            T0001 response = new T0001();
            response.copyBy(message);
            response.setMessageId(JT808.平台通用应答);
            response.setSerialNo(session.nextSerialNo());
            response.setResponseSerialNo(message.getSerialNo());
            response.setResponseMessageId(message.getMessageId());
            response.setResultCode(T0001.Failure);
            return response;
        }
        if (stored.newlyStored()) {
            messageDispatcher.dispatch(session, message, stored.deliveryMetadata());
        }
        return new T8800().setMediaId(message.getId());
    }

    @Mapping(types = 存储多媒体数据检索应答, desc = "存储多媒体数据检索应答")
    public void T0802(T0802 message, Session session) {
        session.response(message);
    }

    @Mapping(types = 摄像头立即拍摄命令应答, desc = "摄像头立即拍摄命令应答")
    public void T0805(T0805 message, Session session) {
        session.response(message);
    }

    @Mapping(types = 数据上行透传, desc = "数据上行透传")
    public void T0900(T0900 message, Session session) {
    }

    @Mapping(types = 数据压缩上报, desc = "数据压缩上报")
    public void T0901(T0901 message, Session session) {
    }

    @Mapping(types = 终端RSA公钥, desc = "终端RSA公钥")
    public void T0A00(T0A00_8A00 message, Session session) {
        session.response(message);
    }
}
