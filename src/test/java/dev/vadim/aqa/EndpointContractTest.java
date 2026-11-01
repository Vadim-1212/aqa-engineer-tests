package dev.vadim.aqa;

import io.qameta.allure.*;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@Epic("Веб-сервис пользовательских сессий")
@Feature("Авторизация, действия и завершение сессии")
@Owner("Вадим")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndpointContractTest {
    private final TestEnvironment environment = new TestEnvironment();
    private ApiClient api;

    @BeforeAll
    void startApplication() throws Exception {
        environment.start();
        api = new ApiClient(environment);
    }

    @BeforeEach
    void resetExternalService() {
        environment.external.resetAll();
    }

    @AfterEach
    void attachExternalCalls() {
        Allure.addAttachment("Обращения к внешнему сервису", "text/plain",
                environment.external.getAllServeEvents().stream()
                        .map(event -> event.getRequest().getMethod() + " " + event.getRequest().getUrl()
                                + "\n" + event.getRequest().getBodyAsString()
                                + "\nОтвет: " + event.getResponse().getStatus())
                        .reduce((left, right) -> left + "\n\n" + right)
                        .orElse("Обращений не было"));
    }

    @AfterAll
    void stopApplication() {
        environment.close();
    }

    @Test
    @DisplayName("Успешный вход передаёт токен во внешний сервис")
    @Story("Вход в систему")
    @Severity(SeverityLevel.BLOCKER)
    void loginSendsExpectedRequest() {
        String token = token();
        stub("/auth", 200, "arbitrary response");
        api.assertOk(api.call(token, "LOGIN"));
        verifyCall("/auth", token, 1);
        noCall("/doAction");
    }

    @Test
    @DisplayName("После входа можно выполнить действие, после выхода доступ закрыт")
    @Story("Жизненный цикл сессии")
    @Severity(SeverityLevel.BLOCKER)
    void fullSessionLifecycle() {
        String token = token();
        login(token);
        stub("/doAction", 200, "done");
        api.assertOk(api.call(token, "ACTION"));
        api.assertOk(api.call(token, "LOGOUT"));
        api.assertError(api.call(token, "ACTION"));
        verifyCall("/auth", token, 1);
        verifyCall("/doAction", token, 1);
    }

    @Test
    @DisplayName("Действие без предварительного входа отклоняется")
    @Story("Защита сессии")
    @Severity(SeverityLevel.CRITICAL)
    void actionWithoutLoginIsRejected() {
        stub("/doAction", 200, "done");
        api.assertError(api.call(token(), "ACTION"));
        noExternalCalls();
    }

    @Test
    @DisplayName("Отклонённый внешним сервисом вход не открывает доступ к действиям")
    @Story("Неуспешный вход")
    @Severity(SeverityLevel.CRITICAL)
    void rejectedLoginDoesNotCreateSession() {
        String token = token();
        stub("/auth", 401, "denied");
        stub("/doAction", 200, "done");
        api.assertError(api.call(token, "LOGIN"));
        api.assertError(api.call(token, "ACTION"));
        verifyCall("/auth", token, 1);
        noCall("/doAction");
    }

    @Test
    @DisplayName("Неверный API-ключ блокирует запрос до обращения к внешнему сервису")
    @Story("Защита API")
    @Severity(SeverityLevel.CRITICAL)
    void wrongApiKeyIsRejected() {
        stub("/auth", 200, "ok");
        ApiClient.Response response = api.call(token(), "LOGIN", "wrong-key");
        Allure.step("Проверить: доступ запрещён на уровне HTTP", () ->
                assertTrue(response.status() >= 400 && response.status() < 500,
                        "Без верного ключа ожидался отказ 4xx, получен " + response.status()));
        noExternalCalls();
    }

    @Test
    @DisplayName("Токен длиной 31 символ отклоняется до обращения к внешнему сервису")
    @Story("Проверка входных данных")
    @Severity(SeverityLevel.CRITICAL)
    void shortTokenIsRejected() {
        stub("/auth", 200, "ok");
        api.assertError(api.call("A".repeat(31), "LOGIN"));
        noExternalCalls();
    }

    private void login(String token) {
        stub("/auth", 200, "ok");
        Allure.step("Войти с корректным токеном", () -> api.assertOk(api.call(token, "LOGIN")));
    }

    private void stub(String path, int status, String body) {
        environment.external.stubFor(post(urlEqualTo(path)).willReturn(aResponse()
                .withStatus(status).withHeader("Content-Type", "text/plain").withBody(body)));
    }

    private void verifyCall(String path, String token, int count) {
        Allure.step("Проверить внешний запрос " + path + ": токен, заголовки и число вызовов = " + count,
                () -> environment.external.verify(count, postRequestedFor(urlEqualTo(path))
                        .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                        .withHeader("Accept", containing("application/json"))
                        .withRequestBody(equalTo("token=" + ApiClient.encode(token)))));
    }

    private void noCall(String path) {
        Allure.step("Проверить: внешний сервис " + path + " не вызывался", () ->
                environment.external.verify(0, anyRequestedFor(urlPathEqualTo(path))));
    }

    private void noExternalCalls() {
        Allure.step("Проверить: обращений к внешнему сервису не было", () ->
                assertTrue(environment.external.getAllServeEvents().isEmpty(),
                        "Недопустимый запрос не должен обращаться к внешнему сервису"));
    }

    private static String token() {
        return UUID.randomUUID().toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);
    }
}
