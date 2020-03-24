package com.fchen_group.CloudObjectStorageIntegrityChecking.Run;

import java.io.*;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Properties;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fchen_group.CloudObjectStorageIntegrityChecking.Core.CloudObjectStorageIntegrityChecking;
import com.fchen_group.CloudObjectStorageIntegrityChecking.Core.Key;
import com.fchen_group.CloudObjectStorageIntegrityChecking.Core.ChallengeData;
import com.fchen_group.CloudObjectStorageIntegrityChecking.Core.ProofData;
import com.fchen_group.CloudObjectStorageIntegrityChecking.Utils.FileTransferProtocol;
import com.fchen_group.CloudObjectStorageIntegrityChecking.Utils.FileTransferProtocolDecoder;
import com.fchen_group.CloudObjectStorageIntegrityChecking.Utils.FileTransferProtocolEncoder;
import com.fchen_group.CloudObjectStorageIntegrityChecking.Utils.Serialization;

public class Client {
    private static Logger logger = LoggerFactory.getLogger("client");
    private static String serverAddress;
    private static int serverPort;
    private String command;
    private String filePath;
    String propertiesFilePath;
    String keyFilePath;
    String tagsFilePath;
    CloudObjectStorageIntegrityChecking cloudObjectStorageIntegrityChecking;
    private Key key;
    private ChallengeData challengeData;

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[1].equals("audit")) {
            serverAddress = args[0];
            logger.info("Start audit process.");
            new Client(args[1], args[2]).run();
        } else if (args.length == 4 && args[1].equals("outsource")) {
            serverAddress = args[0];
            logger.info("Start outsource process.");
            new Client(args[1], args[2], Integer.parseInt(args[3])).run();
        } else {
            show_help();
        }
    }

    public Client(String command, String filePath) {
        this(command, filePath, 0);
    }

    public Client(String command, String filePath, int SECTOR_NUMBER) {
        this.command = command;
        try {
            this.filePath = (new File(filePath)).getCanonicalPath();
        } catch (IOException e) {
            e.printStackTrace();
        }
        propertiesFilePath = this.filePath + ".properties";
        keyFilePath = this.filePath + ".key";
        tagsFilePath = this.filePath + ".tags";
        int BLOCK_NUMBER = 0;
        try {
            // initial SECTOR_NUMBER
            if (SECTOR_NUMBER == 0 && (new File(propertiesFilePath)).exists()) {
                // get SECTOR_NUMBER from file
                FileInputStream propertiesFIS = new FileInputStream(propertiesFilePath);
                Properties properties = new Properties();
                properties.load(propertiesFIS);
                propertiesFIS.close();
                SECTOR_NUMBER = Integer.parseInt(properties.getProperty("SECTOR_NUMBER"));
                BLOCK_NUMBER = Integer.parseInt(properties.getProperty("BLOCK_NUMBER"));
            }

            // initial auditingWithoutErrorCorrection
            cloudObjectStorageIntegrityChecking = new CloudObjectStorageIntegrityChecking(filePath, BLOCK_NUMBER, SECTOR_NUMBER);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void run() throws Exception {
        // 配置客户端NIO线程组
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            // 客户端辅助启动类 对客户端配置
            Bootstrap b = new Bootstrap();
            b.group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            // 添加自定义协议的编解码工具
                            ch.pipeline().addLast(new FileTransferProtocolEncoder());
                            ch.pipeline().addLast(new FileTransferProtocolDecoder());
                            // 处理客户端操作
                            if (command.equals("outsource"))
                                ch.pipeline().addLast(new ClientOutsourceHandler());
                            else
                                ch.pipeline().addLast(new ClientAuditHandler());
                        }
                    })
                    .option(ChannelOption.TCP_NODELAY, true);

            serverPort = 9999;
            logger.info("Connect to {}:{}.", serverAddress, serverPort);
            ChannelFuture f = b.connect(serverAddress, serverPort).sync();
            f.channel().closeFuture().sync();

        } finally {
            group.shutdownGracefully();
        }
    }

    class ClientOutsourceHandler extends SimpleChannelInboundHandler<Object> {
        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            // keyGen
            key = cloudObjectStorageIntegrityChecking.keyGen();
            // store key
            File keyFile = new File(keyFilePath);
            if (!keyFile.exists())
                keyFile.createNewFile();
            FileOutputStream keyFOS = new FileOutputStream(keyFile);
            keyFOS.write(Serialization.serialize(key));
            keyFOS.close();
            logger.info("Generate key and store it to {}", keyFile);

            // outsource
            BigInteger[] tags = cloudObjectStorageIntegrityChecking.outsource(key);
            // store tags
            byte[] bytesFromTag;
            byte[] bytesToWrite = new byte[17];
            File tagsFile = new File(tagsFilePath);
            if (!tagsFile.exists())
                tagsFile.createNewFile();
            FileOutputStream tagsFOS = new FileOutputStream(tagsFile);
            for (BigInteger tag : tags) {
                bytesFromTag = tag.toByteArray();
                Arrays.fill(bytesToWrite, (byte) 0);
                System.arraycopy(bytesFromTag, 0, bytesToWrite, 17 - bytesFromTag.length, bytesFromTag.length);
                tagsFOS.write(bytesToWrite);
            }
            tagsFOS.close();
            logger.info("Calculate tags and store it to {}", tagsFile);

            // store SECTOR_NUMBER and BLOCK_NUMBER to file
            File propertiesFile = new File(propertiesFilePath);
            propertiesFile.createNewFile();
            FileOutputStream propertiesFOS = new FileOutputStream(propertiesFile);
            Properties properties = new Properties();
            properties.setProperty("SECTOR_NUMBER", String.valueOf(cloudObjectStorageIntegrityChecking.getSECTOR_NUMBER()));
            properties.setProperty("BLOCK_NUMBER", String.valueOf(cloudObjectStorageIntegrityChecking.getBLOCK_NUMBER()));
            properties.store(propertiesFOS, "SECTOR_NUMBER: the number of sectors in a block\n" +
                    "BLOCK_NUMBER: the number of blocks of the file");
            propertiesFOS.close();
            logger.info("Store SECTOR_NUMBER and BLOCK_NUMBER to {}", propertiesFilePath);

            // send file
            logger.info("Send source file {}", filePath);
            FileTransferProtocol fileTransferProtocol = new FileTransferProtocol(0, filePath.getBytes());
            ctx.writeAndFlush(fileTransferProtocol);
        }

        @Override
        public void channelRead0(ChannelHandlerContext ctx, Object msg) {
            switch (((FileTransferProtocol) msg).op) {
                case 0:
                    logger.info("Send properties file {}", propertiesFilePath);
                    ctx.writeAndFlush(new FileTransferProtocol(1, propertiesFilePath.getBytes()));
                    break;
                case 1:
                    logger.info("Send tags file {}", tagsFilePath);
                    ctx.writeAndFlush(new FileTransferProtocol(2, tagsFilePath.getBytes()));
                    break;
                case 2:
                    logger.info("Finish outsource process");
                    ctx.close();
                    break;
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }
    }

    class ClientAuditHandler extends SimpleChannelInboundHandler<Object> {
        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            challengeData = cloudObjectStorageIntegrityChecking.audit(460);
            logger.info("Send challenge data");
            FileTransferProtocol fileTransferProtocol = new FileTransferProtocol(3, (filePath + ".challenge").getBytes(), Serialization.serialize(challengeData));
            ctx.writeAndFlush(fileTransferProtocol);
        }

        @Override
        public void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
            // receive proof data
            FileTransferProtocol fileTransferProtocolReceived = (FileTransferProtocol) msg;
            ProofData proofData = (ProofData) Serialization.deserialize(fileTransferProtocolReceived.content);
            ctx.close();
            logger.info("Receive proof data");

            // get key
            Key key;
            try {
                FileInputStream keyFIS = new FileInputStream(keyFilePath);
                ObjectInputStream in = new ObjectInputStream(keyFIS);
                key = (Key) in.readObject();
                in.close();
                keyFIS.close();
            } catch (ClassNotFoundException e) {
                System.out.println("Class Key not found");
                e.printStackTrace();
                return;
            }
            logger.info("Get key from {}.", keyFilePath);

            // verify
            logger.info("Verifying proof.");
            boolean verifyResult = cloudObjectStorageIntegrityChecking.verify(key, challengeData, proofData);
            logger.info("Verify result is {}", verifyResult);
            if (verifyResult)
                System.out.println("Verify pass");
            else
                System.out.println("Verify failed");
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }
    }

    public static void show_help() {
        System.out.print("使用方法：\n" +
                "客户端在审计过程中有两个阶段：outsource 阶段和 audit 阶段\n" +
                "\n" +
                "启动 outsource 的命令为：\n" +
                "    java -jar client.jar [SERVER_IP] outsource [filename] [SECTOR_NUMBER]\n" +
                "注：\n" +
                "    在 outsource 完成之后，程序会在文件所在目录生成以下文件\n" +
                "        [filename].key  [filename].tags  [filename].properties\n" +
                "    这三个文件在 audit 阶段中需要用到，请妥善保管好！\n" +
                "\n" +
                "启动 audit 的命令为：\n" +
                "    java -jar client.jar [SERVER_IP] audit [filename]\n" +
                "注：\n" +
                "    运行 audit 需要 outsource 阶段生成的三个文件：\n" +
                "        [filename].key  [filename].tags  [filename].properties\n");
    }
}
