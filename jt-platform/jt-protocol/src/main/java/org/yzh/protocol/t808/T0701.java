package org.yzh.protocol.t808;

import io.github.yezhihao.protostar.annotation.Field;
import io.github.yezhihao.protostar.annotation.Message;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.protocol.commons.JT808;

/**
 * 电子运单上报（0x0701）。
 *
 * <p>标准只规定一个 DWORD 长度前缀和对应的数据块，内容格式由运输行业或厂商自行约定，
 * 因此协议层只负责无损解码，不尝试解释正文。
 */
@ToString
@Data
@Accessors(chain = true)
@Message(JT808.电子运单上报)
public class T0701 extends JTMessage {

    @Field(lengthUnit = 4, desc = "电子运单数据")
    private byte[] data;
}
