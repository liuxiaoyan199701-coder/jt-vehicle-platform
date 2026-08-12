package org.yzh.web.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yzh.protocol.basics.JTMessage;
import org.yzh.protocol.commons.JT808;
import org.yzh.protocol.t808.*;
import org.yzh.web.endpoint.MessageManager;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/device")
public class JT808Controller {
    private final MessageManager messageManager;

    public JT808Controller(MessageManager messageManager) {
        this.messageManager = messageManager;
    }

    @PostMapping("/8103")
    public Mono<T0001> T8103(@RequestBody T8103 request) {
        return messageManager.request(request.build(), T0001.class);
    }

    @PostMapping("/8104")
    public Mono<T0104> T8104(@RequestBody JTMessage request) {
        return messageManager.request(request.setMessageId(JT808.查询终端参数), T0104.class);
    }

    @PostMapping("/8106")
    public Mono<T0104> T8106(@RequestBody T8106 request) {
        return messageManager.request(request, T0104.class);
    }

    @PostMapping("/8105")
    public Mono<T0001> T8105(@RequestBody T8105 request) {
        return messageManager.request(request, T0001.class);
    }

    @PostMapping("/8107")
    public Mono<T0107> T8107(@RequestBody JTMessage request) {
        return messageManager.request(request.setMessageId(JT808.查询终端属性), T0107.class);
    }

    @PostMapping("/8201")
    public Mono<T0201_0500> T8201(@RequestBody JTMessage request) {
        return messageManager.request(request.setMessageId(JT808.位置信息查询), T0201_0500.class);
    }

    @PostMapping("/8202")
    public Mono<T0001> T8202(@RequestBody T8202 request) {
        return messageManager.request(request, T0001.class);
    }

    @PostMapping("/8203")
    public Mono<T0001> T8203(@RequestBody T8203 request) {
        return messageManager.request(request, T0001.class);
    }

    @PostMapping("/8204")
    public Mono<T0001> T8204(@RequestBody JTMessage request) {
        return messageManager.request(request.setMessageId(JT808.服务器向终端发起链路检测请求), T0001.class);
    }

    @PostMapping("/8300")
    public Mono<T0001> T8300(@RequestBody T8300 request) {
        return messageManager.request(request, T0001.class);
    }

    @PostMapping("/8301")
    public Mono<T0001> T8301(@RequestBody T8301 request) {
        return messageManager.request(request, T0001.class);
    }

    @PostMapping("/8302")
    public Mono<T0001> T8302(@RequestBody T8302 request) {
        return messageManager.request(request, T0001.class);
    }

    @PostMapping("/8303")
    public Mono<T0001> T8303(@RequestBody T8303 request) {
        return messageManager.request(request, T0001.class);
    }

    @PostMapping("/8304")
    public Mono<T0001> T8304(@RequestBody T8304 request) {
        return messageManager.request(request, T0001.class);
    }

    @PostMapping("/8400")
    public Mono<T0001> T8400(@RequestBody T8400 request) {
        return messageManager.request(request, T0001.class);
    }

    @PostMapping("/8401")
    public Mono<T0001> T8401(@RequestBody T8401 request) {
        return messageManager.request(request, T0001.class);
    }

    @PostMapping("/8500")
    public Mono<T0201_0500> T8500(@RequestBody T8500 request) {
        return messageManager.request(request, T0201_0500.class);
    }

    @PostMapping("/8600")
    public Mono<T0001> T8600(@RequestBody T8600 request) {
        return messageManager.request(request, T0001.class);
    }

    @PostMapping("/8601")
    public Mono<T0001> T8601(@RequestBody T8601 request) {
        return messageManager.request(request.setMessageId(JT808.删除圆形区域), T0001.class);
    }

    @PostMapping("/8602")
    public Mono<T0001> T8602(@RequestBody T8602 request) {
        return messageManager.request(request, T0001.class);
    }

    @PostMapping("/8603")
    public Mono<T0001> T8603(@RequestBody T8601 request) {
        return messageManager.request(request.setMessageId(JT808.删除矩形区域), T0001.class);
    }

    @PostMapping("/8604")
    public Mono<T0001> T8604(@RequestBody T8604 request) {
        return messageManager.request(request, T0001.class);
    }

    @PostMapping("/8605")
    public Mono<T0001> T8605(@RequestBody T8601 request) {
        return messageManager.request(request.setMessageId(JT808.删除多边形区域), T0001.class);
    }

    @PostMapping("/8606")
    public Mono<T0001> T8606(@RequestBody T8606 request) {
        return messageManager.request(request, T0001.class);
    }

    @PostMapping("/8607")
    public Mono<T0001> T8607(@RequestBody T8601 request) {
        return messageManager.request(request.setMessageId(JT808.删除路线), T0001.class);
    }

    @PostMapping("/8608")
    public Mono<T0608> T8608(@RequestBody T8608 request) {
        return messageManager.request(request, T0608.class);
    }

    @PostMapping("/8700")
    public Mono<T0001> T8700(@RequestBody JTMessage request) {
        return messageManager.request(request.setMessageId(JT808.行驶记录仪数据采集命令), T0001.class);
    }

    @PostMapping("/8701")
    public Mono<T0001> T8701(@RequestBody T8701 request) {
        return messageManager.request(request, T0001.class);
    }

    @PostMapping("/8702")
    public Mono<T0702> T8702(@RequestBody JTMessage request) {
        return messageManager.request(request.setMessageId(JT808.上报驾驶员身份信息请求), T0702.class);
    }

    @PostMapping("/8801")
    public Mono<T0805> T8801(@RequestBody T8801 request) {
        return messageManager.request(request, T0805.class);
    }

    @PostMapping("/8802")
    public Mono<T0802> T8802(@RequestBody T8802 request) {
        return messageManager.request(request, T0802.class);
    }

    @PostMapping("/8803")
    public Mono<T0001> T8803(@RequestBody T8803 request) {
        return messageManager.request(request, T0001.class);
    }

    @PostMapping("/8804")
    public Mono<T0001> T8804(@RequestBody T8804 request) {
        return messageManager.request(request, T0001.class);
    }

    @PostMapping("/8805")
    public Mono<T0001> T8805(@RequestBody T8805 request) {
        return messageManager.request(request, T0001.class);
    }

    @PostMapping("/8108")
    public Mono<T0001> T8108(@RequestBody T8108 request) {
        return messageManager.request(request, T0001.class);
    }

    @PostMapping("/8900")
    public Mono<T0001> T8900(@RequestBody T8900 request) {
        return messageManager.request(request.build(), T0001.class);
    }

    @PostMapping("/8A00")
    public Mono<T0A00_8A00> T8A00(@RequestBody T0A00_8A00 request) {
        return messageManager.request(request, T0A00_8A00.class);
    }
}
