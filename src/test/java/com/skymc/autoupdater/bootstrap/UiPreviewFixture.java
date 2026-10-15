package com.skymc.autoupdater.bootstrap;

import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the real Swing components; excluded from the distribution. */
public final class UiPreviewFixture {
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args[0]); Files.createDirectories(output);
        byte[] pcm=CompletionSound.samples();
        try(var audio=new javax.sound.sampled.AudioInputStream(new java.io.ByteArrayInputStream(pcm),
                new javax.sound.sampled.AudioFormat(CompletionSound.SAMPLE_RATE,16,1,true,false),pcm.length/2)) {
            javax.sound.sampled.AudioSystem.write(audio,javax.sound.sampled.AudioFileFormat.Type.WAVE,output.resolve("completion-chime.wav").toFile());
        }
        AtomicInteger cues=new AtomicInteger();
        ProgressWindow window=new ProgressWindow(null,cues::incrementAndGet); window.open(); if(!window.available()) throw new IllegalStateException("UI unavailable");
        // Keep captures independent of desktop window management. These are real
        // Swing components; the production animation tick is driven by this fixture.
        SwingUtilities.invokeAndWait(() -> {
            try {
                frame(window).setVisible(false);
                Field timer=ProgressWindow.class.getDeclaredField("clock"); timer.setAccessible(true);
                ((Timer)timer.get(window)).stop();
            } catch(Exception e) { throw new RuntimeException(e); }
        });
        ExecutorService loader=Executors.newSingleThreadExecutor();
        try {
            window.begin();
            for(int i=0;i<94;i++) window.inventory(UpdateProgress.Kind.MOD,"mod-"+i,i==0 ? "JourneyMap · 1.20.1" : "Minecraft mod "+i);
            window.inventory(UpdateProgress.Kind.CONFIG,"config","config/örnek-ayarlar/settings.toml");
            window.item(UpdateProgress.Kind.MOD,"mod-0","",UpdateProgress.Phase.DOWNLOADING,2_800_000,8_000_000);
            window.item(UpdateProgress.Kind.CONFIG,"config","",UpdateProgress.Phase.CHECKING,0,-1);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
            while(!animatedAssetsLoaded(window) && System.nanoTime()<deadline) Thread.sleep(50);
            if(!animatedAssetsLoaded(window)) throw new AssertionError("Animated assets did not load");
            advance(window,650);
            JFrame frame=frame(window); capture(frame,output.resolve("night-downloading.png"));
            SwingUtilities.invokeAndWait(() -> button(frame,"settings").doClick()); advance(window,300);
            capture(frame,output.resolve("night-settings.png"));
            SwingUtilities.invokeAndWait(() -> button(frame,"theme-toggle").doClick()); advance(window,550);
            assertTheme(window,false);
            capture(frame,output.resolve("light-settings.png"));
            SwingUtilities.invokeAndWait(() -> button(frame,"motion-toggle").doClick()); advance(window,100);
            capture(frame,output.resolve("reduced-motion-a.png")); advance(window,220);
            capture(frame,output.resolve("reduced-motion-b.png"));
            if(!java.util.Arrays.equals(Files.readAllBytes(output.resolve("reduced-motion-a.png")),Files.readAllBytes(output.resolve("reduced-motion-b.png"))))
                throw new AssertionError("Reduced motion is not still");
            SwingUtilities.invokeAndWait(() -> button(frame,"motion-toggle").doClick());
            SwingUtilities.invokeAndWait(() -> { frame.getRootPane().getActionMap(); button(frame,"settings").doClick(); }); advance(window,250);
            capture(frame,output.resolve("light-downloading.png"));
            if(cues.get()!=0) throw new AssertionError("Completion cue fired before all operations finished");
            SwingUtilities.invokeAndWait(() -> button(frame,"quick-theme").doClick()); advance(window,100);
            SwingUtilities.invokeAndWait(() -> button(frame,"quick-theme").doClick()); advance(window,550);
            assertTheme(window,false);
            SwingUtilities.invokeAndWait(() -> {
                if(button(frame,"theme-toggle").isSelected()) throw new AssertionError("Theme controls are out of sync");
            });
            for(int i=0;i<94;i++) window.item(UpdateProgress.Kind.MOD,"mod-"+i,"",UpdateProgress.Phase.READY,0,-1);
            window.item(UpdateProgress.Kind.CONFIG,"config","",UpdateProgress.Phase.READY,0,-1);
            Future<ProgressWindow.Choice> success=loader.submit(() -> window.awaitSuccess(new UpdateReport(94,1,""),true,true));
            advance(window,750); capture(frame,output.resolve("light-completion.png"));
            JComponent completion=completion(window);
            if(!completion.isVisible() || completion.isOpaque() || completion.contains(1,1)) throw new AssertionError("Completion must be visible, transparent and click-through");
            if(cues.get()!=1) throw new AssertionError("Completion cue must fire exactly once");
            advance(window,2400);
            if(completion.isVisible()) throw new AssertionError("Completion overlay did not clear after three seconds");
            capture(frame,output.resolve("light-ready.png"));
            SwingUtilities.invokeAndWait(() -> button(frame,"settings").doClick()); advance(window,250);
            SwingUtilities.invokeAndWait(() -> button(frame,"theme-toggle").doClick()); advance(window,550);
            SwingUtilities.invokeAndWait(() -> button(frame,"settings").doClick()); advance(window,250);
            capture(frame,output.resolve("night-ready.png"));
            assertTheme(window,true);
            SwingUtilities.invokeAndWait(() -> { frame.setSize(680,500); }); advance(window,300); capture(frame,output.resolve("compact-ready.png"));
            SwingUtilities.invokeAndWait(() -> { button(frame,"continue").doClick(); button(frame,"continue").doClick(); });
            advance(window,150); capture(frame,output.resolve("continue-confirmed.png"));
            if(success.isDone()) throw new AssertionError("Continue skipped visible button confirmation");
            if(button(frame,"continue").isEnabled() || !"Tamam".equals(button(frame,"continue").getText())) throw new AssertionError("Animation must lock the action and preserve its accessible label");
            if(success.get(2,TimeUnit.SECONDS)!=ProgressWindow.Choice.CONTINUE) throw new AssertionError("Continue failed");
            window.begin();
            window.inventory(UpdateProgress.Kind.MOD,"failed","JourneyMap · 1.20.1");
            window.inventory(UpdateProgress.Kind.CONFIG,"pending","config/örnek-ayarlar/settings.toml");
            window.item(UpdateProgress.Kind.MOD,"failed","",UpdateProgress.Phase.DOWNLOADING,2_800_000,8_000_000);
            Future<ProgressWindow.Choice> error=loader.submit(() -> window.awaitError("ConnectException: connection refused",true));
            advance(window,350); capture(frame,output.resolve("compact-error.png"));
            if(completion(window).isVisible() || cues.get()!=1) throw new AssertionError("Errors must not announce success");
            SwingUtilities.invokeAndWait(() -> frame.setSize(940,820)); advance(window,300); capture(frame,output.resolve("night-error.png"));
            SwingUtilities.invokeAndWait(() -> button(frame,"retry").doClick());
            if(error.get(2,TimeUnit.SECONDS)!=ProgressWindow.Choice.RETRY) throw new AssertionError("Retry failed");
            Future<ProgressWindow.Choice> exit=loader.submit(() -> window.awaitSuccess(new UpdateReport(0,0,""),false,false));
            advance(window,250); SwingUtilities.invokeAndWait(() -> frame.dispatchEvent(new java.awt.event.WindowEvent(frame,java.awt.event.WindowEvent.WINDOW_CLOSING)));
            if(exit.get(2,TimeUnit.SECONDS)!=ProgressWindow.Choice.EXIT) throw new AssertionError("Window close failed");
            if(cues.get()!=1) throw new AssertionError("Unchanged releases must not repeat the download cue");
            window.begin();
            window.inventory(UpdateProgress.Kind.MOD,"fresh-mod","JourneyMap · 1.20.1");
            window.inventory(UpdateProgress.Kind.CONFIG,"fresh-config","config/settings.toml");
            window.item(UpdateProgress.Kind.MOD,"fresh-mod","",UpdateProgress.Phase.READY,1,1);
            window.item(UpdateProgress.Kind.CONFIG,"fresh-config","",UpdateProgress.Phase.READY,1,1);
            Future<ProgressWindow.Choice> early=loader.submit(() -> window.awaitSuccess(new UpdateReport(1,1,""),false,false));
            advance(window,100);
            SwingUtilities.invokeAndWait(() -> button(frame,"settings").doClick()); advance(window,250);
            SwingUtilities.invokeAndWait(() -> button(frame,"motion-toggle").doClick()); advance(window,32);
            SwingUtilities.invokeAndWait(() -> button(frame,"settings").doClick()); advance(window,32);
            capture(frame,output.resolve("reduced-completion-a.png")); advance(window,200);
            capture(frame,output.resolve("reduced-completion-b.png"));
            if(!java.util.Arrays.equals(Files.readAllBytes(output.resolve("reduced-completion-a.png")),Files.readAllBytes(output.resolve("reduced-completion-b.png"))))
                throw new AssertionError("Reduced-motion completion must remain still");
            SwingUtilities.invokeAndWait(() -> button(frame,"continue").doClick());
            advance(window,350); capture(frame,output.resolve("early-continue-confirmed.png"));
            if(early.isDone()) throw new AssertionError("Early Continue skipped the three-second notification");
            advance(window,2300);
            if(early.get(2,TimeUnit.SECONDS)!=ProgressWindow.Choice.CONTINUE) throw new AssertionError("Early Continue failed");
            if(completion(window).isVisible()) throw new AssertionError("Reduced-motion completion did not disappear");
            if(cues.get()!=2) throw new AssertionError("A new successful pass must notify once again");
            System.out.println("Offscreen Swing preview: themes, 3-second transparent completion, once-only cue, delayed action feedback, continue/retry/close and double-click checks passed");
        } finally { window.close(); loader.shutdownNow(); }
    }
    private static void advance(ProgressWindow window,int milliseconds) throws Exception {
        var tick=ProgressWindow.class.getDeclaredMethod("tick"); tick.setAccessible(true);
        long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(milliseconds);
        do {
            Thread.sleep(16);
            SwingUtilities.invokeAndWait(() -> { try { tick.invoke(window); } catch(Exception e) { throw new RuntimeException(e); } });
        } while(System.nanoTime()<until);
    }
    private static void layout(Container parent) {
        parent.doLayout();
        for(Component child:parent.getComponents()) if(child instanceof Container container) layout(container);
    }
    private static void assertTheme(ProgressWindow window,boolean night) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                Field mix=ProgressWindow.class.getDeclaredField("mix"); mix.setAccessible(true);
                if(Math.abs(mix.getDouble(window)-(night ? 1 : 0))>0.002) throw new AssertionError("Theme transition did not finish");
            } catch(ReflectiveOperationException e) { throw new RuntimeException(e); }
        });
    }
    private static boolean animatedAssetsLoaded(ProgressWindow window) throws Exception {
        boolean[] result={false};
        SwingUtilities.invokeAndWait(() -> {
            try {
                Field d=ProgressWindow.class.getDeclaredField("day"),n=ProgressWindow.class.getDeclaredField("night"); d.setAccessible(true); n.setAccessible(true);
                result[0]=d.get(window) instanceof UiAssets.Clip day && day.frames().length>1 && n.get(window) instanceof UiAssets.Clip night && night.frames().length>1;
            } catch(Exception error) { throw new RuntimeException(error); }
        });
        return result[0];
    }
    private static JFrame frame(ProgressWindow window) throws Exception { Field f=ProgressWindow.class.getDeclaredField("frame"); f.setAccessible(true); return (JFrame)f.get(window); }
    private static JComponent completion(ProgressWindow window) throws Exception { Field f=ProgressWindow.class.getDeclaredField("completionOverlay"); f.setAccessible(true); return (JComponent)f.get(window); }
    private static AbstractButton button(Container parent,String name) {
        for(Component c:parent.getComponents()) { if(c instanceof AbstractButton b && name.equals(b.getName())) return b; if(c instanceof Container child) { AbstractButton found=buttonOrNull(child,name); if(found!=null) return found; } }
        throw new IllegalStateException("Missing control "+name);
    }
    private static AbstractButton buttonOrNull(Container parent,String name) { try { return button(parent,name); } catch(IllegalStateException e) { return null; } }
    private static void capture(JFrame frame,Path path) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            layout(frame);
            Container content=frame.getContentPane(); double scale=frame.getGraphicsConfiguration().getDefaultTransform().getScaleX();
            BufferedImage image=new BufferedImage((int)(content.getWidth()*scale),(int)(content.getHeight()*scale),BufferedImage.TYPE_INT_RGB);
            Graphics2D g=image.createGraphics(); g.scale(scale,scale); content.printAll(g);
            // The settings popover is in the layered pane, above content.
            for(int level:new int[]{JLayeredPane.PALETTE_LAYER,JLayeredPane.POPUP_LAYER})
                for(Component layer:frame.getLayeredPane().getComponentsInLayer(level)) if(layer.isVisible()) layer.printAll(g);
            g.dispose(); try { ImageIO.write(image,"png",path.toFile()); } catch(Exception e) { throw new RuntimeException(e); }
        });
    }
}
