package com.stereoo.zontface;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.content.Intent;
import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ConfigActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private TextView feedback;
    private EditText user, pass;
    private Button connect;
    private SecureStore store;
    private int dp(float value){return (int)(getResources().getDisplayMetrics().density*value+0.5f);}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        store=new SecureStore(this);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(0xFF020704);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22),dp(22),dp(22),dp(26));root.setGravity(Gravity.CENTER_HORIZONTAL);scroll.addView(root);
        TextView title=text("ZONT  •  XL7",21,0xFF55FA6E);title.setGravity(Gravity.CENTER);root.addView(title);
        root.addView(text("Stereoo ZONT Face\nTicWatch Pro 3 • Wear OS 2",12,Color.WHITE));
        user=edit("Логин my.zont.online",false);user.setText(store.username());root.addView(user);
        pass=edit("Пароль ZONT",true);root.addView(pass);
        root.addView(text("Пароль хранится в зашифрованном виде только на часах.",10,0xFF9AB79F));
        connect=button("Подключиться / проверить",()->connect());root.addView(connect);
        root.addView(button("Обновить состояние",()->refresh()));
        root.addView(button("Выбрать устройство",()->select()));
        feedback=text(store.configured()?"Устройство: "+store.deviceName():"Сначала введи логин и пароль ZONT",12,Color.LTGRAY);
        root.addView(feedback);setContentView(scroll);
    }
    private TextView text(String str,int sp,int color){ TextView t=new TextView(this);t.setText(str);t.setTextSize(sp);t.setTextColor(color);t.setGravity(Gravity.CENTER);t.setPadding(0,dp(9),0,dp(9));return t; }
    private EditText edit(String hint,boolean secret){EditText e=new EditText(this);e.setSingleLine(true);e.setTextSize(14);e.setTextColor(Color.WHITE);e.setHintTextColor(0xFF9CAAA0);e.setHint(hint);e.setInputType(secret?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);return e;}
    private Button button(String label,Runnable action){Button b=new Button(this);b.setText(label);b.setTextSize(11);b.setTextColor(Color.WHITE);b.setOnClickListener(v->action.run());return b;}
    private void message(String m){runOnUiThread(()->feedback.setText(m));}
    private void emitUpdate(){sendBroadcast(new Intent(ZontClient.UPDATE_ACTION).setPackage(getPackageName()));}
    private void connect(){
        connect.setEnabled(false);feedback.setText("Проверка связи с ZONT…");
        final String login=user.getText().toString().trim(); final String password=pass.getText().toString();
        worker.execute(()->{
            try {
                if (password.isEmpty() && store.configured() && login.equals(store.username())) {
                    // Reuse already encrypted credential; no need to type again.
                } else store.save(login,password);
                ZontClient api=new ZontClient(this);
                List<ZontClient.Device> devices=api.listDevices();
                if (devices.isEmpty()) throw new Exception("Устройств в кабинете ZONT нет");
                if (devices.size()==1)store.selectDevice(devices.get(0).id,devices.get(0).name);
                else if (store.deviceId()==0) {
                    ZontClient.Device first=null;
                    for (ZontClient.Device d:devices)if(d.payload.optString("devtype", "").startsWith("ZTC")){first=d;break;}
                    if(first==null)first=devices.get(0);
                    store.selectDevice(first.id,first.name);
                }
                ZontClient.State s=api.refresh();
                emitUpdate();
                message("Связь с кабинетом ZONT есть.\nАвто: "+s.name+"\nОхрана: "+(s.armedKnown?(s.armed?"включена":"выключена"):"нет данных"));
            }catch(Exception e){message("Ошибка: "+e.getMessage());}
            finally{runOnUiThread(()->connect.setEnabled(true));}
        });
    }
    private void refresh(){message("Обновление…");worker.execute(()->{
        try {ZontClient.State s=new ZontClient(this).refresh();emitUpdate();message("Обновлено: "+s.name);}
        catch(Exception e){message("Ошибка: "+e.getMessage());}
    });}
    private void select(){message("Загружаем устройства…");worker.execute(()->{
        try {
            List<ZontClient.Device> devices=new ZontClient(this).listDevices();
            if (devices.isEmpty())throw new Exception("Нет устройств ZONT");
            String[] options=new String[devices.size()];
            for(int i=0;i<devices.size();i++)options[i]=devices.get(i).name+" (#"+devices.get(i).id+")";
            runOnUiThread(()->new AlertDialog.Builder(this).setTitle("Выбери автомобиль")
                .setItems(options,(dialog,which)->{ZontClient.Device d=devices.get(which);store.selectDevice(d.id,d.name);feedback.setText("Выбрано: "+d.name);emitUpdate();})
                .setNegativeButton("Назад",null).show());
        }catch(Exception e){message("Ошибка: "+e.getMessage());}
    });}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
