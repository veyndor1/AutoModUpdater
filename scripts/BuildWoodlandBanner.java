import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.awt.image.IndexColorModel;
import java.util.*;
import javax.imageio.*;
import javax.imageio.metadata.*;
import javax.imageio.stream.*;

/** Packages the generated day/night atlas and renders a seamless ambient GIF loop.
 * Run: java scripts/BuildWoodlandBanner.java (JDK 17, no extra dependencies).
 * Artwork is generated; animation consists of deterministic semantic particles.
 */
public class BuildWoodlandBanner {
    static final int W=640,H=214,FRAMES=72;
    public static void main(String[] args) throws Exception {
        BufferedImage atlas=ImageIO.read(new File("design/assets/woodland-source.png"));
        for(int theme=0;theme<2;theme++) {
            String name=theme==0 ? "day" : "night";
            BufferedImage base=new BufferedImage(W,H,BufferedImage.TYPE_INT_RGB);
            Graphics2D g=base.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(atlas,0,0,W,H,0,theme*atlas.getHeight()/2,atlas.getWidth(),(theme+1)*atlas.getHeight()/2,null); g.dispose();
            ImageIO.write(base,"png",new File("src/main/resources/updater/"+name+".png"));
            IndexColorModel palette=palette(base);
            ImageWriter writer=ImageIO.getImageWritersByFormatName("gif").next();
            BufferedImage expectedFirst=null;
            try(ImageOutputStream out=ImageIO.createImageOutputStream(new File("src/main/resources/updater/"+name+".gif"))) {
                writer.setOutput(out); writer.prepareWriteSequence(null);
                for(int frame=0;frame<FRAMES;frame++) {
                    BufferedImage image=new BufferedImage(W,H,BufferedImage.TYPE_BYTE_INDEXED,palette);
                    g=image.createGraphics(); g.drawImage(base,0,0,null);
                    double t=frame*Math.PI*2/FRAMES;
                    // Periodic trajectories and opacity make the last-to-first transition continuous.
                    for(int i=0;i<20;i++) {
                        double phase=i*2.399963;
                        int x=(int)(35+(i*97%565)+Math.sin(t+phase)*7);
                        int y=(int)(100+(i*37%94)+Math.cos(t+phase*1.7)*5);
                        int alpha=(int)((theme==0 ? 60 : 125)*(0.5+0.5*Math.sin(t+phase)));
                        g.setColor(new Color(theme==0 ? 255 : 197,theme==0 ? 240 : 255,theme==0 ? 174 : 185,alpha));
                        g.fillRect(x,y,2,2);
                        if(theme==1) { g.setColor(new Color(195,255,176,alpha/6)); g.fillRect(x-2,y-2,6,6); }
                    }
                    for(int i=0;i<24;i++) {
                        int y=151+i*7%62;
                        int spread=(y-145)/2;
                        int x=300+(i*17%(Math.max(8,spread*2)))-spread;
                        int alpha=(int)(90*(0.5+0.5*Math.sin(t*2+i*1.8)));
                        g.setColor(new Color(210,236,255,alpha));
                        g.fillRect(x+(int)(Math.sin(t+i)*2),y,3+i%5,1);
                    }
                    g.dispose();
                    if(frame==0) expectedFirst=image;
                    // The generic TYPE_BYTE_INDEXED spec uses the JDK default palette.
                    // Carry the actual scene palette into GIF metadata explicitly.
                    IIOMetadata meta=writer.getDefaultImageMetadata(new ImageTypeSpecifier(image.getColorModel(),image.getSampleModel()),null);
                    String format=meta.getNativeMetadataFormatName();
                    IIOMetadataNode root=(IIOMetadataNode)meta.getAsTree(format);
                    IIOMetadataNode control=node(root,"GraphicControlExtension");
                    control.setAttribute("disposalMethod","none"); control.setAttribute("userInputFlag","FALSE");
                    control.setAttribute("transparentColorFlag","FALSE"); control.setAttribute("delayTime","8"); control.setAttribute("transparentColorIndex","0");
                    if(frame==0) {
                        IIOMetadataNode app=new IIOMetadataNode("ApplicationExtension");
                        app.setAttribute("applicationID","NETSCAPE"); app.setAttribute("authenticationCode","2.0"); app.setUserObject(new byte[]{1,0,0});
                        node(root,"ApplicationExtensions").appendChild(app);
                        IIOMetadataNode comment=new IIOMetadataNode("CommentExtension");
                        comment.setAttribute("value","Original Imagegen woodland atlas; prompt: design/assets/banner-prompt.txt; animation: scripts/BuildWoodlandBanner.java");
                        node(root,"CommentExtensions").appendChild(comment);
                    }
                    meta.setFromTree(format,root); writer.writeToSequence(new IIOImage(image,null,meta),null);
                }
                writer.endWriteSequence();
            } finally { writer.dispose(); }
            BufferedImage decoded=ImageIO.read(new File("src/main/resources/updater/"+name+".gif"));
            for(int y=0;y<H;y++) for(int x=0;x<W;x++)
                if(decoded.getRGB(x,y)!=expectedFirst.getRGB(x,y)) throw new IllegalStateException(name+": GIF palette changed during encoding");
            new File("build/design").mkdirs();
            ImageIO.write(decoded,"png",new File("build/design/"+name+"-gif-decoded.png"));
            System.out.println(name+": "+FRAMES+" frames, 5760 ms, "+W+"x"+H);
        }
    }
    // One scene-derived palette for every frame prevents palette shimmer and preserves
    // the artwork's subtle twilight colors instead of falling back to a web-safe cube.
    static IndexColorModel palette(BufferedImage base) {
        int[] histogram=new int[32768];
        for(int y=0;y<H;y++) for(int x=0;x<W;x++) {
            int rgb=base.getRGB(x,y); histogram[((rgb>>19)&31)*1024+((rgb>>11)&31)*32+((rgb>>3)&31)]++;
        }
        java.util.List<int[]> initial=new ArrayList<>();
        for(int i=0;i<histogram.length;i++) if(histogram[i]>0) initial.add(new int[]{(i>>10)*8+4,((i>>5)&31)*8+4,(i&31)*8+4,histogram[i]});
        java.util.List<java.util.List<int[]>> boxes=new ArrayList<>(); boxes.add(initial);
        while(boxes.size()<248) {
            int best=-1; double score=-1;
            for(int i=0;i<boxes.size();i++) {
                var box=boxes.get(i); if(box.size()<2) continue;
                int axis=axis(box),min=255,max=0,count=0;
                for(int[] c:box) { min=Math.min(min,c[axis]); max=Math.max(max,c[axis]); count+=c[3]; }
                double value=(max-min)*Math.sqrt(count);
                if(value>score) { score=value; best=i; }
            }
            if(best<0) break;
            var box=boxes.remove(best); int axis=axis(box); box.sort(Comparator.comparingInt(c->c[axis]));
            int count=box.stream().mapToInt(c->c[3]).sum(),sum=0,split=0;
            while(split<box.size()-1 && sum<count/2) sum+=box.get(split++)[3];
            split=Math.max(1,split);
            boxes.add(new ArrayList<>(box.subList(0,split))); boxes.add(new ArrayList<>(box.subList(split,box.size())));
        }
        byte[] r=new byte[256],g=new byte[256],b=new byte[256]; int n=0;
        for(var box:boxes) {
            long[] sum=new long[3]; int count=0;
            for(int[] c:box) { count+=c[3]; for(int a=0;a<3;a++) sum[a]+=(long)c[a]*c[3]; }
            r[n]=(byte)(sum[0]/count); g[n]=(byte)(sum[1]/count); b[n]=(byte)(sum[2]/count); n++;
        }
        while(n<256) { r[n]=(byte)210; g[n]=(byte)(220+(n%8)*5); b[n]=(byte)(175+(n%8)*11); n++; }
        return new IndexColorModel(8,256,r,g,b);
    }
    static int axis(java.util.List<int[]> box) {
        int best=0,range=-1;
        for(int a=0;a<3;a++) { int min=255,max=0; for(int[] c:box) { min=Math.min(min,c[a]); max=Math.max(max,c[a]); } if(max-min>range) { range=max-min; best=a; } }
        return best;
    }
    static IIOMetadataNode node(IIOMetadataNode root,String name) {
        for(int i=0;i<root.getLength();i++) if(root.item(i).getNodeName().equals(name)) return (IIOMetadataNode)root.item(i);
        IIOMetadataNode result=new IIOMetadataNode(name); root.appendChild(result); return result;
    }
}
