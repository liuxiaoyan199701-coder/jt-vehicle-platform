package io.github.jtplatform.simulator.config;

public enum Jt808Version {
    V2013(0, false, 12),
    V2019(1, true, 20);

    private final int protocolVersion;
    private final boolean versionFlag;
    private final int mobileNumberLength;

    Jt808Version(int protocolVersion, boolean versionFlag, int mobileNumberLength) {
        this.protocolVersion = protocolVersion;
        this.versionFlag = versionFlag;
        this.mobileNumberLength = mobileNumberLength;
    }

    public int protocolVersion() {
        return protocolVersion;
    }

    public boolean versionFlag() {
        return versionFlag;
    }

    public int mobileNumberLength() {
        return mobileNumberLength;
    }
}
