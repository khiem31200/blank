package com.example.blank.service;

import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Client goi backend-service Python (3ddfa): /enroll, /recognize, /identities.
 * Dung JDK HttpClient + timeout tuong minh: Python chay 1 worker, /enroll chan event loop
 * nen bat buoc co read-timeout de khong treo thread cua view-service.
 */
@Log4j2
@Service
public class RecognitionClient {

    /** Loi tu backend (HTTP != 2xx, khong ket noi duoc, timeout...). */
    public static class BackendException extends RuntimeException {
        public BackendException(String msg) { super(msg); }
        public BackendException(String msg, Throwable cause) { super(msg, cause); }
    }

    public record EnrollResponse(int id, String message) {}
    public record RecognizeResponse(String identity, Double confidence, String message) {}

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final Duration enrollTimeout;
    private final Duration recognizeTimeout;

    public RecognitionClient(ObjectMapper mapper,
                             @Value("${recognition.base-url}") String baseUrl,
                             @Value("${recognition.connect-timeout:PT5S}") Duration connectTimeout,
                             @Value("${recognition.enroll-timeout:PT20S}") Duration enrollTimeout,
                             @Value("${recognition.recognize-timeout:PT10S}") Duration recognizeTimeout) {
        this.mapper = mapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.enrollTimeout = enrollTimeout;
        this.recognizeTimeout = recognizeTimeout;
        // Ep HTTP/1.1: mac dinh JDK HttpClient thu HTTP/2 (Upgrade: h2c voi http://),
        // uvicorn/h11 khong ho tro upgrade -> bo qua body -> FastAPI 422 "body missing"
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();
    }

    /** Luong 3: gui 5 anh + ten sang Python /enroll (Task P1 da them field name). */
    public EnrollResponse enroll(List<String> images, String name) {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode arr = body.putArray("images");
        images.forEach(arr::add);
        if (name != null && !name.isBlank()) body.put("name", name.trim());

        JsonNode res = post("/enroll", body, enrollTimeout);
        return new EnrollResponse(res.path("id").asInt(-1), res.path("message").asText(null));
    }

    /** Luong 4: gui 1 anh sang Python /recognize. */
    public RecognizeResponse recognize(String image) {
        ObjectNode body = mapper.createObjectNode();
        body.putArray("images").add(image);

        JsonNode res = post("/recognize", body, recognizeTimeout);
        JsonNode idn = res.path("identity");
        JsonNode conf = res.path("confidence");
        return new RecognizeResponse(
                idn.isNull() || idn.isMissingNode() ? null : idn.asText(),
                conf.isNumber() ? conf.asDouble() : null,
                res.path("message").asText(null));
    }

    /**
     * Check trung ten TRUOC khi bat dau chup (chan "identity mo coi": Python /enroll
     * tao identity truoc roi moi gan ten -> gap 409 trung ten thi record khong ten da nam trong gallery).
     * Duyet de quy moi field "name" trong response /identities de khong phu thuoc schema chinh xac.
     */
    public boolean nameExists(String name) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/identities"))
                .timeout(recognizeTimeout)
                .GET()
                .build();
        JsonNode res = send(req, "/identities");
        return containsName(res, name.trim());
    }

    // ---- noi bo ----

    private JsonNode post(String path, ObjectNode body, Duration timeout) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        return send(req, path);
    }

    private JsonNode send(HttpRequest req, String path) {
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                log.warn("Backend {} tra HTTP {}: {}", path, res.statusCode(), res.body());
                throw new BackendException("HTTP " + res.statusCode() + " tu backend " + path + ": " + res.body());
            }
            return mapper.readTree(res.body());
        } catch (BackendException be) {
            throw be;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new BackendException("Bi ngat khi goi backend " + path, ie);
        } catch (Exception e) {
            throw new BackendException("Khong goi duoc backend " + path + ": " + e.getMessage(), e);
        }
    }

    private static boolean containsName(JsonNode node, String target) {
        if (node == null) return false;
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                if (containsName(node.get(i), target)) return true;
            }
            return false;
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                if ("name".equals(e.getKey()) && e.getValue().isTextual()
                        && e.getValue().asText().trim().equalsIgnoreCase(target)) return true;
                if (containsName(e.getValue(), target)) return true;
            }
        }
        return false;
    }
}
