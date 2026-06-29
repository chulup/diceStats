import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

public class Seg {
    static String PHOTOS = "/home/chulup/dice/photos/";
    static String OUT = "/tmp/claude-1000/-home-chulup-dice/c2dc9f7e-e6f5-4b07-87df-31296525a047/scratchpad/";

    // name, l,t,r,b (fractions), label
    static Object[][] JOBS = {
        {"101", 0.30,0.12,0.62,0.45, "101_5"},
        {"104", 0.22,0.26,0.72,0.56, "104_10"},
        {"105", 0.30,0.32,0.66,0.55, "105_70"},
        {"107", 0.37,0.42,0.60,0.66, "107_8"},   // front big face "8" (ground truth)
        {"107", 0.38,0.26,0.62,0.42, "107_20"},  // apex "20" (task prompt)
        {"109", 0.27,0.33,0.45,0.56, "109_5a"},  // upper-left ornate die
        {"109", 0.51,0.47,0.71,0.70, "109_3"},   // center ornate die
        {"109", 0.13,0.66,0.34,0.93, "109_1"},   // lower-left ornate die
    };

    public static void main(String[] a) throws Exception {
        for (Object[] j : JOBS) {
            String name=(String)j[0];
            BufferedImage img = ImageIO.read(new File(PHOTOS+name+".jpg"));
            int W=img.getWidth(), H=img.getHeight();
            int l=(int)((double)j[1]*W), t=(int)((double)j[2]*H);
            int r=(int)((double)j[3]*W), b=(int)((double)j[4]*H);
            String lab=(String)j[5];
            BufferedImage crop = img.getSubimage(l,t,r-l,b-t);
            // upscale 3x for clarity
            int cw=crop.getWidth(), ch=crop.getHeight();
            int S=3;
            BufferedImage big=new BufferedImage(cw*S,ch*S,BufferedImage.TYPE_INT_RGB);
            big.getGraphics().drawImage(crop,0,0,cw*S,ch*S,null);
            ImageIO.write(big, "png", new File(OUT+lab+"_a_crop.png"));

            // grayscale
            int gw=big.getWidth(), gh=big.getHeight();
            int[] gray=new int[gw*gh];
            int[] hist=new int[256];
            for(int y=0;y<gh;y++)for(int x=0;x<gw;x++){
                int rgb=big.getRGB(x,y);
                int rr=(rgb>>16)&255,gg=(rgb>>8)&255,bb=rgb&255;
                int v=(int)(0.299*rr+0.587*gg+0.114*bb);
                gray[y*gw+x]=v; hist[v]++;
            }
            BufferedImage gimg=new BufferedImage(gw,gh,BufferedImage.TYPE_INT_RGB);
            for(int y=0;y<gh;y++)for(int x=0;x<gw;x++){int v=gray[y*gw+x];gimg.setRGB(x,y,(v<<16)|(v<<8)|v);}
            ImageIO.write(gimg,"png",new File(OUT+lab+"_b_gray.png"));

            // Otsu threshold
            int total=gw*gh;
            double sum=0; for(int i=0;i<256;i++) sum+=i*hist[i];
            double sumB=0; int wB=0; double maxVar=-1; int thr=128;
            for(int i=0;i<256;i++){
                wB+=hist[i]; if(wB==0)continue; int wF=total-wB; if(wF==0)break;
                sumB+=i*hist[i];
                double mB=sumB/wB, mF=(sum-sumB)/wF;
                double var=(double)wB*wF*(mB-mF)*(mB-mF);
                if(var>maxVar){maxVar=var;thr=i;}
            }
            // determine glyph polarity by minority class within crop center region
            // produce both: binary normal (glyph dark) and inverted
            BufferedImage bin=new BufferedImage(gw,gh,BufferedImage.TYPE_INT_RGB);
            long below=0,above=0;
            for(int y=0;y<gh;y++)for(int x=0;x<gw;x++){
                int v=gray[y*gw+x];
                if(v<=thr) below++; else above++;
            }
            // glyph assumed minority class -> render glyph white on black
            boolean glyphIsDark = below<above; // fewer dark pixels => glyph is dark
            for(int y=0;y<gh;y++)for(int x=0;x<gw;x++){
                int v=gray[y*gw+x];
                boolean dark=v<=thr;
                boolean glyph = glyphIsDark ? dark : !dark;
                int c=glyph?0xFFFFFF:0x000000;
                bin.setRGB(x,y,c);
            }
            ImageIO.write(bin,"png",new File(OUT+lab+"_c_otsu.png"));
            System.out.printf("%s: crop %dx%d thr=%d glyphIsDark=%b below=%d above=%d%n",lab,cw,ch,thr,glyphIsDark,below,above);
        }
    }
}
