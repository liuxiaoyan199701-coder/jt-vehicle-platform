package org.yzh.protocol.t808;

import io.github.yezhihao.netmc.util.AdapterMap;
import io.github.yezhihao.protostar.annotation.Field;
import io.github.yezhihao.protostar.annotation.Message;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.protocol.commons.JT808;
import org.yzh.protocol.commons.transform.ParameterConverter;
import org.yzh.protocol.commons.transform.parameter.*;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * @author yezhihao
 * https://gitee.com/yezhihao/jt808-server
 */
@ToString
@Data
@Accessors(chain = true)
@Message(JT808.设置终端参数)
public class T8103 extends JTMessage {

    @Field(totalUnit = 1, desc = "参数项列表", converter = ParameterConverter.class)
    private Map<Integer, Object> parameters;

    public T8103 addParameter(Integer key, Object value) {
        if (parameters == null)
            parameters = new TreeMap<>();
        parameters.put(key, value);
        return this;
    }

    private Map<Integer, Integer> parametersInt;
    private Map<Integer, String> parametersLong;
    private Map<Integer, String> parametersStr;
    private ParamImageIdentifyAlarm paramImageIdentifyAlarm;
    private ParamVideoSpecialAlarm paramVideoSpecialAlarm;
    private ParamChannels paramChannels;
    private ParamSleepWake paramSleepWake;
    private ParamVideo paramVideo;
    private ParamVideoSingle paramVideoSingle;
    private ParamBSD paramBSD;
    private ParamTPMS paramTPMS;
    private ParamDSM paramDSM;
    private ParamADAS paramADAS;

    public T8103 build() {
        Map<Integer, Object> map = new TreeMap<>();

        if (parametersInt != null && !parametersInt.isEmpty())
            map.putAll(parametersInt);

        if (parametersLong != null && !parametersLong.isEmpty())
            map.putAll(new AdapterMap(parametersLong, (Function<String, Long>) Long::parseLong));

        if (parametersStr != null && !parametersStr.isEmpty())
            map.putAll(parametersStr);

        if (paramADAS != null)
            map.put(paramADAS.key, paramADAS);
        if (paramBSD != null)
            map.put(paramBSD.key, paramBSD);
        if (paramChannels != null)
            map.put(paramChannels.key, paramChannels);
        if (paramDSM != null)
            map.put(paramDSM.key, paramDSM);
        if (paramImageIdentifyAlarm != null)
            map.put(paramImageIdentifyAlarm.key, paramImageIdentifyAlarm);
        if (paramSleepWake != null)
            map.put(paramSleepWake.key, paramSleepWake);
        if (paramTPMS != null)
            map.put(paramTPMS.key, paramTPMS);
        if (paramVideo != null)
            map.put(paramVideo.key, paramVideo);
        if (paramVideoSingle != null)
            map.put(paramVideoSingle.key, paramVideoSingle);
        if (paramVideoSpecialAlarm != null)
            map.put(paramVideoSpecialAlarm.key, paramVideoSpecialAlarm);

        this.parameters = map;
        return this;
    }
}
