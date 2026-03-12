package io.dcloud.uniplugin;

public class Modbus {

    private static byte[] calculateCRC16(byte[] data) {
        int crc = 0xFFFF;

        for (byte b : data) {
            crc ^= (b & 0xFF);
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x0001) != 0) {
                    crc = (crc >> 1) ^ 0xA001;
                } else {
                    crc = crc >> 1;
                }
            }
        }

        // 低位在前，高位在后
        return new byte[]{
            (byte) (crc & 0xFF),
            (byte) ((crc >> 8) & 0xFF)
        };
    }

    private static byte[] buildRequestFrame(int slaveId, int functionCode, byte[] data) {
        // 帧结构：地址(1) + 功能码(1) + 数据(N) + CRC(2)
        byte[] frame = new byte[1 + 1 + data.length + 2];

        frame[0] = (byte) (slaveId & 0xFF);          // 从站地址
        frame[1] = (byte) (functionCode & 0xFF);     // 功能码

        // 拷贝数据
        System.arraycopy(data, 0, frame, 2, data.length);

        // 计算并添加CRC
        byte[] crc = calculateCRC16(java.util.Arrays.copyOf(frame, frame.length - 2));
        frame[frame.length - 2] = crc[0];
        frame[frame.length - 1] = crc[1];

        return frame;
    }

    // 构建一个读寄存器 ModBus 帧
    public static byte[] makeReadRegisterFrame(int slaveId, int startAddress, int count) {
        if (count > 125) {
            throw new IllegalArgumentException("最多只能读取125个寄存器");
        }

        // 构建请求数据
        byte[] requestData = new byte[4];
        requestData[0] = (byte) ((startAddress >> 8) & 0xFF);    // 地址高字节
        requestData[1] = (byte) (startAddress & 0xFF);           // 地址低字节
        requestData[2] = (byte) ((count >> 8) & 0xFF);           // 数量高字节
        requestData[3] = (byte) (count & 0xFF);                  // 数量低字节

        // 构建并发送请求帧
        return buildRequestFrame(slaveId, 0x03, requestData);
    }
}
