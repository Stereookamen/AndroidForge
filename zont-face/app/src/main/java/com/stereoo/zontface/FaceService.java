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
            // v1.2, no car picture. Vector-only, sized for 400x400 round Wear OS 2.
            // Text, panels and button hit targets stay inside the inscribed circle.
            paint.setShader(new LinearGradient(0,0,400,400,0xFF041308,0xFF010302,Shader.TileMode.CLAMP));
            paint.setColor(Color.WHITE);paint.setStyle(Paint.Style.FILL);
            c.drawCircle(200,200,199,paint);paint.setShader(null);
            strokeOval(c,0xFF12612B,1.4f,11,11,389,389);
            strokeOval(c,GREEN,2.0f,15,15,385,385);

            // Header: actual ZONT connection at left, Suzuki emblem and
            // wordmark centered, physical watch battery on the right.
            int onlineColor=data!=null && data.deviceOnline && error==null ? GREEN : 0xFFFFBB68;
            drawConnection(c,onlineColor);
            txt(c,error!=null || data==null || !data.deviceOnline ? "Нет связи" : "Онлайн",
                    onlineColor,12,116,60,Paint.Align.CENTER,true);
            drawSuzuki(c);
            int battery=charge();
            drawBattery(c,295,49,battery);
            txt(c,battery<0?"—":battery+"%",WHITE,14,350,62,Paint.Align.CENTER,true);

            txt(c,time(),WHITE,67,200,133,Paint.Align.CENTER,true);
            txt(c,date(),0xFFE3F5E7,19,200,161,Paint.Align.CENTER,true);

            // Oversized, background-backed, symmetrical information panels.
            telemetry(c);
            // Analogue hands are deliberately shorter so they do not
            // cover the digital clock or touch the outer telemetry icons.
            drawHands(c,false);
            security(c);
            control(c,125,338,"ARM");
            control(c,200,338,"ENGINE");
            control(c,275,338,"DISARM");
        }

        private void drawConnection(Canvas c,int color){
            // Cloud glyph in vector paths, not OS-dependent text symbols.
            paint.setShader(null);paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);
            Path p=new Path();
            p.moveTo(48,58);p.cubicTo(40,58,40,49,47,47);
            p.cubicTo(47,37,58,36,63,43);
            p.cubicTo(74,41,78,55,69,59);
            p.close();c.drawPath(p,paint);paint.setStyle(Paint.Style.FILL);
        }

        private void drawSuzuki(Canvas c){
            // Small stylized red Suzuki "S" to avoid any embedded car artwork.
            paint.setColor(0xFFF32E36);paint.setShader(null);paint.setStyle(Paint.Style.FILL);
            Path p=new Path();
            p.moveTo(200,18);p.lineTo(216,25);p.lineTo(207,33);
            p.lineTo(190,26);p.close();c.drawPath(p,paint);
            p.reset();p.moveTo(190,29);p.lineTo(204,36);
            p.lineTo(212,33);p.lineTo(216,38);p.lineTo(200,46);
            p.lineTo(184,39);p.close();c.drawPath(p,paint);
            txt(c,"SUZUKI",WHITE,17,200,68,Paint.Align.CENTER,true);
        }

        private void panel(Canvas c,float left,float top,float right,float bottom){
            rect(c,0xFF08170D,left,top,right,bottom,13);
            paint.setShader(null);paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f);paint.setColor(0xFF258D3C);
            c.drawRoundRect(new RectF(left,top,right,bottom),13,13,paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void telemetry(Canvas c){
            panel(c,29,176,170,221);
            panel(c,29,226,170,271);
            panel(c,230,176,371,221);
            panel(c,230,226,371,271);

            drawThermometer(c,49,192);
            txt(c,ZontClient.value(data==null?null:data.engineTemp,"°"),
                    WHITE,26,123,201,Paint.Align.CENTER,true);
            txt(c,"Двигатель",0xFFE2F3E5,12,121,217,Paint.Align.CENTER,true);

            drawThermometer(c,49,242);
            txt(c,ZontClient.value(data==null?null:data.cabinTemp,"°"),
                    WHITE,26,123,251,Paint.Align.CENTER,true);
            txt(c,"Салон",0xFFE2F3E5,12,122,267,Paint.Align.CENTER,true);

            drawVoltage(c,252,193);
            txt(c,ZontClient.value(data==null?null:data.voltage,"В"),
                    WHITE,24,325,201,Paint.Align.CENTER,true);
            txt(c,"АКБ автомобиля",0xFFE2F3E5,11,316,217,Paint.Align.CENTER,true);

            drawFuel(c,252,243);
            txt(c,ZontClient.value(data==null?null:data.fuelLiters," л"),
                    WHITE,25,325,251,Paint.Align.CENTER,true);
            txt(c,"Топливо",0xFFE2F3E5,12,320,267,Paint.Align.CENTER,true);
        }

        private void security(Canvas c){
            boolean stale=data==null || error!=null;
            boolean armed=!stale && data.armedKnown && data.armed;
            rect(c,0xFF082815,68,279,332,308,14);
            paint.setShader(null);paint.setStyle(Paint.Style.STROKE);
            paint.setColor(stale?0xFFFFB54F:GREEN);paint.setStrokeWidth(2);
            c.drawRoundRect(new RectF(68,279,332,308),14,14,paint);
            paint.setStyle(Paint.Style.FILL);
            String s=stale?"Подключить ZONT":!data.armedKnown?
                    "Нет данных охраны":armed?"Под охраной":"Охрана выключена";
            txt(c,s,WHITE,s.length()>17?17:19,200,299,Paint.Align.CENTER,true);
        }

        private void control(Canvas c,float x,float y,String type){
            final float r=31.5f;
            boolean engine="ENGINE".equals(type);
            paint.setShader(null);paint.setColor(engine?0xFF0B5125:0xFF14221B);
            paint.setStyle(Paint.Style.FILL);c.drawCircle(x,y,r,paint);
            strokeOval(c,engine?GREEN:0xFF8CAA94,engine?2.8f:1.7f,
                    x-r,y-r,x+r,y+r);
            if(engine){
                c.save();c.translate(x,y-9);c.scale(0.73f,0.73f);
                drawEngine(c,0,0);c.restore();
                txt(c,data!=null&&data.engineKnown&&data.engineRunning?
                        "Стоп":"Запуск",WHITE,12,x,y+23,Paint.Align.CENTER,true);
            } else {
                // Existing vector padlock: large icon and explicit command label.
                c.save();c.translate(x,y-10);c.scale(0.79f,0.79f);
                lockIconLarge(c,0,0,"ARM".equals(type));c.restore();
                txt(c,"ARM".equals(type)?"Закрыть":"Открыть",
                        WHITE,11.5f,x,y+23,Paint.Align.CENTER,true);
            }
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
                247,37, 256,54, 260,73, 260,106, 253,126,
                244,139, 237,149, 220,162, 210,165, 206,177,
                196,184, 180,186, 172,181, 166,164,
                154,157, 136,157, 109,160, 80,160,
                49,155, 29,146, 13,140, 9,129
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
            int hour=clock.get(Calendar.HOUR),minute=clock.get(Calendar.MINUTE);
            int second=clock.get(Calendar.SECOND);
            drawHand(c,(hour+minute/60f)*30f,45,4.3f,
                    dim?0xFFAAAAAA:0xFFE4FFE8);
            drawHand(c,(minute+second/60f)*6f,71,3.4f,
                    dim?0xFFBBBBBB:0xFFD6F8DD);
            if(!dim)drawHand(c,second*6f,77,1.5f,0xFFF33A52);
            paint.setShader(null);paint.setColor(0xFFEDFFF2);
            c.drawCircle(200,222,5.0f,paint);
            paint.setColor(0xFF16341C);c.drawCircle(200,222,2.0f,paint);
        }
        private void drawHand(Canvas c,float degrees,float length,float width,int color){
            double a=Math.toRadians(degrees-90);
            float x=200+(float)Math.cos(a)*length;
            float y=222+(float)Math.sin(a)*length;
            line(c,0xFF051408,width+2,200,222,x,y);
            line(c,color,width,200,222,x,y);
        }

        private void drawAmbient(Canvas c){
            c.drawColor(Color.BLACK);
            txt(c,"SUZUKI",0xFFB4B4B4,20,200,106,Paint.Align.CENTER,true);
            txt(c,time(),WHITE,60,200,194,Paint.Align.CENTER,true);
            txt(c,date(),0xFFB0B0B0,19,200,224,Paint.Align.CENTER,false);
            String status=data!=null&&data.armedKnown?
                    (data.armed?"Под охраной":"Охрана выключена"):"ZONT";
            txt(c,status,0xFFB4B4B4,16,200,277,Paint.Align.CENTER,true);
        }

        @Override public void onTapCommand(int tapType,int x,int y,long eventTime){
            if(tapType!=WatchFaceService.TAP_TYPE_TAP || ambient)return;
            Rect b=getSurfaceHolder().getSurfaceFrame();
            float size=Math.min(b.width(),b.height());if(size<=0)return;
            float lx=(x-(b.width()-size)/2f)*400f/size;
            float ly=(y-(b.height()-size)/2f)*400f/size;
            boolean settings=(ly<83 && lx>163 && lx<237) ||
                    (ly>=274 && ly<=310);
            // Never offer a control action without configured credentials.
            if(settings || !api.store.configured() || error!=null){
                Intent i=new Intent(FaceService.this,ConfigActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);return;
            }
            if(ly>=303 && ly<=375){
                float[] xs={125,200,275};
                String[] actions={"ARM","ENGINE","DISARM"};
                for(int j=0;j<xs.length;j++){
                    float dx=lx-xs[j],dy=ly-338;
                    if(dx*dx+dy*dy>34*34)continue;
                    String action=actions[j];
                    if("ENGINE".equals(action)){
                        action=data!=null&&data.engineKnown&&data.engineRunning?
                                "STOP":"START";
                    }
                    Intent i=new Intent(FaceService.this,ConfirmActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    i.putExtra("command",action);startActivity(i);return;
                }
            }
        }

    }
}
