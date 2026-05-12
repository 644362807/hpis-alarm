package com.hpis.alarm.transfer;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.hpis.common.core.utils.StringUtils;
import com.hpis.common.redis.service.RedisService;
import com.hpis.common.websocket.WebSocketKeepAliveClient;
import com.hpis.common.websocket.config.WebSocketClientConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.enums.ReadyState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * 创建websocket客户端的bean
 */
@Slf4j
@Component
public class WsDeviceBeanInstance {

    @Autowired
    private ThreadPoolTaskExecutor threadPoolExecutor;

    @Autowired
    private NacosDiscoveryProperties nacosDiscoveryProperties;

    @Autowired
    private WebSocketClientConfiguration wsConf;

    @Autowired
    private RedisService redisService;


    /**
     * 项目启动时自动开启websocket连接，适用于长连接
     * @return
     */
    @Bean
    public WebSocketKeepAliveClient startWebSocketConnection() {
        //获取nacos注册的服务Id
        String serviceId = nacosDiscoveryProperties.getService();
        WebSocketKeepAliveClient webSocketClient = null;
        if (StringUtils.isNotBlank(wsConf.getWsUrl())) {
            try {
                webSocketClient = new WebSocketKeepAliveClient(new URI(wsConf.getWsUrl()), threadPoolExecutor,
                        wsConf.getHeartbeatInterval(), wsConf.getNumberOfNoResponse(), serviceId, redisService, "hpis-access");
                webSocketClient.connect();
                //配置为<=0可取消定时检测(ping-pong)
                webSocketClient.setConnectionLostTimeout(0);
                while (ReadyState.NOT_YET_CONNECTED == webSocketClient.getReadyState()) {
                    log.info("开启websocket连接，地址：{}，当前连接状态：{}", wsConf.getWsUrl(), webSocketClient.getReadyState());
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            } catch (Exception e) {
                log.error("创建websocket连接异常：{}", e.getMessage());
                e.printStackTrace();
            }
        }
        return webSocketClient;
    }

}
