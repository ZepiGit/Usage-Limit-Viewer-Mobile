import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.imageio.ImageIO;

/** Candidate comparison sheets from captured images; run with JDK 21. */
class PrototypeVisibilityContacts {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: java tools/PrototypeVisibilityContacts.java <run-artifact-root>");
        Path root = Path.of(args[0]);
        List<Path> directories;
        try (var paths = Files.walk(root)) {
            directories = paths.filter(Files::isDirectory).filter(path -> {
                String name = path.getFileName().toString();
                return name.startsWith("prototype-visibility-api30")
                    || name.startsWith("prototype-visibility-api36")
                    || name.startsWith("prototype-visibility-api37");
            }).sorted().toList();
        }
        if (directories.isEmpty()) throw new IllegalArgumentException("No prototype visibility API directories found under " + root);
        List<String> preferred = List.of("low", "default", "promoted-one", "promoted-three");
        for (Path directory : directories) {
            List<Path> candidates;
            try (var paths = Files.list(directory)) {
                candidates = paths.filter(Files::isDirectory).sorted(Comparator
                    .comparingInt((Path path) -> {
                        int rank = preferred.indexOf(path.getFileName().toString());
                        return rank < 0 ? preferred.size() : rank;
                    }).thenComparing(path -> path.getFileName().toString())).toList();
            }
            List<Path> captured = new ArrayList<>();
            int barWidth = 1440, rowHeight = 810, labelHeight = 30, shadeWidth = 360;
            for (Path candidate : candidates) {
                Path barPath = candidate.resolve("01-posted-home-statusbar.png");
                Path shadePath = candidate.resolve("01-posted-shade.png");
                boolean hasBar = Files.isRegularFile(barPath), hasShade = Files.isRegularFile(shadePath);
                if (!hasBar && !hasShade) continue;
                if (!hasBar || !hasShade) {
                    throw new IllegalArgumentException("Missing paired capture for " + candidate + ": " + (hasBar ? shadePath : barPath));
                }
                BufferedImage bar = ImageIO.read(barPath.toFile());
                BufferedImage shade = ImageIO.read(shadePath.toFile());
                if (bar == null) throw new IllegalArgumentException("Not an image: " + barPath);
                if (shade == null) throw new IllegalArgumentException("Not an image: " + shadePath);
                barWidth = Math.max(barWidth, bar.getWidth() * 2);
                int shadeHeight = (int) Math.round((double) shade.getHeight() * shadeWidth / shade.getWidth());
                rowHeight = Math.max(rowHeight, labelHeight + Math.max(bar.getHeight() * 2, shadeHeight));
                captured.add(candidate);
            }
            if (captured.isEmpty()) throw new IllegalArgumentException("No candidate captures found in " + directory);
            BufferedImage sheet = new BufferedImage(barWidth + shadeWidth, rowHeight * captured.size(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = sheet.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
            graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 18));
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            for (int index = 0; index < captured.size(); index++) {
                Path candidate = captured.get(index);
                BufferedImage bar = ImageIO.read(candidate.resolve("01-posted-home-statusbar.png").toFile());
                BufferedImage shade = ImageIO.read(candidate.resolve("01-posted-shade.png").toFile());
                int y = index * rowHeight;
                graphics.setColor(Color.BLACK);
                graphics.drawString(directory.getFileName() + " / " + candidate.getFileName(), 8, y + 22);
                graphics.drawImage(bar, 0, y + labelHeight, bar.getWidth() * 2, bar.getHeight() * 2, null);
                int shadeHeight = (int) Math.round((double) shade.getHeight() * shadeWidth / shade.getWidth());
                graphics.drawImage(shade, barWidth, y + labelHeight, shadeWidth, shadeHeight, null);
            }
            graphics.dispose();
            Path destination = directory.resolve("candidate-comparison.png");
            if (!ImageIO.write(sheet, "png", destination.toFile())) throw new IllegalStateException("No PNG writer available");
            System.out.println(destination.toAbsolutePath());
        }
    }
}
