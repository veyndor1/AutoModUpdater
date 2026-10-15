package com.skymc.autoupdater.bootstrap;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;

/** Native early-startup UI; transactions and shutdown remain on the loader thread. */
public final class ProgressWindow implements UpdateProgress {
    public enum Choice { CONTINUE, EXIT, RETRY }
    private final File game;
    private final Runnable completionCue;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t=new Thread(r,"updater-ui-assets"); t.setDaemon(true); return t;
    });
    private Future<?> preferenceSave;
    private volatile boolean available, closed;
    private CompletableFuture<Choice> decision;
    private JFrame frame;
    private JPanel root, lists, overlay, top, words, progressArea;
    private JLabel title, subtitle, summary, credit, modCount, configCount, percentage;
    private JTextArea message;
    private ActionButton primary, exit, gear, themeShortcut;
    private Toggle themeToggle, motionToggle;
    private final DefaultListModel<Entry> mods=new DefaultListModel<>(), configs=new DefaultListModel<>();
    private final Map<String,Entry> entries=new LinkedHashMap<>();
    private JList<Entry> modList, configList;
    private Banner banner;
    private Meter meter;
    private javax.swing.Timer clock;
    private javax.swing.Timer choiceTimer;
    private CompletionOverlay completionOverlay;
    private boolean completionAnnounced;
    private long completionStart,choiceStart;
    private Choice pendingChoice;
    private ActionButton acceptedButton;
    private static final long COMPLETION_NANOS=3_000_000_000L;
    private static final int ACTION_CONFIRM_MS=1000;
    private UiAssets.Clip day, night;
    private boolean nightTheme, reduced, failed, finished, settingsOpen;
    private double mix, from, target, progress, progressTarget, reveal, panelMix, panelFrom, panelTarget;
    private long themeStart, revealStart, panelStart, startTime, lastTick;
    private static final Color[] NIGHT=colors(0x141B2C,0x1D2940,0xF0F2FF,0xAAB9D2,0xB7ADFF,0x8BDFBD,0xFFB5BB);
    private static final Color[] LIGHT=colors(0xEDF2F5,0xFFFFFF,0x243049,0x53637D,0x5947B1,0x246B56,0xA12F45);
    private static Color[] colors(int... v) { return Arrays.stream(v).mapToObj(Color::new).toArray(Color[]::new); }
    public ProgressWindow() { this(null); }
    public ProgressWindow(File game) { this(game,CompletionSound::playSoftChime); }
    ProgressWindow(File game,Runnable completionCue) {
        this.game=game; this.completionCue=Objects.requireNonNull(completionCue);
        nightTheme=Config.nightTheme; reduced=Config.reducedMotion;
        mix=from=target=nightTheme ? 1 : 0; UiAssets.font(false,15);
    }
    public boolean available() { return available; }
    public void open() {
        if(GraphicsEnvironment.isHeadless()) return;
        try {
            onEdt(this::build); available=true;
            worker.submit(() -> {
                try {
                    UiAssets.Clip ds=UiAssets.still("day.png"), ns=UiAssets.still("night.png");
                    later(() -> { day=ds; night=ns; banner.repaint(); });
                    UiAssets.Clip d=UiAssets.load("day.gif"), n=UiAssets.load("night.gif");
                    later(() -> { day=d; night=n; banner.repaint(); });
                }
                catch(Exception e) { System.out.println("[AutoModUpdater] Banner okunamadı: "+e.getClass().getSimpleName()); }
            });
        } catch(Exception e) { System.out.println("[AutoModUpdater] Pencere açılamadı: "+e); close(); }
    }
    private void build() {
        frame=new JFrame("Veyndor · Minecraft Mod Güncelleyici"); frame.setName("updater-window");
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { choose(Choice.EXIT); } });
        frame.addWindowStateListener(e -> { if(clock!=null) { if((e.getNewState() & Frame.ICONIFIED)!=0) clock.stop(); else { lastTick=System.nanoTime(); clock.start(); } } });
        root=panel(new BorderLayout(0,18)); root.setOpaque(true); root.setBorder(new EmptyBorder(20,26,18,26));
        top=panel(new BorderLayout(0,18)); words=panel(new BorderLayout(0,7));
        JPanel heading=panel(new BorderLayout(12,0));
        title=label("Oyuna hazırlanıyor",true,27); subtitle=label("Modlar ve configler kontrol ediliyor.",false,14);
        words.add(title,BorderLayout.NORTH); words.add(subtitle,BorderLayout.SOUTH);
        JPanel brand=panel(new FlowLayout(FlowLayout.LEFT,0,0));
        brand.add(new Wordmark()); JLabel edition=label("   /   Minecraft 1.20.1",false,13); brand.add(edition);
        heading.add(brand,BorderLayout.CENTER);
        gear=new ActionButton("Ayarlar",false); gear.setName("settings"); gear.setPreferredSize(new Dimension(44,44));
        gear.setToolTipText("Ayarlar"); gear.addActionListener(e -> toggleSettings());
        themeShortcut=new ActionButton(nightTheme ? "Aydınlık" : "Gece",false); themeShortcut.setName("quick-theme");
        themeShortcut.addActionListener(e -> changeTheme(!nightTheme));
        JPanel gearHolder=panel(new FlowLayout(FlowLayout.RIGHT,8,0)); gearHolder.add(themeShortcut); gearHolder.add(gear); heading.add(gearHolder,BorderLayout.EAST);
        top.add(heading,BorderLayout.NORTH); banner=new Banner(); banner.setPreferredSize(new Dimension(850,250));
        banner.getAccessibleContext().setAccessibleName("Gündüz ve gece yaşayan iki boyutlu orman manzarası"); top.add(banner,BorderLayout.CENTER);
        words.setBorder(new EmptyBorder(0,24,22,24)); banner.add(words,BorderLayout.SOUTH); root.add(top,BorderLayout.NORTH);
        lists=panel(new GridLayout(1,2,18,0));
        lists.add(listPanel(true)); lists.add(listPanel(false)); root.add(lists,BorderLayout.CENTER);
        JPanel footer=panel(new BorderLayout(0,14)), progressHeader=panel(new BorderLayout());
        progressArea=new Surface(new BorderLayout(0,8)); progressArea.setBorder(new EmptyBorder(16,18,12,18));
        summary=label("Yayın bilgisi alınıyor…",true,13); progressHeader.add(summary,BorderLayout.WEST);
        percentage=label("",true,13); progressHeader.add(percentage,BorderLayout.EAST); progressArea.add(progressHeader,BorderLayout.NORTH);
        meter=new Meter(); meter.setPreferredSize(new Dimension(700,8)); progressArea.add(meter,BorderLayout.CENTER);
        message=new JTextArea("Dosyalar doğrulandıktan sonra oyun açılacak."); message.setFont(UiAssets.font(false,14));
        message.setEditable(false); message.setFocusable(false); message.setLineWrap(true); message.setWrapStyleWord(true); message.setOpaque(false);
        message.setRows(2); message.setBorder(new EmptyBorder(4,0,0,0)); progressArea.add(message,BorderLayout.SOUTH); footer.add(progressArea,BorderLayout.NORTH);
        JPanel bottom=panel(new BorderLayout()), actions=panel(new FlowLayout(FlowLayout.RIGHT,10,0));
        credit=label("Made by veyndor  ·  dc: veyndor",false,12); bottom.add(credit,BorderLayout.CENTER);
        exit=new ActionButton("Çıkış",false); primary=new ActionButton("Tamam",true); exit.setName("exit"); primary.setName("continue");
        exit.addActionListener(e -> choose(Choice.EXIT)); primary.addActionListener(e -> choose(failed ? Choice.RETRY : Choice.CONTINUE));
        exit.setVisible(false); primary.setVisible(false); actions.add(exit); actions.add(primary); actions.setPreferredSize(new Dimension(248,44));
        bottom.add(actions,BorderLayout.EAST); footer.add(bottom,BorderLayout.SOUTH); root.add(footer,BorderLayout.SOUTH);
        frame.setContentPane(root); buildSettings();
        completionOverlay=new CompletionOverlay(); completionOverlay.setVisible(false);
        frame.getLayeredPane().add(completionOverlay,JLayeredPane.PALETTE_LAYER);
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0),"close-settings");
        root.getActionMap().put("close-settings",new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { if(settingsOpen) toggleSettings(); } });
        root.addComponentListener(new ComponentAdapter() { @Override public void componentResized(ComponentEvent e) { responsive(); } });
        Rectangle b=GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        frame.setSize(Math.min(940,b.width-40),Math.min(820,b.height-40));
        frame.setMinimumSize(new Dimension(Math.min(680,b.width-40),Math.min(500,b.height-40))); frame.setLocationRelativeTo(null);
        startTime=lastTick=themeStart=System.nanoTime(); applyTheme(); frame.setVisible(true); responsive();
        clock=new javax.swing.Timer(16,e -> tick()); clock.setCoalesce(true); clock.start();
    }
    private JPanel listPanel(boolean mod) {
        JPanel p=new Surface(new BorderLayout(0,12)), header=panel(new BorderLayout());
        p.setBorder(new EmptyBorder(16,16,10,16));
        JLabel heading=label(mod ? "Modlar" : "Configler",true,17);
        heading.setIcon(new SectionIcon(mod)); heading.setIconTextGap(10); header.add(heading,BorderLayout.WEST);
        JLabel count=new CountLabel(); header.add(count,BorderLayout.EAST);
        JList<Entry> list=new JList<>(mod ? mods : configs) {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if(getModel().getSize()==0) {
                    Graphics2D p=graphics(g); p.setColor(color(3)); p.setFont(UiAssets.font(false,13));
                    p.drawString(fit(p,"Liste kontrol ediliyor…",getWidth()-16),8,28); p.dispose();
                }
            }
            @Override public String getToolTipText(MouseEvent e) { int i=locationToIndex(e.getPoint()); return i<0 || !getCellBounds(i,i).contains(e.getPoint()) ? null : getModel().getElementAt(i).name; }
        };
        list.setName(mod ? "mods" : "configs"); list.setFixedCellHeight(66); list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer((owner,item,index,selected,focus) -> new Row(item,selected,focus)); ToolTipManager.sharedInstance().registerComponent(list);
        list.getAccessibleContext().setAccessibleName(mod ? "Mod indirmeleri" : "Config indirmeleri");
        JScrollPane scroll=new JScrollPane(list); scroll.setBorder(null); scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        JScrollBar bar=scroll.getVerticalScrollBar(); bar.setUnitIncrement(24); bar.setPreferredSize(new Dimension(8,0));
        bar.setUI(new javax.swing.plaf.basic.BasicScrollBarUI() {
            @Override protected JButton createDecreaseButton(int orientation) { return emptyButton(); }
            @Override protected JButton createIncreaseButton(int orientation) { return emptyButton(); }
            @Override protected void paintTrack(Graphics g,JComponent c,Rectangle r) { g.setColor(color(1)); g.fillRect(r.x,r.y,r.width,r.height); }
            @Override protected void paintThumb(Graphics g,JComponent c,Rectangle r) { g.setColor(blend(color(1),color(3),0.45)); g.fillRoundRect(r.x+2,r.y,r.width-4,r.height,4,4); }
        });
        p.add(header,BorderLayout.NORTH); p.add(scroll,BorderLayout.CENTER);
        if(mod) { modList=list; modCount=count; } else { configList=list; configCount=count; } return p;
    }
    private JButton emptyButton() { JButton b=new JButton(); b.setPreferredSize(new Dimension(0,0)); return b; }
    private void responsive() {
        if(lists==null || overlay==null) return;
        GridLayout layout=(GridLayout)lists.getLayout(); boolean stacked=root.getWidth()<560; int rows=stacked ? 2 : 1;
        if(layout.getRows()!=rows) { layout.setRows(rows); layout.setColumns(stacked ? 1 : 2); layout.setVgap(stacked ? 16 : 0); lists.revalidate(); }
        banner.setVisible(frame.getHeight()>=620);
        boolean compact=frame.getHeight()<620;
        root.setBorder(compact ? new EmptyBorder(14,20,14,20) : new EmptyBorder(20,26,18,26));
        ((BorderLayout)root.getLayout()).setVgap(compact ? 12 : 18);
        ((BorderLayout)top.getLayout()).setVgap(compact ? 10 : 18);
        for(Component section:lists.getComponents()) ((JComponent)section).setBorder(compact ? new EmptyBorder(8,10,6,10) : new EmptyBorder(16,16,10,16));
        progressArea.setBorder(compact ? new EmptyBorder(10,12,6,12) : new EmptyBorder(16,18,12,18));
        JPanel host=compact ? top : banner;
        if(words.getParent()!=host) { words.getParent().remove(words); host.add(words,BorderLayout.SOUTH); }
        words.setBorder(compact ? new EmptyBorder(0,0,0,0) : new EmptyBorder(0,24,22,24));
        title.setFont(UiAssets.font(true,compact ? 22 : 32));
        title.setForeground(compact ? color(2) : Color.WHITE);
        subtitle.setForeground(compact ? color(3) : new Color(0xDBE6F7));
        banner.setPreferredSize(new Dimension(850,Math.max(150,Math.min(250,frame.getHeight()-520)))); root.revalidate();
        overlay.setBounds(0,0,frame.getLayeredPane().getWidth(),frame.getLayeredPane().getHeight());
        if(completionOverlay!=null) completionOverlay.setBounds(overlay.getBounds());
    }
    private void buildSettings() {
        overlay=new JPanel(null) {
            @Override protected void paintComponent(Graphics g) {
                if(panelMix<=0) return; Graphics2D p=graphics(g); p.setComposite(AlphaComposite.SrcOver.derive((float)panelMix));
                Rectangle b=popoverBounds();
                for(int i=10;i>0;i--) { p.setColor(new Color(0,0,0,3)); p.fillRoundRect(b.x-i,b.y-i+5,b.width+i*2,b.height+i*2,22,22); }
                p.setColor(color(1)); p.fillRoundRect(b.x,b.y,b.width,b.height,16,16);
                p.setColor(blend(color(1),color(3),0.3)); p.drawRoundRect(b.x,b.y,b.width-1,b.height-1,16,16); p.dispose();
            }
            @Override public boolean contains(int x,int y) { return settingsOpen; }
        };
        overlay.setOpaque(false); overlay.setVisible(false); overlay.add(label("Görünüm",true,15));
        overlay.setFocusCycleRoot(true);
        themeToggle=new Toggle("Aydınlık","Gece",nightTheme); themeToggle.setName("theme-toggle");
        themeToggle.addActionListener(e -> changeTheme(themeToggle.isSelected())); overlay.add(themeToggle);
        motionToggle=new Toggle("Hareketi azalt","",reduced); motionToggle.setName("motion-toggle");
        motionToggle.addActionListener(e -> { reduced=motionToggle.isSelected(); savePreferences(); }); overlay.add(motionToggle);
        overlay.addMouseListener(new MouseAdapter() { @Override public void mousePressed(MouseEvent e) { if(!popoverBounds().contains(e.getPoint()) && settingsOpen) toggleSettings(); } });
        frame.getLayeredPane().add(overlay,JLayeredPane.POPUP_LAYER);
    }
    private Rectangle popoverBounds() { return new Rectangle(Math.max(12,overlay.getWidth()-300),82+(int)((1-panelMix)*-8),262,145); }
    private void changeTheme(boolean night) {
        nightTheme=night; themeToggle.setSelected(night);
        themeShortcut.setText(night ? "Aydınlık" : "Gece");
        themeShortcut.setToolTipText(night ? "Aydınlık temaya geç" : "Gece temasına geç");
        from=mix; target=night ? 1 : 0; themeStart=System.nanoTime(); savePreferences();
    }
    private void toggleSettings() {
        settingsOpen=!settingsOpen; panelFrom=panelMix; panelTarget=settingsOpen ? 1 : 0; panelStart=System.nanoTime(); overlay.setVisible(true);
        gear.getAccessibleContext().setAccessibleDescription(settingsOpen ? "Görünüm ayarları açık. Escape ile kapat." : "Görünüm ayarlarını aç.");
        themeToggle.setEnabled(settingsOpen); motionToggle.setEnabled(settingsOpen);
        if(settingsOpen) themeToggle.requestFocusInWindow(); else gear.requestFocusInWindow();
    }
    private void savePreferences() {
        if(game==null) return; boolean n=nightTheme,r=reduced;
        preferenceSave=worker.submit(() -> { try { Config.savePresentation(game,n,r); } catch(Exception e) { later(() -> subtitle.setText("Tema değişti; tercih kaydedilemedi.")); } });
    }
    public void begin() { later(() -> {
        resetChoice(); completionAnnounced=false; completionStart=0; completionOverlay.setVisible(false);
        summary.getAccessibleContext().setAccessibleDescription(null);
        entries.clear(); mods.clear(); configs.clear(); failed=finished=false; progress=progressTarget=reveal=0;
        title.setText("Oyuna hazırlanıyor"); subtitle.setText("Modlar ve configler kontrol ediliyor."); message.setText("Dosyalar doğrulandıktan sonra oyun açılacak.");
        primary.setVisible(false); exit.setVisible(false); frame.getRootPane().setDefaultButton(null); updateCounts(); applyTheme();
    }); }
    @Override public void inventory(Kind kind,String id,String name) { later(() -> {
        String key=kind+":"+id; if(entries.containsKey(key)) return; Entry item=new Entry(name); entries.put(key,item);
        (kind==Kind.MOD ? mods : configs).addElement(item); updateCounts();
    }); }
    @Override public void item(Kind kind,String id,String name,Phase phase,long bytes,long total) { later(() -> {
        Entry item=entries.get(kind+":"+id); if(item==null) return; item.phase=phase; item.bytes=bytes; item.total=total;
        DefaultListModel<Entry> model=kind==Kind.MOD ? mods : configs; int index=model.indexOf(item); model.set(index,item);
        JList<Entry> list=kind==Kind.MOD ? modList : configList;
        if(phase==Phase.DOWNLOADING && list.getLastVisibleIndex()>=model.size()-2) list.ensureIndexIsVisible(index);
        updateCounts();
    }); }
    @Override public void status(String text) { later(() -> { if(!finished) message.setText(text); }); }
    private void updateCounts() {
        long m=done(mods),c=done(configs); modCount.setText(m+" / "+mods.size()); configCount.setText(c+" / "+configs.size()); int total=entries.size();
        double partial=entries.values().stream().filter(e -> e.phase==Phase.DOWNLOADING && e.total>0).mapToDouble(e -> Math.min(0.9,e.bytes/(double)e.total*0.9)).sum();
        if(!finished) progressTarget=total==0 ? 0 : Math.min(0.98,(m+c+partial)/(double)total*0.98);
        summary.setText(total==0 ? "Yayın bilgisi alınıyor…" : (m+c)+" / "+total+" dosya hazır");
        meter.getAccessibleContext().setAccessibleDescription(summary.getText());
    }
    private long done(DefaultListModel<Entry> model) { return Collections.list(model.elements()).stream().filter(e -> e.phase==Phase.CURRENT || e.phase==Phase.READY).count(); }
    public Choice awaitSuccess(UpdateReport report,boolean pending,boolean automatic) {
        return await(false,true,"Her şey hazır",report.mods()+" mod · "+report.configs()+" config güncellendi.",pending ?
                (automatic ? "Tamam'a bastığında Minecraft güncel paketle yeniden açılacak." : "Yeni modlar için yeniden açılış gerekiyor. Tamam oyunu kapatır; ardından Launcher'dan tekrar açın.") : "Tamam'a bastığında oyun açılışına devam edilecek.",report.changed());
    }
    public Choice awaitError(String error,boolean retry) { return await(true,retry,"Güncelleme tamamlanamadı","Dosyaların güvenli kopyaları korundu.",friendly(error),false); }
    private static String friendly(String error) {
        String v=error.toLowerCase(Locale.ROOT);
        if(v.contains("connect") || v.contains("unknownhost") || v.contains("timeout")) return "İnternet bağlantısı kurulamadı. Bağlantını kontrol edip tekrar dene.";
        if(v.contains("sha") || v.contains("hash") || v.contains("mismatch")) return "Bir dosya doğrulanamadı. Tekrar dene; doğrulanmış indirmeler korunur.";
        if(v.contains("space")) return "Kurulum için yeterli disk alanı yok. Alan açıp tekrar dene.";
        if(v.contains("http")) return "İndirme sunucusu dosyayı sağlayamadı. Biraz sonra tekrar dene.";
        return error.length()>260 ? error.substring(0,257)+"…" : error;
    }
    private Choice await(boolean error,boolean allowPrimary,String heading,String detail,String explanation,boolean announce) {
        if(!available) return error ? Choice.EXIT : Choice.CONTINUE;
        if(SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Never await a decision on EDT");
        CompletableFuture<Choice> next=new CompletableFuture<>(); later(() -> {
            resetChoice();
            decision=next; failed=error; finished=true; revealStart=System.nanoTime(); reveal=0;
            if(error) { completionStart=0; completionOverlay.setVisible(false); }
            if(error) entries.values().stream().filter(e -> e.phase==Phase.CHECKING || e.phase==Phase.DOWNLOADING || e.phase==Phase.VERIFYING).forEach(e -> e.phase=Phase.FAILED);
            else progressTarget=1;
            title.setText(heading); subtitle.setText(detail); message.setText(explanation); primary.setText(error ? "Tekrar dene" : "Tamam"); primary.setName(error ? "retry" : "continue");
            primary.setEnabled(allowPrimary); primary.setVisible(allowPrimary); exit.setText("Çıkış"); exit.setEnabled(true); exit.setVisible(true); applyTheme();
            if(announce && !completionAnnounced) announceCompletion();
            frame.getRootPane().setDefaultButton(allowPrimary ? primary : exit); if(allowPrimary) primary.requestFocusInWindow(); else exit.requestFocusInWindow();
        });
        try { return next.get(); } catch(InterruptedException e) { Thread.currentThread().interrupt(); return Choice.EXIT; } catch(ExecutionException e) { return Choice.EXIT; }
    }
    public Choice currentAndContinue() {
        if(!available) return Choice.CONTINUE;
        later(() -> { finished=true; progressTarget=1; title.setText("Her şey güncel"); subtitle.setText("Yeni dosya gerekmiyor."); message.setText("Oyun açılıyor…"); });
        try { Thread.sleep(reduced ? 250 : 650); } catch(InterruptedException e) { Thread.currentThread().interrupt(); return Choice.EXIT; } return Choice.CONTINUE;
    }
    private void announceCompletion() {
        completionAnnounced=true; completionStart=System.nanoTime();
        completionOverlay.setBounds(overlay.getBounds()); completionOverlay.setVisible(true); completionOverlay.repaint();
        summary.setText("İndirmeler tamamlandı · "+entries.size()+" dosya hazır");
        summary.getAccessibleContext().setAccessibleDescription("Tüm mod ve config işlemleri başarıyla tamamlandı.");
        try { completionCue.run(); } catch(RuntimeException ignored) { /* Visual feedback still works. */ }
    }
    private void resetChoice() {
        if(choiceTimer!=null) { choiceTimer.stop(); choiceTimer=null; }
        if(primary!=null) { primary.confirmed=false; primary.getAccessibleContext().setAccessibleDescription(null); }
        if(exit!=null) { exit.confirmed=false; exit.getAccessibleContext().setAccessibleDescription(null); }
        pendingChoice=null; acceptedButton=null; choiceStart=0;
    }
    private void choose(Choice value) {
        if(decision==null || decision.isDone() || pendingChoice!=null) return;
        pendingChoice=value; choiceStart=System.nanoTime(); acceptedButton=value==Choice.EXIT ? exit : primary;
        acceptedButton.confirmed=true;
        acceptedButton.getAccessibleContext().setAccessibleDescription("Seçimin alındı; işlem birazdan devam edecek.");
        primary.setEnabled(false); exit.setEnabled(false);
        acceptedButton.repaint();
        long waitNanos=ACTION_CONFIRM_MS*1_000_000L;
        if(value==Choice.CONTINUE && completionStart>0) waitNanos=Math.max(waitNanos,completionStart+COMPLETION_NANOS-choiceStart);
        CompletableFuture<Choice> expected=decision;
        choiceTimer=new javax.swing.Timer((int)Math.ceil(waitNanos/1_000_000.0),e -> {
            if(!closed && decision==expected && pendingChoice==value) expected.complete(value);
        });
        choiceTimer.setRepeats(false); choiceTimer.start();
    }
    public void close() {
        if(closed) return; closed=true; available=false;
        if(preferenceSave!=null && !SwingUtilities.isEventDispatchThread()) try { preferenceSave.get(5,TimeUnit.SECONDS); } catch(Exception ignored) {}
        try { onEdt(() -> { if(clock!=null) clock.stop(); resetChoice(); if(decision!=null) decision.complete(Choice.EXIT); if(frame!=null) frame.dispose(); day=night=null; }); } catch(Exception ignored) {}
        worker.shutdownNow();
    }
    private void tick() {
        if(closed) return; long now=System.nanoTime(); double dt=Math.min(0.1,(now-lastTick)/1e9); lastTick=now;
        double t=reduced ? 1 : Math.min(1,(now-themeStart)/460_000_000.0), next=from+(target-from)*ease(t);
        if(Math.abs(next-mix)>0.0001) { mix=next; applyTheme(); }
        progress+=(progressTarget-progress)*(reduced ? 1 : 1-Math.exp(-dt*12));
        if(Math.abs(progressTarget-progress)<0.003) progress=progressTarget;
        for(Entry entry:entries.values()) {
            double desired=entry.total>0 ? Math.min(1,entry.bytes/(double)entry.total) : 0;
            entry.displayProgress+=(desired-entry.displayProgress)*(reduced ? 1 : 1-Math.exp(-dt*10));
        }
        percentage.setText(entries.isEmpty() ? "" : failed ? "Duraklatıldı" : Math.round(progress*100)+"%");
        for(ActionButton b:List.of(primary,exit,gear,themeShortcut)) {
            b.hover+=( (b.getModel().isRollover() ? 1 : 0)-b.hover)*(reduced ? 1 : 1-Math.exp(-dt*25));
            b.press+=((b.getModel().isPressed() ? 1 : 0)-b.press)*(reduced ? 1 : 1-Math.exp(-dt*30));
        }
        if(completionOverlay.isVisible()) {
            if(now-completionStart>=COMPLETION_NANOS) completionOverlay.setVisible(false);
            else completionOverlay.repaint();
        }
        if(finished) reveal=reduced ? 1 : ease(Math.min(1,(now-revealStart)/240_000_000.0));
        double st=reduced ? 1 : Math.min(1,(now-panelStart)/(settingsOpen ? 220_000_000.0 : 160_000_000.0)); panelMix=panelFrom+(panelTarget-panelFrom)*ease(st);
        if(!settingsOpen && panelMix<0.001) overlay.setVisible(false);
        Rectangle b=popoverBounds(); overlay.getComponent(0).setBounds(b.x+18,b.y+15,b.width-36,23);
        themeToggle.setBounds(b.x+16,b.y+48,b.width-32,35); motionToggle.setBounds(b.x+16,b.y+92,b.width-32,35);
        if(frame.isShowing() && frame.getState()!=Frame.ICONIFIED) {
            banner.repaint(); meter.repaint(); primary.repaint(); exit.repaint(); gear.repaint(); themeShortcut.repaint();
            modList.repaint(); configList.repaint(); if(overlay.isVisible()) overlay.repaint();
        }
    }
    private static double ease(double t) { return t>=1 ? 1 : 1-Math.pow(2,-10*t); }
    private Color color(int i) { return blend(LIGHT[i],NIGHT[i],mix); }
    private void applyTheme() {
        root.setBackground(color(0));
        for(JList<Entry> list:List.of(modList,configList)) { list.setBackground(color(1)); list.setForeground(color(2)); ((JViewport)list.getParent()).setBackground(color(1)); list.getParent().getParent().setBackground(color(1)); }
        tintLabels(root); tintLabels(overlay); subtitle.setForeground(color(3)); summary.setForeground(color(3)); credit.setForeground(color(3)); modCount.setForeground(color(3)); configCount.setForeground(color(3)); message.setForeground(color(failed ? 6 : 3)); root.repaint();
        if(words.getParent()==banner) { title.setForeground(Color.WHITE); subtitle.setForeground(new Color(0xDBE6F7)); }
    }
    private void tintLabels(Container parent) { for(Component c:parent.getComponents()) { if(c instanceof JLabel l) l.setForeground(color(2)); if(c instanceof Container child) tintLabels(child); } }
    private JPanel panel(LayoutManager layout) { JPanel p=new JPanel(layout); p.setOpaque(false); return p; }
    private JLabel label(String text,boolean strong,float size) { JLabel l=new JLabel(text); l.setFont(UiAssets.font(strong,size)); return l; }
    private static Graphics2D graphics(Graphics g) { Graphics2D p=(Graphics2D)g.create(); p.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON); p.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON); return p; }
    private void later(Runnable r) { if(!closed) SwingUtilities.invokeLater(() -> { if(!closed) r.run(); }); }
    private static void onEdt(Runnable r) throws Exception { if(SwingUtilities.isEventDispatchThread()) r.run(); else SwingUtilities.invokeAndWait(r); }
    private static final class Entry {
        final String name; Phase phase=Phase.WAITING; long bytes,total=-1; double displayProgress;
        Entry(String name) { this.name=name; }
        @Override public String toString() { return name+", "+phase.label; }
    }
    private class Surface extends JPanel {
        Surface(LayoutManager layout) { super(layout); setOpaque(false); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D p=graphics(g); p.setColor(color(1)); p.fillRoundRect(0,0,getWidth(),getHeight(),20,20);
            p.setColor(blend(color(1),color(3),0.15)); p.drawRoundRect(0,0,getWidth()-1,getHeight()-1,20,20); p.dispose();
        }
    }
    /** Half-transparent black check backgrounds; no hit area over the two lists. */
    private class CompletionOverlay extends JPanel {
        CompletionOverlay() {
            setOpaque(false); setFocusable(false);
            getAccessibleContext().setAccessibleName("Tüm mod ve config indirmeleri tamamlandı");
        }
        @Override public boolean contains(int x,int y) { return false; }
        @Override protected void paintComponent(Graphics g) {
            double age=Math.max(0,(System.nanoTime()-completionStart)/1e9);
            if(completionStart==0 || age>=3) return;
            float alpha=reduced ? 1 : (float)Math.min(1,Math.min(age/0.18,(3-age)/0.35));
            double ring=reduced ? 1 : ease(Math.min(1,age/0.65));
            double check=reduced ? 1 : ease(Math.max(0,Math.min(1,(age-0.22)/0.62)));
            for(Component section:lists.getComponents()) {
                Rectangle b=SwingUtilities.convertRectangle(lists,section.getBounds(),this);
                double size=Math.max(32,Math.min(100,Math.min(b.width-24,b.height-16)));
                double x=b.getCenterX(),y=b.getCenterY()+3;
                Graphics2D p=graphics(g); p.setComposite(AlphaComposite.SrcOver.derive(Math.max(0,alpha)));
                p.setColor(new Color(0,0,0,128));
                p.fill(new Ellipse2D.Double(x-size/2,y-size/2,size,size));
                // A thin silhouette separates the mark from file text without a panel or scrim.
                p.setStroke(new BasicStroke(8,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
                p.setColor(blend(color(1),color(0),0.5));
                p.draw(new Arc2D.Double(x-size/2,y-size/2,size,size,90,-360*ring,Arc2D.OPEN));
                drawCheck(p,x,y,size,check);
                p.setColor(color(5)); p.setStroke(new BasicStroke(3,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
                p.draw(new Arc2D.Double(x-size/2,y-size/2,size,size,90,-360*ring,Arc2D.OPEN));
                p.setStroke(new BasicStroke(6,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
                drawCheck(p,x,y,size,check); p.dispose();
            }
        }
    }
    private static void drawCheck(Graphics2D p,double x,double y,double size,double amount) {
        double ax=x-size*0.25,ay=y+size*0.01,bx=x-size*0.06,by=y+size*0.20,cx=x+size*0.28,cy=y-size*0.20;
        double first=Math.hypot(bx-ax,by-ay),second=Math.hypot(cx-bx,cy-by),length=(first+second)*amount;
        if(length<=0) return;
        Path2D path=new Path2D.Double(); path.moveTo(ax,ay);
        if(length<first) path.lineTo(ax+(bx-ax)*length/first,ay+(by-ay)*length/first);
        else { path.lineTo(bx,by); double t=Math.min(1,(length-first)/second); path.lineTo(bx+(cx-bx)*t,by+(cy-by)*t); }
        p.draw(path);
    }
    private class CountLabel extends JLabel {
        CountLabel() { super("0 / 0"); setFont(UiAssets.font(true,12)); setBorder(new EmptyBorder(4,10,4,10)); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D p=graphics(g); p.setColor(blend(color(1),color(4),0.09)); p.fillRoundRect(0,0,getWidth(),getHeight(),18,18); p.dispose(); super.paintComponent(g);
        }
    }
    private class SectionIcon implements Icon {
        final boolean mod;
        SectionIcon(boolean mod) { this.mod=mod; }
        public int getIconWidth() { return 26; }
        public int getIconHeight() { return 26; }
        public void paintIcon(Component c,Graphics g,int x,int y) {
            Graphics2D p=graphics(g); p.translate(x,y); p.setColor(color(4)); p.setStroke(new BasicStroke(1.6f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
            if(mod) {
                Path2D box=new Path2D.Double(); box.moveTo(13,3); box.lineTo(23,8); box.lineTo(23,19); box.lineTo(13,24); box.lineTo(3,19); box.lineTo(3,8); box.closePath(); p.draw(box);
                p.drawLine(3,8,13,13); p.drawLine(23,8,13,13); p.drawLine(13,13,13,24); p.drawLine(8,5,18,10);
            } else {
                for(int i=0;i<3;i++) { int yy=6+i*7,xx=i==1 ? 17 : 9; p.drawLine(3,yy,23,yy); p.setColor(color(1)); p.fillRoundRect(xx-2,yy-3,5,6,2,2); p.setColor(color(4)); p.drawRoundRect(xx-2,yy-3,5,6,2,2); }
            }
            p.dispose();
        }
    }
    /** A typographic wordmark, not an imported or claimed official brand logo. */
    private class Wordmark extends JPanel {
        Wordmark() { setOpaque(false); setPreferredSize(new Dimension(143,42)); getAccessibleContext().setAccessibleName("Veyndor"); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D p=graphics(g); p.setColor(color(4));
            int[][] blocks={{0,9},{5,14},{10,19},{15,14},{20,9}};
            for(int[] block:blocks) p.fillRoundRect(block[0],block[1],7,7,2,2);
            p.setColor(color(2)); p.setFont(UiAssets.font(true,23)); p.drawString("veyndor",36,28); p.dispose();
        }
    }
    private class Banner extends JPanel {
        Banner() { super(new BorderLayout()); setOpaque(false); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D p=graphics(g); p.clip(new RoundRectangle2D.Double(0,0,getWidth(),getHeight(),20,20)); p.setColor(color(1)); p.fillRect(0,0,getWidth(),getHeight());
            long elapsed=(System.nanoTime()-startTime)/1_000_000; draw(p,day,elapsed,1); draw(p,night,elapsed,(float)mix);
            p.setComposite(AlphaComposite.SrcOver);
            p.setPaint(new GradientPaint(0,Math.max(0,getHeight()-180),new Color(10,18,35,0),0,getHeight(),new Color(10,18,35,246)));
            p.fillRect(0,0,getWidth(),getHeight());
            p.setColor(new Color(255,255,255,38)); p.drawRoundRect(0,0,getWidth()-1,getHeight()-1,20,20); p.dispose();
        }
        private void draw(Graphics2D p,UiAssets.Clip clip,long elapsed,float alpha) {
            if(clip==null || alpha<=0) return; BufferedImage image=clip.frame(elapsed,reduced); p.setComposite(AlphaComposite.SrcOver.derive(alpha));
            p.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            double scale=Math.max(getWidth()/(double)image.getWidth(),getHeight()/(double)image.getHeight()); int w=(int)Math.ceil(image.getWidth()*scale),h=(int)Math.ceil(image.getHeight()*scale);
            p.drawImage(image,(getWidth()-w)/2,(getHeight()-h)/2,w,h,null);
        }
    }
    private class Meter extends JPanel {
        Meter() { setOpaque(false); getAccessibleContext().setAccessibleName("Genel indirme ilerlemesi"); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D p=graphics(g); p.setColor(blend(color(0),color(3),0.16)); p.fillRoundRect(0,0,getWidth(),getHeight(),8,8);
            Color accent=color(failed ? 6 : finished ? 5 : 4);
            int width=(int)(getWidth()*progress);
            if(entries.isEmpty() && !finished) {
                int span=Math.max(40,getWidth()/6);
                double phase=reduced ? 0.5 : (1-Math.cos((System.nanoTime()-startTime)/1e9*1.6))/2;
                p.setColor(accent); p.fillRoundRect((int)((getWidth()-span)*phase),0,span,getHeight(),8,8);
            } else {
                p.setPaint(new GradientPaint(0,0,accent,width,0,blend(accent,color(5),0.45)));
                p.fillRoundRect(0,0,width,getHeight(),8,8);
            }
            p.dispose();
        }
    }
    private class Row extends JPanel {
        final Entry item; final boolean selected,focused;
        Row(Entry item,boolean selected,boolean focused) { this.item=item; this.selected=selected; this.focused=focused; setOpaque(false); getAccessibleContext().setAccessibleName(item.toString()); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D p=graphics(g); int w=getWidth();
            boolean ready=item.phase==Phase.CURRENT || item.phase==Phase.READY;
            boolean active=item.phase==Phase.DOWNLOADING || item.phase==Phase.VERIFYING || item.phase==Phase.CHECKING;
            Color accent=color(item.phase==Phase.FAILED ? 6 : ready ? 5 : 4);
            if(selected || item.phase==Phase.DOWNLOADING) { p.setColor(blend(color(1),color(4),selected ? 0.12 : 0.06)); p.fillRoundRect(0,1,w,62,10,10); }
            if(focused) { p.setColor(color(4)); p.drawRoundRect(1,2,w-3,60,10,10); }
            p.setColor(blend(color(1),accent,0.13)); p.fillRoundRect(6,14,32,32,10,10);
            p.setColor(accent); p.setStroke(new BasicStroke(1.7f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
            if(ready) { p.drawLine(15,30,20,35); p.drawLine(20,35,29,25); }
            else if(item.phase==Phase.FAILED) { p.drawLine(17,25,27,35); p.drawLine(27,25,17,35); }
            else if(active) {
                int angle=reduced ? 90 : (int)((System.nanoTime()-startTime)/4_000_000%360);
                p.drawArc(14,22,16,16,-angle,245);
            } else { p.drawRoundRect(16,23,12,14,2,2); p.drawLine(19,27,24,27); p.drawLine(19,31,24,31); }
            p.setFont(UiAssets.font(true,14)); p.setColor(color(2)); p.drawString(fit(p,item.name,Math.max(0,w-56)),48,25);
            p.setFont(UiAssets.font(false,13)); p.setColor(color(item.phase==Phase.FAILED ? 6 : 3)); String text=item.phase.label;
            if(item.phase==Phase.DOWNLOADING) text+=" · "+bytes(item.bytes)+(item.total>0 ? " / "+bytes(item.total) : "");
            p.drawString(fit(p,text,Math.max(0,w-56)),48,44);
            if(item.phase==Phase.DOWNLOADING && item.total>0) {
                p.setColor(blend(color(1),accent,0.15)); p.fillRoundRect(48,54,w-60,3,3,3);
                p.setColor(accent); p.fillRoundRect(48,54,(int)((w-60)*item.displayProgress),3,3,3);
            } p.dispose();
        }
    }
    private static String fit(Graphics2D g,String text,int width) { FontMetrics f=g.getFontMetrics(); if(f.stringWidth(text)<=width) return text; int end=text.length(); while(end>0 && f.stringWidth(text.substring(0,end)+"…")>width) end--; return text.substring(0,end)+"…"; }
    private static String bytes(long v) { return v<1024*1024 ? String.format(Locale.ROOT,"%.0f KB",v/1024.0) : String.format(Locale.ROOT,"%.1f MB",v/(1024.0*1024)); }
    private class ActionButton extends JButton {
        final boolean main; double hover,press; boolean confirmed;
        ActionButton(String text,boolean main) { super(text); this.main=main; setFont(UiAssets.font(true,15)); setPreferredSize(new Dimension(main ? 128 : 98,44)); setContentAreaFilled(false); setBorderPainted(false); setFocusPainted(false); setOpaque(false); setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D p=graphics(g); if((this==primary || this==exit) && finished) p.setComposite(AlphaComposite.SrcOver.derive((float)Math.max(0.05,reveal)));
            double elapsed=confirmed ? Math.max(0,(System.nanoTime()-choiceStart)/1e9) : 0;
            double inset=reduced ? 0 : press*1.5+(confirmed ? 2.5*(1-ease(Math.min(1,elapsed/0.28))) : 0);
            p.translate(inset,inset); p.scale((getWidth()-inset*2)/getWidth(),(getHeight()-inset*2)/getHeight());
            Color fill=blend(color(main ? 4 : 1),color(2),hover*0.08);
            fill=blend(fill,fill.darker(),getModel().isPressed() ? 1 : press);
            if(confirmed) fill=blend(fill,color(5),reduced ? 1 : ease(Math.min(1,elapsed/0.45)));
            else if(!isEnabled()) fill=blend(fill,color(0),0.45);
            p.setColor(fill); p.fillRoundRect(0,0,getWidth(),getHeight(),12,12);
            if(confirmed && !reduced) {
                double t=Math.min(1,(System.nanoTime()-choiceStart)/(ACTION_CONFIRM_MS*1_000_000.0));
                Graphics2D pulse=(Graphics2D)p.create(); pulse.clip(new RoundRectangle2D.Double(0,0,getWidth(),getHeight(),12,12));
                pulse.setColor(new Color(255,255,255,(int)(38*(1-t))));
                double radius=Math.hypot(getWidth(),getHeight())*ease(t);
                pulse.fill(new Ellipse2D.Double(getWidth()/2.0-radius,getHeight()/2.0-radius,radius*2,radius*2)); pulse.dispose();
            }
            if(isFocusOwner()) { p.setColor(main ? blend(LIGHT[1],NIGHT[0],mix) : color(4)); p.drawRoundRect(2,2,getWidth()-5,getHeight()-5,7,7); }
            p.setColor(confirmed || main ? blend(LIGHT[1],NIGHT[0],mix) : color(2)); p.setFont(getFont());
            if(confirmed) {
                double x=getWidth()/2.0,y=getHeight()/2.0;
                double ring=reduced ? 1 : ease(Math.min(1,elapsed/0.55));
                double check=reduced ? 1 : ease(Math.max(0,Math.min(1,(elapsed-0.20)/0.60)));
                p.setStroke(new BasicStroke(1.7f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
                p.draw(new Arc2D.Double(x-13,y-13,26,26,90,-360*ring,Arc2D.OPEN));
                p.setStroke(new BasicStroke(2.4f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
                drawCheck(p,x,y,26,check); p.dispose(); return;
            }
            if(this==gear) { p.setStroke(new BasicStroke(1.6f)); double x=getWidth()/2.0,y=getHeight()/2.0; p.draw(new Ellipse2D.Double(x-3.5,y-3.5,7,7));
                Path2D outline=new Path2D.Double();
                for(int i=0;i<32;i++) { double a=i*Math.PI/16, radius=i%4==1 || i%4==2 ? 10 : 7.5;
                    if(i==0) outline.moveTo(x+Math.cos(a)*radius,y+Math.sin(a)*radius); else outline.lineTo(x+Math.cos(a)*radius,y+Math.sin(a)*radius); }
                outline.closePath(); p.draw(outline);
            } else {
                FontMetrics f=p.getFontMetrics(); int width=f.stringWidth(getText());
                p.drawString(getText(),(getWidth()-width)/2,(getHeight()+f.getAscent()-f.getDescent())/2);
            } p.dispose();
        }
    }
    private class Toggle extends JToggleButton {
        final String left,right;
        Toggle(String left,String right,boolean value) { super(left+(right.isEmpty() ? "" : " / "+right),value); this.left=left; this.right=right; setFont(UiAssets.font(false,14)); setContentAreaFilled(false); setBorderPainted(false); setFocusPainted(false); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D p=graphics(g); p.setColor(color(2)); p.setFont(getFont()); p.drawString(left,2,23); if(!right.isEmpty()) p.drawString(right,getWidth()-p.getFontMetrics().stringWidth(right)-2,23);
            int x=right.isEmpty() ? getWidth()-42 : (getWidth()-42)/2; double v=this==themeToggle ? mix : isSelected() ? 1 : 0;
            p.setColor(color(isSelected() ? 4 : 3)); p.fillRoundRect(x,8,40,22,22,22); p.setColor(color(1)); p.fillOval(x+3+(int)(v*18),11,16,16);
            if(isFocusOwner()) { p.setColor(color(4)); p.drawRoundRect(0,1,getWidth()-1,getHeight()-3,7,7); } p.dispose();
        }
    }
    private static Color blend(Color a,Color b,double t) { return new Color((int)(a.getRed()+(b.getRed()-a.getRed())*t),(int)(a.getGreen()+(b.getGreen()-a.getGreen())*t),(int)(a.getBlue()+(b.getBlue()-a.getBlue())*t)); }
}
