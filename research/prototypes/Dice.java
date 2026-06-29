import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.List;

/**
 * Compare classical-CV localizers for dice. Localization only -> bounding boxes.
 * Approaches:
 *   D1  current  : adaptive-saturation + near-square shape filter (baseline reimpl)
 *   D3  relaxed  : same saturation segmentation, loosened aspect/fill
 *   D2  edge     : Sobel edge map -> close -> fill holes -> components -> bbox
 *   D4  otsu     : Otsu on gray, minority class = foreground -> components
 *   D5  union    : D3 (saturation) UNION D2 (edge), NMS-merged   [our 4th]
 */
public class Dice {

    static final String DIR = "/home/chulup/dice/photos/";
    static final int MAX_EDGE = 512;

    // ---------- box ----------
    static class Box {
        double l, t, r, b;
        Box(double l, double t, double r, double b){this.l=l;this.t=t;this.r=r;this.b=b;}
        double area(){return Math.max(0,r-l)*Math.max(0,b-t);}
    }
    static double iou(Box a, Box b){
        double il=Math.max(a.l,b.l), it=Math.max(a.t,b.t), ir=Math.min(a.r,b.r), ib=Math.min(a.b,b.b);
        double iw=Math.max(0,ir-il), ih=Math.max(0,ib-it), inter=iw*ih;
        double uni=a.area()+b.area()-inter;
        return uni<=0?0:inter/uni;
    }

    // ---------- image ----------
    static class Img { int[] px; int w,h; Img(int[]p,int w,int h){px=p;this.w=w;this.h=h;} }

    static Img load(String path) throws Exception {
        BufferedImage bi = ImageIO.read(new File(path));
        int w=bi.getWidth(), h=bi.getHeight();
        int[] px=bi.getRGB(0,0,w,h,null,0,w);
        return new Img(px,w,h);
    }

    static Img downscale(Img in){
        int longest=Math.max(in.w,in.h);
        if(longest<=MAX_EDGE) return in;
        double s=(double)MAX_EDGE/longest;
        int dw=Math.max(1,(int)(in.w*s)), dh=Math.max(1,(int)(in.h*s));
        int[] out=new int[dw*dh];
        for(int dy=0;dy<dh;dy++){
            int sy0=dy*in.h/dh, sy1=Math.max(sy0+1,(dy+1)*in.h/dh);
            for(int dx=0;dx<dw;dx++){
                int sx0=dx*in.w/dw, sx1=Math.max(sx0+1,(dx+1)*in.w/dw);
                long r=0,g=0,b=0; int c=0;
                for(int sy=sy0;sy<sy1;sy++){int rb=sy*in.w; for(int sx=sx0;sx<sx1;sx++){int p=in.px[rb+sx]; r+=(p>>16)&0xFF; g+=(p>>8)&0xFF; b+=p&0xFF; c++;}}
                out[dy*dw+dx]=0xFF000000|(((int)(r/c))<<16)|(((int)(g/c))<<8)|((int)(b/c));
            }
        }
        return new Img(out,dw,dh);
    }

    static int sat(int p){int r=(p>>16)&0xFF,g=(p>>8)&0xFF,b=p&0xFF;int mx=Math.max(r,Math.max(g,b)),mn=Math.min(r,Math.min(g,b));return mx==0?0:(mx-mn)*255/mx;}
    static int val(int p){int r=(p>>16)&0xFF,g=(p>>8)&0xFF,b=p&0xFF;return Math.max(r,Math.max(g,b));}
    static int gray(int p){int r=(p>>16)&0xFF,g=(p>>8)&0xFF,b=p&0xFF;return (r*299+g*587+b*114)/1000;}

    // ---------- morphology (square SE) ----------
    static void erode(byte[] m,int w,int h,int rad){
        if(rad<=0)return; byte[] s=m.clone();
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){boolean keep=true;
            outer: for(int dy=-rad;dy<=rad;dy++){int ny=y+dy; if(ny<0||ny>=h){keep=false;break;} for(int dx=-rad;dx<=rad;dx++){int nx=x+dx; if(nx<0||nx>=w||s[ny*w+nx]==0){keep=false;break outer;}}}
            m[y*w+x]=(byte)(keep?1:0);}
    }
    static void dilate(byte[] m,int w,int h,int rad){
        if(rad<=0)return; byte[] s=m.clone();
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){boolean hit=false;
            outer: for(int dy=-rad;dy<=rad;dy++){int ny=y+dy; if(ny<0||ny>=h)continue; for(int dx=-rad;dx<=rad;dx++){int nx=x+dx; if(nx>=0&&nx<w&&s[ny*w+nx]==1){hit=true;break outer;}}}
            m[y*w+x]=(byte)(hit?1:0);}
    }
    static void open(byte[] m,int w,int h,int r){erode(m,w,h,r);dilate(m,w,h,r);}
    static void close(byte[] m,int w,int h,int r){dilate(m,w,h,r);erode(m,w,h,r);}

    // ---------- fill holes (flood background from border, anything not reached = hole) ----------
    static void fillHoles(byte[] m,int w,int h){
        boolean[] bg=new boolean[w*h];
        ArrayDeque<Integer> q=new ArrayDeque<>();
        for(int x=0;x<w;x++){push(q,bg,m,x,w,h);push(q,bg,m,(h-1)*w+x,w,h);}
        for(int y=0;y<h;y++){push(q,bg,m,y*w,w,h);push(q,bg,m,y*w+w-1,w,h);}
        int[] dx={1,-1,0,0},dy={0,0,1,-1};
        while(!q.isEmpty()){int p=q.poll();int x=p%w,y=p/w;for(int d=0;d<4;d++){int nx=x+dx[d],ny=y+dy[d];if(nx>=0&&nx<w&&ny>=0&&ny<h){int np=ny*w+nx; if(!bg[np]&&m[np]==0){bg[np]=true;q.add(np);}}}}
        for(int i=0;i<m.length;i++) if(m[i]==0&&!bg[i]) m[i]=1;
    }
    static void push(ArrayDeque<Integer> q, boolean[] bg, byte[] m, int p, int w, int h){ if(m[p]==0&&!bg[p]){bg[p]=true;q.add(p);} }

    // ---------- connected components ----------
    static class Region{int l,t,r,b,area;}
    static List<Region> components(byte[] m,int w,int h,int minArea){
        boolean[] vis=new boolean[w*h]; int[] st=new int[w*h];
        List<Region> out=new ArrayList<>();
        for(int s=0;s<m.length;s++){
            if(vis[s]||m[s]==0){vis[s]=true;continue;}
            int sp=0; st[sp++]=s; vis[s]=true; int sz=0,minX=w,minY=h,maxX=-1,maxY=-1;
            while(sp>0){int p=st[--sp];sz++;int x=p%w,y=p/w; if(x<minX)minX=x;if(x>maxX)maxX=x;if(y<minY)minY=y;if(y>maxY)maxY=y;
                for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){if(dx==0&&dy==0)continue;int nx=x+dx,ny=y+dy;if(nx>=0&&nx<w&&ny>=0&&ny<h){int np=ny*w+nx;if(!vis[np]&&m[np]==1){vis[np]=true;st[sp++]=np;}}}}
            if(sz>=minArea){Region rg=new Region();rg.l=minX;rg.t=minY;rg.r=maxX+1;rg.b=maxY+1;rg.area=sz;out.add(rg);}
        }
        return out;
    }

    // ---------- distance-transform split (port of pipeline) ----------
    static int[] distanceTransform(byte[] m,int w,int h){
        int inf=w*h*4; int[] d=new int[m.length];
        for(int i=0;i<m.length;i++) d[i]=m[i]==1?inf:0;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){int i=y*w+x;if(d[i]==0)continue;int best=d[i];
            if(x>0)best=Math.min(best,d[i-1]+3); if(y>0)best=Math.min(best,d[i-w]+3);
            if(x>0&&y>0)best=Math.min(best,d[i-w-1]+4); if(x<w-1&&y>0)best=Math.min(best,d[i-w+1]+4); d[i]=best;}
        for(int y=h-1;y>=0;y--)for(int x=w-1;x>=0;x--){int i=y*w+x;if(d[i]==0)continue;int best=d[i];
            if(x<w-1)best=Math.min(best,d[i+1]+3); if(y<h-1)best=Math.min(best,d[i+w]+3);
            if(x<w-1&&y<h-1)best=Math.min(best,d[i+w+1]+4); if(x>0&&y<h-1)best=Math.min(best,d[i+w-1]+4); d[i]=best;}
        return d;
    }
    // split a region's local mask, return list of sub-boxes (in full coords)
    static List<Region> splitTouching(Region rg, byte[] full, int w, int h, double seedFraction){
        int rw=rg.r-rg.l, rh=rg.b-rg.t;
        byte[] local=new byte[rw*rh];
        for(int y=0;y<rh;y++)for(int x=0;x<rw;x++) local[y*rw+x]=full[(rg.t+y)*w+(rg.l+x)];
        int[] dist=distanceTransform(local,rw,rh); int maxD=0; for(int d:dist) if(d>maxD)maxD=d;
        if(maxD==0){return Collections.singletonList(rg);}
        double th=seedFraction*maxD; byte[] seed=new byte[local.length];
        for(int i=0;i<local.length;i++) if(dist[i]>=th) seed[i]=1;
        int[] lbl=new int[local.length]; Arrays.fill(lbl,-1);
        int seeds=labelSeeds(seed,rw,rh,lbl);
        if(seeds<=1) return Collections.singletonList(rg);
        int[] labels=lbl.clone(); ArrayDeque<Integer> q=new ArrayDeque<>();
        for(int i=0;i<local.length;i++) if(labels[i]>=0) q.add(i);
        int[] dx={1,-1,0,0},dy={0,0,1,-1};
        while(!q.isEmpty()){int p=q.poll();int x=p%rw,y=p/rw,L=labels[p];for(int d=0;d<4;d++){int nx=x+dx[d],ny=y+dy[d];if(nx>=0&&nx<rw&&ny>=0&&ny<rh){int np=ny*rw+nx;if(local[np]==1&&labels[np]<0){labels[np]=L;q.add(np);}}}}
        int[] minX=new int[seeds],minY=new int[seeds],maxX=new int[seeds],maxY=new int[seeds],ar=new int[seeds];
        Arrays.fill(minX,rw);Arrays.fill(minY,rh);Arrays.fill(maxX,-1);Arrays.fill(maxY,-1);
        for(int i=0;i<local.length;i++){int L=labels[i];if(L<0)continue;int x=i%rw,y=i/rw;if(x<minX[L])minX[L]=x;if(x>maxX[L])maxX[L]=x;if(y<minY[L])minY[L]=y;if(y>maxY[L])maxY[L]=y;ar[L]++;}
        List<Region> out=new ArrayList<>();
        for(int L=0;L<seeds;L++){if(ar[L]==0)continue;Region s=new Region();s.l=rg.l+minX[L];s.t=rg.t+minY[L];s.r=rg.l+maxX[L]+1;s.b=rg.t+maxY[L]+1;s.area=ar[L];out.add(s);}
        return out;
    }
    static int labelSeeds(byte[] seed,int w,int h,int[] out){
        int[] st=new int[seed.length]; int label=0;
        for(int s=0;s<seed.length;s++){ if(seed[s]==0||out[s]>=0)continue; int sp=0;st[sp++]=s;out[s]=label;
            while(sp>0){int p=st[--sp];int x=p%w,y=p/w;for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){if(dx==0&&dy==0)continue;int nx=x+dx,ny=y+dy;if(nx>=0&&nx<w&&ny>=0&&ny<h){int np=ny*w+nx;if(seed[np]==1&&out[np]<0){out[np]=label;st[sp++]=np;}}}}
            label++;}
        return label;
    }

    static int medianSat(int[] px){int[] s=new int[px.length];for(int i=0;i<px.length;i++)s[i]=sat(px[i]);Arrays.sort(s);return s[s.length/2];}

    // ============ Approaches: each returns normalized boxes ============

    // D1 baseline current
    static List<Box> d1(Img im){ return satApproach(im,0.7,1.4,0.6); }
    // D3 relaxed
    static List<Box> d3(Img im){ return satApproach(im,0.45,2.2,0.35); }

    static List<Box> satApproach(Img im,double minAspect,double maxAspect,double minFill){
        Img s=downscale(im); int w=s.w,h=s.h;
        int median=medianSat(s.px); int minSat=Math.max(100,median+25);
        byte[] m=new byte[w*h];
        for(int i=0;i<w*h;i++) if(sat(s.px[i])>=minSat && val(s.px[i])>=90) m[i]=1;
        open(m,w,h,1); close(m,w,h,3);
        int tot=w*h; int minArea=Math.max(1,(int)(0.0008*tot)); int maxArea=(int)(0.12*tot);
        List<Box> out=new ArrayList<>();
        for(Region rg:components(m,w,h,minArea)){
            for(Region bl:splitTouching(rg,m,w,h,0.55)){
                int bw=bl.r-bl.l,bh=bl.b-bl.t;
                if(bl.area<minArea||bl.area>maxArea)continue;
                double asp=(double)bw/bh; if(asp<minAspect||asp>maxAspect)continue;
                double fill=(double)bl.area/((double)bw*bh); if(fill<minFill)continue;
                out.add(new Box((double)bl.l/w,(double)bl.t/h,(double)bl.r/w,(double)bl.b/h));
            }
        }
        return out;
    }

    // D2 edge/contour
    static List<Box> d2(Img im){
        Img s=downscale(im); int w=s.w,h=s.h;
        int[] g=new int[w*h]; for(int i=0;i<w*h;i++) g[i]=gray(s.px[i]);
        int[] mag=new int[w*h]; long sum=0; double sum2=0;
        for(int y=1;y<h-1;y++)for(int x=1;x<w-1;x++){
            int gx=-g[(y-1)*w+x-1]-2*g[y*w+x-1]-g[(y+1)*w+x-1]+g[(y-1)*w+x+1]+2*g[y*w+x+1]+g[(y+1)*w+x+1];
            int gy=-g[(y-1)*w+x-1]-2*g[(y-1)*w+x]-g[(y-1)*w+x+1]+g[(y+1)*w+x-1]+2*g[(y+1)*w+x]+g[(y+1)*w+x+1];
            int mm=Math.abs(gx)+Math.abs(gy); mag[y*w+x]=mm; sum+=mm; sum2+=(double)mm*mm;
        }
        double mean=(double)sum/(w*h); double var=sum2/(w*h)-mean*mean; double sd=Math.sqrt(Math.max(0,var));
        int th=(int)(mean+1.0*sd);
        byte[] e=new byte[w*h]; for(int i=0;i<w*h;i++) if(mag[i]>=th) e[i]=1;
        close(e,w,h,3); fillHoles(e,w,h); open(e,w,h,2);
        int tot=w*h; int minArea=Math.max(1,(int)(0.0015*tot)); int maxArea=(int)(0.45*tot);
        List<Box> out=new ArrayList<>();
        for(Region rg:components(e,w,h,minArea)){
            for(Region bl:splitTouching(rg,e,w,h,0.6)){
                int bw=bl.r-bl.l,bh=bl.b-bl.t; if(bl.area<minArea||bl.area>maxArea)continue;
                double asp=(double)bw/bh; if(asp<0.4||asp>2.5)continue;
                double fill=(double)bl.area/((double)bw*bh); if(fill<0.45)continue;
                // reject border-hugging giant blobs (table)
                out.add(new Box((double)bl.l/w,(double)bl.t/h,(double)bl.r/w,(double)bl.b/h));
            }
        }
        return out;
    }

    // D4 Otsu (minority class = foreground)
    static List<Box> d4(Img im){
        Img s=downscale(im); int w=s.w,h=s.h;
        int[] g=new int[w*h]; int[] hist=new int[256];
        for(int i=0;i<w*h;i++){g[i]=gray(s.px[i]);hist[g[i]]++;}
        int total=w*h; long sumAll=0; for(int t=0;t<256;t++) sumAll+=(long)t*hist[t];
        long sumB=0; int wB=0; double maxVar=-1; int thr=0;
        for(int t=0;t<256;t++){wB+=hist[t]; if(wB==0)continue; int wF=total-wB; if(wF==0)break; sumB+=(long)t*hist[t];
            double mB=(double)sumB/wB, mF=(double)(sumAll-sumB)/wF; double between=(double)wB*wF*(mB-mF)*(mB-mF);
            if(between>maxVar){maxVar=between;thr=t;}}
        int below=0; for(int t=0;t<=thr;t++) below+=hist[t]; boolean fgDark = below <= total-below;
        byte[] m=new byte[w*h];
        for(int i=0;i<w*h;i++){boolean dark=g[i]<=thr; if(dark==fgDark) m[i]=1;}
        open(m,w,h,1); close(m,w,h,3);
        int minArea=Math.max(1,(int)(0.0015*total)); int maxArea=(int)(0.45*total);
        List<Box> out=new ArrayList<>();
        for(Region rg:components(m,w,h,minArea)){
            for(Region bl:splitTouching(rg,m,w,h,0.6)){
                int bw=bl.r-bl.l,bh=bl.b-bl.t; if(bl.area<minArea||bl.area>maxArea)continue;
                double asp=(double)bw/bh; if(asp<0.4||asp>2.5)continue;
                double fill=(double)bl.area/((double)bw*bh); if(fill<0.45)continue;
                out.add(new Box((double)bl.l/w,(double)bl.t/h,(double)bl.r/w,(double)bl.b/h));
            }
        }
        return out;
    }

    // D5 union of D3 + D2 with NMS
    static List<Box> d5(Img im){
        List<Box> all=new ArrayList<>(); all.addAll(d3(im)); all.addAll(d2(im));
        return nms(all,0.3);
    }
    static List<Box> nms(List<Box> in,double iouTh){
        List<Box> srt=new ArrayList<>(in); srt.sort((a,b)->Double.compare(b.area(),a.area()));
        List<Box> keep=new ArrayList<>();
        for(Box b:srt){boolean dup=false; for(Box k:keep) if(iou(b,k)>iouTh){dup=true;break;} if(!dup)keep.add(b);}
        return keep;
    }

    // ---------- ground truth ----------
    static Map<String,List<Box>> gt(){
        Map<String,List<Box>> m=new LinkedHashMap<>();
        m.put("1.jpg",L(new Box(0.3035,0.4053,0.4384,0.5176)));
        m.put("2.jpg",L(new Box(0.3658,0.3438,0.5629,0.4941)));
        m.put("3.jpg",L(new Box(0.4371,0.4238,0.5875,0.5469)));
        m.put("4.jpg",L(new Box(0.4436,0.5049,0.6213,0.6357)));
        m.put("5.jpg",L(new Box(0.3606,0.5273,0.5136,0.6455),new Box(0.5006,0.3633,0.6394,0.4736)));
        m.put("6.jpg",L(new Box(0.5331,0.3906,0.7263,0.5449),new Box(0.3567,0.4619,0.5655,0.624)));
        m.put("7.jpg",L());
        m.put("8.jpg",L());
        m.put("9.jpg",L(new Box(0.3839,0.3906,0.4254,0.4268),new Box(0.5914,0.5752,0.6407,0.6094)));
        m.put("10.jpg",L(new Box(0.2070,0.4767,0.2813,0.5648),new Box(0.4414,0.4948,0.5078,0.5829)));
        m.put("11.jpg",L(new Box(0.1065,0.1641,0.2935,0.3086),new Box(0.7948,0.6973,0.9688,0.8203)));
        m.put("12.jpg",L(new Box(0.5957,0.3005,0.6797,0.4197),new Box(0.3672,0.6192,0.4570,0.7254)));
        return m;
    }
    static List<Box> L(Box... b){return new ArrayList<>(Arrays.asList(b));}

    // true counts for 101-110
    static Map<String,Integer> counts(){
        Map<String,Integer> c=new LinkedHashMap<>();
        c.put("101.jpg",1);c.put("102.jpg",2);c.put("103.jpg",3);c.put("104.jpg",1);c.put("105.jpg",1);
        c.put("106.jpg",1);c.put("107.jpg",1);c.put("108.jpg",1);c.put("109.jpg",4);c.put("110.jpg",3);
        return c;
    }

    interface Approach{ List<Box> run(Img im); }

    public static void main(String[] args) throws Exception {
        LinkedHashMap<String,Approach> ap=new LinkedHashMap<>();
        ap.put("D1-base",Dice::d1); ap.put("D3-relax",Dice::d3); ap.put("D2-edge",Dice::d2);
        ap.put("D4-otsu",Dice::d4); ap.put("D5-union",Dice::d5);

        // ----- 1..12 recall + FP -----
        Map<String,List<Box>> gt=gt();
        System.out.println("=== PART A: pip d6 with ground truth (IoU>=0.5) ===");
        System.out.printf("%-9s %6s %6s %6s%n","approach","recall","TP","FP");
        for(Map.Entry<String,Approach> e:ap.entrySet()){
            int tp=0,fp=0,totalGt=0;
            for(Map.Entry<String,List<Box>> ge:gt.entrySet()){
                Img im=load(DIR+ge.getKey());
                List<Box> det=e.getValue().run(im);
                List<Box> g=ge.getValue(); totalGt+=g.size();
                boolean[] used=new boolean[det.size()];
                for(Box gb:g){int best=-1;double bestI=0.5; for(int i=0;i<det.size();i++){if(used[i])continue;double v=iou(gb,det.get(i)); if(v>=bestI){bestI=v;best=i;}} if(best>=0){used[best]=true;tp++;}}
                for(int i=0;i<det.size();i++) if(!used[i]) fp++;
            }
            System.out.printf("%-9s %6.2f %6d %6d%n",e.getKey(),(double)tp/totalGt,tp,fp);
        }

        // ----- 101..110 detected vs true -----
        Map<String,Integer> cnt=counts();
        System.out.println("\n=== PART B: new dice, detected boxes vs true count ===");
        System.out.print(String.format("%-9s","img/true"));
        for(String k:ap.keySet()) System.out.print(String.format("%9s",k));
        System.out.println();
        // store detections for viz
        Map<String,Map<String,List<Box>>> detStore=new HashMap<>();
        for(Map.Entry<String,Integer> ce:cnt.entrySet()){
            Img im=load(DIR+ce.getKey());
            System.out.print(String.format("%-9s",ce.getKey()+"/"+ce.getValue()));
            Map<String,List<Box>> perAp=new HashMap<>();
            for(Map.Entry<String,Approach> e:ap.entrySet()){
                List<Box> det=e.getValue().run(im); perAp.put(e.getKey(),det);
                System.out.print(String.format("%9d",det.size()));
            }
            detStore.put(ce.getKey(),perAp);
            System.out.println();
        }

        // ----- visualizations -----
        String out="/tmp/claude-1000/-home-chulup-dice/c2dc9f7e-e6f5-4b07-87df-31296525a047/scratchpad/";
        String[] vizImgs={"105.jpg","102.jpg","109.jpg","107.jpg","104.jpg","106.jpg","108.jpg","101.jpg"};
        String[] vizAps={"D2-edge","D4-otsu","D5-union","D3-relax"};
        for(String img:vizImgs){
            for(String apn:vizAps){
                List<Box> det=detStore.get(img).get(apn);
                drawAndSave(DIR+img, det, out+"viz_"+img.replace(".jpg","")+"_"+apn+".png");
            }
        }
        System.out.println("\nVisualizations written to "+out);
    }

    static void drawAndSave(String path, List<Box> boxes, String outPath) throws Exception {
        BufferedImage bi=ImageIO.read(new File(path));
        int w=bi.getWidth(),h=bi.getHeight();
        Graphics2D g=bi.createGraphics();
        g.setStroke(new BasicStroke(Math.max(3,w/200f)));
        g.setColor(Color.RED);
        for(Box b:boxes){int x=(int)(b.l*w),y=(int)(b.t*h),bw=(int)((b.r-b.l)*w),bh=(int)((b.b-b.t)*h);g.drawRect(x,y,bw,bh);}
        g.dispose();
        ImageIO.write(bi,"png",new File(outPath));
    }
}
