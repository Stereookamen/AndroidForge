package com.stereoo.zontface;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicit confirmation for every state-changing command, even STOP. */
public final class ConfirmActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private Button yes;
    private TextView status;
    private String command;
    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        command=getIntent().getStringExtra("command");
        if (!"ARM".equals(command)&&!"DISARM".equals(command)&&!"START".equals(command)&&!"STOP".equals(command)){finish();return;}
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);root.setBackgroundColor(0xFF050C08);root.setPadding(14,12,14,12);
        String title="";
        switch(command){case "ARM":title="Поставить на охрану?";break;
            case "DISARM":title="Снять с охраны?";break;
            case "START":title="Запустить двигатель?";break;
            default:title="Остановить двигатель?";}
        TextView header=new TextView(this);header.setText(title);header.setTextSize(18);header.setTextColor(Color.WHITE);
        header.setGravity(Gravity.CENTER);root.addView(header);
        status=new TextView(this);status.setText("Подтверди команду ZONT.\nПеред запуском проверь, что машина на улице.");
        status.setTextColor(0xFFB7E0BC);status.setTextSize(12);status.setGravity(Gravity.CENTER);
        status.setPadding(12,8,12,8);root.addView(status);
        yes=new Button(this);yes.setText("ПОДТВЕРДИТЬ");yes.setTextColor(0xFF28F45A);root.addView(yes);
        Button no=new Button(this);no.setText("Отмена");root.addView(no);no.setOnClickListener(v->finish());
        yes.setOnClickListener(v->executeCommand());setContentView(root);
    }
    private void info(String m){runOnUiThread(()->status.setText(m));}
    private void executeCommand(){
        yes.setEnabled(false); info("Отправляем команду ZONT…");
        worker.execute(()->{
            ZontClient z=new ZontClient(this);
            try {
                z.command(command);
                info("Команда передана серверу.\nПроверяем состояние автомобиля…");
                boolean confirmed=false;String last="";
                for(int i=0;i<5;i++){
                    Thread.sleep(i==0?900:1800);
                    try {
                        ZontClient.State state=z.refresh();
                        if(ZontClient.matches(state,command)){confirmed=true;break;}
                    }catch(Exception e){last=e.getMessage();}
                }
                if(confirmed)info("✅ Состояние подтверждено по ZONT");
                else info("⚠ Сервер принял команду, но выполнение пока НЕ подтверждено.\nПроверь автомобиль в ZONT."+(last.isEmpty()?"":"\n"+last));
            }catch(Exception e){info("Ошибка отправки: "+e.getMessage());}
            finally{sendBroadcast(new Intent(ZontClient.UPDATE_ACTION).setPackage(getPackageName()));}
        });
    }
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
