package io.github.gabrielbbaldez.stacktale.example;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.github.gabrielbbaldez.stacktale.logback.StacktaleAppender;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Logs one error with a state-carrying {@link OrderException}, then asserts the produced
 * report contains a {@code fields:} line. Exits 0 on success, 1 on failure — so the same
 * binary is the assertion. On the JVM it always passes; compiled with native-image it
 * passes only because {@code OrderException} is registered for reflection, which is the
 * whole point of the smoke test.
 */
public final class NativeSmokeMain {

    public static void main(String[] args) throws Exception {
        Path log = Path.of("errors-ai-native-smoke.log");
        Files.deleteIfExists(log);

        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        StacktaleAppender appender = new StacktaleAppender();
        appender.setContext(ctx);
        appender.setFile(log.toString());
        appender.setAppPackages("io.github.gabrielbbaldez.stacktale.example");
        appender.start();
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);

        org.slf4j.Logger app = LoggerFactory.getLogger(NativeSmokeMain.class);
        try {
            throw new OrderException(889, false);
        } catch (OrderException e) {
            app.error("checkout failed for order {}", 889, e);
        }
        appender.stop(); // flush

        String content = Files.exists(log) ? Files.readString(log) : "";
        boolean ok = content.contains("fields:") && content.contains("orderId=889");
        if (ok) {
            System.out.println("NATIVE SMOKE OK — fields: captured under this runtime");
            System.exit(0);
        }
        System.out.println("NATIVE SMOKE FAILED — expected a fields: line with orderId=889. Report was:\n" + content);
        System.exit(1);
    }

    private NativeSmokeMain() {
    }
}
