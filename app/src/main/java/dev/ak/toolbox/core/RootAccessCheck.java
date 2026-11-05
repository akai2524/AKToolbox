package dev.ak.toolbox.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Checks actual effective UID without changing settings. */
public final class RootAccessCheck {
    public enum Result { GRANTED, NOT_GRANTED, UNKNOWN }
    private RootAccessCheck() { }

    public static Result check(String[] command, long timeoutMs) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            Process running = process;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (InputStream stream = running.getInputStream()) {
                    byte[] buffer = new byte[512];
                    int count;
                    while ((count = stream.read(buffer)) != -1)
                        if (output.size() < 4096) output.write(buffer, 0, count);
                } catch (IOException ignored) { }
            }, "AK-root-check-output");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) return Result.UNKNOWN;
            reader.join(300);
            String text = output.toString(StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) return Result.NOT_GRANTED;
            for (String line : text.split("\\R")) if ("0".equals(line.trim())) return Result.GRANTED;
            return text.isEmpty() ? Result.UNKNOWN : Result.NOT_GRANTED;
        } catch (IOException error) {
            return Result.NOT_GRANTED;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return Result.UNKNOWN;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }
}
