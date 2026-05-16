package io.dcloud.uniplugin;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;
import com.tencent.bugly.crashreport.CrashReport;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.dcloud.feature.uniapp.annotation.UniJSMethod;
import io.dcloud.feature.uniapp.bridge.UniJSCallback;
import io.dcloud.feature.uniapp.common.UniModule;

public class UsbModule extends UniModule implements SerialInputOutputManager.Listener {

    final String ACTION_USB_PERMISSION = "USB_PERMISSION";

    SerialInputOutputManager usbIoManager;

    boolean once = false;

    UniJSCallback currentCallback;

    // 添加超时相关常量
    private static final int OPERATION_TIMEOUT_MS = 5000; // 5秒超时

    // 添加超时处理器
    Handler timeoutHandler = new Handler(Looper.getMainLooper());
    Runnable timeoutRunnable;

    BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            switch (action) {
                case ACTION_USB_PERMISSION: {
                    boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);

                    if (granted) {
                        connect();
                    } else {
                        new Handler(Looper.getMainLooper()).post(() -> {
                            Toast.makeText(context.getApplicationContext(), "拒绝授权", Toast.LENGTH_SHORT).show();
                        });
                    }
                    break;
                }
                case UsbManager.ACTION_USB_DEVICE_DETACHED: {
                    // 停止 USB IO 管理器
                    if (usbIoManager != null) {
                        usbIoManager.stop();
                    }

                    // 清除当前回调并触发断开通知
                    UniJSCallback callback;
                    synchronized (this) {
                        callback = currentCallback;
                        currentCallback = null;
                    }

                    if (callback != null) {
                        callback.invoke(Map.of("status", "disconnect", "message", "设备已断开"));
                    }

                    HashMap<String, Object> map = new HashMap<>();
                    map.put("status", "disconnect");
                    mUniSDKInstance.fireGlobalEventCallback("usb_event", map);
                    break; // 重要：添加这一行
                }
                case UsbManager.ACTION_USB_DEVICE_ATTACHED: {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    break;
                }
            }
        }
    };

    @UniJSMethod
    public void init(String appId) {
        CrashReport.initCrashReport(mWXSDKInstance.getContext(), appId, false);
    }

    @UniJSMethod
    public String connect() {
        Context context = mWXSDKInstance.getContext();

        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);

        if (availableDrivers.isEmpty()) {
            Toast.makeText(context, "找不到设备", Toast.LENGTH_SHORT).show();
            return "1";
        }

        UsbSerialDriver driver = availableDrivers.get(0);
        UsbDevice usbDevice = driver.getDevice();

        if (!once) {
            once = true;
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_USB_PERMISSION);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            context.registerReceiver(usbReceiver, filter);
        }

        if (!manager.hasPermission(driver.getDevice())) {
            PendingIntent permissionIntent = PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_MUTABLE);
            manager.requestPermission(usbDevice, permissionIntent);
            return "2";
        }

        UsbDeviceConnection connection = manager.openDevice(usbDevice);
        if (connection == null) {
            Toast.makeText(context, "连接" + usbDevice.getDeviceName() + "失败", Toast.LENGTH_SHORT).show();
            return "3";
        }

        try {
            // Most devices have just one port (port 0)
            UsbSerialPort port = driver.getPorts().get(0);
            port.open(connection);
            port.setParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

            usbIoManager = new SerialInputOutputManager(port, this);
            usbIoManager.start();

            Toast.makeText(context, "USB连接成功，正在进行认证...", Toast.LENGTH_SHORT).show();

            HashMap<String, Object> map = new HashMap<>();
            map.put("status", "connected");
            mUniSDKInstance.fireGlobalEventCallback("usb_event", map);

            return driver.getDevice().getDeviceName();
        } catch (IOException e) {
            Toast.makeText(context, "连接异常", Toast.LENGTH_SHORT).show();
            throw new RuntimeException(e);
        }
    }

    @UniJSMethod
    public void read(int address, int count, UniJSCallback callback) {
        // 从站地址
        int slaveId = 8;

        byte[] bytes = Modbus.makeReadRegisterFrame(slaveId, address, count);

        doWrite(bytes, callback);
    }

    @UniJSMethod
    public void write(int address, int value, int bytesLength, UniJSCallback callback) {
        // 从站地址
        int slaveId = 8;

        byte[] bytes = Modbus.makeWriteSingleRegisterFrame(slaveId, address, value, bytesLength);

        doWrite(bytes, callback);
    }

    private synchronized void doWrite(byte[] bytes, UniJSCallback callback) {
        if (currentCallback != null) {
            callback.invoke(Map.of("status", "busy", "message", "上一条指令未响应"));
            return;
        }

        currentCallback = callback;

        // 设置超时定时器
        timeoutRunnable = () -> {
            UniJSCallback tempCallback;
            synchronized (this) {
                if (currentCallback == null) {
                    return;
                }
                tempCallback = currentCallback;
                currentCallback = null;
            }
            tempCallback.invoke(Map.of("status", "timeout", "message", "操作超时，请检查设备连接"));
        };
        timeoutHandler.postDelayed(timeoutRunnable, OPERATION_TIMEOUT_MS);

        usbIoManager.writeAsync(bytes);
    }

    @Override
    public void onNewData(byte[] bytes) {
        // 取消超时定时器
        if (timeoutRunnable != null) {
            timeoutHandler.removeCallbacks(timeoutRunnable);
        }

        UniJSCallback callback;
        synchronized (this) {
            if (currentCallback == null) {
                return;
            }
            callback = currentCallback;
            currentCallback = null;
        }

        // 转换byte数组为int数组
        int[] intArray = new int[bytes.length];

        for (int i = 0; i < bytes.length; i++) {
            intArray[i] = bytes[i] & 0xFF;
        }

        final UniJSCallback finalCallback = callback;
        mUniSDKInstance.runOnUiThread(() -> {
            finalCallback.invoke(Map.of("status", "ok", "data", intArray));
        });
    }

    @Override
    public void onRunError(Exception e) {
        // 处理 USB 通信错误
        CrashReport.postCatchedException(e);

        // 取消超时定时器
        if (timeoutRunnable != null) {
            timeoutHandler.removeCallbacks(timeoutRunnable);
        }

        UniJSCallback callback;
        synchronized (this) {
            if (currentCallback == null) {
                return;
            }
            callback = currentCallback;
            currentCallback = null;
        }

        final UniJSCallback finalCallback = callback;
        final String errorMessage = e.getMessage();
        mUniSDKInstance.runOnUiThread(() -> {
            finalCallback.invoke(Map.of("status", "error", "message", "USB通信错误: " + errorMessage));
        });
    }
}
