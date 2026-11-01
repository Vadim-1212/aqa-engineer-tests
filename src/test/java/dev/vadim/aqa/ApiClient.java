package dev.vadim.aqa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Allure;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

final class ApiClient {
    private final TestEnvironment environment;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json = new ObjectMapper();

    ApiClient(TestEnvironment environment) {
        this.environment = environment;
    }

    Response call(String token, String action) {
        return call(token, action, environment.apiKey);
    }

    Response call(String token, String action, String apiKey) {
        String body = (token == null ? "" : "token=" + encode(token))
                + (action == null ? "" : (token == null ? "" : "&") + "action=" + encode(action));
        return send("POST", "application/x-www-form-urlencoded", body, apiKey);
    }

    Response send(String method, String contentType, String body, String apiKey) {
        return Allure.step("Отправить запрос: " + method + " /endpoint", () -> {
            Allure.addAttachment("Параметры запроса", "text/plain",
                    "Метод: " + method + "\nContent-Type: " + contentType
                            + "\nX-Api-Key: " + (apiKey == null ? "отсутствует" : "[скрыт]") + "\n\n" + body);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(environment.endpoint()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", contentType).header("Accept", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
            if (apiKey != null) {
                builder.header("X-Api-Key", apiKey);
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            Allure.addAttachment("Ответ приложения", "text/plain",
                    "HTTP " + response.statusCode() + "\nContent-Type: "
                            + response.headers().firstValue("Content-Type").orElse("не задан")
                            + "\n\n" + response.body());
            return new Response(response.statusCode(),
                    response.headers().firstValue("Content-Type").orElse(""), response.body());
        });
    }

    void assertOk(Response response) {
        Allure.step("Проверить: операция выполнена успешно, result = OK", () -> {
            assertTrue(response.status() >= 200 && response.status() < 300,
                    "Ожидался успешный HTTP-ответ, получен " + response.status());
            JsonNode actual = parse(response);
            assertEquals(json.readTree("{\"result\":\"OK\"}"), actual,
                    "Успешный ответ должен соответствовать документированному JSON");
        });
    }

    void assertError(Response response) {
        Allure.step("Проверить: операция отклонена с понятной причиной", () -> {
            JsonNode actual = parse(response);
            assertAll(
                    () -> assertEquals("ERROR", actual.path("result").asText(),
                            "Ожидался result = ERROR"),
                    () -> assertTrue(actual.path("message").isTextual()
                                    && !actual.path("message").asText().isBlank(),
                            "Ошибка должна содержать непустое текстовое объяснение message"));
        });
    }

    private JsonNode parse(Response response) throws Exception {
        assertTrue(response.contentType().toLowerCase(java.util.Locale.ROOT).contains("application/json"),
                "Ожидался Content-Type: application/json, получен " + response.contentType());
        JsonNode result = json.readTree(response.body());
        assertNotNull(result, "Тело JSON не должно быть пустым");
        assertTrue(result.isObject(), "Ответ должен быть JSON-объектом");
        return result;
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    record Response(int status, String contentType, String body) { }
}
