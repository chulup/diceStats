import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

public class Seg2 {
    static String PHOTOS = "/home/chulup/dice/photos/";
    static String OUT = "/tmp/claude-1000/-home-chulup-dice/c2dc9f7e-e6f5-4b07-87df-31296525a047/scratchpad/";

    // 107 "8": gold-on-green. Test R-B channel (gold high R low B; green low R med B) and saturation.
    public static void main(String[] a) throws Exception {
        crop107();
    }

    static void crop107() throws Exception {
        BufferedImage img = ImageIO.read(new File(PHOTOS+"107.jpg"));
        int W=img.getWidth(),H=img.getHeight();
        int l=(int)(0.37*W),t=(int)(0.42*H),r=(int)(0.60*W),b=(int)(0.66*H);
        BufferedImage crop=img.getSubimage(l,t,r-l,b-t);
        int cw=crop.getWidth(),ch=crop.getHeight(),S=4;
        BufferedImage big=new BufferedImage(cw*S,ch*S,BufferedImage.TYPE_INT_RGB);
        big.getGraphics().drawImage(crop,0,0,cw*S,ch*S,null);
        int gw=big.getWidth(),gh=big.getHeight();
        int[] feat=new int[gw*gh]; int[] hist=new int[256];
        for(int y=0;y<gh;y++)for(int x=0;x<gw;x++){
            int rgb=big.getRGB(x,y); int rr=(rgb>>16)&255,gg=(rgb>>8)&255,bb=rgb&255;
            // gold has R-B large positive; green surface R-B near 0 or negative. clamp 0..255
            int v=Math.max(0,Math.min(255, (rr-bb)+128 ));
            feat[y*gw+x]=v; hist[v]++;
        }
        // otsu on feature
        int total=gw*gh; double sum=0; for(int i=0;i<256;i++)sum+=i*hist[i];
        double sumB=0;int wB=0;double mx=-1;int thr=128;
        for(int i=0;i<256;i++){wB+=hist[i];if(wB==0)continue;int wF=total-wB;if(wF==0)break;sumB+=i*hist[i];double mB=sumB/wB,mF=(sum-sumB)/wF;double var=(double)wB*wF*(mB-mF)*(mB-mF);if(var>mx){mx=var;thr=i;}}
        BufferedImage bin=new BufferedImage(gw,gh,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<gh;y++)for(int x=0;x<gw;x++){int v=feat[y*gw+x];int c=(v>thr)?0xFFFFFF:0;bin.setRGB(x,y,c);}
        ImageIO.write(bin,"png",new File(OUT+"107_8_d_redblue.png"));
        System.out.println("107_8 R-B otsu thr="+thr);
    }
}
