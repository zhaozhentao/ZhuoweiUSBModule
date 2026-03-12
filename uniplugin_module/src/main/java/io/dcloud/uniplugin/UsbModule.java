package io.dcloud.uniplugin;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;

import io.dcloud.feature.uniapp.annotation.UniJSMethod;
import io.dcloud.feature.uniapp.common.UniModule;

public class UsbModule extends UniModule implements SerialInputOutputManager.Listener {

    SerialInputOutputManager usbIoManager;

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

        if (!manager.hasPermission(driver.getDevice())) {
            PendingIntent permissionIntent = PendingIntent.getBroadcast(context, 0, new Intent("USB_PERMISSION"), PendingIntent.FLAG_IMMUTABLE);
            manager.requestPermission(usbDevice, permissionIntent);
            Toast.makeText(context, "没有权限", Toast.LENGTH_SHORT).show();
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
    public void readRegister(int address) {
        // 从站地址
        int slaveId = 8;

        byte[] bytes = Modbus.makeReadRegisterFrame(slaveId, address, 1);

        usbIoManager.writeAsync(bytes);
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

        HashMap<String, Object> map = new HashMap<String, Object>(1) {{
            put("data", hexString.toString());
        }};

        mUniSDKInstance.fireGlobalEventCallback("usb_data", map);
    }

    @Override
    public void onRunError(Exception e) {

    }
}
