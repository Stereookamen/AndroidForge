package com.stereoo.zontface;

import android.content.Context;
import android.os.SystemClock;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Official ZONT API v1. All operations called on worker threads. */
final class ZontClient {
    static final String UPDATE_ACTION = "com.stereoo.zontface.STATE_REFRESHED";
    final SecureStore store;
    ZontClient(Context context) { store = new SecureStore(context); }

    private JSONObject post(String method, JSONObject body) throws Exception {
        if (!store.configured()) throw new IllegalStateException("Сначала введи данные ZONT в настройках");
        HttpURLConnection conn = (HttpURLConnection)new URL("https://my.zont.online/api/" + method).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(9000); conn.setReadTimeout(14000);
        conn.setDoOutput(true); conn.setUseCaches(false);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("X-ZONT-Client", store.username());
        String auth = store.username() + ":" + store.password();
        conn.setRequestProperty("Authorization", "Basic " + Base64.encodeToString(auth.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try {
            try (OutputStream stream = conn.getOutputStream()) { stream.write(bytes); }
            int status = conn.getResponseCode();
            InputStream stream = status < 400 ? conn.getInputStream() : conn.getErrorStream();
            if (stream == null) throw new IllegalStateException("ZONT HTTP " + status);
            StringBuilder text = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line; while ((line = reader.readLine()) != null) text.append(line);
            }
            if (status == 401 || status == 403) throw new SecurityException("Неверный логин или пароль ZONT");
            JSONObject json = new JSONObject(text.toString());
            if (!json.optBoolean("ok", false))
                throw new IllegalStateException(json.optString("error_ui", json.optString("error", "Ошибка ZONT")));
            return json;
        } finally { conn.disconnect(); }
    }
    static class Device {
        final int id; final String name; final JSONObject payload;
        Device(int id, String name, JSONObject payload) { this.id=id; this.name=name; this.payload=payload; }
    }
    List<Device> listDevices() throws Exception {
        JSONObject request = new JSONObject().put("load_io", true);
        JSONArray json = post("devices", request).getJSONArray("devices");
        List<Device> devices = new ArrayList<>();
        for (int i=0;i<json.length();i++) {
            JSONObject d = json.getJSONObject(i);
            int id = d.optInt("device_id", d.optInt("id", 0));
            if (id>0) devices.add(new Device(id, d.optString("name", "ZONT №"+id), d));
        }
        return devices;
    }
    State refresh() throws Exception {
        List<Device> devices=listDevices();
        if (devices.isEmpty()) throw new IllegalStateException("В аккаунте ZONT нет устройств");
        int selected=store.deviceId(); Device target=null;
        for (Device d:devices) if (d.id==selected) { target=d; break; }
        if (target==null) {
            if (devices.size()==1) target=devices.get(0);
            else for (Device d:devices) {
                String value=d.payload.optString("devtype",d.payload.optString("type", ""));
                if (value.startsWith("ZTC-")) {target=d;break;}
            }
            if (target==null) target=devices.get(0);
            store.selectDevice(target.id,target.name);
        }
        return parse(target);
    }
    static class State {
        boolean armed, armedKnown, engineRunning, engineKnown, autostartAvailable, deviceOnline;
        Double engineTemp, cabinTemp, voltage, fuelLiters;
        String name = "";
        long receivedAt;
    }
    private static Double number(JSONObject json, String... keys) {
        for (String key : keys) {
            Object raw=json.opt(key);
            if (raw instanceof Number) return ((Number)raw).doubleValue();
        }
        return null;
    }
    private State parse(Device device) {
        JSONObject d=device.payload; JSONObject io=d.optJSONObject("io"); if (io==null) io=new JSONObject();
        State state=new State(); state.name=device.name;
        if (io.has("guard-state")) {
            state.armedKnown=true;
            state.armed="enabled".equals(io.optString("guard-state")) || "enabling".equals(io.optString("guard-state"));
        }
        if (io.has("engine-state")) { state.engineRunning=io.optBoolean("engine-state");state.engineKnown=true; }
        JSONObject auto=io.optJSONObject("auto-ignition");
        if (auto!=null) {
            state.autostartAvailable=auto.optBoolean("available",false);
            if (!state.engineKnown) {
                state.engineKnown=true;
                state.engineRunning="enabled".equals(auto.optString("state")) || "enabling".equals(auto.optString("state"));
            }
        }
        JSONArray assignments=d.optJSONObject("temperature_conf")!=null
          ? d.optJSONObject("temperature_conf").optJSONArray("assignments") : null;
        JSONArray temps=io.optJSONArray("temperature");
        if (temps!=null && assignments!=null) {
            for (int i=0;i<Math.min(assignments.length(),temps.length());i++) {
                JSONObject t=temps.optJSONObject(i);
                if (t==null || !"ok".equals(t.optString("state"))) continue;
                String type=assignments.optString(i);
                if ("engine".equals(type))state.engineTemp=number(t,"value");
                if ("cabin".equals(type))state.cabinTemp=number(t,"value");
            }
        }
        // Optional data only. API v1 may not return fuel/voltage through devices.
        state.voltage=number(io,"voltage","power-voltage","battery-voltage");
        state.fuelLiters=number(io,"fuel-liters","fuel-litres");
        // A successful cloud API call alone does not guarantee the ZTC modem is online.
        state.deviceOnline=d.optBoolean("online",false) || d.optBoolean("connected",false) || io.optBoolean("connected",false);
        state.receivedAt=System.currentTimeMillis();
        return state;
    }
    void command(String action) throws Exception {
        int id=store.deviceId(); if (id<=0) throw new IllegalStateException("Сначала выбери устройство");
        JSONObject payload=new JSONObject().put("device_id",id);
        switch (action) {
            case "ARM": payload.put("portname","guard-state").put("type","string").put("value","enabled"); break;
            case "DISARM": payload.put("portname","guard-state").put("type","string").put("value","disabled"); break;
            case "START": payload.put("portname","auto-ignition").put("type","auto-ignition")
                    .put("value",new JSONObject().put("state","engine")); break;
            case "STOP": payload.put("portname","auto-ignition").put("type","auto-ignition")
                    .put("value",new JSONObject().put("state","disabled")); break;
            default: throw new IllegalArgumentException("Неизвестная команда");
        }
        // API returns ok:true even if the alarm is offline: caller must verify state.
        post("set_io_port",payload);
    }
    static boolean matches(State st, String action) {
        switch (action) {
            case "ARM": return st.armedKnown && st.armed;
            case "DISARM":return st.armedKnown && !st.armed;
            case "START":return st.engineKnown && st.engineRunning;
            case "STOP":return st.engineKnown && !st.engineRunning;
            default:return false;
        }
    }
    static String value(Double n, String suffix) {
        if (n==null || !Double.isFinite(n)) return "—";
        if ("В".equals(suffix))return String.format(Locale.US,"%.1f %s",n,suffix);
        return String.format(Locale.US,"%.0f%s",n,suffix);
    }
}
