package io.github.jtplatform.signal.auth;

/**
 * What to do with a device that the remote source has no archive for.
 *
 * <p>Only meaningful in remote-api mode. The default is {@link #REJECT} because that is what
 * remote-api mode has always done: an unknown device is refused. Loosening it silently would
 * weaken a guarantee existing deployments already rely on, so "connect first, archive later"
 * has to be asked for by name.
 */
public enum UnregisteredDevicePolicy {
    /** Refuse registration and authentication until the device is archived. */
    REJECT,
    /**
     * Let the device in; the console shows it as unarchived so an operator can archive it.
     * Choose this when devices are commissioned in the field before paperwork catches up.
     */
    ALLOW
}
