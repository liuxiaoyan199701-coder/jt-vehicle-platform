package io.github.jtplatform.signal.delivery;

import io.netty.buffer.ByteBuf;
import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.protocol.t808.T0200;
import org.yzh.protocol.t808.T0801;

public final class ProtocolPayloadMapper {
    private static final Object OMIT = new Object();
    private static final int MAX_DEPTH = 12;
    private static final Set<String> TRANSPORT_PROPERTIES = Set.of(
            "bodyLength", "class", "clientId", "encryption", "extData", "messageId", "messageName",
            "noBuffer", "packageNo", "packageTotal", "payload", "properties", "protocolVersion", "reserved",
            "serialNo", "session", "subpackage", "vehicleId", "driverId", "verified", "version");

    private static final String[] ALARM_NAMES = {
            "emergency", "overspeed", "fatigueDriving", "dangerousDrivingBehavior",
            "gnssModuleFault", "gnssAntennaDisconnected", "gnssAntennaShortCircuit",
            "terminalMainPowerUndervoltage", "terminalMainPowerFailure", "terminalDisplayFault",
            "textToSpeechFault", "cameraFault", "icCardModuleFault", "overspeedWarning",
            "fatigueWarning", "reserved15", "reserved16", "reserved17", "drivingOvertime",
            "parkingOvertime", "areaEntryExit", "routeEntryExit", "routeTravelTime",
            "routeDeviation", "vehicleVssFault", "fuelAbnormal", "vehicleTheft", "illegalIgnition",
            "illegalDisplacement", "collisionRollover", "rolloverWarning", "reserved31"
    };

    private static final String[] STATUS_NAMES = {
            "accOn", "positioned", "southLatitude", "westLongitude", "operating",
            "coordinatesEncrypted", "reserved6", "reserved7", "loadStateBit0", "loadStateBit1",
            "oilCircuitDisconnected", "electricalCircuitDisconnected", "doorsLocked", "frontDoorOpen",
            "middleDoorOpen", "rearDoorOpen", "driverDoorOpen", "customDoorOpen", "gpsPositioning",
            "beidouPositioning", "glonassPositioning", "galileoPositioning", "vehicleMoving",
            "reserved23", "reserved24", "reserved25", "reserved26", "reserved27", "reserved28",
            "reserved29", "reserved30", "reserved31"
    };

    public Map<String, Object> map(JTMessage message) {
        Object mapped = mapValue(message, new IdentityHashMap<>(), 0);
        if (!(mapped instanceof Map<?, ?> values)) {
            return Map.of();
        }
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        values.forEach((key, value) -> payload.put((String) key, value));
        if (message instanceof T0200 location) {
            enrichLocation(payload, location);
        } else if (message instanceof T0801 multimedia) {
            enrichMultimedia(payload, multimedia);
        }
        return payload;
    }

    private void enrichMultimedia(Map<String, Object> payload, T0801 multimedia) {
        if (multimedia.getLocation() == null) {
            return;
        }
        Map<String, Object> location = map(multimedia.getLocation());
        payload.put("location", location);
        LinkedHashMap<String, Object> relatedAlarm = new LinkedHashMap<>();
        relatedAlarm.put("eventCode", multimedia.getEvent());
        relatedAlarm.put("location", location);
        payload.put("relatedAlarm", relatedAlarm);
    }

    private static void enrichLocation(Map<String, Object> payload, T0200 location) {
        payload.remove("warnBit");
        payload.remove("statusBit");
        payload.remove("latitude");
        payload.remove("longitude");
        payload.remove("lat");
        payload.remove("lng");
        payload.remove("speed");
        payload.put("latitude", location.getLat());
        payload.put("longitude", location.getLng());
        payload.put("speedKph", location.getSpeedKph());
        payload.put("alarmFlags", expandBits(location.getWarnBit(), ALARM_NAMES));
        payload.put("statusFlags", expandBits(location.getStatusBit(), STATUS_NAMES));
    }

    private static Map<String, Object> expandBits(int bits, String[] names) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>(names.length);
        for (int index = 0; index < names.length; index++) {
            result.put(names[index], ((bits >>> index) & 1) == 1);
        }
        return result;
    }

    private Object mapValue(Object value, IdentityHashMap<Object, Boolean> visiting, int depth) {
        if (value == null) {
            return null;
        }
        if (isBinary(value)) {
            return OMIT;
        }
        if (value instanceof String || value instanceof Boolean || value instanceof Character
                || value instanceof BigDecimal || value instanceof BigInteger
                || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return value instanceof Character character ? character.toString() : value;
        }
        if (value instanceof Double number) {
            return Double.isFinite(number) ? number : null;
        }
        if (value instanceof Float number) {
            return Float.isFinite(number) ? number : null;
        }
        if (value instanceof Enum<?> enumeration) {
            return enumeration.name();
        }
        if (value instanceof TemporalAccessor || value instanceof UUID || value instanceof URI || value instanceof Path) {
            return value.toString();
        }
        if (value instanceof Date date) {
            return date.toInstant().toString();
        }
        if (value instanceof Optional<?> optional) {
            return optional.map(item -> mapValue(item, visiting, depth + 1)).orElse(null);
        }
        if (depth >= MAX_DEPTH || visiting.put(value, Boolean.TRUE) != null) {
            return OMIT;
        }
        try {
            if (value instanceof Map<?, ?> map) {
                return mapMap(map, visiting, depth);
            }
            if (value instanceof Iterable<?> iterable) {
                return mapIterable(iterable, visiting, depth);
            }
            if (value.getClass().isArray()) {
                return mapArray(value, visiting, depth);
            }
            Package valuePackage = value.getClass().getPackage();
            if (valuePackage != null && valuePackage.getName().startsWith("java.")) {
                return value.toString();
            }
            return mapBean(value, visiting, depth);
        } finally {
            visiting.remove(value);
        }
    }

    private Object mapMap(Map<?, ?> source, IdentityHashMap<Object, Boolean> visiting, int depth) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            Object mapped = mapValue(value, visiting, depth + 1);
            if (mapped != OMIT) {
                result.put(mapKey(key), mapped);
            }
        });
        return result;
    }

    private Object mapIterable(Iterable<?> source, IdentityHashMap<Object, Boolean> visiting, int depth) {
        List<Object> result = new ArrayList<>();
        for (Object value : source) {
            Object mapped = mapValue(value, visiting, depth + 1);
            if (mapped != OMIT) {
                result.add(mapped);
            }
        }
        return result;
    }

    private Object mapArray(Object source, IdentityHashMap<Object, Boolean> visiting, int depth) {
        List<Object> result = new ArrayList<>(Array.getLength(source));
        for (int index = 0; index < Array.getLength(source); index++) {
            Object mapped = mapValue(Array.get(source, index), visiting, depth + 1);
            if (mapped != OMIT) {
                result.add(mapped);
            }
        }
        return result;
    }

    private Object mapBean(Object source, IdentityHashMap<Object, Boolean> visiting, int depth) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (PropertyDescriptor descriptor : properties(source.getClass())) {
            String name = descriptor.getName();
            Method getter = descriptor.getReadMethod();
            if (getter == null || getter.getParameterCount() != 0 || TRANSPORT_PROPERTIES.contains(name)) {
                continue;
            }
            try {
                if (!getter.canAccess(source)) {
                    getter.trySetAccessible();
                }
                Object mapped = mapValue(getter.invoke(source), visiting, depth + 1);
                if (mapped != OMIT) {
                    result.put(name, mapped);
                }
            } catch (IllegalAccessException | InvocationTargetException | RuntimeException ignored) {
                // A derived protocol getter may be undefined until all optional fields are present.
            }
        }
        return result;
    }

    private static List<PropertyDescriptor> properties(Class<?> type) {
        try {
            BeanInfo beanInfo = Introspector.getBeanInfo(type, Object.class);
            return java.util.Arrays.stream(beanInfo.getPropertyDescriptors())
                    .sorted(Comparator.comparing(PropertyDescriptor::getName))
                    .toList();
        } catch (IntrospectionException exception) {
            throw new IllegalArgumentException("Unable to inspect protocol payload " + type.getName(), exception);
        }
    }

    private static boolean isBinary(Object value) {
        return value instanceof ByteBuf
                || value instanceof byte[]
                || value instanceof ByteBuffer
                || value instanceof InputStream;
    }

    private static String mapKey(Object key) {
        if (key instanceof Number number) {
            return "0x" + Integer.toHexString(number.intValue()).toUpperCase(java.util.Locale.ROOT);
        }
        return String.valueOf(key);
    }
}
