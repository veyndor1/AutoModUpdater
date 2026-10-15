import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import javax.swing.*;
import javax.imageio.ImageIO;

/** Test-only agent drives only updater-owned controls in isolated acceptance profiles. */
public final class UiAcceptanceDriver {
    public static void premain(String directory, Instrumentation instrumentation) {
        Thread driver=new Thread(() -> run(Path.of(directory)),"updater-acceptance-driver"); driver.setDaemon(true); driver.start();
    }
    private static void run(Path game) {
        try {
            String mode=Files.readString(game.resolve("automated-case.txt")).trim();
            long deadline=System.nanoTime()+120_000_000_000L;
            while(System.nanoTime()<deadline) {
                final boolean[] done={false};
                SwingUtilities.invokeAndWait(() -> {
                    for(Window w:Window.getWindows()) if(w instanceof JFrame frame && "updater-window".equals(w.getName()) && w.isShowing()) {
                        AbstractButton primary=find(frame,mode.equals("ui-offline") ? "retry" : "continue");
                        if(primary==null || !primary.isVisible() || !primary.isEnabled()) continue;
                        try {
                            BufferedImage image=new BufferedImage(frame.getContentPane().getWidth(),frame.getContentPane().getHeight(),BufferedImage.TYPE_INT_RGB);
                            Graphics2D g=image.createGraphics(); frame.getContentPane().printAll(g); g.dispose();
                            ImageIO.write(image,"png",game.resolve("updater-ready.png").toFile());
                            Files.writeString(game.resolve("startup-decision.txt"),mode);
                            AbstractButton button=find(frame,mode.equals("ui-exit") || mode.equals("ui-offline") ? "exit" : "continue");
                            button.doClick(); done[0]=true;
                        } catch(Exception e) { throw new RuntimeException(e); }
                    }
                });
                if(done[0]) return;
                Thread.sleep(100);
            }
            // An unchanged package intentionally has no decision controls.
            if(!mode.equals("ui-current")) throw new IllegalStateException("Updater decision timed out");
        } catch(Exception e) { try { Files.writeString(game.resolve("ui-driver-error.txt"),e.toString()); } catch(Exception ignored) {} }
    }
    private static AbstractButton find(Container container,String name) {
        for(Component c:container.getComponents()) { if(c instanceof AbstractButton b && name.equals(b.getName())) return b;
            if(c instanceof Container child) { AbstractButton found=find(child,name); if(found!=null) return found; } }
        return null;
    }
}
