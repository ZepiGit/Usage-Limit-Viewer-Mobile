import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

/** Contact sheets from real status-bar crops; run with JDK 21, no image dependencies. */
class StatusIconContacts {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: java tools/StatusIconContacts.java <artifact-root>");
        Path root = Path.of(args[0]);
        List<Path> directories;
        try (var paths = Files.walk(root)) {
            directories = paths.filter(Files::isDirectory).filter(path -> {
                try (var files = Files.list(path)) {
                    return files.anyMatch(file -> file.getFileName().toString().endsWith("-statusbar.png"));
                } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
            }).sorted().toList();
        }
        if (directories.isEmpty()) throw new IllegalArgumentException("No status-bar crops found under " + root);
        for (Path directory : directories) {
            List<Path> crops;
            try (var paths = Files.list(directory)) {
                crops = paths.filter(path -> path.getFileName().toString().endsWith("-statusbar.png")).sorted().toList();
            }
            int scale = 2, labelHeight = 30, rowHeight = 0, width = 0;
            for (Path path : crops) {
                BufferedImage crop = ImageIO.read(path.toFile());
                if (crop == null) throw new IllegalArgumentException("Not an image: " + path);
                rowHeight = Math.max(rowHeight, crop.getHeight() * scale + labelHeight);
                width = Math.max(width, crop.getWidth() * scale);
            }
            BufferedImage sheet = new BufferedImage(width, rowHeight * crops.size(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = sheet.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
            graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 18));
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            for (int index = 0; index < crops.size(); index++) {
                Path path = crops.get(index);
                BufferedImage crop = ImageIO.read(path.toFile());
                graphics.setColor(Color.BLACK);
                graphics.drawString(path.getFileName().toString(), 8, index * rowHeight + 22);
                graphics.drawImage(crop, 0, index * rowHeight + labelHeight, crop.getWidth() * scale, crop.getHeight() * scale, null);
            }
            graphics.dispose();
            Path destination = directory.resolve("statusbar-contact-sheet.png");
            ImageIO.write(sheet, "png", destination.toFile());
            System.out.println(destination.toAbsolutePath());

            boolean hasScenarioFrames;
            try (var paths = Files.list(directory)) {
                hasScenarioFrames = paths.anyMatch(path -> path.getFileName().toString().matches("\\d{2}-.*\\.png"));
            }
            if (!hasScenarioFrames) continue;

            List<String> names = List.of("03-enabled-shade.png", "14-same-provider-shade.png", "05-reordered-shade.png", "03-enabled-lockscreen.png", "14-same-provider-lockscreen.png");
            BufferedImage[] frames = new BufferedImage[names.size()];
            int frameWidth = 360, frameHeight = 0;
            for (int index = 0; index < names.size(); index++) {
                Path path = directory.resolve(names.get(index));
                if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Missing scenario frame: " + path);
                frames[index] = ImageIO.read(path.toFile());
                if (frames[index] == null) throw new IllegalArgumentException("Not an image: " + path);
                frameHeight = Math.max(frameHeight, Math.max(1, (int) Math.round(frames[index].getHeight() * (double) frameWidth / frames[index].getWidth())));
            }
            BufferedImage surfaces = new BufferedImage(frameWidth * frames.length, labelHeight + frameHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D surfaceGraphics = surfaces.createGraphics();
            surfaceGraphics.setColor(Color.WHITE);
            surfaceGraphics.fillRect(0, 0, surfaces.getWidth(), surfaces.getHeight());
            surfaceGraphics.setColor(Color.BLACK);
            surfaceGraphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 16));
            surfaceGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            for (int index = 0; index < frames.length; index++) {
                BufferedImage frame = frames[index];
                int height = Math.max(1, (int) Math.round(frame.getHeight() * (double) frameWidth / frame.getWidth()));
                surfaceGraphics.drawString(names.get(index), index * frameWidth + 8, 22);
                surfaceGraphics.drawImage(frame, index * frameWidth, labelHeight, frameWidth, height, null);
            }
            surfaceGraphics.dispose();
            Path surfaceDestination = directory.resolve("shade-lockscreen-contact-sheet.png");
            ImageIO.write(surfaces, "png", surfaceDestination.toFile());
            System.out.println(surfaceDestination.toAbsolutePath());
        }
    }
}
