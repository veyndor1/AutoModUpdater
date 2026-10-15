package com.skymc.autoupdater.bootstrap;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.*;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import org.w3c.dom.*;

/** Decode once outside the EDT; replay GIF timing on the shared UI clock. */
final class UiAssets {
    static Font font(boolean strong, float size) {
        return (strong ? Fonts.STRONG : Fonts.REGULAR).deriveFont(size);
    }
    private static class Fonts {
        static final Font REGULAR = load("manrope-regular.ttf", Font.PLAIN);
        static final Font STRONG = load("manrope-semibold.ttf", Font.BOLD);
        private static Font load(String name, int fallback) {
            try (InputStream in = UiAssets.class.getResourceAsStream("/updater/" + name)) {
                if (in == null) throw new IOException("Missing font");
                return Font.createFont(Font.TRUETYPE_FONT, in);
            } catch (Exception e) { return new Font(Font.SANS_SERIF, fallback, 15); }
        }
    }
    record Clip(BufferedImage[] frames, int[] ends, int duration) {
        BufferedImage frame(long elapsed, boolean reduced) {
            if (reduced) return frames[0];
            int position = (int) (Math.max(0, elapsed) % duration);
            for (int i = 0; i < ends.length; i++) if (position < ends[i]) return frames[i];
            return frames[frames.length - 1];
        }
    }
    static Clip load(String name) throws IOException {
        try { return decode(name); }
        catch (IOException error) {
            return still(name.replace(".gif", ".png"));
        }
    }
    static Clip still(String name) throws IOException {
        try (InputStream in=UiAssets.class.getResourceAsStream("/updater/"+name)) {
            if(in==null) throw new IOException("Missing banner: "+name);
            BufferedImage image=ImageIO.read(in);
            if(image==null) throw new IOException("Invalid banner: "+name);
            return new Clip(new BufferedImage[]{image},new int[]{1000},1000);
        }
    }
    private static Clip decode(String name) throws IOException {
        try (InputStream resource = UiAssets.class.getResourceAsStream("/updater/" + name);
             ImageInputStream in = resource == null ? null : ImageIO.createImageInputStream(resource)) {
            if (in == null) throw new IOException("Missing banner: " + name);
            ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
            try {
                reader.setInput(in);
                int count = reader.getNumImages(true);
                if (count < 1 || count > 150) throw new IOException("Invalid GIF frame count");
                Node stream = reader.getStreamMetadata().getAsTree("javax_imageio_gif_stream_1.0");
                Node descriptor = find(stream, "LogicalScreenDescriptor");
                int width = number(descriptor, "logicalScreenWidth", reader.getWidth(0));
                int height = number(descriptor, "logicalScreenHeight", reader.getHeight(0));
                if ((long) width * height * count > 16_000_000L) throw new IOException("Banner memory limit");
                BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                BufferedImage[] frames = new BufferedImage[count]; int[] ends = new int[count]; int time = 0;
                String previousDisposal = "none"; Rectangle previousRect = new Rectangle(); BufferedImage restore = null;
                for (int i = 0; i < count; i++) {
                    Graphics2D g = canvas.createGraphics();
                    if (previousDisposal.equals("restoreToBackgroundColor")) {
                        g.setComposite(AlphaComposite.Clear); g.fill(previousRect); g.setComposite(AlphaComposite.SrcOver);
                    } else if (previousDisposal.equals("restoreToPrevious") && restore != null) {
                        g.setComposite(AlphaComposite.Src); g.drawImage(restore, 0, 0, null); g.setComposite(AlphaComposite.SrcOver);
                    }
                    IIOMetadata metadata = reader.getImageMetadata(i);
                    Node tree = metadata.getAsTree("javax_imageio_gif_image_1.0");
                    Node image = find(tree, "ImageDescriptor"), control = find(tree, "GraphicControlExtension");
                    String disposal = attribute(control, "disposalMethod", "none");
                    restore = disposal.equals("restoreToPrevious") ? copy(canvas) : null;
                    int x = number(image, "imageLeftPosition", 0), y = number(image, "imageTopPosition", 0);
                    BufferedImage frame = reader.read(i); g.drawImage(frame, x, y, null); g.dispose();
                    frames[i] = copy(canvas); time += Math.max(20, number(control, "delayTime", 10) * 10); ends[i] = time;
                    previousDisposal = disposal; previousRect = new Rectangle(x, y, frame.getWidth(), frame.getHeight());
                }
                return new Clip(frames, ends, time);
            } finally { reader.dispose(); }
        }
    }
    private static BufferedImage copy(BufferedImage source) {
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics(); g.drawImage(source, 0, 0, null); g.dispose(); return result;
    }
    private static Node find(Node parent, String name) {
        if (parent == null) return null;
        if (parent.getNodeName().equals(name)) return parent;
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            Node result = find(node, name); if (result != null) return result;
        }
        return null;
    }
    private static String attribute(Node node, String key, String fallback) {
        Node value = node == null ? null : node.getAttributes().getNamedItem(key); return value == null ? fallback : value.getNodeValue();
    }
    private static int number(Node node, String key, int fallback) { return Integer.parseInt(attribute(node, key, String.valueOf(fallback))); }
}
