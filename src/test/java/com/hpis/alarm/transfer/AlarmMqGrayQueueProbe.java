package com.hpis.alarm.transfer;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class AlarmMqGrayQueueProbe {

    private AlarmMqGrayQueueProbe() {
    }

    static QueueStats read(GraySuiteOptions options) throws Exception {
        QueueStats passive = readPassive(options);
        try {
            return readManagement(options.getMqManagementUrl(), options.getMqUsername(), options.getMqPassword(),
                    options.getMqVirtualHost(), options.getQueueName(), passive);
        } catch (Exception ex) {
            if (options.isRequireMqManagement()) {
                throw new IllegalStateException("RabbitMQ management API is required for unacked preflight", ex);
            }
            return passive;
        }
    }

    private static QueueStats readPassive(GraySuiteOptions options) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(options.getMqHost());
        factory.setPort(options.getMqPort());
        factory.setUsername(options.getMqUsername());
        factory.setPassword(options.getMqPassword());
        factory.setVirtualHost(options.getMqVirtualHost());
        try (Connection connection = factory.newConnection("hpis-alarm-gray-preflight");
             Channel channel = connection.createChannel()) {
            AMQP.Queue.DeclareOk queue = channel.queueDeclarePassive(options.getQueueName());
            return new QueueStats(queue.getMessageCount(), -1L, queue.getConsumerCount());
        }
    }

    static QueueStats readManagement(String managementUrl, String username, String password,
                                     String virtualHost, String queueName, QueueStats passive) throws Exception {
        String base = managementUrl.replaceAll("/+$", "");
        String vhost = URLEncoder.encode(virtualHost, StandardCharsets.UTF_8.name());
        String queue = URLEncoder.encode(queueName, StandardCharsets.UTF_8.name());
        HttpURLConnection connection = (HttpURLConnection) new URL(base + "/api/queues/" + vhost + "/" + queue).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8)));
        if (connection.getResponseCode() != 200) {
            throw new IllegalStateException("RabbitMQ management API returned HTTP " + connection.getResponseCode());
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), StandardCharsets.UTF_8))) {
            JSONObject json = JSON.parseObject(reader.readLine());
            return new QueueStats(
                    json.getLongValue("messages_ready"),
                    json.getLongValue("messages_unacknowledged"),
                    json.containsKey("consumers") ? json.getIntValue("consumers") : passive.consumers);
        } finally {
            connection.disconnect();
        }
    }

    static final class QueueStats {
        private final long ready;
        private final long unacked;
        private final int consumers;

        QueueStats(long ready, long unacked, int consumers) {
            this.ready = ready;
            this.unacked = unacked;
            this.consumers = consumers;
        }

        long getReady() {
            return ready;
        }

        long getUnacked() {
            return unacked;
        }

        int getConsumers() {
            return consumers;
        }
    }
}
