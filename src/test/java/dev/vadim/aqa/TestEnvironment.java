package dev.vadim.aqa;

import com.github.tomakehurst.wiremock.WireMockServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

final class TestEnvironment implements AutoCloseable {
    final WireMockServer external = new WireMockServer(wireMockConfig().bindAddress("127.0.0.1").dynamicPort());
    final String apiKey = UUID.randomUUID().toString();
    private Process application;
    private Thread shutdownHook;
    private int port;

    void start() throws Exception {
        Path jar = Path.of(System.getProperty("app.jar")).toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException("JAR приложения не найден: " + jar
                    + ". Укажите путь через -Dapp.jar=/path/to/application.jar.");
        }
        Path runtime = Path.of("target", "runtime").toAbsolutePath();
        Files.createDirectories(runtime);
        Path log = runtime.resolve("application.log");
        external.start();
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx192m", "-Duser.home=" + runtime, "-Dsecret=" + apiKey,
                    "-Dmock=http://127.0.0.1:" + external.port() + "/", "-jar", jar.toString(),
                    "--server.address=127.0.0.1", "--server.port=0", "--spring.main.banner-mode=off");
            builder.directory(runtime.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().clear();
            builder.environment().put("LANG", "en_US.UTF-8");
            application = builder.start();
            shutdownHook = new Thread(this::stopProcesses, "aqa-test-cleanup");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            Pattern started = Pattern.compile("Tomcat started on port (\\d+)");
            long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            while (System.nanoTime() < deadline) {
                if (!application.isAlive()) {
                    throw new IllegalStateException("Приложение завершилось при запуске. См. " + log);
                }
                var matcher = started.matcher(Files.readString(log));
                if (matcher.find()) {
                    port = Integer.parseInt(matcher.group(1));
                    return;
                }
                Thread.sleep(100);
            }
            throw new IllegalStateException("Приложение не запустилось за 45 секунд. См. " + log);
        } catch (Exception error) {
            close();
            throw error;
        }
    }

    String endpoint() {
        return "http://127.0.0.1:" + port + "/endpoint";
    }

    @Override
    public void close() {
        stopProcesses();
        if (shutdownHook != null) {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
            shutdownHook = null;
        }
    }

    private void stopProcesses() {
        if (application != null && application.isAlive()) {
            application.destroy();
            try {
                if (!application.waitFor(5, TimeUnit.SECONDS)) {
                    application.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException error) {
                application.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        if (external.isRunning()) {
            external.stop();
        }
    }
}
