package ai.intelliswarm.swarmai.tool.windows.internal;

import java.nio.file.Path;

/**
 * Captures the screen (or a region of it) to a PNG file.
 * Abstracted so the tool can be unit tested without an actual display.
 */
public interface ScreenCapturer {

    /** Capture the primary monitor and write a PNG to {@code outputDir/name}. */
    Path capturePrimary(Path outputDir, String name);

    /** Capture monitor {@code index} (0-based) and write a PNG to {@code outputDir/name}. */
    Path captureMonitor(int index, Path outputDir, String name);

    /** Capture an arbitrary virtual-desktop rectangle and write a PNG. */
    Path captureRect(int x, int y, int width, int height, Path outputDir, String name);

    /** Number of screens. Used for input validation in monitor-index ops. */
    int screenCount();
}
