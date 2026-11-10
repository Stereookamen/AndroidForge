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
        private void drawActive(Canvas c){
            // Face rings and subtle shadows.
            paint.setColor(0xFF010703);paint.setShader(new LinearGradient(0,0,400,400,
                    0xFF001908,0xFF000201,Shader.TileMode.CLAMP));
            c.drawCircle(200,200,200,paint);paint.setShader(null);
            strokeOval(c,0xFF1D3D2A,2,6,6,394,394);
            strokeOval(c,GREEN,1.8f,12,12,388,388);
            strokeOval(c,0xFF0B721F,0.8f,17,17,383,383);
            // Top data row.
            txt(c,"☁",GREEN,20,50,54,Paint.Align.CENTER,true);
            String cloud=data==null?"НЕТ ДАННЫХ":data.deviceOnline?"Онлайн":"ZONT API";
            txt(c,cloud,data!=null?WHITE:0xFFA0A0A0,11,86,52,Paint.Align.CENTER,false);
            line(c,0xFF237538,1,148,38,148,60);
            txt(c,"ZONT",WHITE,19,200,54,Paint.Align.CENTER,true);
            line(c,0xFF237538,1,251,38,251,60);
            txt(c,"▮▮▮",GREEN,12,290,52,Paint.Align.CENTER,true);
            int battery=charge();
            strokeOval(c,0xFF9DF0AE,0.9f,307,40,332,54);
            rect(c,GREEN,310,43,310+Math.max(1,19*Math.max(0,battery)/100f),51,1);
            txt(c,battery<0?"—":battery+"%",WHITE,12,353,53,Paint.Align.CENTER,false);
            txt(c,time(),WHITE,55,200,126,Paint.Align.CENTER,true);
            txt(c,date(),WHITE,19,200,151,Paint.Align.CENTER,false);
            line(c,0xFF0A6422,1,62,155,115,155);line(c,0xFF0A6422,1,285,155,338,155);
            // Personal owner's XL7 photograph; tinted via vignette in bundled photo.
            if(car!=null){
                paint.setShader(null);paint.setColor(Color.WHITE);paint.setAlpha(255);
                Rect src=new Rect(0,0,car.getWidth(),car.getHeight());
                c.drawBitmap(car,src,new RectF(91,165,309,282),paint);
            }
            // Left telemetry.
            txt(c,"♨",GREEN,17,38,191,Paint.Align.CENTER,true);
            txt(c,ZontClient.value(data==null?null:data.engineTemp,"°"),WHITE,24,77,189,Paint.Align.CENTER,true);
            txt(c,"Двигатель",WHITE,10,77,204,Paint.Align.CENTER,false);
            line(c,0xFF217D39,0.8f,22,215,114,215);
            txt(c,"♨",GREEN,17,38,246,Paint.Align.CENTER,true);
            txt(c,ZontClient.value(data==null?null:data.cabinTemp,"°"),WHITE,24,77,244,Paint.Align.CENTER,true);
            txt(c,"Салон",WHITE,10,77,258,Paint.Align.CENTER,false);
            // Right telemetry. Do not show fictitious 12.6 V / 58 L.
            txt(c,"▣",GREEN,16,337,189,Paint.Align.CENTER,true);
            txt(c,ZontClient.value(data==null?null:data.voltage,"В"),WHITE,18,342,209,Paint.Align.CENTER,true);
            line(c,0xFF217D39,0.8f,306,217,383,217);
            txt(c,"▤",GREEN,17,334,242,Paint.Align.CENTER,true);
            txt(c,ZontClient.value(data==null?null:data.fuelLiters," л"),WHITE,18,345,261,Paint.Align.CENTER,true);
            // Overlay true moving hour/minute/second hands, no Roman or Arabic dial numerals.
            drawHands(c,false);
            // Arming status and actions.
            rect(c,0xFF042A0E,104,287,296,319,16);
            paint.setColor(data!=null&&data.armedKnown&&data.armed?GREEN:0xFFFFA53C);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.8f);
            c.drawRoundRect(new RectF(104,287,296,319),16,16,paint);paint.setStyle(Paint.Style.FILL);
            boolean armed=data!=null&&data.armedKnown&&data.armed;
            lockIcon(c,123,292,armed,armed?GREEN:0xFFFFBD6B);
            String status=data==null?"Подключи ZONT":
                    !data.armedKnown?"Нет данных охраны":armed?"Под охраной":"Охрана выключена";
            txt(c,status,WHITE,status.length()>17?13:17,207,309,Paint.Align.CENTER,true);
            drawButton(c,106,356,"ARM");drawButton(c,200,356,"ENGINE");drawButton(c,294,356,"DISARM");
            if(error!=null){txt(c,"Данные устарели • открой настройки",0xFFFFB75C,9,200,393,Paint.Align.CENTER,false);}
        }
        private void drawButton(Canvas c,float x,float y,String type){
            boolean engine=type.equals("ENGINE");
            paint.setColor(engine?0xFF033B16:0xFF121D18);paint.setShader(null);
            c.drawCircle(x,y,32,paint);strokeOval(c,engine?GREEN:0xFF717E76,1.6f,x-32,y-32,x+32,y+32);
            if(engine){txt(c,"▣",GREEN,32,x,y+11,Paint.Align.CENTER,true);}
            else lockIcon(c,x,y-10,type.equals("ARM"),WHITE);
        }
        private void drawHands(Canvas c,boolean dim){
            // Hands rotate against local device time. Center placed over the vehicle image.
            int hour=clock.get(Calendar.HOUR);int minute=clock.get(Calendar.MINUTE);int second=clock.get(Calendar.SECOND);
            float hourDeg=(hour+minute/60f)*30f;
            float minDeg=(minute+second/60f)*6f;
            drawHand(c,hourDeg,60,8,dim?0xFF999999:0xFFD4FFF0);
            drawHand(c,minDeg,91,5,dim?0xFF888888:0xFFB5FFC7);
            if(!dim){drawHand(c,second*6f,98,1.5f,0xFFF33448);}
            paint.setColor(dim?Color.WHITE:0xFFC3FFC9);c.drawCircle(200,216,5,paint);
            paint.setColor(0xFF182119);c.drawCircle(200,216,2,paint);
        }
        private void drawHand(Canvas c,float angle,float length,float width,int color){
            double a=Math.toRadians(angle-90);
            float x=(float)(200+Math.cos(a)*length), y=(float)(216+Math.sin(a)*length);
            line(c,0xFF0F2013,width+2,200,216,x,y);
            line(c,color,width,200,216,x,y);
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
            // Coordinates in physical screen pixels, convert to 400 logical units.
            Rect b=getSurfaceHolder().getSurfaceFrame();
            float size=Math.min(b.width(),b.height());if(size<=0)return;
            float lx=(x-(b.width()-size)/2f)*400/size;
            float ly=(y-(b.height()-size)/2f)*400/size;
            if(ly<85||ly>375&&lx>140&&lx<260){
                Intent i=new Intent(FaceService.this,ConfigActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);return;
            }
            if(ly>320&&ly<390){
                String action=null;
                if(lx>=68&&lx<145)action="ARM";
                else if(lx>=161&&lx<239){action=data!=null&&data.engineKnown&&data.engineRunning?"STOP":"START";}
                else if(lx>=258&&lx<337)action="DISARM";
                if(action!=null){
                    Intent i=new Intent(FaceService.this,ConfirmActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    i.putExtra("command",action);startActivity(i);
                }
            }
        }
    }
}
