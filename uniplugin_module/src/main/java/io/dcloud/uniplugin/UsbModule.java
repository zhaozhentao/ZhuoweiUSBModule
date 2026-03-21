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

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import io.dcloud.feature.uniapp.annotation.UniJSMethod;
import io.dcloud.feature.uniapp.bridge.UniJSCallback;
import io.dcloud.feature.uniapp.common.UniModule;

public class UsbModule extends UniModule implements SerialInputOutputManager.Listener {

    final Queue<UniJSCallback> callbackQueue = new LinkedList<>();

    final String ACTION_USB_PERMISSION = "USB_PERMISSION";

    SerialInputOutputManager usbIoManager;

    boolean once = false;

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
                case UsbManager.ACTION_USB_DEVICE_DETACHED:
                case UsbManager.ACTION_USB_DEVICE_ATTACHED: {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    break;
                }
            }
        }
    };

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

            Toast.makeText(context, "连接成功", Toast.LENGTH_SHORT).show();

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

    private void doWrite(byte[] bytes, UniJSCallback callback) {
        callbackQueue.offer(callback);

        usbIoManager.writeAsync(bytes);
    }

    @Override
    public void onNewData(byte[] bytes) {
        // 从队列中取出callback并执行
        UniJSCallback callback = callbackQueue.poll();

        if (callback == null) {
            return;
        }

        mUniSDKInstance.runOnUiThread(() -> callback.invoke(Map.of("status", "ok", "data", bytes)));
    }

    @Override
    public void onRunError(Exception e) {

    }
}
