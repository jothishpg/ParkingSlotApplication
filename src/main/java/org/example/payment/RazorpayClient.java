package org.example.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class RazorpayClient {
    public static final class ApiException extends IllegalStateException {
        private final int status;

        public ApiException(int status) {
            this(status, null);
        }

        public ApiException(int status, String response) {
            super(errorMessage(status, response));
            this.status = status;
        }

        public int status() {
            return status;
        }

        public boolean definitiveRejection() {
            return status == 400 || status == 401 || status == 403 || status == 422;
        }
    }

    public static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    private RazorpayClient() {
    }

    static String errorMessage(int status, String response) {
        String prefix = "Razorpay request failed (HTTP " + status + ").";
        if (response == null) return prefix;
        try {
            JsonNode error = JSON.readTree(response).path("error");
            String description = error.path("description").asText("");
            // Only the provider's error description, never the full response or request credentials.
            description = description.replaceAll("[\\p{Cntrl}]", " ")
                    .replaceAll("(?i)(https?://|rzp_(?:test|live)_|Bearer\\s+|Basic\\s+)\\S+", "[redacted]")
                    .replaceAll("\\+?\\d{10,}", "[redacted]");
            if (description.length() > 400) description = description.substring(0, 400);
            return description.isBlank() ? prefix : prefix + " " + description;
        } catch (Exception ignored) {
            return prefix;
        }
    }

    public static JsonNode api(String path, Object body, String authorization) throws Exception {
        return send("https://api.razorpay.com/v1/" + path, body, authorization);
    }

    private static JsonNode send(String url, Object body, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json");
        if (authorization != null) request.header("Authorization", authorization);
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        HttpResponse<String> response;
        try {
            response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new ApiException(response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    public static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Set " + name);
        return value;
    }

    public static String webhookSecret() {
        return required("RAZORPAY_WEBHOOK_SECRET");
    }

    public static String text(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) throw new IllegalStateException("Razorpay response missing " + field);
        return value;
    }
}
