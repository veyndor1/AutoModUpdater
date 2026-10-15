package com.skymc.autoupdater.bootstrap;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompletionSoundTest {
    @Test void chimeIsShortQuietAndHasNoAbruptEndpoints() {
        byte[] pcm=CompletionSound.samples();
        assertEquals((int)(CompletionSound.SAMPLE_RATE*0.95)*2,pcm.length);
        int peak=0,maxStep=0,previous=0;
        for(int i=0;i<pcm.length;i+=2) {
            int sample=(short)((pcm[i]&255)|(pcm[i+1]<<8));
            peak=Math.max(peak,Math.abs(sample)); maxStep=Math.max(maxStep,Math.abs(sample-previous)); previous=sample;
        }
        assertTrue(peak>300,"The cue must be audible");
        assertTrue(peak<Short.MAX_VALUE*0.13,"Keep a low peak level without clipping");
        assertTrue(maxStep<400,"Avoid clicks and abrupt waveform edges");
        for(int i=pcm.length-2000;i<pcm.length;i++) assertEquals(0,pcm[i],"End in silence");
    }
}
