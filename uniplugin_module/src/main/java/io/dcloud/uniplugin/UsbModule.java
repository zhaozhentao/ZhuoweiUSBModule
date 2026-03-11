package io.dcloud.uniplugin;

import android.content.Context;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import io.dcloud.feature.uniapp.annotation.UniJSMethod;
import io.dcloud.feature.uniapp.common.UniModule;
import uni.dcloud.io.uniplugin_module.R;

public class UsbModule extends UniModule implements SerialInputOutputManager.Listener {

    SerialInputOutputManager usbIoManager;

    @UniJSMethod
    public String connect() {
        UsbManager manager = (UsbManager) mUniSDKInstance.getContext().getSystemService(Context.USB_SERVICE);
        List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);

        String s = mUniSDKInstance.getContext().getString(R.string.app_name);

        if (availableDrivers.isEmpty()) {
            Toast.makeText(mUniSDKInstance.getContext(), s + "找不到设备", Toast.LENGTH_SHORT).show();
            return "1";
        }

        Log.i("不是吧大哥", "R.xml.device_filter " + R.xml.device_filter);

        String a = "";
        for (UsbSerialDriver usbSerialDriver : availableDrivers) {
            a += usbSerialDriver.getDevice().getDeviceName() + ", ";
            Toast.makeText(mUniSDKInstance.getContext(), "devices:" + a, Toast.LENGTH_SHORT).show();
        }

        UsbSerialDriver driver = availableDrivers.get(0);
        UsbDeviceConnection connection = manager.openDevice(driver.getDevice());

        if (connection == null) {
            Toast.makeText(mUniSDKInstance.getContext(), s + "连接失败", Toast.LENGTH_SHORT).show();
            return "2";
        }

        try {
            // Most devices have just one port (port 0)
            UsbSerialPort port = driver.getPorts().get(0);
            port.open(connection);
            port.setParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

            usbIoManager = new SerialInputOutputManager(port, this);
            usbIoManager.start();

            Toast.makeText(mUniSDKInstance.getContext(), "连接成功", Toast.LENGTH_SHORT).show();

            return driver.getDevice().getDeviceName();
        } catch (IOException e) {
            Toast.makeText(mUniSDKInstance.getContext(), "连接异常", Toast.LENGTH_SHORT).show();
            throw new RuntimeException(e);
        }
    }

    @UniJSMethod
    public void send(String base64Str) {
        if (usbIoManager == null) {
            Toast.makeText(mUniSDKInstance.getContext(), "未连接", Toast.LENGTH_SHORT).show();

            return;
        }

        byte[] byteArray = Base64.decode(base64Str, Base64.DEFAULT);

        Log.i("UsbModule", "接收到 " + Arrays.toString(byteArray));

        usbIoManager.writeAsync(byteArray);
    }

    @Override
    public void onNewData(byte[] bytes) {

    }

    @Override
    public void onRunError(Exception e) {

    }
}
