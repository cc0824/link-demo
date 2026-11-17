import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Original product illustrations. Run from project root: java scripts/GenerateImages.java */
public class GenerateImages {
    private static final int WIDTH = 1200;
    private static final int HEIGHT = 630;

    public static void main(String[] args) throws Exception {
        Path directory = Path.of("src/main/resources/static/images");
        Files.createDirectories(directory);
        ImageIO.write(draw(true), "png", directory.resolve("product-1001.png").toFile());
        ImageIO.write(draw(false), "png", directory.resolve("default-product.png").toFile());
        System.out.println("Generated two original 1200 x 630 PNG illustrations.");
    }

    private static BufferedImage draw(boolean bottle) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setPaint(new GradientPaint(0, 0, new Color(227, 233, 218), 1050, 630, new Color(211, 220, 201)));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.setColor(new Color(236, 239, 225));
        g.fill(new Ellipse2D.Double(-120, -400, 1180, 900));
        g.setColor(new Color(221, 227, 211));
        Path2D plane = new Path2D.Double();
        plane.moveTo(0, 483); plane.lineTo(1200, 390); plane.lineTo(1200, 630); plane.lineTo(0, 630); plane.closePath();
        g.fill(plane);
        if (bottle) drawBottle(g); else drawObject(g);
        g.dispose();
        return image;
    }

    private static void shadow(Graphics2D g, double x, double y, double width, double height) {
        for (int i = 18; i >= 0; i--) {
            g.setColor(new Color(63, 73, 50, 2));
            g.fill(new Ellipse2D.Double(x - i * 4, y - i, width + i * 8, height + i * 2));
        }
    }

    private static void drawBottle(Graphics2D g) {
        shadow(g, 443, 490, 390, 34);
        AffineTransform transform = g.getTransform();
        g.rotate(.09, 600, 335);
        // A quiet fabric loop behind the lid.
        g.setColor(new Color(135, 134, 110));
        g.setStroke(new BasicStroke(14f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawArc(670, 112, 79, 155, -88, 255);
        g.setPaint(new GradientPaint(508, 0, new Color(191, 178, 150), 704, 0, new Color(154, 143, 118)));
        g.fill(new RoundRectangle2D.Double(511, 142, 194, 376, 57, 57));
        g.setPaint(new GradientPaint(520, 0, new Color(213, 202, 176), 699, 0, new Color(176, 165, 139)));
        g.fill(new RoundRectangle2D.Double(515, 139, 187, 370, 51, 51));
        g.setPaint(new GradientPaint(515, 0, new Color(231, 222, 199, 150), 640, 0, new Color(220, 209, 184, 0)));
        g.fill(new RoundRectangle2D.Double(521, 160, 95, 330, 35, 35));
        g.setColor(new Color(157, 148, 125));
        g.fill(new RoundRectangle2D.Double(507, 125, 202, 59, 22, 22));
        g.setPaint(new GradientPaint(507, 0, new Color(81, 87, 70), 710, 0, new Color(54, 62, 49)));
        g.fill(new RoundRectangle2D.Double(505, 106, 205, 62, 22, 22));
        g.setColor(new Color(104, 111, 89));
        g.fill(new Ellipse2D.Double(505, 98, 205, 37));
        g.setColor(new Color(128, 135, 111, 90));
        g.fill(new Ellipse2D.Double(523, 103, 155, 21));
        g.setColor(new Color(134, 126, 104, 110));
        g.setStroke(new BasicStroke(1.4f));
        g.drawArc(523, 466, 170, 28, 180, 175);
        // Minimal inset maker's mark, intentionally without font dependencies.
        g.setColor(new Color(119, 113, 92));
        g.fillRect(589, 358, 34, 2);
        g.fillRect(598, 367, 16, 2);
        g.setTransform(transform);
        // A folded orange note adds a small, warm accent.
        AffineTransform saved = g.getTransform();
        g.rotate(-.16, 825, 486);
        g.setColor(new Color(178, 90, 54));
        g.fill(new RoundRectangle2D.Double(777, 461, 114, 68, 3, 3));
        g.setColor(new Color(205, 121, 80));
        Path2D fold = new Path2D.Double();
        fold.moveTo(777, 461); fold.lineTo(891, 461); fold.lineTo(868, 481); fold.lineTo(777, 481); fold.closePath();
        g.fill(fold);
        g.setColor(new Color(242, 221, 196));
        g.fillRect(795, 492, 37, 2);
        g.setTransform(saved);
    }

    private static void drawObject(Graphics2D g) {
        shadow(g, 400, 480, 405, 37);
        g.setColor(new Color(179, 185, 163));
        g.fill(new RoundRectangle2D.Double(446, 275, 310, 226, 16, 16));
        g.setPaint(new GradientPaint(446, 0, new Color(221, 219, 195), 756, 0, new Color(192, 194, 167)));
        g.fill(new RoundRectangle2D.Double(446, 256, 310, 229, 15, 15));
        g.setColor(new Color(232, 229, 207));
        g.fill(new RoundRectangle2D.Double(433, 234, 336, 51, 12, 12));
        g.setColor(new Color(203, 204, 181));
        g.fillRect(588, 238, 30, 247);
        g.setColor(new Color(247, 242, 224));
        g.fill(new RoundRectangle2D.Double(626, 334, 72, 91, 4, 4));
        g.setColor(new Color(178, 90, 54));
        g.fillRect(645, 369, 34, 3);
        g.fillRect(652, 380, 20, 2);
        g.setColor(new Color(169, 174, 147));
        g.setStroke(new BasicStroke(2f));
        g.drawLine(662, 334, 662, 285);
    }
}
