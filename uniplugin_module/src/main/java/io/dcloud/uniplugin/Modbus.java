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
            throw new IllegalArgumentException("最多只能读取 125 个寄存器");
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

    // 构建一个写单个寄存器 ModBus 帧 (功能码 06)
    // bytesLength: 2 表示传输 2 字节 (1 个寄存器), 4 表示传输 4 字节 (2 个寄存器)
    public static byte[] makeWriteSingleRegisterFrame(int slaveId, int address, int value, int bytesLength) {
        if (bytesLength != 2 && bytesLength != 4) {
            throw new IllegalArgumentException("bytesLength 只能是 2 或 4");
        }

        if (bytesLength == 2) {
            // 2 字节模式：写入 1 个寄存器
            byte[] requestData = new byte[4];
            requestData[0] = (byte) ((address >> 8) & 0xFF);         // 地址高字节
            requestData[1] = (byte) (address & 0xFF);                // 地址低字节
            requestData[2] = (byte) ((value >> 8) & 0xFF);           // 值高字节
            requestData[3] = (byte) (value & 0xFF);                  // 值低字节

            return buildRequestFrame(slaveId, 0x06, requestData);
        } else {
            // 4 字节模式：写入 2 个连续寄存器 (使用功能码 16 - 写多个寄存器)
            byte[] requestData = new byte[5];
            requestData[0] = (byte) ((address >> 8) & 0xFF);         // 起始地址高字节
            requestData[1] = (byte) (address & 0xFF);                // 起始地址低字节
            requestData[2] = (byte) 0x00;                            // 寄存器数量高字节 (2 个)
            requestData[3] = (byte) 0x02;                            // 寄存器数量低字节 (2 个)
            requestData[4] = (byte) 0x04;                            // 字节计数 (4 字节)

            // 构建 4 字节数据：高 16 位在前，低 16 位在后
            byte[] data = new byte[9];
            System.arraycopy(requestData, 0, data, 0, 5);
            data[5] = (byte) ((value >> 24) & 0xFF);                 // 值最高字节
            data[6] = (byte) ((value >> 16) & 0xFF);                 // 值次高字节
            data[7] = (byte) ((value >> 8) & 0xFF);                  // 值次低字节
            data[8] = (byte) (value & 0xFF);                         // 值最低字节

            return buildRequestFrame(slaveId, 0x10, data);
        }
    }
}
