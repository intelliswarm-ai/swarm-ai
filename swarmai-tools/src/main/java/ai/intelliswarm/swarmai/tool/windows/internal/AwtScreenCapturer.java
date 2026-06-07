package ai.intelliswarm.swarmai.tool.windows.internal;

import javax.imageio.ImageIO;
import java.awt.AWTException;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@link ScreenCapturer} backed by {@link Robot} and {@link ImageIO}.
 * Headless-safe: any AWT exception is wrapped and rethrown so tools can map
 * it into a structured error string instead of crashing.
 */
public class AwtScreenCapturer implements ScreenCapturer {

    @Override
    public Path capturePrimary(Path outputDir, String name) {
        Rectangle bounds = primaryBounds();
        return doCapture(bounds, outputDir, name);
    }

    @Override
    public Path captureMonitor(int index, Path outputDir, String name) {
        GraphicsDevice[] screens = screens();
        if (index < 0 || index >= screens.length) {
            throw new IllegalArgumentException("monitor index " + index
                    + " out of range (0.." + (screens.length - 1) + ")");
        }
        GraphicsConfiguration cfg = screens[index].getDefaultConfiguration();
        return doCapture(cfg.getBounds(), outputDir, name);
    }

    @Override
    public Path captureRect(int x, int y, int width, int height, Path outputDir, String name) {
        return doCapture(new Rectangle(x, y, width, height), outputDir, name);
    }

    @Override
    public int screenCount() {
        try {
            return screens().length;
        } catch (HeadlessException e) {
            return 0;
        }
    }

    // ---- helpers ----

    private static GraphicsDevice[] screens() {
        return GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
    }

    private static Rectangle primaryBounds() {
        return GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
    }

    private Path doCapture(Rectangle bounds, Path outputDir, String name) {
        try {
            Files.createDirectories(outputDir);
            Robot robot = new Robot();
            BufferedImage image = robot.createScreenCapture(bounds);
            Path file = outputDir.resolve(name.endsWith(".png") ? name : name + ".png");
            ImageIO.write(image, "png", file.toFile());
            return file;
        } catch (AWTException | IOException | HeadlessException e) {
            throw new RuntimeException("Screen capture failed: " + e.getMessage(), e);
        }
    }
}
