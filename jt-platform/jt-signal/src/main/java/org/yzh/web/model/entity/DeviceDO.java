package org.yzh.web.model.entity;

import java.util.Objects;
import org.yzh.protocol.t1078.T1003;

/** Runtime-only device session information. */
public class DeviceDO {
    private String deviceId;
    private String mobileNo;
    private String plateNo;
    private int protocolVersion;
    private T1003 audioVideoCapabilities;

    public DeviceDO() {
    }

    public DeviceDO(DeviceDO source) {
        this.deviceId = source.deviceId;
        this.mobileNo = source.mobileNo;
        this.plateNo = source.plateNo;
        this.protocolVersion = source.protocolVersion;
        this.audioVideoCapabilities = source.audioVideoCapabilities;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public DeviceDO setDeviceId(String deviceId) {
        this.deviceId = deviceId;
        return this;
    }

    public String getMobileNo() {
        return mobileNo;
    }

    public DeviceDO setMobileNo(String mobileNo) {
        this.mobileNo = mobileNo;
        return this;
    }

    public String getPlateNo() {
        return plateNo;
    }

    public DeviceDO setPlateNo(String plateNo) {
        this.plateNo = plateNo;
        return this;
    }

    public int getProtocolVersion() {
        return protocolVersion;
    }

    public DeviceDO setProtocolVersion(int protocolVersion) {
        this.protocolVersion = protocolVersion;
        return this;
    }

    public T1003 getAudioVideoCapabilities() {
        return audioVideoCapabilities;
    }

    public DeviceDO setAudioVideoCapabilities(T1003 audioVideoCapabilities) {
        this.audioVideoCapabilities = audioVideoCapabilities;
        return this;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof DeviceDO device
                && Objects.equals(deviceId, device.deviceId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(deviceId);
    }
}
