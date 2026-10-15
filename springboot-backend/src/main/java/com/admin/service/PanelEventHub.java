package com.admin.service;

import com.alibaba.fastjson.JSON;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.PostConstruct;

/**
 * Real-time panel events over Server-Sent Events (GET /api/v1/events). Administrators receive
 * every event; other users only events about their own resources. Node events are admin-only.
 */
@Slf4j
@Service
public class PanelEventHub {
    private static final long STREAM_TIMEOUT_MS = 30 * 60_000L;
    private static final int MAX_STREAMS = 200;
    private static final int MAX_STREAMS_PER_USER = 10;
    private static volatile PanelEventHub instance;

    record Subscriber(SseEmitter emitter, int userId, boolean admin) {}

    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong();

    @PostConstruct
    void register() {
        instance = this;
    }

    /**
     * Publishes an event. ownerUserId limits it to that user (and administrators); null makes it
     * admin-only. Safe to call from anywhere; does nothing before the hub starts.
     */
    public static void publish(String type, Integer ownerUserId, Map<String, Object> data) {
        PanelEventHub hub = instance;
        if (hub != null) hub.dispatch(type, ownerUserId, data);
    }

    public SseEmitter subscribe(int userId, boolean admin) {
        if (subscribers.size() >= MAX_STREAMS) throw new IllegalStateException("实时事件连接已达上限，请稍后再试");
        long mine = subscribers.stream().filter(s -> s.userId() == userId).count();
        if (mine >= MAX_STREAMS_PER_USER) throw new IllegalStateException("同一账号最多同时保持 " + MAX_STREAMS_PER_USER + " 个实时事件连接");
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        Subscriber subscriber = new Subscriber(emitter, userId, admin);
        subscribers.add(subscriber);
        emitter.onCompletion(() -> subscribers.remove(subscriber));
        emitter.onTimeout(() -> subscribers.remove(subscriber));
        emitter.onError(error -> subscribers.remove(subscriber));
        send(subscriber, SseEmitter.event().name("ready").data("{\"ok\":true}"));
        return emitter;
    }

    void dispatch(String type, Integer ownerUserId, Map<String, Object> data) {
        if (subscribers.isEmpty()) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        payload.put("time", System.currentTimeMillis());
        payload.put("data", data);
        String id = Long.toString(sequence.incrementAndGet());
        String json = JSON.toJSONString(payload);
        for (Subscriber subscriber : subscribers) {
            if (!subscriber.admin() && (ownerUserId == null || !Objects.equals(ownerUserId, subscriber.userId()))) continue;
            send(subscriber, SseEmitter.event().id(id).name(type).data(json));
        }
    }

    /** Keeps proxies from closing idle streams. */
    @Scheduled(fixedRate = 25_000L, initialDelay = 25_000L)
    void heartbeat() {
        for (Subscriber subscriber : subscribers) send(subscriber, SseEmitter.event().comment("ping"));
    }

    int subscriberCount() {
        return subscribers.size();
    }

    void send(Subscriber subscriber, SseEmitter.SseEventBuilder event) {
        try {
            subscriber.emitter().send(event);
        } catch (IOException | IllegalStateException e) {
            subscribers.remove(subscriber);
            subscriber.emitter().completeWithError(e);
        }
    }
}
