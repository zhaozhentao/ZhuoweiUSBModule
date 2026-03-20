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
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import io.dcloud.feature.uniapp.annotation.UniJSMethod;
import io.dcloud.feature.uniapp.bridge.UniJSCallback;
import io.dcloud.feature.uniapp.common.UniModule;

public class UsbModule extends UniModule implements SerialInputOutputManager.Listener {

    final BlockingDeque<String> queue = new LinkedBlockingDeque<>();

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
                case UsbManager.ACTION_USB_DEVICE_ATTACHED: {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        Toast.makeText(context.getApplicationContext(),
                            "Device attached: " + device,
                            Toast.LENGTH_SHORT).show();
                    });
                    break;
                }
                case UsbManager.ACTION_USB_DEVICE_DETACHED: {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        Toast.makeText(context.getApplicationContext(),
                            "Device detached: " + device,
                            Toast.LENGTH_SHORT).show();
                    });
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

        usbIoManager.writeAsync(bytes);

        try {
            String data = queue.poll(100, TimeUnit.MICROSECONDS);

            callback.invoke(Map.of("data", data, "status", "ok"));
        } catch (InterruptedException e) {
            mUniSDKInstance.runOnUiThread(() -> Toast.makeText(mUniSDKInstance.getContext(), "读取超时", Toast.LENGTH_SHORT).show());

            callback.invoke(Map.of("data", "", "status", "timeout"));
        }
    }

    @UniJSMethod
    public void write(int address, int value, int bytesLength, UniJSCallback callback) {
        // 从站地址
        int slaveId = 8;

        byte[] bytes = Modbus.makeWriteSingleRegisterFrame(slaveId, address, value, bytesLength);

        usbIoManager.writeAsync(bytes);

        try {
            String data = queue.poll(100, TimeUnit.MILLISECONDS);

            callback.invoke(Map.of("data", data, "status", "ok"));
        } catch (InterruptedException e) {
            mUniSDKInstance.runOnUiThread(() -> Toast.makeText(mUniSDKInstance.getContext(), "写入超时", Toast.LENGTH_SHORT).show());

            callback.invoke(Map.of("data", "", "status", "timeout"));
        }
    }

    @Override
    public void onNewData(byte[] bytes) {
        // 将 byte 数组转换为十六进制字符串
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(b & 0xFF);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }

        try {
            queue.put(hexString.toString());
        } catch (InterruptedException e) {
            mUniSDKInstance.runOnUiThread(() -> Toast.makeText(mUniSDKInstance.getContext(), "发送数据超时", Toast.LENGTH_SHORT).show());
        }
    }

    @Override
    public void onRunError(Exception e) {

    }
}
