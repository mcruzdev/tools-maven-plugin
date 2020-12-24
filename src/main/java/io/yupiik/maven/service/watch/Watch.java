/*
 * Copyright (c) 2020 - Yupiik SAS - https://www.yupiik.com
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.maven.service.watch;

import lombok.RequiredArgsConstructor;
import org.apache.maven.plugin.logging.Log;
import org.asciidoctor.Asciidoctor;
import org.asciidoctor.Options;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

import static java.util.concurrent.TimeUnit.SECONDS;

@RequiredArgsConstructor
public class Watch implements Runnable {
    private final Log log;
    private final Path source;
    private final Options options;
    private final Asciidoctor asciidoctor;
    private final long watchDelay;
    private final BiConsumer<Options, Asciidoctor> renderer;
    private final Runnable onFirstRender;

    @Override
    public void run() {
        watch(options, asciidoctor);
    }

    private void watch(final Options options, final Asciidoctor adoc) {
        final AtomicBoolean toggle = new AtomicBoolean(true);
        final ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(final Runnable worker) {
                final Thread thread = new Thread(worker, getClass().getName() + "-watch");
                thread.setPriority(Thread.NORM_PRIORITY);
                return thread;
            }
        });
        final AtomicLong lastModified = new AtomicLong(findLastUpdated(-1, source));
        final AtomicLong checksCount = new AtomicLong(0);
        service.scheduleWithFixedDelay(() -> {
            final long currentLM = findLastUpdated(-1, source);
            final long lastModifiedValue = lastModified.get();
            if (lastModifiedValue < currentLM) {
                lastModified.set(currentLM);
                if (checksCount.getAndIncrement() > 0) {
                    log.debug("Change detected, re-rendering");
                    renderer.accept(options, adoc);
                    checksCount.set(0);
                } else {
                    log.debug("Change detected, waiting another iteration to ensure it is fully refreshed");
                }
            } else if (checksCount.get() > 0) {
                log.debug("Change detected, re-rendering");
                renderer.accept(options, adoc);
                checksCount.set(0);
            } else {
                log.debug("No change");
            }
        }, watchDelay, watchDelay, TimeUnit.MILLISECONDS);
        launchCli(options, adoc);
        toggle.set(false);
        try {
            service.shutdownNow();
            service.awaitTermination(2, SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void launchCli(final Options options, final Asciidoctor adoc) {
        renderer.accept(options, adoc);
        onFirstRender.run();

        try (final BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            log.info("Type '[refresh|exit]' to either force a rendering or exit");
            while ((line = reader.readLine()) != null)
                switch (line) {
                    case "":
                    case "r":
                    case "refresh":
                        renderer.accept(options, adoc);
                        break;
                    case "exit":
                    case "quit":
                    case "q":
                        return;
                    default:
                        log.error("Unknown command: '" + line + "', type: '[refresh|exit]'");
                }
        } catch (final IOException e) {
            log.debug("Exiting waiting loop", e);
        }
    }

    private long findLastUpdated(final long value, final Path dir) {
        if (Files.exists(dir) && Files.isDirectory(dir)) {
            try {
                return Files.walk(dir).reduce(value, (current, path) -> {
                    try {
                        return Math.max(current, Files.getLastModifiedTime(path).toMillis());
                    } catch (final IOException e) {
                        throw new IllegalStateException(e);
                    }
                }, Math::max);
            } catch (final IOException e) {
                // no-op, default to value for this iteration
            }
        }
        try {
            return Files.getLastModifiedTime(dir).toMillis();
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
