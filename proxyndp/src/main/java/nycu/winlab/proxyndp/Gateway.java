/*
 * Copyright 2020-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nycu.winlab.proxyndp;

import org.onosproject.core.ApplicationId;
import org.onosproject.net.config.Config;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onlab.packet.IpAddress;
import org.onlab.packet.IpPrefix;
import org.onlab.packet.MacAddress;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration facade for the proxyndp app.
 */
public class Gateway extends Config<ApplicationId> {

    private static final String INTRA_PREFIX_4 = "intraPrefix4";
    private static final String INTRA_PREFIX_6 = "intraPrefix6";
    private static final String BLOCK_DEVICE_ID = "blockDeviceId";
    private static final String TARGET_DEVICES = "targetDevices";
    private static final String ALLOWED_ARP_PAIRS = "allowedArpPairs";
    private static final String ALLOWED_NDP_PAIRS = "allowedNdpPairs";
    private static final String PRE_ADD_ARP_ENTRIES = "preAddArpEntries";

    /**
     * Gets the IPv4 intra-domain prefix.
     *
     * @return IPv4 prefix
     */
    public IpPrefix intraPrefix4() {
        String prefix = get(INTRA_PREFIX_4, "172.16.23.0/24");
        return IpPrefix.valueOf(prefix);
    }

    /**
     * Gets the IPv6 intra-domain prefix.
     *
     * @return IPv6 prefix
     */
    public IpPrefix intraPrefix6() {
        String prefix = get(INTRA_PREFIX_6, "2a0b:4e07:c4:23::/64");
        return IpPrefix.valueOf(prefix);
    }

    public MacAddress intraMac() {
        String macStr = get("intraMac", "00:00:00:00:00:23");
        return MacAddress.valueOf(macStr);
    }
    /**
     * Gets the device ID for blocking ingress traffic.
     *
     * @return Device ID
     */
    public DeviceId blockDeviceId() {
        String devId = get(BLOCK_DEVICE_ID, "of:0000d2f4d1307942");
        return DeviceId.deviceId(devId);
    }

    /**
     * Gets the list of target device IDs for packet-in rules.
     *
     * @return List of Device IDs
     */
    public List<DeviceId> targetDevices() {
        List<DeviceId> result = new ArrayList<>();
        JsonNode devicesNode = object.get(TARGET_DEVICES);
        if (devicesNode != null && devicesNode.isArray()) {
            for (JsonNode dev : devicesNode) {
                result.add(DeviceId.deviceId(dev.asText()));
            }
        }
        // Default values if not configured
        if (result.isEmpty()) {
            result.add(DeviceId.deviceId("of:0000000000000001"));
            result.add(DeviceId.deviceId("of:0000000000000002"));
            result.add(DeviceId.deviceId("of:0000d2f4d1307942"));
        }
        return result;
    }

    /**
     * Gets allowed ARP pairs (srcIp, dstIp) for controller punt rules.
     *
     * @return List of IP address pairs
     */
    public List<IpPair> allowedArpPairs() {
        List<IpPair> result = new ArrayList<>();
        JsonNode pairsNode = object.get(ALLOWED_ARP_PAIRS);
        if (pairsNode != null && pairsNode.isArray()) {
            for (JsonNode pair : pairsNode) {
                String srcIp = pair.get("srcIp").asText();
                String dstIp = pair.get("dstIp").asText();
                result.add(new IpPair(srcIp, dstIp));
            }
        }
        // Default values if not configured
        if (result.isEmpty()) {
            result.add(new IpPair("192.168.70.253", "192.168.70.23"));
            result.add(new IpPair("192.168.70.22", "192.168.70.23"));
            result.add(new IpPair("192.168.70.24", "192.168.70.23"));
        }
        return result;
    }

    /**
     * Gets allowed NDP pairs (srcIp, dstIp) for controller punt rules.
     *
     * @return List of IPv6 address pairs
     */
    public List<IpPair> allowedNdpPairs() {
        List<IpPair> result = new ArrayList<>();
        JsonNode pairsNode = object.get(ALLOWED_NDP_PAIRS);
        if (pairsNode != null && pairsNode.isArray()) {
            for (JsonNode pair : pairsNode) {
                String srcIp = pair.get("srcIp").asText();
                String dstIp = pair.get("dstIp").asText();
                result.add(new IpPair(srcIp, dstIp));
            }
        }
        // Default values if not configured
        if (result.isEmpty()) {
            result.add(new IpPair("fd70::fe", "fd70::23"));
            result.add(new IpPair("fd70::22", "fd70::23"));
            result.add(new IpPair("fd70::24", "fd70::23"));
        }
        return result;
    }

    /**
     * Gets pre-populated ARP table entries.
     *
     * @return List of ARP entries
     */
    public List<ArpEntry> preAddArpEntries() {
        List<ArpEntry> result = new ArrayList<>();
        JsonNode entriesNode = object.get(PRE_ADD_ARP_ENTRIES);
        if (entriesNode != null && entriesNode.isArray()) {
            for (JsonNode entry : entriesNode) {
                String ip = entry.get("ip").asText();
                String mac = entry.get("mac").asText();
                String deviceId = entry.get("deviceId").asText();
                int port = entry.get("port").asInt();
                result.add(new ArpEntry(
                        IpAddress.valueOf(ip),
                        MacAddress.valueOf(mac),
                        new ConnectPoint(DeviceId.deviceId(deviceId), PortNumber.portNumber(port))));
            }
        }
        return result;
    }

    /**
     * Helper class for IP address pairs.
     */
    public static class IpPair {
        private final String srcIp;
        private final String dstIp;

        public IpPair(String srcIp, String dstIp) {
            this.srcIp = srcIp;
            this.dstIp = dstIp;
        }

        public String srcIp() {
            return srcIp;
        }

        public String dstIp() {
            return dstIp;
        }
    }

    /**
     * Helper class for ARP table entries.
     */
    public static class ArpEntry {
        private final IpAddress ip;
        private final MacAddress mac;
        private final ConnectPoint connectPoint;

        public ArpEntry(IpAddress ip, MacAddress mac, ConnectPoint connectPoint) {
            this.ip = ip;
            this.mac = mac;
            this.connectPoint = connectPoint;
        }

        public IpAddress ip() {
            return ip;
        }

        public MacAddress mac() {
            return mac;
        }

        public ConnectPoint connectPoint() {
            return connectPoint;
        }
    }
}
