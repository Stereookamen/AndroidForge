package com.stereoo.zontface;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.view.SurfaceHolder;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Watchface layout at 400x400 logical pixels, scaled to the device screen. */
public final class FaceService extends CanvasWatchFaceService {
    private static final int GREEN=0xFF30F759;
    private static final int LIGHT_GREEN=0xFFB9FFBF;
    private static final int WHITE=Color.WHITE;
    private static final Locale RU=new Locale("ru","RU");
    @Override public Engine onCreateEngine(){return new FaceEngine();}

    private final class FaceEngine extends CanvasWatchFaceService.Engine {
        private final Handler handler=new Handler(Looper.getMainLooper());
        private final ExecutorService worker=Executors.newSingleThreadExecutor();
        private final Paint paint=new Paint(3);
        private final Calendar clock=Calendar.getInstance();
        private final Runnable ticker=()->{invalidate();plan();};
        private final ZontClient api=new ZontClient(FaceService.this);
        private long lastFetchElapsed=0;
        private boolean loading=false, ambient=false, visible=false, receiverRegistered=false;
        private ZontClient.State data;
        private Bitmap car;
        private String error=null;
        private final BroadcastReceiver refreshReceiver=new BroadcastReceiver(){
            @Override public void onReceive(Context c,Intent i){lastFetchElapsed=0;fetch();invalidate();}
        };

        @Override public void onCreate(SurfaceHolder holder){
            super.onCreate(holder);
            setWatchFaceStyle(new WatchFaceStyle.Builder(FaceService.this)
                    .setAcceptsTapEvents(true).setShowSystemUiTime(false)
                    .setHideNotificationIndicator(false).build());
            try {car=BitmapFactory.decodeStream(getAssets().open("car.jpg"));}catch(Exception ignored){ }
            registerReceiver(refreshReceiver,new IntentFilter(ZontClient.UPDATE_ACTION));
            receiverRegistered=true;
        }
        @Override public void onVisibilityChanged(boolean nowVisible){
            super.onVisibilityChanged(nowVisible);visible=nowVisible;
            if(nowVisible){fetch();plan();}else handler.removeCallbacks(ticker);
        }
        @Override public void onAmbientModeChanged(boolean newAmbient){
            super.onAmbientModeChanged(newAmbient);ambient=newAmbient;
            invalidate();plan();
        }
        @Override public void onTimeTick(){super.onTimeTick();invalidate();}
        @Override public void onDestroy(){
            visible=false;handler.removeCallbacks(ticker);
            if(receiverRegistered)unregisterReceiver(refreshReceiver);
            worker.shutdownNow();if(car!=null){car.recycle();car=null;}
            super.onDestroy();
        }
        private void plan(){
            handler.removeCallbacks(ticker);if(!visible)return;
            long interval=ambient?60000:1000;
            long delay=interval-System.currentTimeMillis()%interval;
            handler.postDelayed(ticker,delay);
            if(!ambient)fetch();
        }
        private void fetch(){
            if(!visible||ambient||loading)return;
            if(!api.store.configured()){data=null;error="Нужна настройка";return;}
            long now=SystemClock.elapsedRealtime();
            if(lastFetchElapsed!=0 && now-lastFetchElapsed<90000)return;
            lastFetchElapsed=now;loading=true;
            worker.execute(()->{
                try{
                    ZontClient.State newData=api.refresh();
                    handler.post(()->{data=newData;error=null;loading=false;invalidate();});
                }catch(Exception ex){
                    String detail=ex.getMessage();
                    handler.post(()->{error=detail;loading=false;invalidate();});
                }
            });
        }
        @Override public void onDraw(Canvas realCanvas,Rect bounds){
            final float size=Math.min(bounds.width(),bounds.height());
            realCanvas.drawColor(Color.BLACK);
            realCanvas.save();
            realCanvas.translate((bounds.width()-size)/2f,(bounds.height()-size)/2f);
            realCanvas.scale(size/400f,size/400f);
            clock.setTimeInMillis(System.currentTimeMillis());
            if(ambient)drawAmbient(realCanvas);else drawActive(realCanvas);
            realCanvas.restore();
        }
        private void rect(Canvas c,int color,float left,float top,float right,float bottom,float radius){
            paint.setShader(null);paint.setColor(color);paint.setStyle(Paint.Style.FILL);
            c.drawRoundRect(new RectF(left,top,right,bottom),radius,radius,paint);
        }
        private void strokeOval(Canvas c,int color,float width,float left,float top,float right,float bottom){
            paint.setShader(null);paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(width);
            c.drawOval(new RectF(left,top,right,bottom),paint);paint.setStyle(Paint.Style.FILL);
        }
        private void line(Canvas c,int color,float width,float x1,float y1,float x2,float y2){
            paint.setShader(null);paint.setColor(color);paint.setStrokeWidth(width);
            paint.setStrokeCap(Paint.Cap.ROUND);c.drawLine(x1,y1,x2,y2,paint);
        }
        private void txt(Canvas c,String str,int color,float size,float x,float baseline,Paint.Align align,boolean bold){
            paint.setShader(null);paint.setColor(color);paint.setStyle(Paint.Style.FILL);
            paint.setTypeface(bold?android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.BOLD):
                    android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL));
            paint.setTextAlign(align);paint.setTextSize(size);paint.clearShadowLayer();c.drawText(str,x,baseline,paint);
        }
        private String time(){return new SimpleDateFormat("HH:mm",RU).format(new Date());}
        private String date(){return new SimpleDateFormat("EEE, d MMM",RU).format(new Date());}
        private int charge(){
            Intent i=registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if(i==null)return -1;
            int level=i.getIntExtra(BatteryManager.EXTRA_LEVEL,-1);
            int scale=i.getIntExtra(BatteryManager.EXTRA_SCALE,-1);
            return level<0||scale<=0?-1:Math.round(level*100f/scale);
        }
        private void lockIcon(Canvas c,float centerX,float y,boolean closed,int color){
            // Tiny readable padlock glyph, irrespective of device font.
            paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2.6f);
            c.drawRoundRect(centerX-7,y+8,centerX+7,y+21,2.5f,2.5f,paint);
            Path p=new Path();p.moveTo(centerX-4,y+8);p.lineTo(centerX-4,y+4);
            p.quadTo(centerX-4,y-2,centerX+0.5f,y-2);
            p.quadTo(centerX+4.5f,y-2,centerX+4.5f,y+4);
            if(!closed)p.lineTo(centerX+8,y-0.5f);
            else p.lineTo(centerX+4.5f,y+8);
            c.drawPath(p,paint);paint.setStyle(Paint.Style.FILL);
            c.drawCircle(centerX,y+14,1.2f,paint);
        }

        // All coordinates are inside the inscribed circle, even on TicWatch's
        // real 454x454 AMOLED. No interactive control touches the round edge.
        private void drawActive(Canvas c){
            paint.setShader(new LinearGradient(0,0,400,400,0xFF031209,0xFF000302,Shader.TileMode.CLAMP));
            paint.setColor(Color.WHITE);paint.setStyle(Paint.Style.FILL);
            c.drawCircle(200,200,199,paint);paint.setShader(null);
            strokeOval(c,0xFF12672C,1.4f,10,10,390,390);
            strokeOval(c,GREEN,1.8f,14,14,386,386);

            // The top row is inset from the curved edge. The previous v1 row
            // started at x=50 at y=54, which was OUTSIDE the circle.
            int leftColor = data == null ? 0xFFFFC060 : (data.deviceOnline ? GREEN : 0xFFFFC060);
            txt(c,data == null ? "Нет связи" : (data.deviceOnline ? "Онлайн" : "Нет связи"),
                    leftColor,12,123,61,Paint.Align.CENTER,true);
            line(c,0xFF206D38,1,162,42,162,67);
            txt(c,"ZONT",WHITE,22,205,62,Paint.Align.CENTER,true);
            line(c,0xFF206D38,1,247,42,247,67);
            int battery=charge();
            drawBattery(c,263,48,battery);
            txt(c,battery<0?"—":battery+"%",WHITE,13,309,61,Paint.Align.CENTER,true);

            txt(c,time(),WHITE,54,200,121,Paint.Align.CENTER,true);
            txt(c,date(),0xFFE5F4E8,18,200,147,Paint.Align.CENTER,true);
            line(c,0xFF176C2D,1,70,153,118,153);
            line(c,0xFF176C2D,1,282,153,330,153);

            // Actual owner's vehicle, NOT the garage/road background.
            // Preserve the car's 4:3 proportions: v1 stretched it to 218:117.
            drawCutOutCar(c);
            // Readings are painted after the car, for maximum contrast.
            drawTelemetry(c);

            drawHands(c,false);
            drawSecurity(c);
            drawAction(c,125,341,"ARM",false);
            drawAction(c,200,341,"ENGINE",true);
            drawAction(c,275,341,"DISARM",false);
        }
        private void drawBattery(Canvas c,float x,float y,int battery){
            paint.setShader(null);paint.setColor(0xFFDAFFE0);paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.6f);
            c.drawRoundRect(new RectF(x,y,x+27,y+14),2,2,paint);
            c.drawRect(x+27,y+4,x+30,y+10,paint);
            paint.setStyle(Paint.Style.FILL);
            if(battery>=0){paint.setColor(battery<20?0xFFFFA341:GREEN);
                c.drawRoundRect(new RectF(x+3,y+3,x+3+21*battery/100f,y+11),1,1,paint);}
        }
        private void drawCutOutCar(Canvas c){
            if(car==null)return;
            float l=84,t=153,w=232,h=174; // Original photo is 280x210: no distortion.
            final float sx=w/280f,sy=h/210f;
            // Mask follows the XL7 body rather than putting the photo rectangle
            // (including red building and road) on the watch face.
            float[] xy={
                9,116, 13,103, 27,96, 36,87, 72,74, 86,51, 104,34,
                131,23, 153,19, 179,17, 211,18, 222,24, 233,24,
                247,37, 256,54, 260,73, 260,106, 253,128,
                244,143, 237,154, 220,162, 210,165, 207,179,
                198,186, 180,187, 170,182, 166,170,
                151,174, 137,172, 109,166, 80,165,
                49,166, 27,160, 13,149, 9,136
            };
            Path outline=new Path();
            outline.moveTo(l+xy[0]*sx,t+xy[1]*sy);
            for(int i=2;i<xy.length;i+=2)outline.lineTo(l+xy[i]*sx,t+xy[i+1]*sy);
            outline.close();
            c.save();
            c.clipPath(outline);
            paint.setShader(null);paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);paint.setAlpha(255);
            paint.setFilterBitmap(true);
            c.drawBitmap(car,new Rect(0,0,car.getWidth(),car.getHeight()),
                    new RectF(l,t,l+w,t+h),paint);
            c.restore();
            // Replacement plate, physically on the vehicle, not separate text.
            rect(c,0xFFF2F2F2,l+41*sx,t+120*sy,l+94*sx,t+134*sy,2);
            txt(c,"Х125ХР 22",0xFF101010,8.4f,l+67.5f*sx,t+131*sy,Paint.Align.CENTER,true);
        }
        private void drawThermometer(Canvas c,float x,float y){
            paint.setShader(null);paint.setColor(GREEN);paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.6f);paint.setStrokeCap(Paint.Cap.ROUND);
            c.drawRoundRect(new RectF(x-3,y-11,x+3,y+8),3,3,paint);
            c.drawCircle(x,y+10,5,paint);
            paint.setStyle(Paint.Style.FILL);c.drawCircle(x,y+10,2.4f,paint);
        }
        private void drawVoltage(Canvas c,float x,float y){
            paint.setColor(GREEN);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2.2f);
            c.drawRoundRect(new RectF(x-10,y-7,x+10,y+7),2,2,paint);
            c.drawRect(x-12,y-2,x-10,y+2,paint);
            c.drawRect(x+10,y-2,x+12,y+2,paint);
            paint.setStyle(Paint.Style.FILL);
            line(c,GREEN,2,x-4,y,x+4,y);line(c,GREEN,2,x,y-4,x,y+4);
        }
        private void drawFuel(Canvas c,float x,float y){
            paint.setColor(GREEN);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2.2f);
            c.drawRoundRect(new RectF(x-8,y-10,x+5,y+10),2,2,paint);
            c.drawLine(x-8,y-4,x+5,y-4,paint);
            Path p=new Path();p.moveTo(x+5,y-6);p.lineTo(x+12,y+1);
            p.lineTo(x+12,y+10);c.drawPath(p,paint);
            paint.setStyle(Paint.Style.FILL);
        }
        private void drawTelemetry(Canvas c){
            // Values and labels use explicitly sized vector symbols: no missing
            // Android glyphs, no cramped 9-point text.
            drawThermometer(c,47,194);
            txt(c,ZontClient.value(data==null?null:data.engineTemp,"°"),
                    WHITE,22,91,196,Paint.Align.CENTER,true);
            txt(c,"Двигатель",0xFFDBF3E1,12,90,214,Paint.Align.CENTER,true);
            line(c,0xFF17602D,1,32,222,125,222);
            drawThermometer(c,47,246);
            txt(c,ZontClient.value(data==null?null:data.cabinTemp,"°"),
                    WHITE,22,91,249,Paint.Align.CENTER,true);
            txt(c,"Салон",0xFFDBF3E1,12,88,266,Paint.Align.CENTER,true);

            drawVoltage(c,313,190);
            txt(c,ZontClient.value(data==null?null:data.voltage,"В"),
                    WHITE,20,340,210,Paint.Align.CENTER,true);
            line(c,0xFF17602D,1,294,219,370,219);
            drawFuel(c,315,242);
            txt(c,ZontClient.value(data==null?null:data.fuelLiters," л"),
                    WHITE,20,341,263,Paint.Align.CENTER,true);
        }
        private void drawSecurity(Canvas c){
            boolean armed=data!=null && data.armedKnown && data.armed;
            int border=data==null?0xFFFFB548:(data.armedKnown?GREEN:0xFFFFB548);
            rect(c,0xFF082514,80,282,320,314,16);
            paint.setStyle(Paint.Style.STROKE);paint.setColor(border);paint.setStrokeWidth(2);
            c.drawRoundRect(new RectF(80,282,320,314),16,16,paint);
            paint.setStyle(Paint.Style.FILL);
            if(data==null) {
                txt(c,"Подключить ZONT",0xFFFFFFFF,17,200,303,Paint.Align.CENTER,true);
            } else {
                String label=!data.armedKnown?"Нет данных охраны":armed?"Под охраной":"Охрана выключена";
                txt(c,label,WHITE,label.length()>16?16:18,200,303,Paint.Align.CENTER,true);
            }
        }
        private void drawAction(Canvas c,float x,float y,String type,boolean primary){
            // Fits fully within the round screen: old centers at 106/294,y356
            // plus radius 32 were clipped by the physical display.
            float r=29;
            paint.setShader(null);
            paint.setColor(primary?0xFF074420:0xFF101C15);paint.setStyle(Paint.Style.FILL);
            c.drawCircle(x,y,r,paint);
            strokeOval(c,primary?GREEN:0xFF718E79,primary?2.7f:1.9f,
                    x-r,y-r,x+r,y+r);
            if(primary) {
                drawEngine(c,x,y);
            } else lockIconLarge(c,x,y,"ARM".equals(type));
        }
        private void lockIconLarge(Canvas c,float x,float y,boolean closed){
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(3.2f);
            paint.setColor(Color.WHITE);paint.setStrokeCap(Paint.Cap.ROUND);
            c.drawRoundRect(new RectF(x-10,y-1,x+10,y+15),3,3,paint);
            Path p=new Path();p.moveTo(x-6,y-1);p.lineTo(x-6,y-10);
            p.cubicTo(x-6,y-20,x+6,y-20,x+6,y-10);
            if(closed)p.lineTo(x+6,y-1);
            else {p.lineTo(x+6,y-5);p.lineTo(x+13,y-8);}
            c.drawPath(p,paint);paint.setStyle(Paint.Style.FILL);
            c.drawCircle(x,y+6,2.1f,paint);
        }
        private void drawEngine(Canvas c,float x,float y){
            // Engine pictogram drawn directly as a path, not font glyph ▣.
            paint.setColor(GREEN);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(3.4f);
            paint.setStrokeJoin(Paint.Join.ROUND);paint.setStrokeCap(Paint.Cap.ROUND);
            Path p=new Path();
            p.moveTo(x-17,y-7);p.lineTo(x-10,y-7);p.lineTo(x-7,y-13);
            p.lineTo(x+8,y-13);p.lineTo(x+12,y-7);p.lineTo(x+17,y-7);
            p.lineTo(x+17,y+12);p.lineTo(x-17,y+12);p.close();
            c.drawPath(p,paint);
            c.drawLine(x-8,y-16,x+4,y-16,paint);
            c.drawLine(x-21,y-5,x-21,y+9,paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawHands(Canvas c,boolean dim){
            // Slim realistic hands that do not obscure the car and telemetry.
            int hour=clock.get(Calendar.HOUR);
            int minute=clock.get(Calendar.MINUTE);
            int second=clock.get(Calendar.SECOND);
            drawHand(c,(hour+minute/60f)*30f,52,4.2f,
                    dim?0xFFAAAAAA:0xFFDDFAE3);
            drawHand(c,(minute+second/60f)*6f,81,3.1f,
                    dim?0xFF999999:0xFFEEFFF1);
            if(!dim)drawHand(c,second*6f,91,1.3f,0xFFF6424D);
            paint.setShader(null);paint.setColor(0xFFD9F8E0);
            c.drawCircle(200,227,5,paint);
            paint.setColor(0xFF10251A);c.drawCircle(200,227,2,paint);
        }
        private void drawHand(Canvas c,float degrees,float length,float width,int color){
            double angle=Math.toRadians(degrees-90);
            float x=200+(float)Math.cos(angle)*length;
            float y=227+(float)Math.sin(angle)*length;
            line(c,0xFF142819,width+1.7f,200,227,x,y);
            line(c,color,width,200,227,x,y);
        }
        private void drawAmbient(Canvas c){
            // Solid black background. Never show moving second hand or active icons.
            c.drawColor(Color.BLACK);
            txt(c,time(),WHITE,55,200,173,Paint.Align.CENTER,true);
            txt(c,date(),0xFF999999,17,200,205,Paint.Align.CENTER,false);
            boolean armed=data!=null&&data.armedKnown&&data.armed;
            lockIcon(c,200,228,armed,0xFF999999);
            txt(c,armed?"Под охраной":"ZONT",0xFF9D9D9D,13,200,275,Paint.Align.CENTER,false);
        }

        @Override public void onTapCommand(int tapType,int x,int y,long eventTime){
            if(tapType!=WatchFaceService.TAP_TYPE_TAP||ambient)return;
            Rect b=getSurfaceHolder().getSurfaceFrame();
            float size=Math.min(b.width(),b.height());
            if(size<=0)return;
            float lx=(x-(b.width()-size)/2f)*400/size;
            float ly=(y-(b.height()-size)/2f)*400/size;
            // Logo and the wide connection banner open real ZONT settings.
            if((ly>=32&&ly<=73&&lx>156&&lx<254)||
               (ly>=278&&ly<=316) || !api.store.configured()){
                Intent i=new Intent(FaceService.this,ConfigActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);return;
            }
            if(ly>=307&&ly<=378){
                final float[] xs={125,200,275};
                final String[] types={"ARM","ENGINE","DISARM"};
                for(int j=0;j<xs.length;j++){
                    float dx=lx-xs[j],dy=ly-341;
                    if(dx*dx+dy*dy<=37*37){
                        String action=types[j];
                        if("ENGINE".equals(action))
                            action=data!=null&&data.engineKnown&&data.engineRunning?"STOP":"START";
                        Intent i=new Intent(FaceService.this,ConfirmActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        i.putExtra("command",action);
                        startActivity(i);return;
                    }
                }
            }
        }
    }
}
