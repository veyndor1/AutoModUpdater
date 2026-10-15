package com.skymc.autoupdater.bootstrap;

import javax.sound.sampled.*;

/** A quiet, softly enveloped confirmation chime; never blocks Swing or startup. */
final class CompletionSound {
    static final int SAMPLE_RATE=44_100;
    private CompletionSound() {}

    static void playSoftChime() {
        Thread audio=new Thread(() -> {
            AudioFormat format=new AudioFormat(SAMPLE_RATE,16,1,true,false);
            try(SourceDataLine line=AudioSystem.getSourceDataLine(format)) {
                byte[] pcm=samples();
                line.open(format); line.start(); line.write(pcm,0,pcm.length); line.drain();
            } catch(LineUnavailableException | IllegalArgumentException | SecurityException ignored) {
                // The visual completion remains available when there is no audio device.
            }
        },"updater-completion-chime");
        audio.setDaemon(true); audio.start();
    }

    static byte[] samples() {
        int count=(int)(SAMPLE_RATE*0.95); byte[] pcm=new byte[count*2];
        double[] notes={440.0,554.365,659.255};
        for(int i=0;i<count;i++) {
            double time=i/(double)SAMPLE_RATE,value=0;
            for(int n=0;n<notes.length;n++) {
                double t=time-n*0.12;
                if(t<0 || t>0.62) continue;
                double attack=Math.min(1,t/0.035),release=Math.max(0,1-t/0.62);
                double envelope=attack*attack*(3-2*attack)*release*release;
                value+=0.045*envelope*(Math.sin(2*Math.PI*notes[n]*t)+0.12*Math.sin(4*Math.PI*notes[n]*t));
            }
            short sample=(short)Math.round(value*Short.MAX_VALUE);
            pcm[i*2]=(byte)sample; pcm[i*2+1]=(byte)(sample>>8);
        }
        return pcm;
    }
}
