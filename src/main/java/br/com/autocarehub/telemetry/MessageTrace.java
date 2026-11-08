package br.com.autocarehub.telemetry;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class MessageTrace {
    private static final W3CTraceContextPropagator PROPAGATOR = W3CTraceContextPropagator.getInstance();
    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier.get(key);
        }
    };

    private MessageTrace() {}

    public static String capture() {
        Map<String, String> headers = new HashMap<>();
        PROPAGATOR.inject(Context.current(), headers, Map::put);
        return headers.getOrDefault("traceparent", "") + "|" + headers.getOrDefault("tracestate", "");
    }

    public static Scope restore(String stored) {
        var parts = Objects.toString(stored, "|").split("\\|", 2);
        var headers = Map.of("traceparent", parts[0], "tracestate", parts[1]);
        return PROPAGATOR.extract(Context.current(), headers, GETTER).makeCurrent();
    }
}
