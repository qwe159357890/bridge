package com.vnt;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.security.SecureRandom;

/**
 * VNT网络配置
 *
 * 使用Builder模式构建配置
 */
public class VntConfig {

    private final List<String> servers;
    private final List<String> peerAddresses;
    private final List<String> turnRules;
    private final List<String> punchModelRules;
    private final List<String> inputRoutes;
    private final List<String> subnetMappings;
    private final List<String> outputRoutes;
    private final boolean autoSyncSubnet;
    private final String networkCode;
    private final String password;
    private final String deviceId;
    private final String deviceName;
    private final String tunName;
    private final String outboundInterface;
    private final String ip;
    private final String certMode;
    private final boolean noPunch;
    private final boolean noBroadcast;
    private final boolean allowIkev2;
    private final boolean allowWireguard;
    private final boolean compress;
    private final boolean rtx;
    private final boolean fec;
    private final boolean noNat;
    private final String deviceMode;
    private final Integer mtu;
    private final boolean allowMapping;
    private final List<String> portMapping;
    private final List<String> udpStun;
    private final List<String> tcpStun;
    private final String subscription;
    private final long subscriptionRevision;
    private final String subscriptionInstanceId;

    private VntConfig(Builder builder) {
        this.servers = builder.servers;
        this.peerAddresses = builder.peerAddresses;
        this.turnRules = builder.turnRules;
        this.punchModelRules = builder.punchModelRules;
        this.inputRoutes = builder.inputRoutes;
        this.subnetMappings = builder.subnetMappings;
        this.outputRoutes = builder.outputRoutes;
        this.autoSyncSubnet = builder.autoSyncSubnet;
        this.networkCode = builder.networkCode;
        this.password = builder.password;
        this.deviceId = builder.deviceId;
        this.deviceName = builder.deviceName;
        this.tunName = builder.tunName;
        this.outboundInterface = builder.outboundInterface;
        this.ip = builder.ip;
        this.certMode = builder.certMode;
        this.noPunch = builder.noPunch;
        this.noBroadcast = builder.noBroadcast;
        this.allowIkev2 = builder.allowIkev2;
        this.allowWireguard = builder.allowWireguard;
        this.compress = builder.compress;
        this.rtx = builder.rtx;
        this.fec = builder.fec;
        this.noNat = builder.noNat;
        this.deviceMode = builder.deviceMode;
        this.mtu = builder.mtu;
        this.allowMapping = builder.allowMapping;
        this.portMapping = builder.portMapping;
        this.udpStun = builder.udpStun;
        this.tcpStun = builder.tcpStun;
        this.subscription = builder.subscription;
        this.subscriptionRevision = builder.subscriptionRevision;
        this.subscriptionInstanceId = builder.subscriptionInstanceId;
    }

    /**
     * 转换为JSON字符串
     */
    String toJson() {
        JSONObject json = new JSONObject();

        // 必填项
        JSONArray serverArray = new JSONArray();
        for (String server : servers) {
            serverArray.put(server);
        }
        put(json,"server", serverArray);
        put(json,"network_code", networkCode);

        if (!peerAddresses.isEmpty()) {
            JSONArray peerArray = new JSONArray();
            for (String peerAddress : peerAddresses) {
                peerArray.put(peerAddress);
            }
            put(json,"peer_address", peerArray);
        }

        if (!turnRules.isEmpty()) {
            JSONArray turnArray = new JSONArray();
            for (String turnRule : turnRules) {
                turnArray.put(turnRule);
            }
            put(json,"turn", turnArray);
        }
        putStringArray(json, "punch_model", punchModelRules);

        putStringArray(json, "input", inputRoutes);
        putStringArray(json, "subnet_mapping", subnetMappings);
        putStringArray(json, "output", outputRoutes);

        // 可选项
        if (password != null) put(json,"password", password);
        if (deviceId != null) put(json,"device_id", deviceId);
        if (deviceName != null) put(json,"device_name", deviceName);
        if (tunName != null) put(json,"tun_name", tunName);
        if (outboundInterface != null) put(json,"outbound_interface", outboundInterface);
        if (ip != null) put(json,"ip", ip);
        if (certMode != null) put(json,"cert_mode", certMode);
        if (mtu != null) put(json,"mtu", mtu);
        if (subscription != null) {
            put(json,"subscription", subscription);
            put(json,"subscription_revision", subscriptionRevision);
            put(json,"subscription_instance_id", subscriptionInstanceId);
        }

        // 布尔值
        put(json,"no_punch", noPunch);
        put(json,"no_broadcast", noBroadcast);
        put(json,"allow_ikev2", allowIkev2);
        put(json,"allow_wireguard", allowWireguard);
        put(json,"compress", compress);
        put(json,"rtx", rtx);
        put(json,"fec", fec);
        put(json,"no_nat", noNat);
        put(json,"device_mode", deviceMode);
        put(json,"allow_mapping", allowMapping);
        put(json,"auto_sync_subnet", autoSyncSubnet);

        // 数组
        if (!portMapping.isEmpty()) {
            JSONArray mappingArray = new JSONArray();
            for (String mapping : portMapping) {
                mappingArray.put(mapping);
            }
            put(json,"port_mapping", mappingArray);
        }

        if (!udpStun.isEmpty()) {
            JSONArray stunArray = new JSONArray();
            for (String stun : udpStun) {
                stunArray.put(stun);
            }
            put(json,"udp_stun", stunArray);
        }

        if (!tcpStun.isEmpty()) {
            JSONArray stunArray = new JSONArray();
            for (String stun : tcpStun) {
                stunArray.put(stun);
            }
            put(json,"tcp_stun", stunArray);
        }

        return json.toString();
    }

    private static void putStringArray(JSONObject json, String name, List<String> values) {
        if (values.isEmpty()) return;
        JSONArray array = new JSONArray();
        for (String value : values) array.put(value);
        put(json,name, array);
    }

    /**
     * Android 适配（v0.2.30）：Android 框架自带的 org.json.JSONObject.put() 声明
     * **受检异常 JSONException**，而官方 java_example 是按标准 JVM org.json（不抛受检异常）
     * 编写的，直接拷进 Android 工程会编译失败（30 处「未报告的异常错误 JSONException」）。
     *
     * 这里统一包装成非受检异常：只改 Java 层实现，不改包名/类名/native 声明，
     * JNI 导出符号（Java_com_vnt_*）与 config JSON 内容均不受影响。
     */
    private static void put(JSONObject json, String name, Object value) {
        try {
            json.put(name, value);
        } catch (org.json.JSONException e) {
            // 写入的都是确定类型的基本值 / JSONArray，理论上不会触发
            throw new IllegalStateException("Failed to put VNT config field: " + name, e);
        }
    }

    /**
     * 配置构建器
     */
    public static class Builder {
        private List<String> servers = new ArrayList<>();
        private List<String> peerAddresses = new ArrayList<>();
        private List<String> turnRules = new ArrayList<>();
        private List<String> punchModelRules = new ArrayList<>();
        private List<String> inputRoutes = new ArrayList<>();
        private List<String> subnetMappings = new ArrayList<>();
        private List<String> outputRoutes = new ArrayList<>();
        private boolean autoSyncSubnet = false;
        private String networkCode;
        private String password;
        private String deviceId;
        private String deviceName;
        private String tunName;
        private String outboundInterface;
        private String ip;
        private String certMode;
        private boolean noPunch = false;
        private boolean noBroadcast = false;
        private boolean allowIkev2 = false;
        private boolean allowWireguard = false;
        private boolean compress = false;
        private boolean rtx = false;
        private boolean fec = false;
        private boolean noNat = false;
        private String deviceMode = "tun";
        private Integer mtu;
        private boolean allowMapping = false;
        private List<String> portMapping = new ArrayList<>();
        private List<String> udpStun = new ArrayList<>();
        private List<String> tcpStun = new ArrayList<>();
        private String subscription;
        private long subscriptionRevision;
        private String subscriptionInstanceId;

        /** Enables authenticated server management for this instance. */
        public Builder setSubscription(String subscription, long acknowledgedRevision) {
            return setSubscription(subscription, acknowledgedRevision, randomInstanceId());
        }

        /**
         * Enables subscription synchronization with a task-stable instance ID.
         * Persist and reuse instanceId while rebuilding the same Android VPN task.
         */
        public Builder setSubscription(String subscription, long acknowledgedRevision, String instanceId) {
            this.subscription = subscription;
            this.subscriptionRevision = acknowledgedRevision;
            this.subscriptionInstanceId = instanceId;
            return this;
        }

        private static String randomInstanceId() {
            byte[] value = new byte[32];
            new SecureRandom().nextBytes(value);
            StringBuilder result = new StringBuilder(64);
            for (byte item : value) result.append(String.format("%02x", item & 0xff));
            return result.toString();
        }

        /**
         * 添加服务器地址（必填）
         * @param server 服务器地址，格式：tcp://host:port 或 wss://host:port
         */
        public Builder addServer(String server) {
            this.servers.add(server);
            return this;
        }

        /**
         * 添加可直连节点地址（可选）。
         * @param peerAddress 格式：ip:port、tcp://ip:port、udp://ip:port，或 dynamic://域名 / dynamic://http(s)://地址列表接口
         */
        public Builder addPeerAddress(String peerAddress) {
            this.peerAddresses.add(peerAddress);
            return this;
        }

        /**
         * 添加指定中转规则（可选）。
         * @param turnRule 格式：目标虚拟IP或CIDR,中转虚拟IP
         */
        public Builder addTurnRule(String turnRule) {
            this.turnRules.add(turnRule);
            return this;
        }

        /**
         * 添加目标节点打洞方式规则（可选）。
         * @param rule 格式：目标虚拟IP或CIDR,IPv4Tcp[,IPv4Udp,IPv6Tcp,IPv6Udp]
         */
        public Builder addPunchModelRule(String rule) {
            this.punchModelRules.add(rule);
            return this;
        }

        /** 添加点对网入口路由，格式：映射CIDR,出口节点虚拟IP。 */
        public Builder addInputRoute(String route) {
            this.inputRoutes.add(route);
            return this;
        }

        /** 自动获取并应用其他在线节点的出口子网。 */
        public Builder setAutoSyncSubnet(boolean autoSyncSubnet) {
            this.autoSyncSubnet = autoSyncSubnet;
            return this;
        }

        /** 添加出口端子网映射，格式：映射CIDR,真实CIDR；真实网段需由 output 允许。 */
        public Builder addSubnetMapping(String mapping) {
            this.subnetMappings.add(mapping);
            return this;
        }

        /** 添加出口端允许转发的真实 CIDR。 */
        public Builder addOutputRoute(String cidr) {
            this.outputRoutes.add(cidr);
            return this;
        }

        /**
         * 设置网络代码（必填）
         * @param networkCode 组网代码
         */
        public Builder setNetworkCode(String networkCode) {
            this.networkCode = networkCode;
            return this;
        }

        /**
         * 设置密码（可选）
         */
        public Builder setPassword(String password) {
            this.password = password;
            return this;
        }

        /**
         * 设置设备ID（可选，默认自动生成）
         */
        public Builder setDeviceId(String deviceId) {
            this.deviceId = deviceId;
            return this;
        }

        /**
         * 设置设备名称（可选）
         */
        public Builder setDeviceName(String deviceName) {
            this.deviceName = deviceName;
            return this;
        }

        /**
         * 设置TUN设备名称（可选）
         */
        public Builder setTunName(String tunName) {
            this.tunName = tunName;
            return this;
        }

        /**
         * 绑定对外通信Socket的出口网卡名称（可选）
         */
        public Builder setOutboundInterface(String outboundInterface) {
            this.outboundInterface = outboundInterface;
            return this;
        }

        /**
         * 设置固定 IP 或 CIDR（纯 IP 默认 /24；无服务器时必填）
         */
        public Builder setIp(String ip) {
            this.ip = ip;
            return this;
        }

        /**
         * 设置证书验证模式（可选）
         * @param certMode "insecure" | "system" | "embedded"
         */
        public Builder setCertMode(String certMode) {
            this.certMode = certMode;
            return this;
        }

        /**
         * 禁用打洞（默认false）
         */
        public Builder setNoPunch(boolean noPunch) {
            this.noPunch = noPunch;
            return this;
        }

        /**
         * 禁用 IPv4 广播和组播转发（默认false，即开启）
         */
        public Builder setNoBroadcast(boolean noBroadcast) {
            this.noBroadcast = noBroadcast;
            return this;
        }

        /**
         * 允许与服务端接入的 IKEv2/IPsec 客户端通信（默认false）
         */
        public Builder setAllowIkev2(boolean allowIkev2) {
            this.allowIkev2 = allowIkev2;
            return this;
        }

        /**
         * 允许与服务端接入的 WireGuard 客户端通信（默认false）
         */
        public Builder setAllowWireguard(boolean allowWireguard) {
            this.allowWireguard = allowWireguard;
            return this;
        }

        /**
         * 启用压缩（默认false）
         */
        public Builder setCompress(boolean compress) {
            this.compress = compress;
            return this;
        }

        /**
         * 启用QUIC重传（默认false）
         */
        public Builder setRtx(boolean rtx) {
            this.rtx = rtx;
            return this;
        }

        /**
         * 启用FEC冗余传输（默认false）
         */
        public Builder setFec(boolean fec) {
            this.fec = fec;
            return this;
        }

        /**
         * 禁用NAT（默认false）
         */
        public Builder setNoNat(boolean noNat) {
            this.noNat = noNat;
            return this;
        }

        /**
         * 设置虚拟网卡模式：no、tun（默认）或 tap。
         */
        public Builder setDeviceMode(String deviceMode) {
            if (!"no".equals(deviceMode) && !"tun".equals(deviceMode) && !"tap".equals(deviceMode)) {
                throw new IllegalArgumentException("deviceMode must be no, tun, or tap");
            }
            this.deviceMode = deviceMode;
            return this;
        }

        /**
         * 设置MTU（可选，默认1380）
         */
        public Builder setMtu(int mtu) {
            this.mtu = mtu;
            return this;
        }

        /**
         * 允许端口映射（默认false）
         */
        public Builder setAllowMapping(boolean allowMapping) {
            this.allowMapping = allowMapping;
            return this;
        }

        /**
         * 添加端口映射规则（可选）
         * @param mapping 格式：tcp:80->192.168.1.100:8080
         */
        public Builder addPortMapping(String mapping) {
            this.portMapping.add(mapping);
            return this;
        }

        /**
         * 添加UDP STUN服务器（可选）
         */
        public Builder addUdpStun(String stun) {
            this.udpStun.add(stun);
            return this;
        }

        /**
         * 添加TCP STUN服务器（可选）
         */
        public Builder addTcpStun(String stun) {
            this.tcpStun.add(stun);
            return this;
        }

        /**
         * 构建配置对象
         */
        public VntConfig build() {
            if (servers.isEmpty() && (ip == null || ip.isEmpty())) {
                throw new IllegalArgumentException("A fixed IP/CIDR is required without servers");
            }
            if (networkCode == null || networkCode.isEmpty()) {
                throw new IllegalArgumentException("Network code must be specified");
            }
            return new VntConfig(this);
        }
    }
}
