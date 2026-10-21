package io.inspector.ops;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The subset of Prometheus text exposition this BFF reads. Labels stay a fixed set. */
public final class PrometheusText {

    public record Sample(String name, Map<String, String> labels, double value) {}

    private PrometheusText() {}

    public static List<Sample> parse(String text) {
        List<Sample> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            int valueStart = line.lastIndexOf(' ');
            if (valueStart <= 0) {
                continue;
            }
            double value;
            try {
                value = Double.parseDouble(line.substring(valueStart + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            String head = line.substring(0, valueStart);
            int brace = head.indexOf('{');
            if (brace < 0) {
                out.add(new Sample(head, Map.of(), value));
                continue;
            }
            if (!head.endsWith("}")) {
                continue;
            }
            out.add(new Sample(head.substring(0, brace), labels(head.substring(brace + 1, head.length() - 1)), value));
        }
        return out;
    }

    public static Double labeled(List<Sample> samples, String name, String label, String labelValue) {
        for (Sample sample : samples) {
            if (name.equals(sample.name()) && labelValue.equals(sample.labels().get(label))) {
                return sample.value();
            }
        }
        return null;
    }

    private static Map<String, String> labels(String body) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (String part : body.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
                value = value.substring(1, value.length() - 1);
            }
            labels.put(key, value);
        }
        return Map.copyOf(labels);
    }
}
